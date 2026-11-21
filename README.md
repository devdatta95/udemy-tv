# Udemy TV (Fire TV)

A personal Fire TV app for udemy.com, built for the remote.

## Pages — two control styles (☰ → Control style)

**Pointer (default)**

| Button | Action |
|---|---|
| ◀ ▲ ▼ ▶ | Glide the pointer (short press = small step, hold = faster) |
| OK | Click. Text boxes open a fast native input box. Video player controls work too |
| ⏪ / ⏩ | Scroll a screen (the panel under the pointer, e.g. the lecture list) |
| Push pointer against an edge | Scroll that column (lecture list or page) |

**Navigation** (like the YouTube app, no pointer)

| Button | Action |
|---|---|
| ◀ ▲ ▼ ▶ | Move the purple highlight (stays inside the lecture list while it scrolls) |
| OK | Open / click (on the video: play / pause) |
| ⏪ / ⏩ | Scroll a screen |

Everywhere: ⏯ play / pause, Back = close popup or previous page, ☰ = menu.

## Fullscreen video (☰ → Watch fullscreen)

| Button | Action |
|---|---|
| OK / ⏯ | Play / pause |
| ◀ / ▶ | −5 s / +5 s (hold to keep skipping) |
| ▲ / ▼ | Faster / slower (0.5× – 2×) |
| ⏪ / ⏩ | Previous / next lecture (fullscreen comes back on the new lecture) |
| Back | Exit fullscreen |
| ☰ | Video menu: speed list, captions, ±30 s, restart, exit fullscreen, help |

## Install / update on Fire TV

Downloader → `https://github.com/devdatta95/udemy-tv/releases/download/latest/udemy-tv.apk` → Install.

## Build

GitHub Actions builds `udemy-tv.apk` on every push to `main` and publishes it as the `latest` release.

## Debugging

The WebView is debuggable: `adb connect <stick-ip>:5555`, then open `chrome://inspect` on a laptop.
