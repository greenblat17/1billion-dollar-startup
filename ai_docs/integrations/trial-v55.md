# Trial v5.5 — исполнение и исходные материалы SP-06

Владелец образовательных Observations — SP-17A/B; диагноза — SP-07. Этот контракт описывает
только исполнение звонка, пользовательские предпочтения и фактические источники.

## Исполнение

Новые активные Trial создаются на 30 минут с `lesson.trial_version=v5.5`.
`trial.ready` начинает отсчёт после подтверждённой конфигурации Realtime.
Backend суммирует время всех подключений Lesson. При истечении lease учитывается только
последнее подтверждённое heartbeat-время, а не время простоя до обнаружения обрыва.
Отсчёт включает паузы внутри подключённого разговора; это не измерение речевой беглости.

Повторный admission разрешён строго до `starts_at + duration_minutes`.
Если ученик успел вернуться, активный звонок использует остаток 30 минут и после конца слота.
Отключённый Trial после окончания слота worker закрывает с `technical_disconnect`.
End button завершает Lesson сразу. Natural goodbye ждёт окончания playback и доступных
pending transcripts; ранний natural goodbye отмечается `ended_early` по времени, без диагноза.
Недоставленные ASR-результаты при закрытии транспорта не выдумываются; сохранённое аудио остаётся источником.

Фазы `warmup → conversation → wrapup` сохраняются в Lesson. Их меняет native tool
`set_trial_phase`; backend запрещает движение назад. Подсказки модели на 5/25/30 минутах мягкие.
Они не являются доказательством фактического языка или качества разговора.
Mini App получает `trialExecution.connectedSeconds` при подключении и heartbeat;
локальный таймер между сообщениями интерполирует этот серверный отсчёт.

## Предпочтения нагрузки

`get_trial_preferences` читает SP-03; `update_trial_preferences` вызывает `trial.preferences`.
Команда доступна только активному voice scope этого Lesson v5.5. Она принимает:

- `lessonId`, `callSessionId`, `sourceId`, `expectedRevision`;
- `changes`: `{field, value, evidence}`;
- поля `lessonsPerWeek`, `lessonDurationMinutes`, `consent`, `minutes`, `period`.

Backend проверяет user source этого Lesson, точную цитату, текущую ревизию, типы и ограничения SP-03.
Новые подтверждённые значения защищены от запоздалой записи более старого источника.
Отсутствующее поле сохраняется; null очищает nullable-поле. CEFR, цель, Topics и расписание недоступны.
Положительные Homework minutes требуют period в согласованном состоянии. `consent=no` не блокирует урок.
Диапазоны и смысл слов уточняет модель: backend не является семантическим проверяющим цитат.
Реальное следование этим правилам требует живой приёмки.

## Исходный call envelope v1

`GET /internal/foundation/v1/trial/events?lessonId=…&offset=0` — service token + student scope.
Допускаются operator, learning и voice; scope с Lesson не может читать другой Lesson.
Это GET, поэтому доступен и ограниченному learning job scope. Пагинация — 100 событий, `nextOffset`.
Для завершённого Lesson курсор стабилен; при чтении активного звонка потребитель дедуплицирует по ID/revision.

```json
{
  "eventId": "raw-event-id",
  "sourceEventId": "conversation-event-id-or-null",
  "sourceSequence": 4,
  "lessonId": "lesson-id",
  "callSessionId": "session-id",
  "revision": 1,
  "kind": "transcript",
  "occurredAt": "2026-11-16T12:00:00Z",
  "body": "Actually recorded transcript",
  "sourceContentAvailable": true,
  "metadata": {
    "role": "user",
    "providerItemId": "provider-item",
    "finality": "final",
    "turnId": "session-scoped-source-key",
    "asrConfidence": null,
    "uncertaintySignals": []
  }
}
```

`eventId` — ID транспортной записи; `sourceEventId` — существующий conversation_event для provenance SP-03/17.
`revision` относится к одному raw source, не к sequence и не к transcript_revision всего Lesson.
Replay возвращает те же ID. Изменённый текст требует следующую revision, сохраняет оба ID;
sourceSequence обновляется, чтобы прежние ссылки SP-03 отражали устаревший источник.
Первая запись имеет revision=1. Reconnect создаёт новый callSessionId; providerItemId имеет смысл только внутри него.
Реплика, действительно повторённая учеником после reconnect, — новый источник; её педагогический смысл определяет SP-17B.

