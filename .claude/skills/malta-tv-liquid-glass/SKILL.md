---
name: malta-tv-liquid-glass
description: Malta TV's Liquid Glass design system. Use whenever adding or changing ANY user-facing part of the Malta TV Android TV app — buttons, the control bar, dialogs, menus and sub-menus, text fields, toasts, the pointer, the splash screen, the launcher icon, the TV banner or the logo — so everything keeps the same Liquid Glass look.
---

# Malta TV — Liquid Glass

Every screen, control, menu and piece of artwork in Malta TV uses one look: **Liquid Glass**,
modelled on Apple's design language (iOS / tvOS 26). Do not add a UI element that is not glass.

## What Liquid Glass is (the principles we follow)

Apple describes Liquid Glass as a digital material that bends and shapes light. It is translucent,
takes its colour from what's behind it, catches specular highlights on its edges, and adapts.
What that means here:

1. **Glass floats over an environment.** The content and backdrop sit underneath. Controls, bars,
   sheets and toasts are glass layers floating above them, with rounded **capsule** shapes,
   margins from the screen edges and never edge-to-edge slabs. The backdrop is `bg_ambient`:
   near-black with a Malta-red glow top-left and a faint cool glow bottom-right. The splash
   uses `bg_splash`, which is the icon's red.
2. **Glass is for controls, not content.** Never put glass over the web page or video.
   The page sits in a thin glass *frame* (`glass_frame`, rounded and clipped), and in full
   screen it goes edge-to-edge with no frame.
3. **Light lives on the edges.** Each glass surface has:
   - a translucent vertical gradient body (lighter at the top);
   - a sheen in its top half;
   - a 1 dp **specular rim** (`glass_rim`);
   - a faint **inner rim** inset 1.5 dp (`glass_rim_inner`).
4. **Focus = the glass lights up and swells.** On TV the remote's focus is the main interaction:
   - a focused control gets bright glass (`glass_focus_*`), a 2 dp white rim, a 1.08× scale
     and a little lift (`@animator/glass_focus`);
   - a selected control (the active station) is **red-tinted glass** (`glass_red_*`);
   - selected and focused together is red glass with the white rim.
5. **Restraint.** Text is white (`text`) or white at 70 % (`text_dim`). The only extra colour
   comes from the Malta red and the four remote colour-key beads. Leave out emoji in chrome:
   use white line icons (vector, `#FFFFFFFF`) and the glossy `dot_*` beads.
6. **No live blur on our TVs.** The target Sony Bravia runs Android 9 (API 28), and
   `RenderEffect` blur needs API 31. So bars, sheets and toasts use a **denser** dark glass
   (`panel_*`, `sheet_*`) to stay legible over anything. Don't add blur that only works on
   new devices unless there's a fallback that looks the same.

## The kit: use these, don't invent new ones

| Need | Use |
|---|---|
| Screen background | `@drawable/bg_ambient` (app), `@drawable/bg_splash` (splash and launch window) |
| Bar / floating panel | `@drawable/glass_bar` (radius 34 dp) |
| Small pill (address, credits) | `@drawable/glass_pill` |
| Frame around web content | `@drawable/glass_frame` + `clipToOutline` |
| Big hero card (splash) | `@drawable/glass_card` |
| Text button | `style="@style/GlassButton"`; in code `Button(ctx, null, 0, R.style.GlassButton)` |
| Icon-only button | `style="@style/GlassIconButton"` + a white vector icon |
| Remote colour key | `drawableStart="@drawable/dot_red|dot_green|dot_yellow|dot_blue"` |
| Dialog, menu, sub-menu | `AlertDialog.Builder(this)`: the app theme sets `alertDialogTheme = GlassDialog`, so sheets, lists, buttons and titles are glass automatically. Don't pass another theme. |
| Text field / label built in code for a dialog | create it with the `glass` context (`EditText(glass)`, `TextView(glass)`), which picks up `GlassField` |
| Toast / notice | the activity's `toast(msg)`, a glass capsule. Never call `Toast.makeText` directly. |
| Pointer | `PointerOverlay`: frosted white arrow, thin dark rim, soft shadow |

