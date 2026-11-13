# Ohm — Tesla-style Launcher for Junsun / Android Head Units

> Working name: **Ohm** (placeholder — rename freely).
> Target: **Junsun V2 Pro** in a **Renault Mégane 3**, but built to run on any landscape Android head unit.
> UI/UX reference: the Tesla Model 3/Y touchscreen layout, and how [VoltaOS](https://github.com/Omardigio/VoltaOS) reinterprets it.

---

## 1. Goals & non-goals

### Goals
- Replace the stock Junsun home screen with a **full-screen, Tesla-like launcher**: car on one side, big map on the other, dock along the bottom.
- Show **real car data** (gear, lights, doors, speed) from the canbox where available.
- **Day/night theme** driven by the headlights (ILL wire / CAN), not just the clock — fixing the "auto mode" problem.
- Keep everything the unit already does working: Android Auto / CarPlay (ZLink), radio, Bluetooth, Spotify, etc.
- Smooth on mid-range head-unit hardware (60 fps target, 30 fps floor).

### Non-goals
- Not a custom ROM. No bootloader unlocking, no system partition changes (root optional, never required).
- No **writing** to the car's CAN bus. Read-only, for safety.
- No climate/seat/charging controls — the Mégane 3 can't be safely controlled that way.

### Branding boundary
The **layout, interaction model and overall feel** follow Tesla's UI closely. The app does **not** use Tesla's name, "T" logo, proprietary fonts, icons, or 3D car assets. It uses its own name/icon, open fonts, and a Mégane 3 (or generic) car model. This keeps it publishable if you ever want to share it.

---

## 2. Target hardware

| Item | Junsun V2 Pro (verify on your unit: *Settings → About*) |
|---|---|
| SoC | Unisoc UIS7862-class, 8-core ARM64 (Cortex-A75 + A55) |
| GPU | Mali-G52-class — OpenGL ES 3.2 / Vulkan 1.1 |
| RAM / storage | 4–8 GB / 64–128 GB |
| Display | Landscape, 1280×720 or 2000×1200 (QLED/2K variants) |
| Android | 10–12 (vendor firmware) |
| CAN | External canbox → MCU → vendor car service |
| Inputs | Touch, steering-wheel keys (via canbox/adapter), ILL wire |

**Rule:** design for **1280×720 at mdpi-ish density first**; scale up to 2K. Everything must remain usable with one finger while driving.

---

## 3. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Native Android, best API access |
| UI | Jetpack Compose (Material 3, heavily themed) | Fast iteration, animations, theming |
| Map | **MapLibre Native Android** (+ MapTiler/OpenFreeMap tiles) or Mapbox | Embeddable, custom dark/light styles, 3D buildings |
| Navigation | Mapbox Navigation SDK *or* hand-off to Google Maps/Waze | Google Maps nav can't be embedded |
| 3D car | **Filament** via **SceneView** (glTF/GLB) | Lightweight PBR, runs well on Mali |
| Media | `MediaSessionManager` + `NotificationListenerService` | Control any playing app |
| Car data | Vendor car-service bridge (see §8) or ESP32 CAN fallback | |
| Weather | Open-Meteo (no API key) | Same as VoltaOS |
| Storage | DataStore (settings), Room (favourites, recents) | |
| Min SDK | 29 (Android 10) | Covers Junsun firmwares |

---

## 4. Screen layout

The home screen is a fixed split, mirrored for right-hand drive (Settings → Display → Driving position).

```
┌──────────────────────────────┬──────────────────────────────────────────────┐
│  P R N D        42 km/h      │ 🔒  14:32   ☀ 18°   📍 Lisboa        ⚠ PASS │ ← status bar (over map)
│                              │                                              │
│                              │                                              │
│        [ 3D CAR MODEL ]      │                                              │
│      (drag to rotate,        │                 FULL-BLEED MAP               │
│     tap doors/trunk/hood)    │         (follows heading, 3D buildings)      │
│                              │                                              │
│   🔓 Unlock   Trunk   Hood   │       ┌─ search bar ─────────────┐           │
│                              │       │ 🔍 Navigate…             │           │
│ ┌──────────────────────────┐ │       └──────────────────────────┘           │
│ │ ▣ Title — Artist     ♡   │ │                                       ⊕  ⊖ ◎ │
│ │ ━━━━━━━━━━━○──────────── │ │                                              │
│ │   ⏮    ⏯    ⏭   🔍  ≡   │ │                                              │
│ └──────────────────────────┘ │                                              │
├──────────────────────────────┴──────────────────────────────────────────────┤
│ 🚗 │  📞  🎵  📻  🅰  ▶  📷  ⋯ (drawer) │  recent: ◻ ◻ ◻     │  ‹ 🔊 ›  │ ← dock
└─────────────────────────────────────────────────────────────────────────────┘
```

### Proportions (landscape, LHD)
| Region | Size |
|---|---|
| Car panel (left) | **~35 %** width (draggable 28–45 %, via a grab strip on the map's left edge) |
| Map / app area (right) | remainder |
| Dock (bottom) | **~11 %** height (min 72 dp) |
| Status bar | overlays the map, ~40 dp, translucent |

### Behaviour
- **Apps open on the right side**, over the map, as a sliding card (Tesla style), leaving the car panel and dock visible. Swipe the card down or tap outside to close.
- **Full-screen apps** (Android Auto, video, browser) can take the whole screen; a small pull-tab brings the dock back.
- In **reverse gear** the right side switches automatically to the camera app (if the unit exposes it) and returns when leaving R.

---

## 5. Components

### 5.1 Car panel (left)
- **Gear strip** `P R N D` — active gear bright, others muted. From CAN; fallback hidden.
- **Speed** — large digits, from CAN if available, else GPS. Optional speed-limit badge (from map data).
- **3D car** (GLB, ~50–80k triangles max):
  - drag to orbit (limited pitch), pinch to zoom, double-tap to reset
  - floor reflection + soft shadow (disabled in low-power mode)
  - live state from CAN: **doors / trunk / hood open** animations, **headlights on** glow, **brake lights** in R/braking
  - car colour picker (paint swatches)
- **Quick buttons** under the car: Lock indicator, Trunk, Hood — **display-only** on the Mégane (they show state, they don't actuate anything).
- **Music widget** at the bottom of the panel (§5.4).
- **Notification stack** sits just above the music widget (calls, messages, volume banner).

### 5.2 Status bar (top of the map)
Left to right: lock icon · clock · weather (icon + °) · location name · passenger-airbag indicator (if CAN provides it) · connectivity (Wi-Fi / LTE / BT).
- Tap **temperature** → weather popup: hourly + 7-day, rain %, wind, UV, air quality (Open-Meteo). Cached offline.
- Tap **clock** → quick settings (brightness, theme override, Wi-Fi, BT).

### 5.3 Map
- Full-bleed, **north-up / heading-up** toggle, follow-me puck with smooth heading interpolation.
- Styles: **Light**, **Dark**, **Satellite**; Auto switches Light/Dark with the global theme (§6).
- Floating **search bar** ("Navigate…") with recents, Home/Work favourites, categories (fuel, parking, EV, food).
- Route preview card: ETA, distance, traffic, alternative routes.
- During navigation: top **maneuver card** (next turn, distance, lane guidance), bottom-left **ETA panel** (arrival time, remaining km/min), speed-camera alerts.
- Zoom ⊕ ⊖ and recenter ◎ buttons, bottom-right.
- Optional: **"Open in Google Maps / Waze"** button for those who prefer those engines.

### 5.4 Music widget & Media Hub
- **Widget:** cover art, title, artist, progress bar, ⏮ ⏯ ⏭, ♡ like (where supported), 🔍 search, ≡ queue.
- Source-aware controls: radio → stop instead of skip; podcasts → ±15 s and speed.
- Tap cover/title → **Now Playing** sheet on the right side: big art (blurred background from cover colours), queue, source switcher.
- **Sources:** any MediaSession app (Spotify, YT Music, Deezer…), Bluetooth audio, local/USB files, **FM/AM radio** via the Junsun radio app (launch + metadata if exposed).
- Volume change (from steering wheel or dock) → slim **banner above the widget**, auto-hides after 2 s.

### 5.5 Dock (bottom bar)
| Zone | Content |
|---|---|
| Far left | **Car button** → Car/Settings sheet (Tesla "car" icon equivalent — own icon) |
| Centre-left | **Pinned apps** (up to 8): Phone, Music, Radio, Android Auto/ZLink, Video, Camera, Browser… |
| Centre | **App drawer** (grid icon) |
| Centre-right | **Recent apps** (last 3) |
| Far right | **Volume**: ‹ › in 5 % steps, tap speaker to mute |

- Long-press an icon → edit mode: drag to reorder, drag between drawer and dock to pin/unpin.
- Optional left/right **climate-style placeholders** are *not* included (no control on Mégane).

### 5.6 App drawer
- Right-side card, grid of all launchable apps, alphabetic, search field at top.
- Edit mode: hide apps (stay installed), pin to dock.
- Web shortcuts (PWA-style) at the top.

### 5.7 Phone
- Uses the head unit's BT phone stack: launch the Junsun dialer, or (if the vendor exposes call state broadcasts) show **in-call banner** with answer/decline/mute in the notification stack.
- Contacts/recents read via vendor provider if available; otherwise hand off to the stock app.

### 5.8 Settings ("Car" sheet)
Tabs on the left of the sheet, content on the right (Tesla Controls layout):
- **Display:** theme (Auto-headlights / Auto-time / Light / Dark), brightness, driving position (LHD/RHD), car panel width, low-power mode.
- **Car:** model (Mégane 3 hatch / estate / coupé / generic), paint colour, units (km/h, °C), CAN source (Vendor / ESP32 / None), CAN live view (debug).
- **Map:** provider, default style, voice guidance, speed cameras, avoid tolls/highways.
- **Media:** default source, auto-resume on boot, volume step.
- **Apps:** dock layout, hidden apps, default nav app, default camera app.
- **System:** language (PT, EN, + others), screen-clean mode (lock touch, hold to exit), about, export logs.

---

## 6. Visual design

### 6.1 Principles (Tesla feel)
- **Content first:** map and car dominate; chrome is minimal, thin and flat.
- **Monochrome + one accent blue.** Colour only for state (red = alert, green = OK, amber = warning).
- **Large, calm typography;** no visible borders, separation by spacing and subtle surfaces.
- **Cards slide in from the side/bottom**, never pop modally over the car.
- **Dark mode is the hero;** light mode is the clean white daytime version.

### 6.2 Colour tokens (adapted from VoltaOS `Theme.qml`)

| Token | Light | Dark |
|---|---|---|
| `bg` | `#FFFFFF` | `#171717` |
| `surface` | `#F0F0F0` | white @ 8 % (`#14FFFFFF`) |
| `surfaceElevated` | `#E0E0E0` | white @ 14 % (`#24FFFFFF`) |
| `surfaceInput` | `#F0F0F0` | white @ 10 % (`#1AFFFFFF`) |
| `textPrimary` | `#171717` | `#FFFFFF` |
| `textSecondary` | `#757575` | `#A2A3A5` |
| `textHint` | `#A2A3A5` | `#757575` |
| `border` | `#D0D1D2` | white @ 15 % |
| `divider` | `#E0E0E0` | white @ 8 % |
| `icon` | `#414141` | `#E0E0E0` |
| `iconMuted` | `#A2A3A5` | `#757575` |
| `overlay` | `#171717` @ 20 % | `#171717` @ 60 % |

| Accent | Hex |
|---|---|
| `accent` | `#2E78FF` |
| `accentDeep` | `#005AFF` |
| `accentSoft` | `#A8C3F4` |
| `success` | `#25CB55` |
| `warning` | `#FFC107` |
| `danger` | `#ED4E3B` |
| `dangerDeep` | `#B74134` |

### 6.3 Typography
- Font: **Inter** (or Manrope) — open-licence, geometric/neutral. *Not* Tesla's proprietary font.
- Scale (at 1280×720): speed 64 sp light · title 22 sp medium · body 16 sp · label 13 sp medium · caption 11 sp.
- Numbers use tabular figures (speed, clock, ETA).

### 6.4 Shape, spacing, motion
- Corner radius: cards 16 dp · buttons 12 dp · chips 999 (pill).
- Spacing grid: 4 dp; panel padding 16–24 dp.
- Touch targets ≥ 56 dp (driving).
- Motion: 220 ms ease-out for card slide, 150 ms for fades, spring for car orbit release. No animation > 300 ms.
- Glass/blur on cards over the map (RenderEffect blur, API 31+); **solid surfaces fallback** on Android 10–11 and in low-power mode.

### 6.5 Iconography
- Outline icons, 2 dp stroke, rounded caps (Material Symbols Rounded or Lucide). Own custom car/dock icons.

---

## 7. Day / night logic

Priority order for **Auto (headlights)** mode:
1. **CAN headlight state** from the vendor service / ESP32.
2. **ILL wire** state (exposed by the Junsun firmware as night mode / backlight dimming — read via broadcast or system setting).
3. **Sunrise/sunset** from GPS position (fallback).

On change:
- Switch app theme (Light ↔ Dark) with a 300 ms cross-fade.
- Switch map style.
- Optionally set Android's system dark mode (`UiModeManager`, needs `MODIFY_DAY_NIGHT_MODE` / root on some firmwares) so **Google Maps, Waze, Spotify etc. follow too**.
- Hysteresis: 10 s debounce to avoid flicker in tunnels.

---

## 8. Car data (CAN) integration

The canbox decodes the Mégane's CAN bus and the Junsun MCU passes data to Android via a vendor service. The app abstracts this behind one interface:

```kotlin
interface CarDataSource {
    val gear: StateFlow<Gear?>              // P R N D
    val speedKmh: StateFlow<Float?>
    val headlightsOn: StateFlow<Boolean?>
    val doors: StateFlow<DoorState?>        // FL FR RL RR trunk hood
    val reverse: StateFlow<Boolean>
    val outsideTempC: StateFlow<Float?>
    val steeringKeys: SharedFlow<SteeringKey> // vol+, vol-, next, prev, mode
}
```

Implementations:
1. **`VendorCarSource`** — reverse-engineered from the Junsun firmware:
   - pull system APKs (`adb shell pm list packages -f`, `adb pull`), decompile with **jadx**
   - look for car/CAN services, AIDL interfaces, broadcast actions (e.g. reverse, illumination, door, key events)
   - confirm with `adb logcat` while toggling lights, opening doors, shifting into R
2. **`Esp32CanSource`** — fallback: ESP32 + SN65HVD230 transceiver on the Mégane's comfort/multimedia CAN, read-only, sends JSON/binary frames over **USB-serial** (or BLE). Full control, independent of Junsun firmware.
3. **`NoCarSource`** — GPS speed only; car panel shows a static car.

Settings → Car → **CAN live view** shows the raw values for debugging.

---

## 9. Android integration

| Need | How |
|---|---|
| Be the home screen | `<category android:name="android.intent.category.HOME"/>` + `DEFAULT`; user picks it once |
| Start on boot | It's the launcher — automatic |
| Read now-playing | `NotificationListenerService` permission → `MediaSessionManager.getActiveSessions()` |
| Volume | `AudioManager` (STREAM_MUSIC); check if vendor firmware routes volume through MCU instead |
| Location | Fused/GPS provider; `ACCESS_FINE_LOCATION` |
| Launch other apps | `PackageManager.getLaunchIntentForPackage()` |
| Android Auto / CarPlay | Launch the unit's ZLink/TLink app full-screen (can't be embedded) |
| Reverse camera | Launch vendor camera app on reverse event, or let firmware handle it |
| Keep stock apps | Nothing is removed; user can switch launcher back any time |

Optional with root (Magisk, if possible on the V2 Pro): system-wide dark mode toggle, hide vendor overlays, faster boot to launcher.

---

## 10. Performance budget

| Item | Budget |
|---|---|
| Cold start to usable home | < 2.5 s |
| Frame time | 16 ms target, 33 ms worst case |
| RAM (app) | < 400 MB with map + 3D car |
| 3D car | ≤ 80k tris, ≤ 2 × 2K textures, 1 directional light + IBL |
| Map | vector tiles, max 60 fps, pause rendering when covered by full-screen app |

**Low-power mode** (auto-enabled if frame time > 25 ms for 5 s): no blur, no reflections, static car shadow, 30 fps map, reduced animations.

---

## 11. Project structure

```
ohm/
├─ app/src/main/java/.../
│  ├─ MainActivity.kt          # launcher entry (HOME)
│  ├─ ui/
│  │  ├─ home/HomeScreen.kt    # split layout, LHD/RHD mirroring
│  │  ├─ car/CarPanel.kt, CarScene.kt, GearSpeed.kt
│  │  ├─ map/MapPanel.kt, SearchBar.kt, ManeuverCard.kt, EtaPanel.kt
│  │  ├─ media/MusicWidget.kt, NowPlayingSheet.kt
│  │  ├─ dock/DockBar.kt, AppDrawer.kt
│  │  ├─ status/StatusBar.kt, WeatherPopup.kt
│  │  ├─ notifications/NotificationStack.kt
│  │  ├─ settings/SettingsSheet.kt
│  │  └─ theme/Theme.kt, Color.kt, Type.kt
│  ├─ car/CarDataSource.kt, VendorCarSource.kt, Esp32CanSource.kt
│  ├─ media/MediaListenerService.kt
│  └─ daynight/DayNightController.kt
├─ app/src/main/assets/models/megane3.glb
├─ esp32-can/                  # optional fallback firmware
└─ docs/
```

---

## 12. Roadmap

| Phase | Deliverable |
|---|---|
| **0 — Recon** | Read canbox sticker, set correct CAN profile, fix SWC volume + ILL wiring. Dump Junsun APKs, find car-data broadcasts. |
| **1 — Shell** | Launcher with split layout, dock, app drawer, status bar, light/dark theme, RHD mirroring. |
| **2 — Map** | MapLibre map, follow mode, search, styles, hand-off to Google Maps/Waze. |
| **3 — Media** | Music widget, Now Playing, volume banner. |
| **4 — Car** | 3D Mégane model, orbit, colour picker; live gear/speed/doors/lights from CAN. |
| **5 — Day/night** | Headlight-driven theme + system dark mode. |
| **6 — Polish** | Notifications, weather popup, settings, low-power mode, navigation (Mapbox Nav). |
| **7 — Optional** | ESP32 CAN fallback, reverse-camera auto-switch, extra languages. |

---

## 13. Open questions
- Exact Junsun V2 Pro platform/firmware version (Settings → About) and whether its car service is reachable without root.
- Canbox brand/protocol (from the sticker) — determines which data is available at all.
- Screen resolution of your unit (720p vs 2K variant).
- Source of a Mégane 3 3D model (licensed/free GLB, or build a simplified one).
