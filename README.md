<img src="logo.png" alt="Kotatsu Desktop Logo" width="192" />

# Kotatsu Desktop (Unofficial)

An unofficial, open-source desktop manga reader built with **Kotlin Multiplatform** and **Compose Multiplatform**, powered by [kotatsu-parsers-redo](https://github.com).

**Status:** `v0.2.0` — Desktop release for Windows. 

---

## Features (v0.2.0 Highlights)
* **Offline & Export:** Batch download chapters or export as `.cbz` archives.
* **Library & Migration:** Import Android `.bk.zip` backups, track new chapter badges (`+NEW`), and view date-grouped history.
* **UI & Reader:** Navigation Rail, grid/list toggles, incognito mode, 3-mode reader (Webtoon, Single Page, Two-Page Spread with RTL/LTR), smooth zoom/pan, and Material 3 dark/light themes.
* **Search:** Parallel multi-source or single-source searching with language and NSFW filters.

---

## Download & Build
1. Download **`Kotatsu Desktop-0.2.0.exe`** or **`Kotatsu Desktop-0.2.0.msi`** from [Releases](https://github.com).
2. To build from source (JDK 17/21 required):
   * Run dev mode: `./gradlew :desktopApp:run`
   * Build Windows installers: `./gradlew :desktopApp:packageExe :desktopApp:packageMsi`

---

## Roadmap & Credits
* **Completed (`v0.2.0`):** Background preloading, offline `.cbz` exports, Android `.bk.zip` imports, and update checkers.
* **Upcoming:** Favorites shelves & MangaDex SNI/proxy support (`v0.3.0`), plus Cloudflare solver & tracker sync (`v0.4.0`).
---
## License:
GNU General Public License v3.0 (GPL-3.0). Unofficial project; does not host copyrighted content.