Colour tokens are in `res/values/colors.xml`. The styles and the `GlassDialog` theme are in
`res/values/styles.xml`. Change a token rather than hard-coding a colour in a layout or in code.

## Remote colour keys: the bottom dock

The colour-key actions live in a **floating glass dock** at the bottom centre (`@id/dock`), in the
remote's key order: **RED** Refresh, **GREEN** Full screen, **YELLOW** Add station,
**BLUE** Settings. Each button shows its glossy bead. The top bar holds only the logo, the
stations and Exit (icon only). How the dock behaves:
- `showDock()` is called on remote activity outside the page and on colour keys
  (`flashDockButton`). It slides up and fades in.
- `hideDock()` runs after `DOCK_HIDE_MS` (4 s) with no key presses. It hands focus back to the
  active station first, so focus never lands on an invisible button.
- It never shows in full screen or over HTML5 fullscreen video, and it sits under the
  fullscreen video holder in z-order.
- D-pad route: pushing the pointer off the bottom of the page (`onPointerExitBottom`) focuses
  the dock. The dock buttons' `nextFocusUp` is the WebView.

A new remote-key action goes in the dock in key order, with its bead and a
`flashDockButton` call, wired in `MainActivity.dispatchKeyEvent`.

## Artwork (icon, banner, splash logo)

- Don't draw new artwork by hand. Run `python3 scripts/make_glass_art.py`. It cuts the white TV /
  Maltese-cross glyph and the "Malta TV" wordmark out of the original AI banner
  (`art/banner-1536x864.png`). Then it lays them on a Malta-red environment under a glass slab
  with a frosted body, sheen, specular rim, inner rim and drop shadow.
- It writes:
  - the launcher icon to every `mipmap-*`;
  - the TV banner to `drawable-xhdpi/banner.png` (640×360);
  - the splash/bar logo to `drawable-nodpi/splash_logo.png`;
  - the full-size masters to `art/*-glass-*.png`.
- To change the art, change the script and re-run it, so all the artwork stays consistent.

## Splash screen

- Layout: `@id/splash` in `activity_main.xml`.
  - Malta-red `bg_splash`.
  - A glass card holding the logo, which scales from 0.9 to 1 and fades in.
  - A glass pill at the bottom: **"Developed by vellaphilip.com"**. It must always stay.
- The window background (`AppTheme`) is also `bg_splash`, so a cold start goes straight from red
  into the splash.
- Timing:
  - it hides on the first `onPageFinished`, after at least `SPLASH_MIN_MS` (2 s);
  - it always hides by `SPLASH_MAX_MS` (6 s);
  - any key except BACK skips it.

## Checklist for any UI change

- [ ] Every new surface is one of the kit drawables, with no flat opaque `#…` backgrounds.
- [ ] Focusable controls use `glass_button`/`glass_row` + `@animator/glass_focus`. Give them
      room for the 1.08× focus swell with padding plus `clipToPadding="false"`. Never set
      `clipChildren="false"` on a **scrolling** container (like the station strip), because the
      scrolled-away buttons then draw over their neighbours.
- [ ] Text is `@color/text` / `@color/text_dim`. Icons are white vectors. There's no emoji in chrome.
- [ ] Dialogs use `AlertDialog.Builder(this)`, fields use the `glass` context, and notices use `toast()`.
- [ ] Full screen still goes edge to edge (no frame, no margins, no clip).
- [ ] Build in CI (no local SDK), install the debug APK on the TV and take a screenshot:
      `adb -s 192.168.68.100:5555 exec-out screencap -p > shot.png`. Check the bar, a focused
      button, a dialog with its list, a toast and the splash. Only use the TV if nobody is watching it.
