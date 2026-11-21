# SP-02 v5.5: учебные каталоги — содержательная приёмка

> Сгенерировано из `learning-catalog/v1/catalog.json`. Править исходный JSON и повторять `python3 scripts/render-learning-catalog.py`.

Версия: `speaky-v55-v2`. **Содержание ожидает приёмки пользователя. В runtime не опубликовано.**

Подготовлены 6 навыков, 163 Topics, 9 типов проблем.
Число записей описывает этот проект, а не согласованную норму полноты. Каталог не является сертифицированной программой CEFR.

## Что подтверждено и что требует проверки

- Подтверждено продуктом: шесть навыков, четыре в MVP, девять типов проблем, отдельные уровневые Topics, общий каталог без ученических состояний.
- Подготовлено агентом: весь набор тем A1–C1, формулировки способностей, конкретное распределение уровней.
- Ожидает вашей приёмки: полнота и границы тем, уровни каждой записи, ожидаемые способности.
- Не проверено на учениках: диагностическая различимость тем, достаточность покрытия реальных целей, качество переноса между голосами и ситуациями.

## Обзор покрытия

| Навык | A1 | A2 | B1 | B2 | C1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Speaking | 7 | 7 | 7 | 7 | 7 |
| Listening | 6 | 6 | 6 | 6 | 6 |
| Grammar | 13 | 12 | 13 | 12 | 9 |
| Vocabulary | 8 | 8 | 8 | 8 | 7 |
| Reading | Вне MVP | Вне MVP | Вне MVP | Вне MVP | Вне MVP |
| Writing | Вне MVP | Вне MVP | Вне MVP | Вне MVP | Вне MVP |

Области покрытия: Grammar — простое предложение, время/аспект, вопросы, модальность, условность, залог, связь частей и смысловой фокус; Speaking — контакт, обмен, рассказ, объяснение, аргументация, взаимодействие и восстановление понимания; Listening — детали, диалоги, инструкции, рассказы, объяснения, медиа, неявный смысл и разнообразие речи; Vocabulary — бытовые и рабочие области, сочетаемость, переносные значения, точность и регистр.

## Вопросы содержательной приёмки

1. Источники Grammar часто объединяют A1–A2 и B1–B2. Разделение внутри диапазонов — решение Speaky, а не точная разметка источника.
2. Present Perfect introduction оставлен на A2 по каркасу v5.5; contrast на B1. Past Perfect оставлен на B2. Эти границы требуют отдельной проверки.
3. Topic Map содержит только целевые способности. Зависимости и последовательность обучения относятся к будущей Roadmap Generation.
4. Каталог не содержит prerequisites, обязательного Curriculum Order или условий допуска к Conversation.
5. Vocabulary описывает общие области и способности. C1 goal-specific terminology — способность работать с нужной терминологией, а не готовый отраслевой словарь.
6. Listening требует разнообразных источников речи. Понимание одного голоса репетитора не доказывает весь Topic.
7. Fluency включена в самостоятельное поддержание речи; численных норм скорости и пауз нет. Pronunciation, Reading и Writing не расширяют обязательный объём MVP.
8. Повторяющиеся области на разных уровнях различаются объёмом способности. Проверить, что границы позволяют отличить уровни, а не просто повторяют названия.

## Skill Map

| ID | Skill | Содержание | MVP |
| --- | --- | --- | --- |
| speaking | Speaking | Свободное самостоятельное выражение мыслей в речи. | Да |
| listening | Listening | Понимание устного английского. | Да |
| grammar | Grammar | Знание и применение конструкций. | Да |
| vocabulary | Vocabulary | Понимание и самостоятельное употребление слов и выражений. | Да |
| reading | Reading | Понимание письменного английского; вне фокуса MVP. | Нет |
| writing | Writing | Создание письменных высказываний и текстов; вне фокуса MVP. | Нет |

## Problem Map

| ID | Skill | Тип | Содержание |
| --- | --- | --- | --- |
| speaking_barrier | speaking | Языковой барьер | Требуется самостоятельная разговорная практика для уверенности и автоматизма; не дублирует отдельные грамматические пробелы. |
| listening_comprehension | listening | Недостаточное понимание устной речи | Ученик затрудняется извлекать смысл из обращённой речи или аудио в условиях данной темы. |
| grammar_unknown | grammar | Не знает конструкцию | Конструкция незнакома: нужны объяснение значения и начальное освоение. |
| grammar_application | grammar | Знает, но ошибается в применении | Правило знакомо, но выбор или построение формы нестабильны; требуется закрепление и перенос в речь. |
| vocabulary_passive | vocabulary | Недостаточный пассивный словарь | Ученик не узнаёт или не понимает нужные слова и выражения. |
| vocabulary_active | vocabulary | Недостаточный активный словарь | Ученик понимает лексику, но не извлекает и не применяет её самостоятельно. |
| reading_comprehension | reading | Недостаточное понимание текста | Трудно извлекать смысл письменного материала. |
| writing_sentences | writing | Сложности с предложениями | Трудно самостоятельно формулировать мысли письменно на уровне предложения. |
| writing_text | writing | Сложности со связным текстом | Трудно выстраивать последовательное письменное высказывание. |

