# Head-unit toolkit — find the car's signals, hand them to Ohm

For the day the car is back: connect a laptop to the Junsun over ADB, let the toolkit work out how the
firmware reports headlights, reverse, doors, boot and lock, and load the result into Ohm. No root, nothing
on the head unit is changed except Ohm's own settings.

```
laptop ──ADB (Wi-Fi or USB)──► Junsun ──► survey · guided capture ──► analyze ──► map.json ──► Ohm
```

## 0. Before you start
- **Canbox profile first.** The canbox (the small box behind the unit) must be set to the right car, or there is
  nothing to find: Junsun settings → Car / CAN → protocol = your canbox brand + *Renault Mégane 3* (see the
  earlier notes on the sticker).
- Ohm 0.6 or newer installed on the unit.
- On the laptop: Android platform-tools (already installed with Android Studio) and Python 3.

## 1. Enable ADB on the Junsun
1. **Developer options:** Settings → About (or System → About) → tap **Build number** 7 times.
   Some Junsun/FYT firmwares hide it; then look in *Car settings → Factory settings* (common codes: `3368`, `8888`,
   `1617`, `0000`) for *Developer options* / *USB debugging* / *ADB*.
2. Pick one way in:
   - **Android 11 or newer — Wireless debugging** (easiest): Developer options → *Wireless debugging* → on →
     *Pair device with pairing code*. On the laptop (same Wi-Fi or the phone's hotspot):
     ```
     python tools/headunit/ohm_hu.py connect <ip:port shown on the main screen> --pair <ip:port in the pairing dialog> --code <6-digit code>
     ```
   - **Android 10 — ADB over network:** if Developer options has *ADB over network* / *Network debugging*, turn it on
     and run `python tools/headunit/ohm_hu.py connect <unit-ip>` (port 5555).
   - **USB:** only works if the unit has a USB port that can switch to *device/OTG* mode (sometimes a setting in
     Factory settings). Then plug in, accept the *Allow USB debugging?* prompt, and once run `adb tcpip 5555` to
     switch to Wi-Fi afterwards.
3. Check: `adb devices` lists the unit as `device`.

Find the unit's IP in Settings → Wi-Fi → (network) → details.

## 2. Find the signals (≈10 minutes, car parked)
Engine off is fine; ignition on (so the CAN bus is awake), handbrake on.

```
python tools/headunit/ohm_hu.py survey      # firmware, vendor apps and the broadcasts they declare, settings…
python tools/headunit/ohm_hu.py listen      # Ohm starts logging every candidate broadcast with its values
python tools/headunit/ohm_hu.py guided      # follow the prompts: lights on/off, reverse in/out, doors, boot, lock…
python tools/headunit/ohm_hu.py analyze     # → map.json + report.md in the session folder
python tools/headunit/ohm_hu.py push-map tools/headunit/captures/<session>/map.json
```

- `guided` asks for one action at a time; do it, wait ~2 s, press **Enter** (or **s** to skip one).
  `--only headlights reverse` repeats just those steps.
- For the **reverse** step: ignition on, clutch in, select reverse, stay still.
- `push-map` loads the rules into Ohm and switches its car source to *Head unit (CAN)*. Check the result in Ohm:
  headlight indicator, reverse camera view, doors/boot on the 3D car, and **Controls › Diagnostics › CAN / MCU**.

Each step is saved as plain text in `tools/headunit/captures/<timestamp>-…/`: logcat, the broadcasts that happened,
setting changes, steering-wheel/input events, and what Ohm received (with values).

## 3. With Claude
Open Claude Code in this project on the laptop (with the unit connected) and ask, for example:
- *"Analyse the latest guided capture in tools/headunit/captures and improve map.json."*
- *"The doors weren't found — look at the logs and the vendor apps and figure out how this firmware reports them."*
- *"Connect to the head unit and work out how the steering-wheel buttons are handled."*

Claude can run `adb` and this toolkit directly, read the vendor apps' declared broadcasts (`dumpsys package`),
pull a vendor APK to look inside it, watch logcat live while you press things, and write the mapping — or, if the
firmware hides everything inside its own service, a dedicated Ohm car source for the Junsun.

## What it can and can't do
- Works with whatever the firmware exposes to apps: broadcasts, Android settings, logs. That's how Junsun/FYT-style
  units normally publish canbox data.
- Doesn't read raw CAN frames or the MCU's serial port (that needs root) and never writes to the car.
- Read-only on the head unit; the only change is Ohm's mapping (undo: Ohm › Controls › Vehicle › Data source).
- The captures contain the unit's logs. They stay on your laptop (`captures/` is not committed).

## Mapping format
`map.json` (also editable by hand):
```json
{"rules": [
  {"field": "headlights", "action": "com.vendor.ILL", "extra": "state", "on": "1"},
  {"field": "reverse",    "setting": "system/back_car_state", "on": "1"},
  {"field": "door_fl",    "action": "com.vendor.DOOR_FL_OPEN",  "value": "1"},
  {"field": "door_fl",    "action": "com.vendor.DOOR_FL_CLOSE", "value": "0"},
  {"field": "gear",       "action": "com.vendor.GEAR", "extra": "gear", "values": {"P": "0", "R": "1", "N": "2", "D": "3"}},
  {"field": "speed",      "action": "com.vendor.SPEED", "extra": "speed", "scale": 0.01}
]}
```
Fields: `headlights reverse gear speed outside_temp locked door_fl door_fr door_rl door_rr trunk hood`.
