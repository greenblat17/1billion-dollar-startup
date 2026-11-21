# Обслуживание инфраструктуры Speaky

[Процедура выкладки](operations.md#выкладка-и-восстановление) использует уже настроенные хосты. Согласуйте отдельное окно для обновления PostgreSQL, мониторинга, Alloy, edge, TLS и firewall.

## Общий порядок

1. Зафиксируйте окружение, хост и ревизию обслуживаемых файлов. Используйте проверенный SSH host key.
2. Дождитесь освобождения очереди окружения. На время работ согласуйте паузу новых выкладок и несовместимых операторских запусков.
3. Сохраните текущую конфигурацию в закрытом каталоге хоста. Проверьте способ возврата и готовность резервной копии данных, если операция затрагивает PostgreSQL.
4. Выполните одну операцию на нужном хосте под блокировкой `flock /var/lock/speaky-release-ENV.lock`. Замените `ENV` на `dev` или `prod`.
5. Проверьте зависимость и рабочую связку с приложением. Для DEV используйте **DEV diagnostics** в режиме `readiness`. Запишите окружение, результат и выполненное восстановление.

| Операция | Действие и проверка |
| --- | --- |
| PostgreSQL | Запустите проверенный [infra/postgres/deploy-remote.sh](../../infra/postgres/deploy-remote.sh) на хосте Ktor. Он читает `/opt/speaking-coach/.env`. Проверьте `docker exec postgres pg_isready`, наличие volume `speaking-coach-postgres` и подключение приложения. Для смены major-версии нужен отдельный план миграции. |
| Мониторинг Ktor | Поместите проверенные файлы из [infra/monitoring](../../infra/monitoring) в `/opt/speaking-coach/monitoring`. Сохраните частные `alerts.env`, `openrouter.env` и сгенерированные пароли. Запустите `bash /opt/speaking-coach/monitoring/deploy-remote.sh`. Проверьте Prometheus targets, Grafana и данные Loki. |
| Alloy на AI | Запустите проверенный [deploy-alloy.sh](../../infra/ai-service/deploy-alloy.sh) на хосте AI. Проверьте состояние `ai-service-alloy` и появление нового технического события AI в Loki. |
| DEV Mini App edge | Загрузите [install-edge.py](../../infra/miniapp/install-edge.py) и [Caddyfile.dev.example](../../infra/miniapp/Caddyfile.dev.example) в один каталог на edge-хосте. Запустите скрипт с блокировкой DEV. Он проверяет Caddyfile перед reload. Проверьте HTTPS, `/miniapp/assets/app.js` и проксирование к AI. Ревизия доступна в `/version.json` внутри контейнера. |
| TLS и CA | Подготовьте согласованную пару `/opt/speaking-coach/tls.crt` и `tls.key`. При собственном CA обновите `/opt/ai-service/foundation-backend-ca.pem`. Примените изменения в согласованном окне и проверьте цепочку TLS с хоста AI и с edge. |
| Ограничение порта AI | Разместите [restrict-8090.sh](../../infra/ai-service/restrict-8090.sh) и [ai-service-port.sh](../../infra/ai-service/ai-service-port.sh) в `/opt/ai-service`. Проверьте `8090.allow` и `AI_SERVICE_PORT`. Установите [restrict-8090.service](../../infra/ai-service/restrict-8090.service), выполните `systemctl daemon-reload` и `systemctl enable --now restrict-8090.service`. После изменения списка примените `systemctl restart restrict-8090.service`. Проверьте правила INPUT/DOCKER-USER и запрос с разрешённого хоста. |

## Таймер DEV test config

Установите таймер после первой выкладки через новый release pipeline. Используйте те же проверенные исходники, что для операторских workflow. На DEV-хостах проверьте синхронизацию времени и режим доступа `600` к базовому `.env`. В базе оставьте `AGENT_TEST_ENABLED=false` или удалите ключ. Для отключённого billing оставьте `SPEAKY_BILLING_PROVIDER` пустым или удалите ключ.

На каждом DEV-хосте выполните из проверенного checkout:

```bash
sudo install -d -m 0755 /opt/speaky-release
sudo install -m 0755 infra/release/host-install.py /opt/speaky-release/host-install.py
sudo install -m 0644 infra/release/speaky-dev-test-config-expiry@.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
# Задайте component=ktor на хосте Ktor и component=ai на хосте AI.
sudo systemctl enable --now "speaky-dev-test-config-expiry@$component.timer"
sudo systemctl list-timers "speaky-dev-test-config-expiry@$component.timer"
```

Релиз и изменение fixtures используют одну хостовую блокировку. Таймер проверяет срок каждую минуту и применяет базовую конфигурацию к тому же образу. После изменения installer обновите его копию для таймера. Если таймер сообщил об ошибке, проверьте `journalctl -u "speaky-dev-test-config-expiry@$component.service"`, состояние контейнера и test flags до следующего запуска приёмки.
