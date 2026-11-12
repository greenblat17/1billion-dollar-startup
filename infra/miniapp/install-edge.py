"""Replace only the owned DEV Mini App vhost; preserve unrelated proxy sites."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

HOST = 'speaky-miniapp-dev.109.71.241.224.sslip.io'


def replace_site(current, replacement):
    match = re.search(r'(?m)^' + re.escape(HOST) + r'\s*\{', current)
    if not match:
        raise ValueError('Owned Mini App vhost not found; refusing to change other sites')
    depth, end = 1, match.end()
    while depth and end < len(current):
        if current[end] == '{': depth += 1
        if current[end] == '}': depth -= 1
        end += 1
    if depth:
        raise ValueError('Unbalanced Mini App vhost')
    block = current[match.start():end]
    if 'reverse_proxy ai-service:8090' not in block:
        raise ValueError('Unexpected Mini App upstream; manual review required')
    replacement_start = re.search(r'(?m)^' + re.escape(HOST) + r'\s*\{', replacement)
    if not replacement_start:
        raise ValueError('Replacement must contain the owned Mini App vhost')
    return current[:match.start()] + replacement[replacement_start.start():].strip() + current[end:]


def install():
    config = Path('/opt/speaky-voice-lab/Caddyfile')
    proxy = 'speaky-voice-lab-proxy-1'
    replacement = Path(__file__).with_name('Caddyfile.dev.example').read_text()
    original = config.read_text()
    updated = replace_site(original, replacement)
    if updated == original:
        return
    with tempfile.TemporaryDirectory() as folder:
        candidate = Path(folder) / 'Caddyfile'
        candidate.write_text(updated)
        subprocess.run(['docker', 'cp', str(candidate), proxy + ':/tmp/mini-app-Caddyfile'], check=True)
        subprocess.run(['docker', 'exec', proxy, 'caddy', 'validate', '--config', '/tmp/mini-app-Caddyfile', '--adapter', 'caddyfile'], check=True)
    backup = config.with_name('Caddyfile.before-mini-app-' + str(time.time_ns()))
    shutil.copy2(config, backup)
    try:
        # Preserve the inode: existing bind mounts must see the new contents.
        config.write_text(updated)
        subprocess.run(['docker', 'exec', proxy, 'caddy', 'reload', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile'], check=True)
    except Exception:
        config.write_text(original)
        subprocess.run(['docker', 'exec', proxy, 'caddy', 'reload', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile'], check=False)
        raise


if __name__ == '__main__':
    install()