`turnId` — техническая опора, не доказательство одной учебной попытки. Самокоррекции, подсказки,
независимость, Topics/Skills/Problems и достаточность данных классифицирует SP-17B.
`connected`, `phase`, `ended` — отдельные lifecycle events без учебной интерпретации.

ASR confidence/logprobs сейчас не получаются; null означает unknown quality, не invalid.
`uncertaintySignals=[]` означает отсутствие явно предоставленных сигналов, а не высокое качество.
Детектор ASR-ошибок и повторная транскрипция не реализованы.
Сохраняемые provider audio spans помечены `audioOffsetBasis=provider_input_buffer`.
Время user source фиксируется при speech-start/commit, если событие доступно, а не при позднем ASR-ответе.

Assistant transcript попадает в историю после bounded playback report. При частичном playback
сохраняется явный placeholder вместо полного неслышанного текста; `finality=partial`, `playedMs`,
`playbackComplete=false`. Client report не доказывает, что человек услышал или понял речь.

`GET /internal/foundation/v1/trial/audio?lessonId=…&cursor=…` возвращает только реальные stored объекты
текущего data epoch, по 200 с `nextCursor`; signed URL и credentials не возвращаются.
User archive chunks имеют `audioStartMs/audioEndMs` и `audioOffsetBasis=archived_input_pcm`;
`forwardRequested` — намерение пересылки, не подтверждение приёма провайдером.
Два пространства смещений не объявляются автоматически синхронизированными.
Assistant audio связывается по callSessionId + metadata.utteranceId/providerItemId.
Отсутствующая ссылка/фрагмент остаётся отсутствующей. Аудио удаляется по существующим правилам 30 дней.

Текст не дублируется в `trial_call_event`: read соединяет ссылку с conversation_event.
Retention, forget/delete/reset удаляют или очищают источники по существующим границам;
новая таблица также включена в privacy cleanup. Raw journal не содержит Observation-модель.

## Handoff и старый pipeline

Завершение ставит `lesson.evaluate` с `analyzerVersion=trial-memory-v55`, `memoryOnly=true`.
Это отдельный Personal Memory extractor без educational observations/notes. Backend отклоняет
образовательные результаты для этого Trial. Старый `RoadmapPreparation` исключает v5.5 Trial.
Старый `trialResult` не показывает memory-only результат как диагностику.
`GET /trial/events` возвращает `observationStatus` и `assessmentStatus` для отдельных jobs.
После успешного SP-17B batch запускается [SP-07 Assessment](trial-assessment.md), который
сохраняет предварительный диагноз в SP-03. Готовые Observations сами по себе не являются диагнозом.

Follow-up благодарит за разговор без выдуманного плана и продажи; при обрыве предлагает допустимый
возврат или новую бесплатную запись. Полная доставка Roadmap — SP-08/11A. Релиз с обещанием плана
требует их реальной сквозной проверки; успешные тесты SP-06 не закрывают эту приёмку.

Обязательное последующее удаление legacy:

| Остаток | Когда удалять |
| --- | --- |
| `VOICE_POLICY`, старый `REALTIME_VOICE_POLICY`, 13-minute cue и старый TrialSession | После удаления последних historical adapters/tests; новый Trial использует `trial_v55.POLICY` |
| Educational ветка `TrialAnalyzer`, старые memory_observation/evidence projections | После замены потребителей regular в SP-17C/SP-20; не удалять Personal Memory extractor |
| Старые RoadmapPreparation/RoadmapPlanner, progress projections и старый follow-up | После SP-07/08/11A и замены оставшихся regular/My Progress потребителей |
| `lesson.evaluate` как общий контейнер memory-only job | Пересмотреть при SP-17B/SP-07; не принять его статус за готовую диагностику |

Родитель ведёт удаление после интеграции зависимостей. Ни старую историю, ни Personal Memory,
ни аудиоархив нельзя удалять вместе с устаревшими обработчиками.

## Локальная проверка и поставка

2026-11-16: Python 701, Kotlin unit 309, PostgreSQL integration 96, HTTP integration 11,
Mini App 16 tests — passed. После последнего read-scope guard отдельно повторены 7 Trial integration tests.
Fat JAR, detekt и Mini App production build прошли. V52 зарезервирована родителем.
Изменения не закоммичены и не поставлены. Живой Realtime/Telegram/S3 и DEV не проверены.
Browser Automation заблокирован ошибкой EATTESTATIONVERIFY; визуальная приёмка не подтверждена.
