#!/usr/bin/env python3
"""
ohm_hu — explore an Android head unit over ADB and work out how its firmware reports car data
(headlights, reverse, doors…), then hand the result to Ohm.

  python tools/headunit/ohm_hu.py connect 192.168.43.120     # ADB over Wi-Fi (or plug USB)
  python tools/headunit/ohm_hu.py survey                     # static facts: firmware, vendor apps, settings…
  python tools/headunit/ohm_hu.py listen                     # tell Ohm to log every candidate broadcast
  python tools/headunit/ohm_hu.py guided                     # walk through lights/reverse/doors…, recording each
  python tools/headunit/ohm_hu.py analyze <session folder>   # find the signals → map.json + report.md
  python tools/headunit/ohm_hu.py push-map <map.json>        # load the mapping into Ohm

Everything is written to tools/headunit/captures/<timestamp>-…/ as plain text so a person — or
Claude — can read and reason about it. Nothing on the head unit is modified except Ohm's own
settings (listen / push-map). No root needed.
"""
import argparse
import base64
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import time

# Windows consoles default to a legacy code page; print UTF-8 (arrows, accents) safely.
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
CAPTURES = os.path.join(HERE, "captures")
OHM = "com.ohm.launcher"

# Package-name fragments used by head-unit firmwares (FYT/SYU, Microntek, TopWay, Junsun, canbox apps…)
VENDOR_HINTS = ["canbus", "can.", ".can", "mcu", "syu", "fyt", "microntek", "topway", "junsun", "carsetting", "carsettings",
                "car.service", "carservice", "hct", "xygala", "canbox", "autochips", "ts10", "ts18", "uis", "sprd", "backcar",
                "reverse", "avm", "radio", "btphone", "bluetooth.phone", "vehicle", "carinfo", "steer", "swc", "zlink"]
# Words that make a broadcast action / setting / log line interesting
SIGNAL_WORDS = ["can", "mcu", "ill", "light", "lamp", "night", "head", "reverse", "back", "gear", "park", "door", "trunk",
                "boot", "hood", "lock", "speed", "acc", "key", "swc", "steer", "temp", "brake", "radar", "camera", "avm", "car"]


# ------------------------------------------------------------------ adb plumbing

def find_adb():
    for c in [os.environ.get("ADB"), shutil.which("adb"),
              os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"),
              os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
              os.path.expanduser("~/Android/Sdk/platform-tools/adb")]:
        if c and os.path.exists(c):
            return c
    sys.exit("adb not found — install Android platform-tools or set ADB=path\\to\\adb")


ADB = find_adb()
SERIAL = os.environ.get("OHM_DEVICE")


def adb(*args, timeout=60, check=False):
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    out = r.stdout.decode("utf-8", "replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} failed: {r.stderr.decode('utf-8', 'replace').strip()}")
    return out


def sh(command, timeout=60):
    return adb("shell", command, timeout=timeout)


def ensure_device():
    lines = [l for l in adb("devices").splitlines()[1:] if l.strip()]
    devs = [l.split()[0] for l in lines if l.split()[-1] == "device"]
    if not devs:
        sys.exit("No device. Connect the head unit (USB, or `connect <ip>`) and accept the 'Allow USB debugging' prompt.")
    if len(devs) > 1 and not SERIAL:
        sys.exit(f"Several devices {devs} — set OHM_DEVICE to one of them.")
    return SERIAL or devs[0]


def new_dir(kind):
    d = os.path.join(CAPTURES, dt.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + kind)
    os.makedirs(d, exist_ok=True)
    return d


def save(d, name, text):
    with open(os.path.join(d, name), "w", encoding="utf-8") as f:
        f.write(text)


def interesting(s):
    s = s.lower()
    return any(w in s for w in SIGNAL_WORDS)


# ------------------------------------------------------------------ parsing helpers

def parse_history(text):
    """dumpsys activity broadcasts history → [{id, action, extras, sender, time}] (format varies by Android version)."""
    out = []
    blocks = re.split(r"\n(?=\s*(?:Historical Broadcast|#\d+:)\s)", text)
    for b in blocks:
        # Android ≤13: "BroadcastRecord{8e4f459 u0 com.x.ACTION}"; Android 14+: "BroadcastRecord{8e4f459 com.x.ACTION/u0/0x10}"
        m = re.search(r"BroadcastRecord\{(\w+)\s+(?:u-?\d+\s+)?([A-Za-z0-9_.$]+)", b)
        act = re.search(r"act=([^\s}]+)", b)
        if not (m or act):
            continue
        extras = re.search(r"extras: (Bundle\[.*?\])\s*$", b, re.M)
        sender = re.search(r"caller=(\S+)", b)
        when = re.search(r"enqueueClockTime=([\d\- :.]+)", b)
        out.append({
            # record hash + enqueue time is stable between dumps (relative times in the block are not)
            "id": (m.group(1) if m else "") + "@" + (when.group(1).strip() if when else ""),
            "action": act.group(1) if act else (m.group(2) if m else "?"),
            "extras": extras.group(1) if extras else "",
            "sender": sender.group(1) if sender else "",
            "time": when.group(1).strip() if when else "",
        })
    return out


