# Предлагаемый текст для общего status.md

Родителю: объединить после интеграции SP-03/SP-04. Общий status.md в SP-03 не изменён.

## Регулярные уроки SP-03

Реализован regular voice через существующий Realtime/Marin и Mini App. Первый вход требует
согласованной executable встречи на 15–60 минут, актуального сохранённого плана и анализа прошлых уроков.
Model-backed planner получает общую память и готовит отдельные образовательную цель, тему и гибкую стратегию.
Голосовые инструкции задают Guided Conversation, discovery/deep conversation, минимальную помощь,
адаптацию речи и устное завершение с feedback. Следующая встреча читается из backend; отсутствующая не выдумывается.
Reconnect сохраняет историю, принятый план и исходное время окончания. Для длинного regular есть смена
provider-сессии через тот же backend admission. Natural goodbye не создаёт автоматический regular Telegram-отчёт.

Локальные проверки SP-03: Python **607 passed**, Kotlin **281 unit + 32 PostgreSQL integration**,
без failed/skipped; fat JAR и detekt прошли. Frontend: **5 tests passed**, syntax/build прошли.
PostgreSQL — отдельный локальный кластер; модели, Realtime и S3 — подставные.
Новые проверки покрывают текущий план/анализ, admission, duration 15/60 и отказ 14/61,
reconnect с историей, finish, ранний уход, next lesson/null/error и совместимость trial.
Подставные модели/Realtime/S3 не доказывают живое качество урока и смены транспорта.
Совместная приёмка с SP-04 `lesson.next`/Schedule, Telegram WebView, педагогика, паузы/эхо и
полный 60-минутный звонок остаются открыты. Поставка не выполнялась.

## Изменение для architecture.md при объединении

В описании фоновой работы заменить утверждение об отсутствующем production lesson planner:
shipping root использует `LessonPlanner` из `regular_lesson.py`; regular генерируется моделью,
trial сохраняет прежний briefing. Канонический план, его версии и admission остаются у Ktor.
Добавить regular path к SP-02 relay: frozen execution_snapshot, backend lesson.next,
завершение от согласованного времени и provider rotation через новый Call Session.
Материализацию/notifications описать по итоговому SP-04, не по этой ветке.
