---
name: cmp-theme
description: >
  Put Compose Multiplatform Material 3 color and type tokens in shared
  ui/theme (Color.kt, Type.kt, Theme.kt) and load fonts from
  composeResources/font. Copy nowinandroid’s type scale and named palettes
  and nav_cupcake’s MaterialTheme wrapper — not LocalContext
  dynamic color, compose-samples R.font, or Downloadable Fonts. Use when adding a
  theme, ColorScheme, Typography, dark mode, seed color, or custom
  font. Skip icon XML (cmp-icons), UI copy (cmp-strings), and widget
  sandbox chrome (compose-widget-sandbox). Do not hardcode colors or
  FontFamily in widgets.
---

# CMP theme

Shared look lives in `:app:shared` `commonMain`, not `androidApp/res`. CMP M3 wrapper (static light/dark, no `LocalContext`): [nav_cupcake `ui/theme`](https://github.com/JetBrains/compose-multiplatform/tree/master/examples/nav_cupcake/shared/src/commonMain/kotlin/org/jetbrains/nav_cupcake/ui/theme) (`Color.kt`, `Type.kt`, `CupcakeTheme`). Named palettes / type-scale ideas: [nowinandroid theme](https://github.com/android/nowinandroid/tree/main/core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/theme) — NIA has **no** `Font.kt` and no `res/font`. Fonts in CMP: [codeviewer `Fonts.kt`](https://github.com/JetBrains/compose-multiplatform/blob/master/examples/codeviewer/shared/src/commonMain/kotlin/org/jetbrains/codeviewer/ui/common/Fonts.kt) (`Font(Res.font.*)`). Docs: [Using multiplatform resources](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-usage.html) (Fonts). Do not copy imageviewer / codeviewer Material **2** palettes.

This repo still uses default `MaterialTheme { }` in `App()` and `SandboxHost`. When you add a real theme, replace those with `AppTheme`.

## Layout

```text
cmp/app/shared/src/commonMain/kotlin/.../ui/theme/
  Color.kt      named palettes + Light / Dark ColorScheme
  Type.kt       M3 Typography slots (fontFamily from composeResources)
  Theme.kt      AppTheme(darkTheme) -> MaterialTheme(...)
cmp/app/shared/src/commonMain/composeResources/font/   TTF/OTF/TTC
```

Do not add a `:core:designsystem` Android module. Do not invent extra `CompositionLocal`s (`LocalGradientColors`, `LocalBackgroundTheme`, `LocalTintTheme`) unless the UI actually needs them — NIA has those for its own chrome.

## Color

Named tokens like NIA (`Purple40`), then `lightColorScheme(...)` / `darkColorScheme(...)`. Widgets read `MaterialTheme.colorScheme.*` only — never `Color(0xFF…)` in a screen.

Seed / brand hex: [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/) export, or ask the user. Do not guess brand colors.

`AppTheme(darkTheme: Boolean = isSystemInDarkTheme())` picks light vs dark. Sandbox chrome still owns the toggle: `AppTheme(darkTheme = dark) { … }` — do not call leftover `lightColorScheme()` / `darkColorScheme()` next to it.

## Type

Map M3 slots (`displayLarge` … `labelSmall`) with size / weight / lineHeight like NIA [`Type.kt`](https://raw.githubusercontent.com/android/nowinandroid/main/core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/theme/Type.kt). Do **not** copy NIA’s `textDirection = Ltr` / `textAlign = Left` (breaks RTL). NIA does not set `fontFamily` on slots.

UI: `Text(..., style = MaterialTheme.typography.bodyLarge)`. Color for text comes from the scheme (`colorScheme.onSurface`), not a hardcoded `Color.Black`.

M3 has no M2-style `defaultFontFamily` parameter. Apply a family with `Typography(fontFamily = …)` (current Material3) or `copy(fontFamily = …)` on the slots you care about. Resources docs still show per-slot copy.

## Fonts

Google Fonts UI is JS-only — use it for the **family name**. Then fetch files from [google/fonts](https://github.com/google/fonts) (`ofl/<family>`, `apache/<family>`, or `ufl/<family>`). List the directory, download `.ttf` / `.otf` / `.ttc`. Variable fonts are OK (CMP 1.8+). Shared format is TTF/OTF/TTC/variable — not WOFF/WOFF2 (web + macOS only).

Generated `Res.font` names: only `-` becomes `_`; camelCase stays; a leading digit gets `_`. Brackets in `Inter[opsz,wght].ttf` are **kept** and are not a valid Kotlin property — rename to `inter_regular.ttf` (letters, digits, `_`).

`org.jetbrains.compose.resources.Font` is `@Composable`, so `FontFamily` / `Typography` that use it must be `@Composable` too:

```kotlin
@Composable
fun appTypography(): Typography {
    val family = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
    )
    return MaterialTheme.typography.copy(
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = family),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
    )
}
```

If the family 404s or is custom/licensed: **ask the user** for the files. Do not invent a font. Do not add only Regular and fake Bold with `FontWeight.Bold`.

## Do not copy from nowinandroid / Android samples

| Source | This project |
| --- | --- |
| NIA `dynamicLightColorScheme(LocalContext.current)` / `Build.VERSION_CODES.S` | Static light/dark schemes in commonMain |
| NIA `androidTheme` second brand | One product scheme unless the user asks |
| compose-samples `R.font` / `GoogleFont.Provider` (NIA has neither) | `composeResources/font` + `Res.font` |
| NIA `:core:designsystem` Android module | `ui/theme` in `:app:shared` |

## Do not

- Hardcoded colors / `FontFamily` inside widgets (sandbox dark toggle will lie)
- `androidApp/src/main/res/font` for shared type
- WOFF/WOFF2 as the shared format
- Mixing default `MaterialTheme { }` with `AppTheme` in the same tree
