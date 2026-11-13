# Ohm — Tesla-style launcher for Android head units (demo build)

Full-screen launcher for landscape Android head units (built for a Junsun V2 Pro in a Renault Mégane 3).
Spec: [SPEC.md](SPEC.md).

## Install
| Device | File |
|---|---|
| Tablet / head unit | `dist/Ohm-0.6.0.apk` |
| Phone (optional) | `dist/OhmCompanion-0.3.0.apk` — shares the phone's GPS with Ohm |

Copy the APK to the device and open it (allow "install unknown apps"), or `adb install -r <file>`.
On first launch Ohm runs a **quick setup** (units, vehicle, theme, navigation app, permissions); run it again from **Controls › System**.

## New in 0.2 (from the product-ideas document)
| Idea | Where |
|---|---|
| Onboarding & regional units (distance, speed, temperature, fuel consumption, pressure, currency) | First launch · **Controls › Units** |
| Vehicle profiles (CAN source, model, paint, driving position, consumption, camera) — global prefs stay shared | **Controls › Vehicle** |
| Capabilities vs settings — options for hardware that isn't detected are hidden | **Controls › Diagnostics › Show unsupported options** |
| Hardware diagnostics: capabilities, GPS/satellites, CAN/MCU, media sessions, frame rate, log, share report | **Controls › Diagnostics** |
| Navigation provider abstraction: Built-in, Google Maps, Waze, any geo: app, with fallback | **Controls › Navigation** |
| Reverse-camera config: on/off, camera app, trigger (CAN / MCU / Android broadcast), return to Ohm | **Controls › Vehicle › Reverse camera** |
| Location providers: head-unit GPS, Ohm Companion, external NMEA GPS — auto or preferred, with freshness/accuracy checks | **Controls › Location** |
| Ohm Companion phone app: GPS, speed, heading, battery/network, optional notification relay | `companion/` module |
| Media modes: this device (standalone) vs phone over Bluetooth | **Controls › Media** |
| Driving statistics: automatic trips, history, estimated fuel & cost | **Controls › Trips** · "Map focus" layout |
| Contextual dock (end route / voice, call controls, hide camera) in a fixed slot | Dock, toggle in **Controls › Display** |
| Customisation: accent colour, animations, layout (Car + map / Map focus), widget visibility | **Controls › Display** |

Not done yet: Bluetooth/USB transports for the companion (Wi-Fi/hotspot UDP only), real fuel/tyre data (needs CAN decoding).

## New in 0.6 — car signals from the head unit
`tools/headunit/` connects a laptop to the Junsun over ADB, works out how its firmware reports headlights, reverse,
doors, boot and lock (guided capture + automatic analysis), and loads the mapping into Ohm. See
[tools/headunit/README.md](tools/headunit/README.md). Open doors/boot/bonnet now light up in the accent colour on the
3D car and the view turns towards them.

## New in 0.5
| Feature | How |
|---|---|
| **Send a destination from your phone** | On the phone: *Share › Send to Ohm* from Google Maps, WhatsApp, a browser, or open any `geo:` link. Ohm previews the route (or hands it to your navigation app). |
| **Find my car** | Companion saves where you parked when the phone leaves the car's Bluetooth; *Walk there* opens walking directions. |
| **Speed-sensitive volume** | Controls › Media › Audio: Off/Low/Medium/High. Above 40 km/h the music rises a little; manual changes become the new base. |
| **Equalizer** | Controls › Media › Audio: 5 bands for the built-in Spotify receiver. |
| **Driving lockout** | Controls › Display: video, games, browsers and typing are blocked while moving (default above 8 km/h, not in P). Allow apps in Controls › Apps. |
| **In-app updates** | PC: `python tools/serve-updates.py` → Ohm: Controls › System › Updates › paste the address › Check now › Install. Android asks once to allow "Install unknown apps" for Ohm. Only APKs signed with your key install. |
| **OBD-II (ELM327)** | Pair the adapter in Android Bluetooth, then Controls › Vehicle › OBD-II. Fuel level, real consumption, coolant, RPM, battery, trouble codes (read/clear). Trips use measured fuel when available. |

Also: built-in routing works again (the public OSRM server dropped speed limits; Ohm now looks them up in OpenStreetMap),
and the speed falls to 0 when GPS goes quiet instead of freezing.

## Spotify (new in 0.3)
In-dash Spotify, like the in-car app: **Home** (Liked Songs + recent shortcuts, Jump back in, top artists, your playlists & podcasts,
on repeat, recently played), **Search** (songs, artists, albums, playlists, podcasts, episodes), **Your Library** (playlists, albums,
artists, podcasts, your episodes), album / playlist / artist / podcast pages with Play, Shuffle, Save/Follow, **Queue** (view + add),
synced **Lyrics** (LRCLIB), **Spotify Connect** device picker with volume, and a full player bar (like, shuffle, repeat, seek).
The music widget shows Connect playback from any device (e.g. your phone) and the dock's Spotify icon opens this screen.

**One-time setup** (Spotify requires every app to have its own developer registration):
1. On a phone or PC: <https://developer.spotify.com/dashboard> → *Create app* → tick **Web API**.
2. Redirect URI: `http://127.0.0.1:43117/callback` (exactly).
3. *User Management*: add your Spotify account's e-mail.
4. Copy the **Client ID** → on the head unit: open Spotify from the music widget (or **Controls › Media › Spotify**), paste it, **Sign in with Spotify**.

**Sound through the car:** install the Spotify app on the head unit and sign in once. Ohm plays on the unit by default
(it starts Spotify in the background if needed and learns its Connect name), unless you choose another device in
Spotify › Devices. "Always play on this unit" switches back.