def parse_settings(text):
    d = {}
    for line in text.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
    return d


def settings_snapshot():
    return {t: parse_settings(sh(f"settings list {t}")) for t in ("system", "global", "secure")}


def settings_diff(before, after):
    diff = []
    for t in before:
        for k in sorted(set(before[t]) | set(after[t])):
            a, b = before[t].get(k), after[t].get(k)
            if a != b:
                diff.append({"setting": f"{t}/{k}", "before": a, "after": b})
    return diff


OHMVENDOR = re.compile(r"OhmVendor\s*:\s*(\S+) \| ?(.*)$")


def parse_ohm_vendor(logcat_text):
    """Ohm's own log of every vendor broadcast it received, with extras values."""
    out = []
    for line in logcat_text.splitlines():
        m = OHMVENDOR.search(line)
        if not m:
            continue
        extras = {}
        for kv in m.group(2).split("; "):
            if "=" in kv:
                k, v = kv.split("=", 1)
                extras[k.strip()] = v.strip()
        out.append({"action": m.group(1), "extras": extras})
    return out


def device_time():
    return sh("date +'%m-%d %H:%M:%S.000'").strip()


# ------------------------------------------------------------------ commands

def cmd_connect(a):
    target = a.address if ":" in a.address else a.address + ":5555"
    if a.pair:
        print(adb("pair", a.pair, a.code or "", timeout=30))
    print(adb("connect", target, timeout=20).strip())
    print(adb("devices"))


def cmd_survey(a):
    ensure_device()
    d = new_dir("survey")
    print(f"Surveying → {d}")
    props = sh("getprop")
    save(d, "getprop.txt", props)
    p = dict(re.findall(r"\[([^\]]+)\]: \[([^\]]*)\]", props))
    pk = sh("pm list packages -f -U")
    save(d, "packages.txt", pk)
    pkgs = sorted(set(re.findall(r"=([\w.]+)(?:\s|$)", pk)))
    vendor = [x for x in pkgs if any(h in x.lower() for h in VENDOR_HINTS) and not x.startswith(("com.google.", "com.android.vending"))]
    actions = {}
    os.makedirs(os.path.join(d, "packages"), exist_ok=True)
    for v in vendor:
        dump = sh(f"dumpsys package {v}", timeout=60)
        save(os.path.join(d, "packages"), v + ".txt", dump)
        for act in set(re.findall(r'Action: "([^"]+)"', dump)):
            if not act.startswith("android.intent.action.MAIN"):
                actions.setdefault(act, set()).add(v)
    history = sh("dumpsys activity broadcasts history", timeout=90)
    save(d, "broadcast_history.txt", history)
    hist = parse_history(history)
    for h in hist:
        if interesting(h["action"]) and not h["action"].startswith("android."):
            actions.setdefault(h["action"], set()).add("(seen in history)")
    for t in ("system", "global", "secure"):
        save(d, f"settings_{t}.txt", sh(f"settings list {t}"))
    save(d, "services.txt", sh("service list"))
    save(d, "dev.txt", sh("ls -l /dev 2>&1"))
    save(d, "input_devices.txt", sh("getevent -lp 2>&1", timeout=20))
    save(d, "media_sessions.txt", sh("dumpsys media_session"))
    save(d, "logcat_tail.txt", adb("logcat", "-d", "-t", "3000", "-v", "threadtime", timeout=60))

    cand = sorted(a for a in actions if interesting(a) and not a.startswith(("android.", "com.google.")))
    cand_settings = []
    for t in ("system", "global", "secure"):
        for k, v in parse_settings(open(os.path.join(d, f"settings_{t}.txt"), encoding="utf-8").read()).items():
            if interesting(k):
                cand_settings.append(f"{t}/{k} = {v}")
    ttys = [l.split()[-1] for l in open(os.path.join(d, "dev.txt"), encoding="utf-8").read().splitlines() if re.search(r"tty(S|MT|HS|GS|USB)\d|can\d", l)]
    summary = {
        "model": p.get("ro.product.model"), "manufacturer": p.get("ro.product.manufacturer"),
        "android": p.get("ro.build.version.release"), "sdk": p.get("ro.build.version.sdk"),
        "platform": p.get("ro.board.platform") or p.get("ro.hardware"), "build": p.get("ro.build.display.id"),
        "vendor_packages": vendor, "candidate_actions": cand, "candidate_settings": cand_settings, "serial_devices": ttys,
        "ohm_installed": f"package:{OHM}" in sh(f"pm list packages {OHM}"),
    }
    save(d, "summary.json", json.dumps(summary, indent=2))
    md = [f"# Head unit survey {os.path.basename(d)}", "",
          f"- **Model:** {summary['manufacturer']} {summary['model']} · Android {summary['android']} (SDK {summary['sdk']}) · platform {summary['platform']}",
          f"- **Build:** {summary['build']}", f"- **Ohm installed:** {summary['ohm_installed']}", "",
          f"## Vendor packages ({len(vendor)})", *[f"- {v}" for v in vendor], "",
          f"## Candidate broadcast actions ({len(cand)})", *[f"- `{c}` — {', '.join(sorted(actions[c]))}" for c in cand], "",
          f"## Car-related settings ({len(cand_settings)})", *[f"- `{s}`" for s in cand_settings], "",
          f"## Serial/CAN devices", *[f"- /dev/{t}" for t in ttys or ['(none visible)']], "",
          "Next: `listen` (Ohm logs these broadcasts with values), then `guided`."]
    save(d, "summary.md", "\n".join(md))
    save(os.path.join(CAPTURES, ""), "latest_survey.txt", d)
    print("\n".join(md[:4]))
    print(f"{len(vendor)} vendor packages, {len(cand)} candidate actions, {len(cand_settings)} car-like settings.")
    print(f"Summary: {os.path.join(d, 'summary.md')}")


