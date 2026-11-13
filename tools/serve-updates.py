"""
Serves Ohm updates to the head unit over your local network.

  python tools/serve-updates.py            (from the project folder)

Writes dist/update.json for the newest dist/Ohm-*.apk (version read from app/build.gradle.kts)
and serves the dist/ folder on port 8765. In Ohm: Controls › System › Updates › paste the
address printed below, then "Check now". Windows may ask to allow Python through the firewall
(allow "Private networks").
"""
import http.server
import json
import os
import re
import socket
import socketserver
import sys

PORT = 8765
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")


def version():
    text = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    code = int(re.search(r"versionCode\s*=\s*(\d+)", text).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1)
    return code, name


def newest_apk():
    apks = [f for f in os.listdir(DIST) if re.match(r"Ohm-.*\.apk$", f)]
    if not apks:
        sys.exit("No dist/Ohm-*.apk found — build one first.")
    return max(apks, key=lambda f: os.path.getmtime(os.path.join(DIST, f)))


def lan_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # no packets are sent; just picks the outgoing interface
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
        ips.add(info[4][0])
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main():
    code, name = version()
    apk = newest_apk()
    notes = " ".join(sys.argv[1:])
    with open(os.path.join(DIST, "update.json"), "w", encoding="utf-8") as f:
        json.dump({"versionCode": code, "versionName": name, "apk": apk, "notes": notes}, f, indent=2)
    print(f"Serving {apk} (version {name}, code {code})")
    for ip in lan_ips():
        print(f"  Update address for Ohm:  http://{ip}:{PORT}/update.json")
    print("Ctrl+C to stop.")
    os.chdir(DIST)
    handler = http.server.SimpleHTTPRequestHandler
    with socketserver.ThreadingTCPServer(("0.0.0.0", PORT), handler) as httpd:
        httpd.serve_forever()


if __name__ == "__main__":
    main()