Типы проблем применимы к Topics соответствующего Skill. Наличие типа в каталоге не является диагнозом ученика.

## Источники и границы их использования

- **speaky-v55** — [Speaky Product Specification v5.5](../../ai_docs/product/2026-11-14-personalized-learning-v5.5.md), §2–4, §6. Согласованные навыки и проблемы; предварительный каркас тем. Не нормативная программа CEFR.
- **cefr-oral** — [Council of Europe: CEFR Companion Volume 2020](https://rm.coe.int/cefr-companion-volume-with-new-descriptors-2020/16809ea0d4), §3.1.1.1, §3.2.1.1, §3.3.1.1; §3.2.2, §3.3.2. Сверка областей устной коммуникации. Авторские Topics Speaky не являются копиями дескрипторов или сертификацией CEFR.
- **cefr-linguistic** — [Council of Europe: CEFR Companion Volume 2020](https://rm.coe.int/cefr-companion-volume-with-new-descriptors-2020/16809ea0d4), §5.1: linguistic range, vocabulary range/control, grammatical accuracy. Сверка широты и контроля языка. Не задаёт список обязательных английских конструкций или слов.
- **bc-grammar-basic** — [British Council: A1–A2 grammar](https://learnenglish.britishcouncil.org/free-resources/grammar/a1-a2), Указатель учебных тем A1–A2. Проверен диапазон раздела; распределение между A1 и A2 и связи — редакционные решения Speaky, ожидают приёмки.
- **bc-grammar-intermediate** — [British Council: B1–B2 grammar](https://learnenglish.britishcouncil.org/free-resources/grammar/b1-b2), Указатель учебных тем B1–B2. Проверен диапазон раздела; отдельные уровни Topics не выдаются за утверждения источника.
- **bc-grammar-advanced** — [British Council: C1 grammar](https://learnenglish.britishcouncil.org/free-resources/grammar/c1), Advanced passives; emphasis; ellipsis; contrasting ideas. Сверка выбранных областей C1; авторские формулировки и граф Speaky требуют приёмки.
- **bc-speaking** — [British Council: Speaking](https://learnenglish.britishcouncil.org/free-resources/speaking), Обзор A1–B2. Дополнительная сверка учебных ситуаций A1–B2; источник не обосновывает C1.
- **bc-listening** — [British Council: Listening](https://learnenglish.britishcouncil.org/free-resources/listening), Обзор A1–C1. Сверка изменения условий аудирования по уровням; понимание одного голоса не доказывает перенос.
- **bc-vocabulary** — [British Council: Vocabulary](https://learnenglish.britishcouncil.org/free-resources/vocabulary), Тематические разделы лексики. Справочная сверка тематического подхода; конкретные уровни Topics и отраслевой набор авторские, не словник источника.

Формулировки Topics и rationale написаны для Speaky. Тексты упражнений, дескрипторы, аудио и словники внешних источников не импортировались. Ссылки сверены 2026-11-15; указатель раздела не означает независимую проверку каждого Topic.

## A1

### Speaking

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| speaking-a1-introductions: Introducing yourself | Начать знакомство и сообщить основные сведения о себе. | Имя, место жизни, занятие; допустимы паузы и помощь, без длинной биографии. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a1-needs: Expressing immediate needs | Попросить нужный предмет или простую помощь. | Короткие бытовые запросы; без сложной претензии. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a1-questions: Asking and answering personal questions | Обменяться простой информацией о себе и собеседнике. | Один вопрос и ответ, уточнение имени; без интервью по списку тестов. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a1-description: Describing familiar people and places | Коротко описать знакомого человека или место. | Понятные признаки и расположение; без развёрнутого сравнения. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a1-routines: Talking about daily routines | Рассказать о типичном дне простыми репликами. | Знакомые действия и время; без сложной причинной связи. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a1-repair: Asking for repetition and clarification | Сообщить о непонимании и попросить повторить или показать смысл. | Простые фразы восстановления диалога; не проверка произношения. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a1-preferences: Expressing likes and dislikes | Назвать предпочтение и спросить о предпочтении собеседника. | Еда, занятия, простые варианты; без аргументированного спора. | speaky-v55, cefr-oral, bc-speaking |

### Listening

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| listening-a1-personal: Recognising personal information | Извлечь имя, место и занятие из короткого знакомства. | Медленная ясная речь, знакомые слова; без обобщения на любые акценты. | speaky-v55, cefr-oral, bc-listening |
| listening-a1-numbers: Recognising numbers, times and prices | Понять значимые числа, время и цену в короткой реплике. | Различение конкретных значений; допускается повтор; без фонового шума. | speaky-v55, cefr-oral, bc-listening |
| listening-a1-instructions: Following simple instructions | Выполнить короткую знакомую инструкцию после прослушивания. | Одно простое действие, ясная речь; без сложной последовательности. | speaky-v55, cefr-oral, bc-listening |
| listening-a1-questions: Understanding simple direct questions | Понять, какую личную информацию спрашивает собеседник. | Знакомые вопросительные модели; без косвенного смысла. | speaky-v55, cefr-oral, bc-listening |
| listening-a1-familiar-words: Recognising familiar words in short speech | Узнать знакомые слова внутри короткой фразы. | Не список изолированных слов; ясная артикуляция; без проверки написания. | speaky-v55, cefr-oral, bc-listening |
| listening-a1-short-exchange: Following a simple exchange | Понять потребность и ответ в коротком бытовом обмене. | Две связанные реплики, ясная ситуация; без требования каждого слова. | speaky-v55, cefr-oral, bc-listening |

### Grammar

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| grammar-a1-be: Be: identity and state | Сообщить, кто человек и в каком он состоянии, используя be. | Утверждение, отрицание и вопрос с am/is/are; без прошедшего времени. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-pronouns: Personal pronouns and possession | Указать участника действия и принадлежность предмета. | I/you/he и объектные формы; my/your и притяжательное 's; без сложных именных групп. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-articles: Articles and singular/plural nouns | Назвать один или несколько знакомых предметов. | a/an, базовое the, регулярное и частотное нерегулярное множественное число; без абстрактного употребления. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-there: There is/are and place | Описать наличие и расположение предметов. | there is/are, in/on/under/next to; конкретное окружение. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-have: Have and have got | Сообщить о семье, вещах и доступных ресурсах. | have/has, have got, отрицание и вопрос; без perfect. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-present-simple: Present Simple: routines | Рассказать о привычных действиях и задать вопрос о них. | Утверждение, -s, do/does, отрицание; без рассказа о прошлом. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-frequency: Time and frequency expressions | Указать, когда и как часто происходит привычное действие. | always/usually/sometimes, at/on/in для времени; без сложных временных отношений. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-questions: Basic question patterns | Запросить личную информацию вопросом с what/where/when/who/how. | Порядок слов с be и do; без косвенных вопросов. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-can: Can: ability and permission | Сказать о простой способности и попросить разрешение. | can/cannot и вопрос; без вероятностного значения. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-imperatives: Imperatives and simple requests | Дать простую инструкцию или попросить прекратить действие. | Утвердительная и отрицательная инструкция, please; без сложных вежливых оборотов. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-present-continuous: Present Continuous: happening now | Описать действие, происходящее сейчас. | be + -ing; вопросы и отрицание; без будущих договорённостей. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-quantity: Basic quantity and countability | Назвать количество еды или предметов в простой ситуации. | some/any, much/many, базовые исчисляемые и неисчисляемые; без тонких значений артиклей. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a1-linking: And, but, because | Связать простые сообщения и назвать причину. | Короткие соединённые предложения; без развёрнутой аргументации. | speaky-v55, cefr-linguistic, bc-grammar-basic |

### Vocabulary

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| vocabulary-a1-identity: Personal details and family | Понимать и использовать слова о себе и близких. | Имя, возраст, отношения, страны и языки; без подробной биографии. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a1-routine: Daily actions and time | Понимать и использовать лексику обычного дня. | Частотные действия, дни, часы; без профессиональной специализации. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a1-home: Home and familiar places | Назвать знакомые места и предметы вокруг. | Дом, комнаты, городские места; без архитектурной терминологии. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a1-food: Food and basic needs | Понимать и назвать еду, напитки и основные потребности. | Простая покупка и заказ; без гастрономического словаря. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a1-quantity: Numbers, prices and quantities | Понимать и использовать числа и единицы в повседневной ситуации. | Время, деньги, количество; без сложной финансовой терминологии. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a1-people: Appearance, feelings and simple qualities | Описать знакомое свойство человека или предмета. | Базовые прилагательные и чувства; без тонких психологических различий. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a1-activities: Leisure and familiar activities | Назвать занятия, которые нравятся или нужны. | Отдых, простые виды спорта и обучение; без отраслевого словаря. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a1-interaction: Basic interaction phrases | Использовать частотные формулы контакта и уточнения. | Приветствие, благодарность, просьба повторить; без требования заученного сценария. | speaky-v55, cefr-linguistic, bc-vocabulary |

## A2

### Speaking

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| speaking-a2-past-story: Telling a short past story | Передать последовательность знакомых событий прошлого. | Короткий личный эпизод; без сложной драматургии. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a2-plans: Discussing plans and arrangements | Согласовать время и место простой встречи. | Предложение, ответ, изменение детали; без переговоров с несколькими ограничениями. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a2-transactions: Handling everyday transactions | Пройти обычный разговор в магазине, кафе или гостинице. | Запрос, уточнение цены или условий, завершение; без сложной жалобы. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a2-comparison: Comparing familiar options | Выбрать один из знакомых вариантов и объяснить простую причину. | Покупки, места, занятия; без анализа рисков. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a2-social: Maintaining a short social exchange | Поддержать короткую беседу реакцией и встречным вопросом. | Знакомые темы, интерес и благодарность; без требования непрерывной беглости. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a2-work-basics: Describing your work and responsibilities | Объяснить, чем занимаешься на работе или учёбе. | Роль, типичные задачи и график; без отраслевой презентации. | speaky-v55, cefr-oral, bc-speaking |
| speaking-a2-repair: Clarifying a specific detail | Переспросить конкретную часть сообщения и подтвердить понимание. | Время, место, имя или шаг; без полного пересказа сложной речи. | speaky-v55, cefr-oral, bc-speaking |

### Listening

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| listening-a2-everyday: Understanding short everyday dialogues | Определить, о чём договорились в коротком бытовом разговоре. | Ясная речь, знакомая ситуация, конкретный итог; без быстрого группового спора. | speaky-v55, cefr-oral, bc-listening |
| listening-a2-announcements: Understanding short announcements | Извлечь нужное действие, место и время из объявления. | Транспорт, встреча, общественное место; без шумной неразборчивой записи. | speaky-v55, cefr-oral, bc-listening |
| listening-a2-sequence: Following a short sequence of instructions | Восстановить порядок знакомых действий после объяснения. | Несколько связанных шагов с ясными связками; без технической инструкции специалиста. | speaky-v55, cefr-oral, bc-listening |
| listening-a2-stories: Understanding short personal stories | Выделить участников, событие и результат короткого рассказа. | Знакомая лексика, ясный порядок; без неявной мотивации. | speaky-v55, cefr-oral, bc-listening |
| listening-a2-messages: Understanding a short voice message | Понять цель сообщения и нужный ответ. | Знакомый контекст, просьба или изменение планов; без опоры на текстовую расшифровку. | speaky-v55, cefr-oral, bc-listening |
| listening-a2-chunking: Recognising common spoken chunks | Распознать знакомое выражение, произнесённое слитно. | Частотные разговорные сочетания; без требования фонетической транскрипции. | speaky-v55, cefr-oral, bc-listening |

### Grammar

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| grammar-a2-past-simple: Past Simple: completed events | Рассказать о завершённых событиях и спросить о прошлом. | was/were, regular/irregular verbs, did; конкретное завершённое время. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-past-continuous: Past Continuous: background | Описать процесс в прошлом и событие, которое его прервало. | was/were + -ing, when/while; без past perfect. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-present-contrast: Present Simple vs Continuous | Различать привычное действие и текущую ситуацию. | Обычные состояния и действия; без сложных стилистических употреблений. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-future: Future plans and predictions | Различать намерение, договорённость и простое предсказание. | going to, present continuous для планов, базовое will; без future perfect. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-comparison: Comparatives and superlatives | Сравнить знакомые варианты и выбрать один. | -er/-est, more/most, as…as; без сложной сравнительной аргументации. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-perfect-intro: Present Perfect: experience and result | Сообщить об опыте или результате, связанном с настоящим. | have + participle, ever/never, just/already/yet; без тонкого выбора всех аспектов. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-modals: Obligation and advice | Объяснить простое правило и дать совет. | must/mustn't, have to, don't have to, should; без вывода о прошлом. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-infinitive-gerund: Common verb complements | Сообщить, что нравится и что планируется делать. | want/need + to, enjoy + -ing, like; без смены значения remember/stop. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-quantifiers: Quantifiers and indefinite pronouns | Уточнить количество и назвать неизвестного человека или предмет. | a few/a little, enough, too, someone/anything; без сложных определителей. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-relative-basic: Basic defining relative clauses | Уточнить, о каком человеке или предмете идёт речь. | who/which/that в простой определяющей части; без non-defining clauses. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-zero-conditional: Zero conditional | Описать повторяющуюся связь условия и результата. | if/when + present, обычные правила; без гипотез о будущем. | speaky-v55, cefr-linguistic, bc-grammar-basic |
| grammar-a2-prepositions: Movement and common preposition patterns | Описать путь и типичные отношения действия с предметом. | to/from/into/through, частотные adjective/verb + preposition; без идиоматического расширения. | speaky-v55, cefr-linguistic, bc-grammar-basic |

### Vocabulary

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| vocabulary-a2-travel: Travel and accommodation | Понимать и использовать лексику поездки и размещения. | Билет, маршрут, бронь, простая проблема; без миграционной или юридической консультации. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a2-shopping: Shopping and everyday services | Объяснить нужный товар, размер, оплату или простую неисправность. | Покупки и сервисы, возврат в простой ситуации; без договорной терминологии. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a2-work: Work and study basics | Понимать названия ролей и типичных обязанностей. | Общая рабочая и учебная среда; отраслевые слова подбираются отдельно. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a2-health: Health and everyday wellbeing | Описать самочувствие и бытовую потребность в помощи. | Части тела, частотные симптомы и привычки; без медицинской диагностики. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a2-relationships: Relationships and social occasions | Понимать и описывать обычные отношения и события. | Приглашения, праздники, договорённости; без сложной эмоциональной оценки. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a2-experiences: Past experiences and life events | Назвать событие жизни и описать впечатление. | Поездка, учёба, изменение работы; без тонкой литературной оценки. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a2-collocations: Everyday verb–noun combinations | Использовать частотные сочетания вместо случайного выбора глагола. | make/do/take/get в бытовых сочетаниях; без полного перечня значений. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-a2-phrasal-basic: Common concrete phrasal verbs | Понимать и использовать частотные бытовые фразовые глаголы. | get up, turn on и подобные конкретные действия; без полного словника. | speaky-v55, cefr-linguistic, bc-vocabulary |

## B1

### Speaking

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| speaking-b1-unprepared: Joining an unprepared conversation | Поддержать неподготовленный разговор на знакомую тему. | Личная жизнь, работа, интересы; допустимы поиск слов и перефразирование. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b1-narrative: Telling a connected story | Рассказать историю с последовательностью, причиной и результатом. | Личный опыт, ясные связи; без сложных параллельных сюжетов. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b1-opinions: Explaining an opinion | Высказать мнение и подкрепить его понятными причинами. | Знакомые вопросы, примеры и простое несогласие; без развитой полемики. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b1-problem-solving: Explaining a problem and agreeing action | Описать бытовую или рабочую проблему и обсудить следующий шаг. | Причина, варианты и договорённость; без сложных конфликтных переговоров. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b1-process: Explaining a familiar process | Объяснить последовательность действий собеседнику. | Знакомая процедура, уточнения по ходу; без специализированной лекции. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b1-paraphrase: Paraphrasing when a word is missing | Продолжить мысль через описание или пример при нехватке слова. | Функция, внешний вид, похожее значение; не замена всему Vocabulary. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b1-interview: Participating in a familiar interview | Ответить на вопросы об опыте и задать уточнения. | Работа, учёба, личный опыт; без гарантии готовности к профессиональному интервью. | speaky-v55, cefr-oral, bc-speaking |

### Listening

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| listening-b1-everyday: Understanding everyday conversations | Понять основную мысль и важные детали связного разговора. | Знакомые жизненные и рабочие темы, ясная речь; без сложного группового спора. | speaky-v55, cefr-oral, bc-listening |
| listening-b1-narrative: Following a connected narrative | Восстановить последовательность, причины и результат рассказа. | Связная речь с явными переходами; без сложных смен перспективы. | speaky-v55, cefr-oral, bc-listening |
| listening-b1-explanation: Understanding a familiar explanation | Понять объяснение причины или процесса на знакомую тему. | Главные шаги и основания; без необходимости понять каждое слово. | speaky-v55, cefr-oral, bc-listening |
| listening-b1-media: Following clear factual audio | Извлечь основные факты из короткого интервью или сообщения. | Понятная дикция, знакомая тема; без подтверждения понимания всех медиажанров. | speaky-v55, cefr-oral, bc-listening |
| listening-b1-attitude: Recognising explicit opinions and feelings | Различить явно выраженное мнение и отношение говорящего. | Словесные сигналы и понятная интонация; без уверенного вывода о сарказме. | speaky-v55, cefr-oral, bc-listening |
| listening-b1-inference: Inferring a local unknown meaning | Использовать ближайший контекст, чтобы предположить смысл незнакомого выражения. | Гипотеза с возможностью уточнения; без выдачи догадки за точное понимание. | speaky-v55, cefr-oral, bc-listening |

### Grammar

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| grammar-b1-perfect-past: Present Perfect vs Past Simple | Выбрать форму для опыта, результата и завершённого эпизода. | Связь с настоящим, finished/unfinished time, for/since; без perfect continuous. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-perfect-continuous: Present Perfect Continuous | Описать длительную деятельность с результатом сейчас. | have been + -ing, for/since, contrast with simple; без прошедшего perfect continuous. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-first-conditional: First Conditional | Обсудить реальное будущее условие и его результат. | if/unless, will/can в результате; без контрфактического прошлого. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-second-conditional: Second Conditional | Обсудить воображаемую ситуацию и возможное следствие. | if + past, would + verb, were; без third/mixed conditional. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-passive: Present and Past Passive | Описать действие с фокусом на результате или объекте. | be + participle в present/past, by при необходимости; без сложных пассивных форм. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-relative: Defining and non-defining clauses | Добавить необходимую или дополнительную информацию о предмете. | who/which/that/whose, различие defining/non-defining в устном смысле; без reduced clauses. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-used-to: Used to and past habits | Сопоставить прежнюю привычку с настоящим. | used to/didn't use to, contrast with past events; без be/get used to. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-reported-basic: Reporting statements | Передать чужое простое утверждение. | say/tell, смена лица и базовый backshift; без сложных reporting verbs. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-indirect-questions: Indirect questions | Вежливо запросить информацию через вводную конструкцию. | Could you tell me / do you know, порядок слов; без сложной косвенной речи. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-modals-deduction: Present deduction and possibility | Различать уверенный вывод и возможность о текущей ситуации. | must/might/could/can't; без modal perfect. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-articles-reference: Articles: general and specific reference | Различать общее понятие и известный собеседнику объект. | the/zero article, общие и конкретные группы; без стилистических исключений. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-verb-patterns: Infinitive and gerund patterns | Выбрать частотную форму после глагола или предлога. | verb + to/-ing, базовый объект перед infinitive; без редких формальных моделей. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b1-linking-contrast: Cause, purpose and contrast | Связать объяснение с причиной, целью и противопоставлением. | although, so that, because of, however; без сложной риторической структуры. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |

### Vocabulary

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| vocabulary-b1-work-communication: Work Communication | Обсудить задачу, срок, результат и затруднение на работе. | Общая рабочая лексика и сочетания; профессия задаёт примеры, не отдельную обязательную программу. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b1-opinions: Opinions, reasons and preferences | Выбрать слова для мнения, основания и простого возражения. | Оценка знакомых вопросов; без тонкой дипломатической дистанции. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b1-media: News, information and digital life | Обсудить информацию, источники и повседневные цифровые действия. | Общие медиа и технологии; без специализированного IT-словаря. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b1-society: Community and environment | Обсудить знакомую общественную или экологическую ситуацию. | Город, услуги, привычки, простые последствия; без экспертных терминов. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b1-feelings: Feelings, reactions and relationships | Различать частотные эмоции и описывать реакцию на событие. | Личный опыт и социальные отношения; без клинической классификации. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b1-collocations: Topic-based collocations | Использовать устойчивые сочетания в знакомых темах. | Работа, жизнь, мнения; без заучивания всех сочетаний сразу. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b1-phrasal: Frequent figurative phrasal verbs | Понимать и применять частотные переносные значения фразовых глаголов. | Контекстные значения и separable patterns; без редких идиом. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b1-word-formation: Word families and contextual paraphrase | Связать частотные формы слова и объяснить незнакомое через известное. | Основные prefix/suffix, синоним или описание; без угадывания как доказанного знания. | speaky-v55, cefr-linguistic, bc-vocabulary |

## B2

### Speaking

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| speaking-b2-argument: Expressing and defending opinions | Развить позицию, ответить на возражение и уточнить аргумент. | Причины, примеры, контраргумент; без обязательной риторической терминологии. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b2-negotiation: Negotiating solutions | Согласовать решение при разных интересах собеседников. | Условия, уступки и альтернатива; без юридических переговоров. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b2-extended-description: Giving a detailed account | Развёрнуто описать событие или явление с существенными деталями. | Структура и акценты для слушателя; без требования академического доклада. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b2-meetings: Contributing to a discussion | Войти в обсуждение, развить чужую мысль и вернуть разговор к цели. | Очередь реплик, уточнение и итог; без искусственной монополизации разговора. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b2-presentation: Presenting and answering follow-up questions | Представить знакомую тему и ответить на дополнительные вопросы. | Структура, примеры и реакция на аудиторию; без подготовки слайдов как учебного требования. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b2-register: Adjusting formality and politeness | Выбрать уместную манеру просьбы, отказа или несогласия. | Бытовое и рабочее общение; без подражания конкретной культуре как единственной норме. | speaky-v55, cefr-oral, bc-speaking |
| speaking-b2-reformulation: Reformulating a misunderstood point | Перестроить объяснение после непонимания без потери основного смысла. | Другой пример, уточнение границы, проверка результата; без требования безошибочной речи. | speaky-v55, cefr-oral, bc-speaking |

### Listening

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| listening-b2-discussion: Following an extended discussion | Проследить разные позиции и их основания в разговоре естественного темпа. | Разборчивая речь нескольких участников; без обещания понимания при любом шуме. | speaky-v55, cefr-oral, bc-listening |
| listening-b2-lecture: Following a structured talk | Понять структуру развёрнутого объяснения и связи между доводами. | Знакомая область, явная организация; без требования специальных знаний. | speaky-v55, cefr-oral, bc-listening |
| listening-b2-media: Understanding interviews and reports | Отделить основные факты, оценку и пример в развёрнутой записи. | Интервью, обзоры, репортажи; без универсальной оценки всего аудирования. | speaky-v55, cefr-oral, bc-listening |
| listening-b2-connected-speech: Understanding reductions at natural pace | Сохранить понимание знакомого содержания при редукциях и связной речи. | Несколько голосов, частотные сокращения; без требования произносить их самому. | speaky-v55, cefr-oral, bc-listening |
| listening-b2-implicit: Recognising implied stance | Объяснить вероятное отношение, когда оно выражено косвенно. | Контекст и языковые сигналы; неоднозначность сохраняется. | speaky-v55, cefr-oral, bc-listening |
| listening-b2-precision: Distinguishing qualifications and corrections | Уловить оговорку, исправление или ограничение в объяснении. | Смысловые изменения, отрицание, исключения; без требования дословного воспроизведения. | speaky-v55, cefr-oral, bc-listening |

### Grammar

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| grammar-b2-past-perfect: Past Perfect and narrative sequence | Показать, какое прошлое событие предшествовало другому. | had + participle и сопоставление с Past Simple; без past perfect continuous. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-past-perfect-continuous: Past Perfect Continuous | Объяснить длительную деятельность до момента в прошлом. | had been + -ing, причина видимого результата; без литературных экспериментов. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-third-conditional: Third Conditional | Рассмотреть альтернативный исход завершённого события. | if + past perfect, would/could have; без mixed conditionals. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-mixed-conditional: Mixed conditionals | Связать нереальное прошлое с текущим результатом и наоборот. | Явная временная связь частей; без сокращённых инверсий. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-future-aspect: Future Continuous and Perfect | Описать процесс или завершение к будущему сроку. | will be doing / will have done; без сложной модальной неоднозначности. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-reported-extended: Reported questions and reporting verbs | Передать вопросы, просьбы, обещания и советы. | ask/wonder, tell/advise/promise + patterns, временные ссылки; без формального цитирования. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-modal-perfect: Past deduction and criticism | Обсудить вероятность, упущенную возможность и оценку прошлого. | must/might/can't/should have + participle; без редких форм. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-passive-extended: Passive with modals and perfect | Описать требования и завершённые действия без фокуса на исполнителе. | modal/passive, have been done, get passive; без сложных reporting passives. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-causative: Have/get something done | Объяснить, что услугу выполняет другой человек. | have/get + object + participle; разграничение самостоятельного действия и услуги. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-wish: Wish and if only | Выразить сожаление о настоящем и прошлом. | wish + past / past perfect, wish + would; без смешения сожаления с реальным планом. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-participle: Participle and reduced clauses | Сжато добавить обстоятельство или характеристику. | -ing/-ed clauses с ясным субъектом; без сложных dangling constructions. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |
| grammar-b2-verb-meaning: Verb patterns that change meaning | Различать смысл stop/remember/try при разных дополнениях. | Infinitive vs gerund в частотных парах; без полного словаря глагольных моделей. | speaky-v55, cefr-linguistic, bc-grammar-intermediate |

### Vocabulary

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| vocabulary-b2-professional: Professional discussion vocabulary | Обсудить варианты, ограничения и результаты рабочей задачи. | Общая профессиональная лексика; конкретная отрасль выбирается под цель. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b2-abstract: Abstract issues and consequences | Обсудить причины, последствия и компромиссы абстрактного вопроса. | Общество, технологии, решения; без экспертного знания предмета. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b2-collocations: Precise collocations across contexts | Выбрать подходящее сочетание для более точного смысла. | Знакомые и абстрактные темы, близкие глаголы и прилагательные; без редких форм ради сложности. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b2-idioms: Common idioms in context | Понять и уместно использовать распространённое образное выражение. | Значение, контекст и ограничение регистра; без обязательного насыщения речи идиомами. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b2-nuance: Near-synonyms and degree | Выбрать слово с нужной силой и оттенком значения. | Отличия близких слов, intensifiers, оценочные сочетания; без искусственной численной шкалы. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b2-register: Formal and informal alternatives | Выбрать лексический вариант под отношения и ситуацию. | Рабочая и бытовая речь; без универсального списка запрещённых разговорных слов. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b2-discourse: Discourse markers and argument links | Связать аргументы и обозначить уточнение или отступление. | Лексические связки устного обсуждения; без замены ими содержания. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-b2-polysemy: Multiple meanings and lexical inference | Различить значения знакомого слова в разных контекстах. | Частотная многозначность, проверка догадки; без автоматического признания всех значений освоенными. | speaky-v55, cefr-linguistic, bc-vocabulary |

## C1

### Speaking

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| speaking-c1-nuanced-argument: Qualifying a complex position | Выразить сложную позицию с оговорками и границами применимости. | Несколько оснований, уступка и точное заключение; не формальный экзамен. | speaky-v55, cefr-oral |
| speaking-c1-facilitation: Facilitating a complex discussion | Связать разные позиции участников и продвинуть обсуждение к решению. | Переформулирование, возврат к вопросу и промежуточный итог; без роли профессионального фасилитатора. | speaky-v55, cefr-oral |
| speaking-c1-complex-explanation: Explaining an unfamiliar complex idea | Сделать сложную идею понятной собеседнику с другим опытом. | Аналогия, структура и адаптация глубины; без проверки специальных знаний. | speaky-v55, cefr-oral |
| speaking-c1-spontaneous: Responding flexibly to unexpected turns | Сохранить линию мысли при неожиданном вопросе или смене темы. | Спонтанный развёрнутый ответ; без нормативов скорости или пауз. | speaky-v55, cefr-oral |
| speaking-c1-register: Using register and indirectness deliberately | Выразить тонкое несогласие, дипломатичный отказ или дистанцию. | Уместность по контексту; без требования конкретного акцента. | speaky-v55, cefr-oral |
| speaking-c1-synthesis: Synthesising spoken viewpoints | Устно объединить разные аргументы, отделив общее от разногласий. | Несколько позиций и собственный вывод; без обязательного письменного текста. | speaky-v55, cefr-oral |
| speaking-c1-storytelling: Shaping a nuanced narrative | Передать сложный опыт с нужным акцентом и отношением говорящего. | Смена перспективы, детали и вывод; без литературного исполнения. | speaky-v55, cefr-oral |

### Listening

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| listening-c1-unstructured: Following loosely structured speech | Восстановить смысл сложного объяснения без явных переходов. | Отступления и возвраты к мысли; без обязательного знания темы заранее. | speaky-v55, cefr-oral, bc-listening |
| listening-c1-implicit: Interpreting nuanced implied meaning | Объяснить косвенный смысл с указанием контекстных оснований. | Намёк, осторожная оценка, возможная ирония; допускается неоднозначность. | speaky-v55, cefr-oral, bc-listening |
| listening-c1-accents: Adapting to unfamiliar accent patterns | Сохранить понимание после адаптации к непривычному варианту произношения. | Разнообразные разборчивые голоса; без обещания понимать любой акцент без усилий. | speaky-v55, cefr-oral, bc-listening |
| listening-c1-multi-party: Tracking a complex multi-party discussion | Различить позиции, смену темы и значимые уточнения нескольких участников. | Перебивания и быстрые переходы при доступной записи; без произвольного шумового порога. | speaky-v55, cefr-oral, bc-listening |
| listening-c1-complex-audio: Understanding complex unfamiliar audio | Выделить тезис и ход объяснения в сложном новом материале. | Абстрактная тема, возможность уточнить термин; без проверки профессиональной квалификации. | speaky-v55, cefr-oral, bc-listening |
| listening-c1-register: Recognising register and rhetorical intent | Различить манеру речи и её роль в воздействии на слушателя. | Дистанция, убеждение, юмор с обоснованием; без психологического диагноза говорящего. | speaky-v55, cefr-oral, bc-listening |

### Grammar

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| grammar-c1-inversion: Inversion for emphasis | Подчеркнуть ограничение или исключительность с помощью инверсии. | Negative adverbials, only/not until, auxiliary inversion; без редких поэтических форм. | speaky-v55, cefr-linguistic, bc-grammar-advanced |
| grammar-c1-cleft: Cleft sentences | Выделить нужную часть сообщения без изменения основного факта. | It/what clefts и смысловой фокус; без обязательного использования в каждой реплике. | speaky-v55, cefr-linguistic, bc-grammar-advanced |
| grammar-c1-advanced-modality: Nuanced modality and hedging | Точно выразить степень уверенности, ожидания и дистанцию от утверждения. | Modal alternatives, tentative past, hedging in context; без численной шкалы уверенности. | speaky-v55, cefr-linguistic, bc-grammar-advanced |
| grammar-c1-advanced-passive: Reporting and complex passive | Сжато передать мнение или сведения с неопределённым источником. | is said to, is believed to have, passive reporting; без сокрытия реального источника в диагностике. | speaky-v55, cefr-linguistic, bc-grammar-advanced |
| grammar-c1-ellipsis: Ellipsis and substitution in speech | Избежать повторов, сохранив понятность ссылки. | so/neither, do so, omitted elements in responses; без потери референта. | speaky-v55, cefr-linguistic, bc-grammar-advanced |
| grammar-c1-concession: Advanced concession and contrast | Выразить уступку и ограничение сложного аргумента. | much as, whereas, despite, whatever; без формальной логики доказательств. | speaky-v55, cefr-linguistic, bc-grammar-advanced |
| grammar-c1-conditional-inversion: Conditional alternatives and inversion | Переформулировать условие с нужным регистром и оттенком. | had/were/should inversion, provided/as long as; без новых временных значений. | speaky-v55, cefr-linguistic, bc-grammar-advanced |
| grammar-c1-complex-noun-phrases: Complex noun phrases and reference | Уплотнить описание и сохранить ясную связь определений с предметом. | Nominalisation, pre/post modifiers, уточнение референта; устная понятность важнее длины. | speaky-v55, cefr-linguistic, bc-grammar-advanced |
| grammar-c1-aspect-nuance: Aspect for stance and temporary meaning | Выбрать аспект для оттенка временности, повторяемости или отношения говорящего. | Контекстные continuous/simple и perfect contrasts; без требования редких форм ради сложности. | speaky-v55, cefr-linguistic, bc-grammar-advanced |

### Vocabulary

| ID / Topic | Ожидаемая способность | Объём и границы | Источники |
| --- | --- | --- | --- |
| vocabulary-c1-precision: Fine distinctions and connotation | Выбрать выражение с точным смыслом и уместной оценочной окраской. | Близкие значения, отношение говорящего и контекст; без требования редкости слова. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-c1-register: Flexible register and diplomatic wording | Менять лексику для дипломатичности, дистанции или неформального контакта. | Уместные альтернативы в сложном общении; без фиксированного культурного шаблона. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-c1-collocations: Advanced collocation and phraseology | Использовать точные устойчивые сочетания в сложных рассуждениях. | Абстрактные и профессиональные контексты; отраслевые термины остаются персональными. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-c1-idiomatic: Idiomatic and figurative interpretation | Понимать образное выражение и оценивать уместность его употребления. | Метафора, разговорный оборот, возможная неоднозначность; без требования владеть всем сленгом. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-c1-stance: Hedging, emphasis and evaluative language | Точно обозначить силу утверждения и собственную позицию. | Оговорки, усиление, оценка основания; без численной вероятности по слову. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-c1-specialised: Acquiring goal-specific terminology | Объяснить нужный термин и использовать его в своей области. | Умение связывать термин с понятием; конкретный отраслевой словарь не зафиксирован в общем каталоге. | speaky-v55, cefr-linguistic, bc-vocabulary |
| vocabulary-c1-reformulation: Flexible lexical reformulation | Выразить сложную мысль иначе без потери существенных различий. | Перефразирование, обобщение и уточнение; без требования письменного резюме. | speaky-v55, cefr-linguistic, bc-vocabulary |
