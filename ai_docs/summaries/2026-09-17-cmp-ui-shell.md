# CMP UI shell (мок-экраны)

**Дата:** 2026-09-17  
**Статус:** оболочка в коде; HTTP к бэку не вызван

В `:app:shared` есть `AppTheme`, Nav3 (Welcome → Main/табы → Call → Review 1…5/5), строки и иконки с макетов Releva. Контент — `MockSpeakingData`. `SpeakingCoachClient` держит `TODO()` до согласования clip/session API.

Светлая палитра сэмплирована с PNG (`#1874FC` / `#FBFAF6` / `#ECF0FC` / `#E9EFFD`); виджеты читают только `colorScheme`. Dark — те же роли (синий CTA, карточки светлее ink), не M3-пастель. Android-хост: `uiMode` без recreate Activity и DayNight `windowBackground`.

На Home нижние табы есть (на PNG их нет) — иначе нельзя открыть Историю.
