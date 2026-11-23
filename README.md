<img src="logo.png" width="128" />
# Kotatsu Desktop (Unofficial)

An unofficial, open-source desktop manga reader built with **Kotlin Multiplatform** and **Compose Multiplatform**, powered by the official [kotatsu-parsers-redo](https://github.com) engine.

> **Status:** `v0.1.0-alpha` — Early desktop release for Windows. Contributions, bug reports, and macOS/Linux build PRs are welcome!

---

## Features (v0.1.0)
- **Global & Single-Source Search:** Search across dozens of sources in parallel or lock onto a single source with dynamic genre tags.
- **Content & Language Filters:** Filter sources by language (`EN`, `JA`, `KO`, `ALL`), sort order (`Popular`, `Latest`), and strict **Hide NSFW** filtering.
- **3-Mode Desktop Reader:**
  - **Webtoon Scroll:** Continuous vertical scroll with Windows scrollbar support.
  - **Single Page:** Fit-to-window reading with `←` / `→` / `Space` keyboard navigation.
  - **Two-Page Spread:** Side-by-side double-page illustration mode with **RTL (Manga)** / **LTR (Comic)** toggle and **Shift Spread** alignment.
- **Zoom & Pan:** Smooth mouse-wheel zoom (`100%`–`400%`), click-and-drag panning, double-click zoom reset, and `Ctrl + +/-/0` shortcuts.
- **Favorites, Reading History & Backup:** Auto-saves your library, last-read chapter, and page progress locally with 1-click `.json` backup export/import.
- **Dark & Light Themes:** Full Material 3 theme switching in Settings.

---

## Download & Installation (Windows)
1. Go to the **[Releases](../../releases)** page on the right.
2. Download **`Kotatsu Desktop-0.1.0.exe`**.
3. Run the installer (includes a bundled Java runtime—no separate JDK installation required).

---

## Building from Source
### Prerequisites
- **JDK 17** or **JDK 21**
- **IntelliJ IDEA** (or Gradle CLI on Windows/macOS/Linux)

### Run in Development Mode
```bash
./gradlew :desktopApp:run
```

### Build Windows Installer (`.exe`)
```bash
./gradlew :desktopApp:packageExe
```
The compiled installer will be generated in `desktopApp/build/compose/binaries/main/exe/`.

---

## Roadmap
- [ ] Background page preloading & auto-hide full-screen reader bars (`v0.2.0`)
- [ ] Offline chapter downloads & `.cbz` archive export (`v0.3.0`)
- [ ] Import/Export Android Kotatsu `.bk.zip` backups (`v0.3.0`)
- [ ] Favorites categories & new chapter update checker (`v0.4.0`)
- [ ] Cloudflare challenge solver & AniList / MyAnimeList tracker sync (`v0.5.0`)

---

## Credits & License
- Powered by **[kotatsu-parsers-redo](https://github.com)** and inspired by **[KotatsuApp/Kotatsu](https://github.com)**.
- Licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see the [LICENSE](LICENSE) file for details.

## Disclaimer

**Kotatsu Desktop** is an unofficial, independent open-source project and is not affiliated with, endorsed by, or connected to the original **KotatsuApp** team or any manga/comic publishers.

This application does not host, own, store, or distribute any manga, manhwa, comics, or copyrighted content. It acts solely as a desktop client and browser interface that fetches publicly available data via third-party parser libraries (`kotatsu-parsers-redo`). Users are responsible for complying with the terms of service of any external sources they access and the copyright laws of their respective jurisdictions.
