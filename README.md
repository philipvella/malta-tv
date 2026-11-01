# 🌐 Malta TV — a free, open-source, web-based Android TV app

**Malta TV** is a free and public Android TV app that opens Maltese TV broadcasters'
**official public websites** on your TV.

> **It is web based.** The app contains no video player, no streams and no content of its own.
> It is a simple embedded web browser (Android `WebView`) that loads the broadcasters'
> own web pages — exactly what you would see if you opened them in a browser on a computer.
> All content belongs to its respective owners, and this project is not affiliated with
> any broadcaster.

## Stations included

| Station        | Web page                          |
| -------------- | --------------------------------- |
| Net Live       | https://netondemand.mt/           |
| Net Schedule   | https://netondemand.mt/schedule   |
| TVM Live       | https://tvmi.mt/live/2            |

## Features

- **Station buttons** — switch between the web pages above with the remote.
- **＋ Add station** — add any web page as your own station (saved on the device).
  Long-press a custom (★) station to remove it.
- **⟳ Refresh** — reload the current page.
- **⛶ Full screen** — hide the controls. Press **BACK** or **MENU** to bring them back.
- **✕ Exit** — close the app.
- The address strip always shows which web page is loaded.
- Remembers the last station you watched.

## Install

1. Download `malta-tv.apk` from the [latest release](../../releases/latest)
   (or from the artifacts of the latest [Build APK](../../actions) run).
2. Sideload it on your Android TV, e.g. with the *Downloader* app or:
   ```sh
   adb connect <tv-ip>
   adb install malta-tv.apk
   ```

## Build it yourself

Requires JDK 17 and the Android SDK (or just open the folder in Android Studio).

```sh
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Every push to `main` is built by GitHub Actions; pushing a `v*` tag publishes a release with the APK.

## Notes

- Whether a page plays in the app depends on the website itself (geo-restrictions,
  logins, or player changes on their side may affect playback).
- Navigating *inside* a web page with a TV remote depends on how well the website
  supports keyboard/D-pad focus. An air-mouse or a remote with a pointer works best.

## License

[MIT](LICENSE) — free to use, share and modify.