def push_to_ohm(map_json=None, actions=None, use=True):
    args = ["shell", "am", "broadcast", "-n", f"{OHM}/.car.ConfigReceiver", "-a", "com.ohm.launcher.SET_VENDOR_MAP", "--ez", "use", "true" if use else "false"]
    if map_json is not None:
        args += ["--es", "map64", base64.b64encode(json.dumps(map_json).encode()).decode()]
    if actions is not None:
        args += ["--es", "actions", ",".join(actions)]
    out = adb(*args, timeout=30)
    m = re.search(r'data="([^"]*)"', out)
    return m.group(1) if m else out.strip()


def cmd_listen(a):
    ensure_device()
    survey = a.survey or open(os.path.join(CAPTURES, "latest_survey.txt"), encoding="utf-8").read().strip()
    s = json.load(open(os.path.join(survey, "summary.json"), encoding="utf-8"))
    acts = s["candidate_actions"] + (a.extra or [])
    if not acts:
        sys.exit("No candidate actions in the survey — add some with --extra ACTION ...")
    print(f"Asking Ohm to listen for {len(acts)} broadcast actions…")
    print("Ohm:", push_to_ohm(actions=acts, use=True))
    print("Ohm now logs each one with its values (tag OhmVendor). Next: guided.")


GUIDED = [
    ("baseline", "Do NOTHING for 10 seconds (records background noise)", None),
    ("headlights_on", "Turn the HEADLIGHTS ON (dipped beam / position lights)", "headlights"),
    ("headlights_off", "Turn the headlights OFF", "headlights"),
    ("reverse_in", "Select REVERSE (ignition on; clutch in / handbrake on)", "reverse"),
    ("reverse_out", "Leave reverse (neutral)", "reverse"),
    ("door_fl_open", "OPEN the DRIVER's door", "door_fl"),
    ("door_fl_close", "CLOSE the driver's door", "door_fl"),
    ("door_fr_open", "OPEN the FRONT PASSENGER door", "door_fr"),
    ("door_fr_close", "CLOSE the front passenger door", "door_fr"),
    ("trunk_open", "OPEN the BOOT / tailgate", "trunk"),
    ("trunk_close", "CLOSE the boot", "trunk"),
    ("lock", "LOCK the car (button on the dash or key)", "locked"),
    ("unlock", "UNLOCK the car", "locked"),
    ("swc_vol_up", "Press STEERING-WHEEL Volume + a few times", None),
    ("swc_next", "Press STEERING-WHEEL Next / scroll", None),
]


