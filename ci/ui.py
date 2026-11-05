#!/usr/bin/env python3
"""Pilota l'app sull'emulatore leggendo l'interfaccia con uiautomator (solo libreria standard).

Comandi:
  dump PERCORSO          salva l'albero dell'interfaccia in PERCORSO
  texts PERCORSO         elenca i testi visibili (uno per riga)
  has-in PERCORSO TESTO  esce 0 se TESTO compare nel dump
  anr                    se c'è una finestra di Android «… non risponde», la chiude («Wait» per la nostra app, «Close app» per gli altri
                         programmi di Android) e stampa il titolo (esce 0); altrimenti esce 1
  has TESTO              come sopra ma su una schermata nuova
  tap TESTO              tocca il primo elemento che contiene TESTO (scorre fino a 3 volte per trovarlo)
  tap-last TESTO         come tap, ma sceglie l'ULTIMO elemento che contiene TESTO (es. la voce del menu laterale)
  tap-item TESTO         come tap, ma ignora i campi di testo: serve nel selettore di sistema, dove la ricerca ripete il nome del file
                         nel proprio campo e «tap» toccherebbe quello invece della riga dell'elenco
  tap-exact TESTO        tocca solo un elemento il cui testo è ESATTAMENTE TESTO (maiuscole a parte) e di cui si vede il centro;
                         altrimenti scorre fino a 3 volte. Serve per «CERCA», «OK», «CHIUDI», che altrove compaiono dentro frasi
  collect FILE [VOLTE]   scorre la pagina dall'alto verso il basso (fino in fondo, al massimo VOLTE volte) e scrive in FILE tutti i
                         testi visti, uno per riga; poi torna in cima. Per controllare presenza e assenza di testi in pagine lunghe
  scan TESTO [VOLTE]     esce 0 se TESTO compare scorrendo la pagina verso il basso e poi verso l'alto (non tocca nulla)
  top                    torna in cima alla pagina
  actions PERCORSO       elenca le azioni toccabili (testo di ogni elemento cliccabile), una per riga
  wait TESTO [SECONDI]   aspetta che TESTO compaia (default 20 s)
  fields PERCORSO        elenca i campi di testo visibili (dall'alto in basso): indice e contenuto
  fill N TESTO           tocca il campo di testo numero N (da 0, dall'alto) e ci scrive TESTO (solo caratteri semplici)
  clear-field N          svuota il campo di testo numero N
  switches               elenca gli interruttori visibili (dall'alto in basso): indice e stato (on/off)
  switch-state N         esce 0 se l'interruttore N è acceso, 1 se è spento, 2 se non esiste
  tap-switch N           tocca l'interruttore numero N
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

REMOTE = "/data/local/tmp/mmi_ui.xml"

# Le classi che Android usa per i campi di testo: quelli di Compose compaiono come EditText, la ricerca del selettore di sistema
# (DocumentsUI) come AutoCompleteTextView.
EDIT_CLASSES = ("android.widget.EditText", "android.widget.AutoCompleteTextView", "android.widget.MultiAutoCompleteTextView")


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
            "cls": n.get("class") or "",
            "box": box,
        })
    return out


def find(ns, needle, last=False, skip_fields=False):
    nl = needle.lower()
    best = None
    for i, n in enumerate(ns):
        x1, y1, x2, y2 = n["box"]
        if x2 <= x1 or y2 <= y1:
            continue
        if skip_fields and n["cls"] in EDIT_CLASSES:
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
    """Scorre la pagina verso il basso. Di solito il gesto parte al 75% dell'altezza; con MMI_SWIPE_SAFE=1 parte al 55%: così non
    comincia sulla tastiera a schermo (che copre il 38% in basso) e scorre la pagina anche quando un campo di testo è in uso."""
    w, h = screen_size()
    if os.environ.get("MMI_SWIPE_SAFE"):
        start, end = 0.55, 0.15
    else:
        start, end = 0.75, 0.30
    adb("shell", "input", "swipe", str(w // 2), str(int(h * start)), str(w // 2), str(int(h * end)), "350")
    time.sleep(0.8)


def tap(needle, scrolls=3, last=False, skip_fields=False):
    for i in range(scrolls + 1):
        p = dump("/tmp/mmi_tap.xml")
        if p:
            n = find(nodes(p), needle, last, skip_fields)
            if n:
                x1, y1, x2, y2 = n["box"]
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
        if i < scrolls:
            swipe_up()
    return False


def find_exact(ns, needle, screen):
    """Come find, ma il testo deve essere uguale a needle e il centro dell'elemento deve stare sullo schermo (con un margine)."""
    nl = needle.strip().lower()
    w, h = screen
    best = None
    for i, n in enumerate(ns):
        x1, y1, x2, y2 = n["box"]
        if x2 <= x1 or y2 <= y1:
            continue
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        if not (20 <= cx <= w - 20 and 80 <= cy <= h - 120):
            continue
        for label in (n["text"], n["desc"]):
            if label and label.strip().lower() == nl:
                rank = (0 if n["clickable"] else 1, i)
                if best is None or rank < best[0]:
                    best = (rank, n)
    return best[1] if best else None


