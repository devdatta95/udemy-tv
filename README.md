# Udemy TV (Fire TV)

A personal Fire TV app for udemy.com, built for the remote.

## Two modes (switches automatically)

**Pointer mode** – all normal pages (login, My Learning, course pages, Q&A, Cloudflare check):

| Button | Action |
|---|---|
| ◀ ▲ ▼ ▶ | Move the mouse pointer (hold to speed up). Pushing against a screen edge scrolls. |
| OK | Click / tap (hold OK + arrows = drag) |
| ⏪ / ⏩ | Scroll page up / down |
| ⏯ | Jump to video controls (when a lecture video is on screen) |
| Back | Previous page (press twice on the first page to exit) |
| ☰ | Menu: My Learning, Reload, Screen layout, Text size, Sign out, Exit |

**Video mode** – turns on by itself when a lecture video is on screen:

| Button | Action |
|---|---|
| OK / ⏯ | Play / pause |
| ◀ ▶ (and ⏪ ⏩) | Back / forward 10 s (hold to keep skipping) |
| ▲ ▼ | Speed up / slow down (0.5× – 2×) |
| ☰ | Video menu: speed list, next / previous lecture, fullscreen, use pointer, … |
| Back | Leave fullscreen, otherwise switch to the pointer (captions, notes, lecture list) |

## Install / update on Fire TV

Downloader → `https://github.com/devdatta95/udemy-tv/releases/download/latest/udemy-tv.apk` → Install.

## Build

GitHub Actions builds `udemy-tv.apk` on every push to `main` and publishes it as the `latest` release.

## Debugging

The WebView is debuggable: `adb connect <stick-ip>:5555`, then open `chrome://inspect` on a laptop.