**Built-in receiver (librespot, new in 0.4, off by default):** Controls › Media › *Built-in Spotify receiver*. Ohm runs
[librespot](https://github.com/librespot-org/librespot) (MIT) as its own Spotify Connect speaker, "Ohm (car)", and plays the audio
through the head unit — no Spotify app needed on the unit. Sign in once (button opens Spotify's login in the browser) or pick
"Ohm (car)" in the Spotify app on a phone on the same Wi-Fi/hotspot. Ohm then plays there by default.
⚠️ librespot is unofficial: it isn't approved by Spotify, needs Premium, and can break when Spotify changes its protocol.

Limits set by Spotify for Development-Mode apps (Feb 2026): the app owner needs **Premium**, up to 5 users, search returns ≤ 10 per type,
only playlists you own/collaborate on list their songs (others still play), no artist top tracks, no Spotify-made mixes/editorial
playlists, no Spotify lyrics (Ohm uses LRCLIB instead). Playback control needs Premium.

## Ohm Companion
1. Install `OhmCompanion-0.3.0.apk` on the phone.
2. Turn on the phone's hotspot and let the head unit join it (or put both on the same Wi-Fi).
3. On the head unit open **Controls › Location** and note the 4-digit pairing code.
4. In the companion app enter the code and tap **Start sharing**. The status turns green ("Connected to …") and the
   head unit shows the phone in **Controls › Location**. Set **Use** to *Phone* to prefer it, or leave *Automatic*.
**Automatic start (0.2):** in the companion turn on *Start automatically* and choose the car's Bluetooth. Sharing then starts
whenever the phone connects to the car and stops when it disconnects — no need to open the app. Work through the in-app
checklist once: location *Allow all the time*, battery optimisation off. Ohm keeps the pairing code, so nothing is re-linked.
If Android blocks a background start, a "Car connected — tap to share" notification appears instead.

Notification relay on Android 13+: Android blocks notification access for apps installed from a file. In the companion,
switch on *Relay notifications* → *Open app info* → ⋮ → **Allow restricted settings**, then grant notification access.
(Installing with `adb install` avoids the restriction.)
Protocol: JSON over UDP port 47017 on the local network (see `companion/src/main/java/com/ohm/companion/Link.kt`).

## Things to try (demo mode)
| What | How |
|---|---|
| Demo drive | Tap **Navigate…** › pick a *Demo drive* (or search any address) › **Start navigation**. The car drives itself along the route: speed, D gear, brake lights, maneuver card, lane arrows, speed limit, speed-camera alert, voice guidance, ETA. |
| Gear / reverse camera | Tap **P R N D** at the top of the car panel. **R** shows the rear-camera view with guidelines. |
| Doors, trunk, hood | Chips under the car (only in **P**). Drag the car to rotate, pinch to zoom, double-tap to reset. |
| Day/night by headlights | **Controls › Diagnostics › CAN / MCU › Headlights**. The theme switches after 10 s (hysteresis, like in a tunnel). |
| Calls / messages | **Controls › System › Demo events**. |
| Music | The demo player works without any app; real apps appear once notification access is granted. |
| Weather / quick settings | Tap the temperature / the clock in the top bar. |
| Map | Layers button cycles Automatic → Satellite → Light → Dark. Compass toggles heading-up / north-up. |
| Layout | Drag the handle on the map's edge to resize the car panel. **Controls › Display** for right-hand drive, theme, low-power mode. |
| Language | **Controls › System › Language** (English / Português). |

## Using real car data (later)
**Controls › Car › Data source › Head unit (CAN)**, then enter the Junsun/canbox broadcast action names.
Everything received is shown in **Controls › Diagnostics › CAN / MCU** so the right names can be discovered (see SPEC.md §8).

## Signing
Release APKs are signed with the project's own key: `keystore/ohm-release.jks` + `keystore.properties` (both git-ignored).
**Back these two files up** (e.g. to a password manager or USB stick). Android only accepts updates signed with the same key —
lose it and every device has to uninstall and reinstall. Without `keystore.properties` the build falls back to the debug key.

Builds before 0.3.0 (final) were debug-signed, so the first install of a release-signed build needs the old app uninstalled.

## Build
librespot binaries (only needed when updating librespot): `bash native/build-librespot.sh` → `app/src/main/jniLibs/*/liblibrespot.so`.
Needs Rust (rustup, gnu host), `cargo-ndk`, the NDK. Windows quirks handled on this PC: a space-free junction
`C:\ohm-build\ndk` → the NDK (paths with spaces break the C wrappers), and rustup's self-contained `dlltool.exe` replaced
by a small shim (`C:\ohm-build\dlltool-shim`) that forwards to the NDK's `llvm-dlltool` (original kept as `dlltool.gnu-original.exe`).

Open the folder in Android Studio and press Run, or:
```
gradlew assembleRelease        # → app/… and companion/build/outputs/apk/release/
gradlew :app:testDebugUnitTest :companion:testDebugUnitTest   # 22 unit tests
```

## Notes / limits of this demo
- Online services (no keys, fair-use public servers): CARTO map tiles, Photon search, OSRM routing, Overpass (categories, speed cameras), Open-Meteo weather. Offline, demo routes fall back to a straight line.
- Third-party apps open full-screen (Android doesn't allow embedding them in a side card); the Home button returns to Ohm.
- The 3D car is a built-in low-poly renderer; a real glTF model via Filament is a later step.
- Not affiliated with Tesla, Inc. — own name, icon and open font (Inter, OFL).
