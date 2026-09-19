---
name: cmp-icons
description: >
  Add Compose Multiplatform icons as Android vector XML in
  commonMain/composeResources/drawable and paint them with Icon + ColorScheme
  tint. Fetch Material Symbols XML from google/material-design-icons on GitHub
  (name from Google Fonts Icons). If 404 or the icon is custom, ask the user
  to create/paste the XML. Use when adding an icon, Material Symbol, drawable,
  painterResource, or Icons.Default. Do not add material-icons-extended
  (frozen). Do not invent vector pathData. Skip Android mipmap/launcher icons
  in androidApp/res.
---

# CMP icons

Shared UI icons are **Compose Resources**, not `material-icons-core` / `extended`. CMP froze those artifacts at 1.7.3 and tells you to use Material Symbols as vector XML. Docs: [Using multiplatform resources](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-usage.html) (section Icons).

This repo already generates `Res` from `cmp/app/shared/src/commonMain/composeResources/`. Put new files next to `drawable/compose-multiplatform.xml`.

## Where to get XML

Google Fonts UI is JS-only — it does **not** give the agent a downloadable `<vector>`. Use it only to learn the **icon name** (mic, settings, send): [Material Symbols](https://fonts.google.com/icons).

Then fetch Android XML from [google/material-design-icons](https://github.com/google/material-design-icons):

```
https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml
```

Example that works: `.../symbols/android/mic/materialsymbolsoutlined/mic_24px.xml`. Style folder can be `materialsymbolsrounded` or `materialsymbolssharp`. If the URL 404s, list `symbols/android/` on GitHub for the real directory name.

The stock file uses `android:tint="?attr/colorControlNormal"` and `android:fillColor="@android:color/white"`. CMP’s vector parser **ignores** `android:tint` and only accepts `#…` colors (not `@android:` / `@color/`). For Material Symbols: `android:fillColor="#000000"`, drop `tint`, then tint in Compose. Multi-color illustrations may keep their own `#hex` fills (e.g. nav_cupcake `cupcake.xml`).

If fetch fails or the glyph is custom/branded: **ask the user** for XML. Do not invent `pathData`. Do not approximate with Kotlin `materialPath`.

## File rules

- Path: `cmp/app/shared/src/commonMain/composeResources/drawable/ic_<name>.xml`. Generated names: `-` → `_`; camelCase kept; a leading digit gets `_` (`3-foo.xml` → `Res.drawable._3_foo`).
- Material Symbols: `android:fillColor="#000000"`. Strip `android:tint` (ignored anyway). Tint in Compose.
- No `@color/` / `@drawable/` Android references (parser cannot link external resources).
- Prefer XML vector, not SVG (`painterResource` SVG errors on Android) and not PNG for UI glyphs.
- Not `androidApp/src/main/res` for shared icons (that is launcher/platform chrome).

Build or IDE sync so `Res.drawable.ic_<name>` exists. The generator splits accessors into 100-item files; still do not dump the whole Material Symbols set. `painterResource` does **not** need `@OptIn(ExperimentalResourceApi)`. This module already has `androidResources { enable = true }` — keep it.

## In UI

```kotlin
Icon(
    painter = painterResource(Res.drawable.ic_mic),
    contentDescription = stringResource(Res.string.cd_mic),
    tint = MaterialTheme.colorScheme.onSurface,
)
```

Decorative: `contentDescription = null`. Meaningful `contentDescription`: skill `cmp-strings`, not a Kotlin literal. Theme/sandbox dark mode works because fill is black and `tint` comes from `ColorScheme` (skill `cmp-theme`).

## Do not

- `org.jetbrains.compose.material:material-icons-extended` (or `compose.materialIconsExtended`)
- `Icons.Default.*` unless the project already depends on `material-icons-core` **and** the user asked for that frozen set
- Guessing vector paths
- SVG as the default shared icon format
