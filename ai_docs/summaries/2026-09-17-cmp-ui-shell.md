# CMP UI shell (мок-экраны)

**Дата:** 2026-09-17  
**Статус:** оболочка в коде; HTTP к бэку не вызван

В `:app:shared` есть `AppTheme`, Nav3 (Welcome → Home → Call → Review 1…2/2 Grammar/Vocabulary → Profile), строки и иконки с макетов Releva. Контент — `MockSpeakingData`. `SpeakingCoachClient` держит `TODO()` до согласования clip/session API.

Светлая палитра сэмплирована с PNG (`#1874FC` / `#FBFAF6` / `#ECF0FC` / `#E9EFFD`); виджеты читают только `colorScheme`. Dark — те же роли (синий CTA, карточки светлее ink), не M3-пастель. Android-хост: `uiMode` без recreate Activity и DayNight `windowBackground`.

Нижнего таббара нет. Профиль — стек с Home (аватар). История и шаги Pronunciation / Fluency / Speed of speech отложены (макеты 06–09 не удалять).
