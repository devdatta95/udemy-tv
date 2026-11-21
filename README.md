# Udemy TV (Fire TV)

A personal Fire TV app that opens udemy.com full-screen and lets you use it with the **remote** (no mouse cursor).

## Remote controls

| Button | What it does |
|---|---|
| ◀ ▲ ▼ ▶ | Move the yellow highlight between items. Scrolls when reading long text. |
| OK | Open / click the highlighted item. On a text box it opens the keyboard. |
| Back | Close a popup or keyboard, otherwise go back a page. Press twice on the first page to exit. |
| ⏯ Play/Pause | Play or pause the lecture video |
| ⏪ / ⏩ | Skip back / forward 10 seconds |
| ☰ Menu | My Learning, Reload, Video fullscreen, Text size, Desktop site, Sign out, Exit |

When the **video itself** is highlighted: ◀ ▶ skip 10 s, OK plays/pauses.
In fullscreen video: ◀ ▶ skip, OK plays/pauses, Back leaves fullscreen.

## Install on Fire TV

1. Fire TV: Developer Options → **ADB debugging ON**, **Install unknown apps → Downloader ON**.
2. Open **Downloader**, enter the APK link from the repo's **Releases → latest → udemy-tv.apk**.
3. Install, then open **Udemy TV** from *Your Apps & Channels*.
4. Sign in with your email (code or password).

Updates: push to `main` → GitHub Actions builds a new APK → install it the same way (it replaces the old one, login is kept).

## Build

GitHub Actions builds `udemy-tv.apk` on every push (`.github/workflows/build.yml`).
Local build: open the folder in Android Studio and run *Build → Build APK(s)*.

Signing uses repository secrets `KEYSTORE_B64`, `KS_PASS`, `KEY_ALIAS`, `KEY_PASS`; without them a debug key is used.

## Debugging

The WebView is debuggable: with the stick on ADB (`adb connect <stick-ip>:5555`), open `chrome://inspect` on your laptop.
