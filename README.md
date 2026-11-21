# Udemy TV (Fire TV)

A personal Fire TV app for udemy.com, built for the remote.

## Pages: pointer

| Button | Action |
|---|---|
| ◀ ▲ ▼ ▶ | Glide the pointer (short press = small step, hold = faster) |
| OK | Click. On a text box (email, password, OTP, search, Q&A) a fast native input box opens |
| ⏪ / ⏩ | Scroll up / down (or push the pointer against a screen edge) |
| ⏯ twice | Video fullscreen |
| Back | Previous page (twice on the first page = exit) |
| ☰ | Menu: My Learning, Search, Back/Forward, Pointer on/off, Pointer speed, Screen layout, Text size, Faster browsing, Help, Sign out |

## Fullscreen video: remote controls (automatic)

| Button | Single press | Double press |
|---|---|---|
| OK / ⏯ | Play / pause | Fullscreen on / off |
| ▶ / ◀ | +5 s / −5 s (hold to keep skipping) | Next / previous lecture |
| ▲ | Faster | Normal speed (1×) |
| ▼ | Slower | Captions on / off |
| ⏩ / ⏪ | Next / previous lecture | |
| Back | Leave fullscreen | |
| ☰ | Video menu: speed list, captions, ±30 s, restart, pointer on/off, help | |

## Install / update on Fire TV

Downloader → `https://github.com/devdatta95/udemy-tv/releases/download/latest/udemy-tv.apk` → Install.

## Build

GitHub Actions builds `udemy-tv.apk` on every push to `main` and publishes it as the `latest` release.

## Debugging

The WebView is debuggable: `adb connect <stick-ip>:5555`, then open `chrome://inspect` on a laptop.