def capture_step(d, name, prompt, wait_s=None):
    print(f"\n▶ {prompt}")
    hist_before = {h["id"] for h in parse_history(sh("dumpsys activity broadcasts history", timeout=90))}
    set_before = settings_snapshot()
    t0 = device_time()
    ge = subprocess.Popen([ADB] + (["-s", SERIAL] if SERIAL else []) + ["shell", "getevent", "-lt"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if wait_s:
        print(f"  recording {wait_s} s…"); time.sleep(wait_s)
    else:
        r = input("  Do it now, wait ~2 s, then press Enter  (s = skip): ").strip().lower()
        if r == "s":
            ge.kill(); print("  skipped"); return None
    time.sleep(1.0)
    ge.kill()
    events = ge.communicate()[0].decode("utf-8", "replace")
    log = adb("logcat", "-d", "-v", "threadtime", "-T", t0, timeout=60)
    hist = [h for h in parse_history(sh("dumpsys activity broadcasts history", timeout=90)) if h["id"] not in hist_before]
    sdiff = settings_diff(set_before, settings_snapshot())
    sd = os.path.join(d, name)
    os.makedirs(sd, exist_ok=True)
    save(sd, "prompt.txt", prompt)
    save(sd, "logcat.txt", log)
    save(sd, "broadcasts.json", json.dumps(hist, indent=1))
    save(sd, "settings_diff.json", json.dumps(sdiff, indent=1))
    save(sd, "getevent.txt", events)
    ohm = parse_ohm_vendor(log)
    save(sd, "ohm_vendor.json", json.dumps(ohm, indent=1))
    print(f"  captured: {len(ohm)} vendor broadcasts seen by Ohm, {len(hist)} broadcasts in history, {len(sdiff)} setting changes, {len(events.splitlines())} input events")
    return sd


def cmd_guided(a):
    ensure_device()
    d = new_dir("guided")
    print(f"Guided capture → {d}\nFollow the prompts in the car. Keep the head unit on Ohm's screen.")
    steps = [s for s in GUIDED if not a.only or s[0] == "baseline" or s[0].split("_")[0] in a.only]
    for name, prompt, _ in steps:
        capture_step(d, name, prompt, wait_s=10 if name == "baseline" else None)
    save(os.path.join(CAPTURES, ""), "latest_guided.txt", d)
    print(f"\nDone. Next:  python {os.path.relpath(__file__)} analyze \"{d}\"")


def cmd_capture(a):
    ensure_device()
    d = a.dir or new_dir("capture")
    capture_step(d, re.sub(r"\W+", "_", a.label), a.prompt or f"Do: {a.label}")
    print(f"Saved in {d}")


def load_step(d, name):
    sd = os.path.join(d, name)
    if not os.path.isdir(sd):
        return None
    j = lambda f: json.load(open(os.path.join(sd, f), encoding="utf-8"))
    return {"ohm": j("ohm_vendor.json"), "hist": j("broadcasts.json"), "settings": j("settings_diff.json"),
            "log": open(os.path.join(sd, "logcat.txt"), encoding="utf-8").read()}


def cmd_analyze(a):
    d = a.dir or open(os.path.join(CAPTURES, "latest_guided.txt"), encoding="utf-8").read().strip()
    base = load_step(d, "baseline") or {"ohm": [], "hist": [], "settings": [], "log": ""}
    noise = {(m["action"], k, v) for m in base["ohm"] for k, v in m["extras"].items()}
    # (action, extra) pairs whose value changes on its own during the baseline (counters, clocks, heartbeats)
    seen = {}
    for m in base["ohm"]:
        for k, v in m["extras"].items():
            seen.setdefault((m["action"], k), set()).add(v)
    noisy_keys = {key for key, vals in seen.items() if len(vals) > 1}
    noise_actions = {m["action"] for m in base["ohm"]} | {h["action"] for h in base["hist"]}
    noise_settings = {s["setting"] for s in base["settings"]}
    pairs = {}
    for name, _, field in GUIDED:
        if field:
            pairs.setdefault(field, []).append(name)
    rules, report = [], [f"# Analysis of {os.path.basename(d)}", ""]
    for field, (on_name, off_name) in pairs.items():
        on, off = load_step(d, on_name), load_step(d, off_name)
        report.append(f"## {field}  ({on_name} / {off_name})")
        if not on or not off:
            report += ["- not captured", ""]
            continue
        cands = []
        # 1) same action+extra, different value between on and off (best)
        last = lambda step: {(m["action"], k): v for m in step["ohm"] for k, v in m["extras"].items()}
        lon, loff = last(on), last(off)
        for (act, k), v in lon.items():
            if (act, k) in loff and loff[(act, k)] != v and (act, k, v) not in noise and (act, k) not in noisy_keys:
                cands.append((3, {"field": field, "action": act, "extra": k, "on": v}, f"`{act}` extra `{k}`: on={v} off={loff[(act, k)]}"))
        # 2) setting that flips
        son = {s["setting"]: s["after"] for s in on["settings"] if s["setting"] not in noise_settings}
        soff = {s["setting"]: s["after"] for s in off["settings"]}
        for k, v in son.items():
            if k in soff and soff[k] != v:
                cands.append((3, {"field": field, "setting": k, "on": v}, f"setting `{k}`: on={v} off={soff[k]}"))
        # 3) an action only in 'on' paired with one only in 'off' (separate ON/OFF broadcasts)
        aon = {m["action"] for m in on["ohm"]} | {h["action"] for h in on["hist"]}
        aoff = {m["action"] for m in off["ohm"]} | {h["action"] for h in off["hist"]}
        only_on = [x for x in aon - aoff - noise_actions if not x.startswith("android.")]
        only_off = [x for x in aoff - aon - noise_actions if not x.startswith("android.")]
        if len(only_on) == 1 and len(only_off) == 1:
            cands.append((2, {"field": field, "action": only_on[0], "value": "1"}, f"separate actions: on=`{only_on[0]}` off=`{only_off[0]}`"))
            cands.append((2, {"field": field, "action": only_off[0], "value": "0"}, ""))
        for x in only_on:
            report.append(f"- action only while ON: `{x}`")
        # 4) log lines unique to the 'on' step mentioning signal words (for humans/Claude)
        lines_on = [l for l in on["log"].splitlines() if interesting(l) and "OhmVendor" not in l]
        lines_off = set(off["log"].splitlines())
        uniq = [l for l in lines_on if l not in lines_off][:15]
        if cands:
            best = max(c[0] for c in cands)
            chosen = [c for c in cands if c[0] == best]
            for score, rule, why in cands:
                if why:
                    report.append(f"- {'**chosen** ' if (score, rule, why) in chosen else ''}{why}")
            # take the first chosen rule (plus its OFF partner for separate-action pairs)
            first = chosen[0]
            rules.append(first[1])
            if "value" in first[1]:
                rules += [c[1] for c in chosen[1:2] if c[1].get("value") == "0"]
        else:
            report.append("- no clear signal found")
        if uniq:
            report.append("- log lines only while ON:")
            report += [f"    {l[:200]}" for l in uniq]
        report.append("")
    mapping = {"rules": rules, "source": os.path.basename(d)}
    save(d, "map.json", json.dumps(mapping, indent=2))
    report.insert(2, f"**{len(rules)} rule(s) found** → `map.json`. Load into Ohm:  `python {os.path.relpath(__file__)} push-map \"{os.path.join(d, 'map.json')}\"`\n")
    save(d, "report.md", "\n".join(report))
    print("\n".join(report))


def cmd_push_map(a):
    ensure_device()
    mapping = json.load(open(a.file, encoding="utf-8"))
    print("Ohm:", push_to_ohm(map_json=mapping, use=True))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = p.add_subparsers(dest="cmd", required=True)
    c = sp.add_parser("connect", help="ADB over Wi-Fi"); c.add_argument("address"); c.add_argument("--pair", help="ip:port shown by 'Wireless debugging › Pair with code'"); c.add_argument("--code")
    sp.add_parser("survey", help="collect static facts about the head unit")
    l = sp.add_parser("listen", help="make Ohm log candidate broadcasts with values"); l.add_argument("--survey"); l.add_argument("--extra", nargs="*")
    g = sp.add_parser("guided", help="guided capture of lights/reverse/doors…"); g.add_argument("--only", nargs="*", help="e.g. headlights reverse door")
    cp = sp.add_parser("capture", help="capture one custom action"); cp.add_argument("label"); cp.add_argument("--prompt"); cp.add_argument("--dir")
    an = sp.add_parser("analyze", help="find the signals in a guided session"); an.add_argument("dir", nargs="?")
    pm = sp.add_parser("push-map", help="load a mapping into Ohm"); pm.add_argument("file")
    a = p.parse_args()
    {"connect": cmd_connect, "survey": cmd_survey, "listen": cmd_listen, "guided": cmd_guided, "capture": cmd_capture,
     "analyze": cmd_analyze, "push-map": cmd_push_map}[a.cmd](a)


if __name__ == "__main__":
    main()
