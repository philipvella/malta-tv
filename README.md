<p align="center"><img src="art/banner-1536x864.png" alt="Malta TV" width="480"></p>

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
| TVM            | https://tvmi.mt/live/2            |
| NET            | https://netondemand.mt/           |
| ONE            | https://play.one.com.mt/tv        |
| SMASH          | https://smashmalta.com/           |

## Features

- **Station buttons** — TVM, NET, ONE, SMASH. The remote's **next / previous** buttons
  (⏭ ⏮ or **P+ / P−**) step through the stations in order.
- **＋ Add station** — add any web page as your own station (saved on the device).
  Long-press a custom (★) station to remove it.
- **Refresh** — reload the current page. Also on the remote's **RED** button.
- **Full screen** — hide the controls. Also toggled by the remote's **GREEN** button;
  **BACK** or **MENU** brings the controls back.
- **On-screen mouse pointer** — move into the web page (press DOWN from the buttons) and a
  pointer appears: arrow keys move it (faster the longer you hold), **OK** clicks,
  pushing against the top/bottom edge scrolls the page. It hides itself after 3 seconds.
- **⚙ Settings** (gear icon) — auto full screen and ad-blocking options (below).
- **⏻ Exit** (power icon) — close the app.
- The address strip always shows which web page is loaded.
- Remembers the last station you watched.

### Auto full screen on channel change

*Settings → Auto full screen on channel change.* When on, every time you change station the app
looks for the page's video player (for ~15 s while it loads), asks the page to go full screen,
and stretches the player over the whole screen with the app's controls hidden. Press **BACK** to
undo it. This is best effort: websites decide how their players behave, so on some sites it may
pick the wrong element or not find the player at all.

### Ad blocking

Ads and trackers loaded by the web pages are blocked **before they download**, using the
[StevenBlack/hosts](https://github.com/StevenBlack/hosts) unified list (adware + malware,
MIT licensed, ~78k domains), plus a few ad servers seen on the built-in stations that the list
misses (currently `ads.netnews.com.mt`, NET's ad server). A copy is built into the app and a fresh one is downloaded
weekly in the background; if a download fails the previous list keeps working.

How it works: every request the page makes passes through `WebViewClient.shouldInterceptRequest`
(and `ServiceWorkerClient` for service workers on Android 7+). If the request's host is on the list,
or a subdomain of a listed domain, the app returns an empty response instead of fetching it.
The page you opened yourself is never blocked. No VPN, DNS change or root is used, and TLS
validation is untouched.

**If a site breaks** (video won't start, login fails, buttons do nothing), open **⚙ Settings**:

| Setting | Effect |
| ------- | ------ |
| *Ad blocking on &lt;site&gt;: ON/OFF* | Turns blocking off just for the current site (and its subdomains). |
| *Allow a blocked domain…* | Shows the hosts blocked on the current page; pick one (or type one) to never block it on any site. |
| *Manage ad-block exceptions* | Lists your exceptions; select one to remove it. |
| *Ad blocking (all sites)* | Master switch. |
| *Update blocklist now* | Downloads the latest list immediately. |

**Limitations — it does not block every advert:**

- Ads stitched into the video stream itself (server-side ad insertion) come from the same
  servers as the programme and cannot be separated by domain.
- Ads served from the site's own domain, or from a domain not on the list, are not blocked.
- Blocking is by domain only (no EasyList-style path rules), and leftover empty ad boxes are not
  hidden (no cosmetic filtering).
- Requests that WebView does not route through the interception APIs (for example some media
  segment fetches made internally by the player, WebSocket traffic) are not filtered.
- Service-worker interception is app-wide: worker requests are judged against the site currently
  open, which can differ from the site that registered the worker.
- Blocking a tracker or ad script can occasionally break a site; use the per-site switch above.

To refresh the copy built into the APK: `scripts/update-blocklist.sh`.

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
See *Build & test* below. Every push to `main` is built by GitHub Actions; pushing a `v*` tag publishes a release with the APK.

## Notes

- Whether a page plays in the app depends on the website itself (geo-restrictions,
  logins, or player changes on their side may affect playback).

## Build & test

```sh
./gradlew testDebugUnitTest   # ad-block unit tests (domain matching, exceptions, list loading)
./gradlew assembleDebug
```

## Artwork

The app icon and TV banner in [`art/`](art) were generated with AI (Figma AI image generation)
and resized into the Android resource folders.

## License

[MIT](LICENSE) — free to use, share and modify.
The bundled blocklist is © Steven Black, MIT licensed
([licence](app/src/main/assets/adblock/LICENSE-StevenBlack-hosts.txt)).
