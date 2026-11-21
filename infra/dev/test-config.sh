#!/usr/bin/env bash
set -euo pipefail
source infra/dev/operator-ssh.sh
umask 077

case "$MODE" in
  enable|disable) ;;
  *) exit 2 ;;
esac
if [[ "$MODE" == enable ]]; then
  [[ "$AGENT_TEST" == true || "$BILLING_MOCK" == true ]] || { echo 'Enable at least one fixture' >&2; exit 2; }
  [[ "$DURATION" =~ ^[1-9][0-9]?$ ]] && (( DURATION >= 5 && DURATION <= 90 )) ||
    { echo 'Enter a whole number of minutes between 5 and 90' >&2; exit 2; }
fi

# Finish checks on both hosts before changing either host.
for component in ktor ai; do
  if [[ "$component" == ktor ]]; then revision="$KTOR_REVISION"; else revision="$AI_REVISION"; fi
  dev_revision "$component" "$revision"
  dev_ssh "bash -s -- $component $MODE" <<'REMOTE'
set -euo pipefail
component="$1"
mode="$2"
test -s /opt/speaky-release/host-install.py
if [[ "$mode" == enable ]]; then
  systemctl is-active --quiet "speaky-dev-test-config-expiry@$component.timer"
  systemctl is-enabled --quiet "speaky-dev-test-config-expiry@$component.timer"
fi
if [[ "$component" == ktor ]]; then root=/opt/speaking-coach; else root=/opt/ai-service; fi
test -s "$root/.env"
grep -qx 'SPEAKY_ENV=dev' "$root/.env"
if grep -qE '^(AGENT_TEST_ENABLED=true|SPEAKY_BILLING_PROVIDER=mock)[[:space:]]*$' "$root/.env"; then
  echo 'Remove test fixtures from base DEV config before using expiring overrides.' >&2
  exit 2
fi
if [[ "$component" == ktor ]] && grep -qE '^SPEAKY_BILLING_PROVIDER=.*[^[:space:]]' "$root/.env"; then
  echo 'DEV billing must be disabled in base config before using expiring overrides.' >&2
  exit 2
fi
REMOTE
done

if [[ "$MODE" == enable ]]; then
  expires="$(( $(date +%s) + DURATION * 60 ))"
fi
for component in ktor ai; do
  dev_ssh_config "$component"
  fixture=-
  set_billing=false
  if [[ "$component" == ktor ]]; then set_billing="$BILLING_MOCK"; fi
  if [[ "$MODE" == enable && ( "$AGENT_TEST" == true || "$set_billing" == true ) ]]; then
    fixture=$(dev_ssh "mktemp /tmp/speaky-test-config.XXXXXXXX")
    [[ "$fixture" =~ ^/tmp/speaky-test-config\.[A-Za-z0-9]+$ ]]
    trap 'dev_ssh "rm -f $fixture" >/dev/null 2>&1 || true' EXIT
    {
      printf '# SPEAKY_EXPIRES_UTC=%s\n' "$expires"
      if [[ "$AGENT_TEST" == true ]]; then printf 'AGENT_TEST_ENABLED=true\n'; fi
      if [[ "$set_billing" == true ]]; then
        printf '%s\n' 'SPEAKY_BILLING_PROVIDER=mock' 'SPEAKY_BILLING_AMOUNT=100' \
          'SPEAKY_BILLING_CURRENCY=TEST' 'SPEAKY_BILLING_PERIOD_DAYS=1'
      fi
    } | dev_ssh "cat > $fixture"
  fi
  if ! dev_ssh "python3 /opt/speaky-release/host-install.py restart-config dev $component $fixture"; then
    echo "DEV test config stopped at $component. Check previously reported components." >&2
    exit 1
  fi
  if [[ "$fixture" != - ]]; then
    dev_ssh "rm -f $fixture"
    trap - EXIT
  fi
  printf 'DEV test configuration updated for %s\n' "$component"
done
