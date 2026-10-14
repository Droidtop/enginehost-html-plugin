# Third-party components

This repository is a thin Android WebView wrapper for HTML/Twine games, RPG
Maker MV/MZ web deploys, and Flash/AIR content (see README.md). It does not
vendor Ruffle, RPG Maker's runtime, or any game engine source itself; those
are either the game's own files (HTML/Twine, RPG Maker) or documented in the
`enginehost-flash-air-plugin` repository for the Ruffle build actually
shipped there.

| Component | Version / commit | Licence | Source | Where in tree |
|---|---|---|---|---|
| Enginehost's own wrapper (WebView bridge, `localStorage` save shim, audio fallback) | this repository | MIT | https://github.com/Droidtop/enginehost-html-plugin | entire tree |
| HTML/Twine game engines (SugarCube, Harlowe, etc.) | supplied per-game | not applicable (not vendored) | shipped inside each game's own files | never present in this repository |
| RPG Maker MV/MZ runtime | supplied per-game | not applicable (not vendored) | shipped inside each game's own `www` folder | never present in this repository |

## Obligations

None beyond MIT's notice-preservation requirement for this repository's own
code; no third-party runtime or engine source is bundled or redistributed.
