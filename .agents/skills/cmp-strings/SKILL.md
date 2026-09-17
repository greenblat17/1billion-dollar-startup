---
name: cmp-strings
description: >
  Put user-visible Compose Multiplatform copy in
  commonMain/composeResources/values XML and read it with stringResource /
  pluralStringResource. Locales are values-<lang> next to the default
  values/ fallback. Use when adding a string, translation, plural,
  stringResource, contentDescription, or hardcoded UI text. Skip theme
  tokens (cmp-theme), icon XML (cmp-icons), and SandboxHost chrome labels
  that MCP clicks. Do not put shared copy in androidApp/res/values.
---

# CMP strings

Shared UI copy is **Compose Resources**, not `androidApp/res/values` and not Kotlin literals in widgets. Docs: [Using multiplatform resources](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-usage.html) (Strings), [Localizing strings](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-localize-strings.html).

This repo already generates `Res` from `cmp/app/shared/src/commonMain/composeResources/` (today only `drawable/`). Add `values/strings.xml` there. Build or IDE sync so `Res.string.*` exists.

## Layout

```text
commonMain/composeResources/
  values/strings.xml       fallback (product default locale)
  values-ru/strings.xml    Russian, if not the fallback
  values-en/strings.xml    English, if not the fallback
```

`values/` is what the runtime uses when a locale file is missing — not “always English”. If the default UI language is unclear, **ask**. Do not add empty `values-*` folders. Same `name=` keys in every locale file; drop a key only if that locale should fall back.

Product / marketing wording: **ask the user**. Do not invent copy, then “translate” it.

## XML

```xml
<resources>
    <string name="conversation_start">Start conversation</string>
    <string name="conversation_welcome">Welcome, %1$s</string>
    <plurals name="weak_spots">
        <item quantity="one">%1$d weak spot</item>
        <item quantity="other">%1$d weak spots</item>
    </plurals>
</resources>
```

- Keys: `snake_case`, stable, prefixed by screen/feature (`conversation_start`). Hyphens become `_` on `Res.string`.
- Placeholders: `%1$s` / `%1$d` (reorderable in translations). CMP treats `$s` and `$d` loosely — still pass args in index order.
- Plurals: `zero` / `one` / `two` / `few` / `many` / `other`. Not every language uses every bucket (English: `one`+`other`; Russian: `one`+`few`+`many`+`other`). Do not emulate plurals with `if (n == 1)` in Kotlin.
- Arrays: `string-array` → `stringArrayResource(Res.array.*)` when the UI needs a fixed list of labels.

## In UI

```kotlin
Text(stringResource(Res.string.conversation_start))
Text(stringResource(Res.string.conversation_welcome, name))
Text(pluralStringResource(Res.plurals.weak_spots, count, count))
```

`contentDescription` for icons: `stringResource(...)`, not `"Microphone"` (skill `cmp-icons`). Decorative stays `null`.

`stringResource` / `pluralStringResource` are `@Composable`. **ViewModel does not call them.** `UiState` holds domain data (counts, names, enums); the Screen/widget maps to `Res.string`. Need a string off the UI thread: suspend `getString` / `getPluralString` in `viewModelScope` — last resort, not the default.

Do not concatenate sentences in Kotlin (`stringResource(a) + " " + name`) — translators cannot reorder. Use one template.

## Sandbox / MCP

`SandboxHost` chrome (`Theme: light` / `Theme: dark`) stays a **literal**. MCP clicks those labels (skill `compose-widget-sandbox`). Product widgets under test still use `Res.string`.

## Do not

- Shared copy in `androidApp/src/main/res/values` (launcher/platform chrome only)
- Hardcoded user-facing strings in widgets/screens once a catalog exists
- `moko-resources` / Libres unless the project already has them **and** the user asked
- Resolving `StringResource` inside a ViewModel as the default pattern
- Changing sandbox chrome labels to localized keys
