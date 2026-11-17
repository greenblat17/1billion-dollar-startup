# Приёмка волн 1–9 на DEV · 17 ноября 2026

**Полная приёмка открыта.** Полный Trial завершился. Оценка и Roadmap сохранены. Обещание Roadmap, запись Homework и `lesson.evaluate` дали сбой. Запись обычного урока подтверждена, подготовка остановилась на HTTP 402. Доставка Roadmap и обычный урок до Review остаются открыты. [Сверка каждого AC](dev-ac-reconciliation-2026-11-17.md), [PR #58](https://github.com/greenblat17/1billion-dollar-startup/pull/58), [версии и readiness](../status.md).

AI `aed54d0` поставлен на DEV, независимая проверка готовности прошла. Правки вступления Trial, сообщения о Roadmap и распознавания рабочей цели ждут живого повтора. [Поставка](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44168130996), [проверка готовности](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44168688013).

## Закрытые критерии

| Критерий | Наблюдение | Статус | Следующее действие |
| --- | --- | --- | --- |
| [SP-02 AC-01](https://app.notion.com/p/418045c3d5768193afb4fb73af379e8d) — шесть навыков | В опубликованной версии четыре навыка MVP и два будущих. [Чтение каталога][catalog] | Закрыт | — |
| SP-02 AC-02 — девять типов проблем | Каталог вернул девять типов с ID, определениями и привязкой к навыкам. [Чтение каталога][catalog] | Закрыт | — |
| SP-02 AC-07 — каталог без данных ученика | В каталоге отсутствуют личные знания, цели, задачи и показатели. [Чтение каталога][catalog] | Закрыт | — |
| SP-02 AC-08 — свободный порядок тем | В Topic Map нет фиксированного графа требований или порядка обучения. [Чтение каталога][catalog] | Закрыт | — |
| SP-02 AC-11 — Reading и Writing | Оба навыка есть в карте как будущие. [Чтение каталога][catalog] | Закрыт | — |
| [SP-03 AC-01](https://app.notion.com/p/418045c3d576815dac08f4a28ee6edc1) — исходное состояние | Новый ученик получил ревизию 0, неизвестные уровни и пустую цель. [Чтение состояния](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44131822603) | Закрыт | — |
| SP-03 AC-04 — отдельные цель и целевой уровень | Цель «B2 for work» и target B2 записаны отдельно. Чтение и повтор операции совпали. [Запись и повтор](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44133802363) | Закрыт | — |
| [SP-17B AC-5](https://app.notion.com/p/41a045c3d5768186b157e286e8486781) — передача Observations в оценку | `trial.observe` и `trial.assess` завершились успешно. Системный Speaking записан как A2. [Состояние после звонка][postcall] | Закрыт | — |
| [SP-17 AC-08](https://app.notion.com/p/418045c3d5768127b1dbcce526d667de) — неизвестная тема | Тема с единичной демонстрацией осталась Unknown. [Состояние после звонка][postcall] | Закрыт | — |
| SP-06 AC-04/08 и SP05-AC02/04 — адаптация и исходные реплики | Саша упростил вопрос и уточнил нагрузку. Сохранены 28 реплик, ссылки на них прочитаны из Observations. [Полный Trial][fulltrial], [оценка и Observations][learning] | Закрыт на синтетическом Voice | — |
| SP-07 AC-02/03/05/07, SP-17B AC-1 — оценка и источники | 11 Observations. Speaking и Overall B1, Grammar A2, Listening/Vocabulary Unknown. Self B1 сохранён отдельно. Roadmap использует Learning State rev2. [Оценка][learning], [Roadmap][roadmap] | Закрыт | — |
| SP-08 AC-02/04/08, SP-09 AC-01 — построение и ближайший фокус | Roadmap rev1 содержит 106 Task ID по четырём навыкам. Старт у Skills разный, строки помечены гипотезами. Все 106 Tasks прочитаны: Identified, Progress 0. Priority использует ту же Roadmap. [Roadmap][roadmap], [все Task][alltasks] | Закрыт | — |
| SP-02 AC-09, SP-04 AC-01/14 — каталог, Task и изоляция | Поиск по ID и фильтр Grammar+A2 вернули одинаковую запись. Три ревизии своей Task прочитаны. Чтение этой Task другим учеником вернуло ожидаемый HTTP 400 `not_owned`. [Чтение][fixtureowned], [проверка изоляции][fixtureforeign] | Закрыт на DEV API | — |
| SP-12A AC-04/05, SP-13A AC-04, SP-17A AC-02/03, SP-12 AC-05, SP-13 AC-08, SP-19 AC-01 — контракты хранения | Совпали ссылки Task, Plan, Stage и Content. Повтор Observation оставил одну запись. Первая оценка обеих стадий равна 50, Progress 0. [Запись][fixturewrite], [чтение][fixtureowned] | Закрыт на синтетическом DEV API | — |

SP-05 AC-01/02 подтверждены в согласованном объёме Telegram-знакомства. Для AC-06 текстовый API создал немедленный Trial при пустом профиле. Telegram отдельно подтвердил запись и кнопку. Девять из десяти прежних DEV-отметок сохранены. SP-17 AC-11 открыт после сбоя в полном Trial. [Текстовый отчёт](runs/text-ac-results.md), [проверка Telegram](runs/telegram-vlama-2026-11-17.md).

## Открытые критерии

В таблице приведены свежие результаты и ближайшие действия. Постатейные критерии и первоначальные прогоны сохранены в [тексте](runs/text-ac-results.md), [проверке Foundation](runs/foundation-contract-ac-2026-11-17.md), [пробном уроке](runs/trial-voice-ac-2026-11-17.md) и [обычном уроке](runs/regular-lesson-ac-2026-11-17.md). Статусы ниже учитывают более поздние прогоны.

| Критерий | Наблюдение | Статус | Следующее действие |
| --- | --- | --- | --- |
| SP-02 AC-03–06/13 — содержание и источники | DEV вернул 163 темы. Содержательное покрытие, смысловые дубли и источники требуют ревью. [Чтение каталога][catalog] | Открыт, не проверен полностью | Провести экспертное ревью по четырём навыкам и A1–C1. Сохранить решения по каждой спорной теме и сравнить примеры Present Perfect. |
| SP-02 AC-09/10/12/14 — фильтры, потребители и совместимость ID | Общее чтение каталога прошло. [Чтение каталога][catalog] | Открыт, не проверен полностью | Сравнить выборку по ID и Skill/CEFR. Проследить версию в сервисах. Проверить Grammar Problem на уроке и чтение старой задачи после обновления каталога. |
| SP-03 AC-02/03/05–14, SP-04 — состояние и задачи | Доказаны пустое чтение и запись цели. Локальный отчёт перечисляет проверки каждой операции. [Проверка Foundation](runs/foundation-contract-ac-2026-11-17.md) | Открыт, не проверен полностью | Через изолированные API создать состояния двух учеников и задачу. Проверить ограничения записи, историю, повтор операции, перезапуск и переходы задачи. |
| SP-05 AC-03–05/07–09 — профиль и передача контекста | Telegram доставил приглашение. Синтетический текст сохранил цель и самооценку. Голос повторно запросил известную тему. [Текст](runs/text-ac-results.md), [Telegram](runs/telegram-vlama-2026-11-17.md), [Trial](runs/trial-voice-ac-2026-11-17.md) | Открыт, проверка частичная | Вернуться к известной цели в Telegram, затем открыть Trial того же ученика. В перенесённых AC по нагрузке уточнить «2–3 раза» и «немного домашки», затем сохранить явный отказ. |
| SP-06 AC-01/02/03/05/09 — длительность, фазы, контекст и порядок | Сервер подтвердил 1 844 секунды и natural_goodbye. Conversation включилась через 2,519 с до первой реплики ученика, вступление не использовало его рабочую цель. Один прерванный ответ не восстановлен. [Полный Trial][fulltrial] | Сбой AC-02/03. Остальные проверены частично | Повторить вход и reconnect с известной целью. Сверить архив прерванного ответа. Проверить порядок предложений после Roadmap в Telegram. |
| SP-06 AC-06 — вопросы, непонимание и ранний выход | Короткий урок прислал `finished`. В базе `completed` и `ended_early=true`. Подтверждение второго аудиофрагмента пришло слишком поздно для клиента. [Звонок][early], [состояние][postcall] | Открыт, ранний выход подтверждён | Доработать обработку `finished` в клиенте. В одном уроке проверить также вопрос и просьбу объяснить непонятное. |
| SP-06 AC-07 и SP05-AC05 — Roadmap и отказ от Homework | Wrap-up не обещал Roadmap в Telegram. Явный отказ от Homework есть в реплике, а согласие в PostgreSQL осталось Unknown. [Полный Trial][fulltrial], [Learning State][learning] | Открыт, сбой | Добавить анонс Roadmap. Проверить получение стабильного ID реплики в записи предпочтений и сверить `consent=no`. |
| SP-17 AC-11 — существующий анализ звонка | AI `3e84655` отклонил весь memory-only batch из-за неизвестного источника или неточной цитаты. Ktor получил HTTP 400 до `job.apply`. В `e2aeeb3` исправлена фильтрация, локально прошли 867 Python-тестов. Платного DEV-повтора нет. [Лог][aievalfailure], [AI-поставка][latestai] | Открыт, сбой | После восстановления бюджета и нового разрешения подтвердить валидные факты и `insufficient_evidence` для пустого набора. |
| SP-17B AC-3, SP-07 AC-06 и SP-17 AC-04 — качество ASR | Искажённое «deadline in Ricky» использовано в обосновании Speaking B1 как usable. Две другие записи с `missing_context` непригодны. [Оценка и Observations][learning] | Открыт, сбой | Проверить маркировку по аудио и транскрипту. Подать паузу, явную ASR uncertainty и Vocabulary. |
| SP-08 AC-06 — влияние рабочей цели | Цель про рабочие встречи сохранена, но у всех 106 элементов `goalFocus=false`. [Roadmap][roadmap] | Открыт, сбой | Воспроизвести `_goal_focus` с опубликованным каталогом и тем же профилем. |
| SP-08, SP-11A и этап A SP-11 — остальные проверки Roadmap и доставка | Сохранены Roadmap rev1 и priority. Прочитаны все 106 Task страницами 50+50+6. У синтетического ученика нет Telegram-адресата. [Roadmap][roadmap], [Task list][alltasks] | Открыт, проверка частичная | Проверить повтор генерации. Получить Roadmap в тестовом Telegram без подписки и сверить сообщение с сохранёнными данными. |
| SP-12B AC-4 — смена действия по просьбе ученика | Обычный урок не использует переход плана по прямому запросу. [Обычный урок](runs/regular-lesson-ac-2026-11-17.md) | Открыт, сбой интеграции | Подключить переход к обычному уроку. Запросить смену действия и сверить реплики и сохранённый этап. |
| SP-13 AC-02 и подготовка SP-16 — генерация Content | На `f40dc58` в `strategy` было 3 723 символа при лимите 3 000. Repair получил HTTP 402 `openrouter_credits`. `lesson.prepare` завершился `failed / budget_exhausted`. В `6bf0061` первый запрос просит до 1 200 символов без обрезки. Локально прошли 52 теста. [Повтор][budgetretry], [AI-поставка][latestai] | Открыт, сбой подготовки и ограничение бюджета | После восстановления бюджета и нового разрешения получить готовый Plan и прочитать Content четырёх методов. |
| SP-16 AC-01 — обычный урок через Mini App | На первом прогоне записался Trial. Два DEV-повтора подтвердили `kind=regular / timing=now / 30m`. Подготовка не дошла до ready. Голос не запускался. [Сбой записи][regular], [первый повтор][regularretry], [последний повтор][budgetretry] | Запись подтверждена, сессия открыта | После готовности подготовки проверить вход и завершение через Mini App. |
| SP-09/10, остальные SP-12/13, SP-14–16, SP-17A/C, SP-18–20 — обычный урок и прогресс | Task из опубликованного каталога прочитана. После остановки подготовки Plan=null, Content=[]. Учебных действий и Review нет. [Повтор][budgetretry], [постатейная сверка](dev-ac-reconciliation-2026-11-17.md) | Открыт, не проверен полностью | После готовности Plan и Content провести действие. Прочитать Observation, Score, Progress и Review. Повторить событие завершения. Прогноз проверить отдельно при Homework No и Unknown. |
| Этап B SP-11, SP-11B и SP-21 — полный My Progress и Homework | Эти поставки выходят за рамки волн 1–9. [Матрица](dev-waves-1-9-matrix.md) | Открыт, зависит от поставки | После поставки сверить видимый Progress с SP-19 и проверить результаты Homework. |

[catalog]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44150548146
[early]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44151150187
[postcall]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44151829965
[regular]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44155917092
[fulltrial]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44155920176
[learning]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44161700195
[roadmap]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44161878970
[alltasks]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44162449917
[evalfailure]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44162684508
[aievalfailure]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44163551674
[regularretry]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44162764114
[planrepair]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44164549223
[budgetretry]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44164723237
[latestai]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44165402409
[fixturewrite]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44167995258
[fixtureowned]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44168661882
[fixtureforeign]: https://github.com/greenblat17/1billion-dollar-startup/actions/runs/44168814920
