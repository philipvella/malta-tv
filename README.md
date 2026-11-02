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
- **Remote colour-key dock** — a floating glass dock at the bottom of the screen holds the four
  colour-key buttons, in the same order as the keys on the remote. It appears when you use the
  remote on the app's controls (or press a colour key: the matching button lights up), and hides
  itself after 4 seconds. To reach it with the arrows, move the pointer off the bottom of the page;
  **UP** goes back into the page:

  | Key | Button | Does |
  |---|---|---|
  | 🔴 **RED** | Refresh | reload the current page |
  | 🟢 **GREEN** | Full screen | hide / show the controls (**BACK** or **MENU** also bring them back) |
  | 🟡 **YELLOW** | Add station | add any web page as your own station (saved on the device); long-press a custom (★) station to remove it |
  | 🔵 **BLUE** | ⚙ Settings | auto full screen and ad-blocking options (below) |
- **On-screen mouse pointer** — move into the web page (press DOWN from the buttons) and a
  pointer appears: arrow keys move it (faster the longer you hold), **OK** clicks,
  pushing against the top/bottom edge scrolls the page. It hides itself after 3 seconds.
- **⏻ Exit** (power icon) — close the app.
- The address strip always shows which web page is loaded.
- Remembers the last station you watched.
- **Splash screen** on launch, in the same style as the app icon — *Developed by vellaphilip.com*.

### Look & feel: Liquid Glass

The whole app uses a Liquid Glass style, inspired by Apple's design language: translucent
glass capsules with specular rims floating over an ambient Malta-red backdrop, bright glass for
the remote's focus, red-tinted glass for the active station, and glass dialogs, menus, fields,
toasts and pointer. The TV's Android version (9) has no live background blur, so the glass is
built from layered translucent gradients and highlight rims. The rules live in
[`.claude/skills/malta-tv-liquid-glass`](.claude/skills/malta-tv-liquid-glass/SKILL.md).

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

**TVM starts with ad blocking off.** TVM's player often stalls after its pre-roll advert
(frozen on the last frame) when its analytics requests are blocked. TVM's own adverts aren't on
the list anyway. You can turn it on for `tvmi.mt` in ⚙ Settings.

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

The original icon and TV banner in [`art/`](art) were generated with AI (Figma AI image
generation). [`scripts/make_glass_art.py`](scripts/make_glass_art.py) cuts the white TV /
Maltese-cross glyph and the wordmark out of that banner and re-composes them as Liquid Glass
artwork: the launcher icon, the TV banner and the splash logo (`python3 scripts/make_glass_art.py`).

## License

[MIT](LICENSE) — free to use, share and modify.
The bundled blocklist is © Steven Black, MIT licensed
([licence](app/src/main/assets/adblock/LICENSE-StevenBlack-hosts.txt)).
