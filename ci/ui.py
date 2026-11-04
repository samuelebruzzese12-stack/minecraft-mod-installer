#!/usr/bin/env python3
"""Pilota l'app sull'emulatore leggendo l'interfaccia con uiautomator (solo libreria standard).

Comandi:
  dump PERCORSO          salva l'albero dell'interfaccia in PERCORSO
  texts PERCORSO         elenca i testi visibili (uno per riga)
  has-in PERCORSO TESTO  esce 0 se TESTO compare nel dump
  has TESTO              come sopra ma su una schermata nuova
  tap TESTO              tocca il primo elemento che contiene TESTO (scorre fino a 3 volte per trovarlo)
  wait TESTO [SECONDI]   aspetta che TESTO compaia (default 20 s)
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

REMOTE = "/sdcard/mmi_ui.xml"


def adb(*args, timeout=60):
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)


def dump(path, tries=4):
    for _ in range(tries):
        adb("shell", "uiautomator", "dump", REMOTE)
        r = adb("pull", REMOTE, path)
        if r.returncode == 0:
            try:
                ET.parse(path)
                return path
            except ET.ParseError:
                pass
        time.sleep(1.5)
    return None


def nodes(path):
    out = []
    for n in ET.parse(path).getroot().iter("node"):
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({
            "text": n.get("text") or "",
            "desc": n.get("content-desc") or "",
            "clickable": n.get("clickable") == "true",
            "box": (x1, y1, x2, y2),
        })
    return out


def find(ns, needle):
    nl = needle.lower()
    best = None
    for i, n in enumerate(ns):
        x1, y1, x2, y2 = n["box"]
        if x2 <= x1 or y2 <= y1:
            continue
        for label in (n["text"], n["desc"]):
            if not label:
                continue
            l = label.lower()
            if nl in l:
                rank = (0 if l.strip() == nl else 1, 0 if n["clickable"] else 1, i)
                if best is None or rank < best[0]:
                    best = (rank, n)
    return best[1] if best else None


def screen_size():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").stdout)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def swipe_up():
    w, h = screen_size()
    adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.30)), "350")
    time.sleep(0.8)


def tap(needle, scrolls=3):
    for i in range(scrolls + 1):
        p = dump("/tmp/mmi_tap.xml")
        if p:
            n = find(nodes(p), needle)
            if n:
                x1, y1, x2, y2 = n["box"]
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
        if i < scrolls:
            swipe_up()
    return False


def wait(needle, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        p = dump("/tmp/mmi_wait.xml", tries=1)
        if p and find(nodes(p), needle):
            return True
        time.sleep(1.0)
    return False


def main(argv):
    cmd = argv[1]
    if cmd == "dump":
        return 0 if dump(argv[2]) else 1
    if cmd == "texts":
        seen = []
        for n in nodes(argv[2]):
            for label in (n["text"], n["desc"]):
                label = label.strip()
                if label and label not in seen:
                    seen.append(label)
        print("\n".join(seen))
        return 0
    if cmd == "has-in":
        return 0 if find(nodes(argv[2]), argv[3]) else 1
    if cmd == "has":
        p = dump("/tmp/mmi_has.xml")
        return 0 if p and find(nodes(p), argv[2]) else 1
    if cmd == "tap":
        return 0 if tap(argv[2]) else 1
    if cmd == "wait":
        return 0 if wait(argv[2], int(argv[3]) if len(argv) > 3 else 20) else 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
