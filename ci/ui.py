#!/usr/bin/env python3
"""Pilota l'app sull'emulatore leggendo l'interfaccia con uiautomator (solo libreria standard).

Comandi:
  dump PERCORSO          salva l'albero dell'interfaccia in PERCORSO
  texts PERCORSO         elenca i testi visibili (uno per riga)
  has-in PERCORSO TESTO  esce 0 se TESTO compare nel dump
  has TESTO              come sopra ma su una schermata nuova
  tap TESTO              tocca il primo elemento che contiene TESTO (scorre fino a 3 volte per trovarlo)
  tap-last TESTO         come tap, ma sceglie l'ULTIMO elemento che contiene TESTO (es. la voce del menu laterale)
  scan TESTO [VOLTE]     esce 0 se TESTO compare scorrendo la pagina verso il basso e poi verso l'alto (non tocca nulla)
  top                    torna in cima alla pagina
  actions PERCORSO       elenca le azioni toccabili (testo di ogni elemento cliccabile), una per riga
  wait TESTO [SECONDI]   aspetta che TESTO compaia (default 20 s)
  fields PERCORSO        elenca i campi di testo visibili (dall'alto in basso): indice e contenuto
  fill N TESTO           tocca il campo di testo numero N (da 0, dall'alto) e ci scrive TESTO (solo caratteri semplici)
  clear-field N          svuota il campo di testo numero N
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

REMOTE = "/data/local/tmp/mmi_ui.xml"


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


def box_of(node):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds") or "")
    return tuple(map(int, m.groups())) if m else None


def outside(box, screen):
    """True se l'elemento è tutto fuori dallo schermo (es. il menu laterale chiuso): all'utente non è visibile."""
    x1, y1, x2, y2 = box
    return x2 <= screen[0] or x1 >= screen[2] or y2 <= screen[1] or y1 >= screen[3]


def nodes(path):
    out = []
    screen = None
    for n in ET.parse(path).getroot().iter("node"):
        box = box_of(n)
        if box is None:
            continue
        if screen is None:
            screen = box  # il primo elemento è la finestra intera
        elif outside(box, screen):
            continue
        out.append({
            "text": n.get("text") or "",
            "desc": n.get("content-desc") or "",
            "clickable": n.get("clickable") == "true",
            "box": box,
        })
    return out


def find(ns, needle, last=False):
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
                # last=True: a parità di corrispondenza vince l'elemento che viene dopo nell'albero
                rank = (0 if l.strip() == nl else 1, 0 if n["clickable"] else 1, -i if last else i)
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


def tap(needle, scrolls=3, last=False):
    for i in range(scrolls + 1):
        p = dump("/tmp/mmi_tap.xml")
        if p:
            n = find(nodes(p), needle, last)
            if n:
                x1, y1, x2, y2 = n["box"]
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
        if i < scrolls:
            swipe_up()
    return False


def swipe_down():
    w, h = screen_size()
    adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.30)), str(w // 2), str(int(h * 0.80)), "300")
    time.sleep(0.6)


def scan(needle, scrolls=5):
    """True se needle compare in qualche punto della pagina: prima scorrendo verso il basso, poi di nuovo verso l'alto
    (la pagina può essere già scorsa oltre il punto cercato). Non tocca nulla."""
    for i in range(scrolls + 1):
        p = dump("/tmp/mmi_scan.xml")
        if p and find(nodes(p), needle):
            return True
        if i < scrolls:
            swipe_up()
    for _ in range(scrolls * 2):
        swipe_down()
        p = dump("/tmp/mmi_scan.xml")
        if p and find(nodes(p), needle):
            return True
    return False


def top():
    for _ in range(6):
        swipe_down()


def actions(path):
    """Per ogni elemento cliccabile, i testi (e le descrizioni) che contiene: sono le azioni che l'utente vede."""
    out = []

    def labels(node):
        found = []
        for lab in (node.get("text") or "", node.get("content-desc") or ""):
            if lab.strip():
                found.append(lab.strip())
        for child in node:
            found.extend(labels(child))
        return found

    screen = [None]

    def walk(node):
        if node.tag == "node":
            box = box_of(node)
            if box is not None:
                if screen[0] is None:
                    screen[0] = box
                elif outside(box, screen[0]):
                    return
            if node.get("clickable") == "true":
                text = " / ".join(dict.fromkeys(labels(node)))
                out.append(text if text else "(senza testo)")
                return
        for child in node:
            walk(child)

    walk(ET.parse(path).getroot())
    return out


def edit_fields(path):
    """I campi di testo visibili (android.widget.EditText), dall'alto in basso e da sinistra a destra."""
    out = []
    screen = None
    for n in ET.parse(path).getroot().iter("node"):
        box = box_of(n)
        if box is None:
            continue
        if screen is None:
            screen = box
            continue
        if outside(box, screen):
            continue
        if n.get("class") == "android.widget.EditText" and box[2] > box[0] and box[3] > box[1]:
            out.append({"text": n.get("text") or "", "box": box})
    out.sort(key=lambda f: (f["box"][1], f["box"][0]))
    return out


SAFE_TEXT = re.compile(r"^[A-Za-z0-9:/._?=-]*$")


def tap_field(index):
    """Tocca il campo di testo numero index (porta il cursore lì). Restituisce il campo, o None se non c'è."""
    p = dump("/tmp/mmi_field.xml")
    if not p:
        return None
    fields = edit_fields(p)
    if index >= len(fields):
        return None
    x1, y1, x2, y2 = fields[index]["box"]
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.8)
    return fields[index]


def fill(index, text):
    if not SAFE_TEXT.match(text):
        print("caratteri non ammessi nel testo da scrivere", file=sys.stderr)
        return 2
    if tap_field(index) is None:
        return 1
    if text:
        # il testo sta tra apici: nessun carattere ammesso ha un significato speciale per la shell del telefono
        adb("shell", "input", "text", "'" + text + "'")
        time.sleep(0.6)
    return 0


def clear_field(index):
    field = tap_field(index)
    if field is None:
        return 1
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * (len(field["text"]) + 3)))
    time.sleep(0.5)
    return 0


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
    if cmd == "tap-last":
        return 0 if tap(argv[2], last=True) else 1
    if cmd == "scan":
        return 0 if scan(argv[2], int(argv[3]) if len(argv) > 3 else 5) else 1
    if cmd == "top":
        top()
        return 0
    if cmd == "actions":
        print("\n".join(actions(argv[2])))
        return 0
    if cmd == "wait":
        return 0 if wait(argv[2], int(argv[3]) if len(argv) > 3 else 20) else 1
    if cmd == "fields":
        for i, f in enumerate(edit_fields(argv[2])):
            print("%d\t%s" % (i, f["text"]))
        return 0
    if cmd == "fill":
        return fill(int(argv[2]), argv[3] if len(argv) > 3 else "")
    if cmd == "clear-field":
        return clear_field(int(argv[2]))
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