def tap_exact(needle, scrolls=3):
    screen = screen_size()
    for i in range(scrolls + 1):
        p = dump("/tmp/mmi_tapx.xml")
        if p:
            n = find_exact(nodes(p), needle, screen)
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


def collect(path_out, scrolls=8):
    """Tutti i testi della pagina, scorrendola dall'alto in basso; si ferma quando la pagina non scorre più."""
    top()
    seen = []
    previous = None
    for i in range(scrolls + 1):
        p = dump("/tmp/mmi_collect.xml")
        if p:
            texts = []
            for n in nodes(p):
                for label in (n["text"], n["desc"]):
                    label = label.strip()
                    if label and label not in texts:
                        texts.append(label)
            for t in texts:
                if t not in seen:
                    seen.append(t)
            if previous is not None and texts == previous:
                break
            previous = texts
        if i < scrolls:
            swipe_up()
    with open(path_out, "w", encoding="utf-8") as f:
        f.write("\n".join(seen) + "\n")
    top()


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
    """I campi di testo visibili (EDIT_CLASSES), dall'alto in basso e da sinistra a destra."""
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
        if n.get("class") in EDIT_CLASSES and box[2] > box[0] and box[3] > box[1]:
            out.append({"text": n.get("text") or "", "box": box})
    out.sort(key=lambda f: (f["box"][1], f["box"][0]))
    return out


def switch_nodes(path):
    """Gli interruttori visibili (nodi «checkable», come lo Switch di Compose), dall'alto in basso."""
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
        if n.get("checkable") == "true" and box[2] > box[0] and box[3] > box[1]:
            out.append({"checked": n.get("checked") == "true", "box": box})
    out.sort(key=lambda f: (f["box"][1], f["box"][0]))
    return out


def locate_switch(index, scrolls=4):
    """Cerca l'interruttore numero index scorrendo la pagina verso il basso (fino a scrolls volte). Restituisce il nodo o None.
    L'indice conta gli interruttori visibili in quel momento, dall'alto: con una sola scheda è sempre 0."""
    for i in range(scrolls + 1):
        p = dump("/tmp/mmi_switch.xml")
        if p:
            found = switch_nodes(p)
            if index < len(found):
                return found[index]
        if i < scrolls:
            swipe_up()
    return None


def tap_switch(index):
    node = locate_switch(index)
    if node is None:
        return False
    x1, y1, x2, y2 = node["box"]
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


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


ANR_RE = re.compile(r"isn.t responding|not responding", re.I)
OWN_APP_RE = re.compile(r"minecraft mod installer|com\.mmi\.modinstaller", re.I)


def anr():
    """Chiude la finestra di sistema «… non risponde» (ANR). Restituisce il titolo, o None se non c'è.

    Se a non rispondere è l'app in prova si tocca «Wait» (il problema viene segnalato a parte dalla prova). Se è un
    altro programma di Android (launcher, System UI…) si tocca «Close app»: premere «Wait» non toglie la finestra quando
    il programma è rimasto appeso, e nell'ultima esecuzione fallita l'avevamo premuto 246 volte senza risultato."""
    p = dump("/tmp/mmi_anr.xml")
    if not p:
        return None
    ns = nodes(p)
    title = None
    for n in ns:
        for label in (n["text"], n["desc"]):
            if label and ANR_RE.search(label):
                title = label.strip()
    if title is None:
        return None
    order = ("wait", "close app") if OWN_APP_RE.search(title) else ("close app", "wait")
    for needle in order:
        for n in ns:
            if n["text"].strip().lower() == needle:
                x1, y1, x2, y2 = n["box"]
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return title
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    return title



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
    if cmd == "anr":
        t = anr()
        if t is None:
            return 1
        print(t)
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
    if cmd == "tap-item":
        return 0 if tap(argv[2], skip_fields=True) else 1
    if cmd == "tap-exact":
        return 0 if tap_exact(argv[2]) else 1
    if cmd == "collect":
        collect(argv[2], int(argv[3]) if len(argv) > 3 else 8)
        return 0
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
    if cmd == "switches":
        p = dump("/tmp/mmi_switch.xml")
        if not p:
            return 1
        for i, sw in enumerate(switch_nodes(p)):
            print("%d\t%s" % (i, "on" if sw["checked"] else "off"))
        return 0
    if cmd == "switch-state":
        node = locate_switch(int(argv[2]))
        if node is None:
            return 2
        return 0 if node["checked"] else 1
    if cmd == "tap-switch":
        return 0 if tap_switch(int(argv[2])) else 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
