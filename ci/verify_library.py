#!/usr/bin/env python3
"""Confronta l'archivio dell'app (library.json) prima e dopo un aggiornamento dell'APK.

Uso: verify_library.py PRIMA.json DOPO.json

Dopo l'aggiornamento, e prima che l'utente faccia altro, ogni elemento di «recents», «installed» e «history» presente
prima deve esserci ancora, identico. Esce con 1 se qualcosa è sparito o è cambiato, oppure se la lista di partenza è
vuota (in quel caso la prova non dimostrerebbe nulla).
"""
import json
import sys

LISTS = ("recents", "installed", "history")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def canon(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False)


def main():
    before, after = load(sys.argv[1]), load(sys.argv[2])
    problems = []
    summary = []
    for key in LISTS:
        b, a = before.get(key, []), after.get(key, [])
        summary.append("%s: %d → %d" % (key, len(b), len(a)))
        if not b:
            problems.append("«%s» era vuota prima dell'aggiornamento: la prova non verifica nulla su questa lista" % key)
        present = {canon(x) for x in a}
        for x in b:
            if canon(x) not in present:
                problems.append("«%s»: elemento perso o cambiato dopo l'aggiornamento: %s" % (key, canon(x)[:200]))
    print("versione del formato: %s → %s; %s" % (before.get("version"), after.get("version"), "; ".join(summary)))
    extra = sorted(set(after) - set(before) - set(LISTS) - {"version"})
    if extra:
        print("sezioni nuove nell'archivio: " + ", ".join(extra))
    for p in problems:
        print("PROBLEMA:", p)
    if not problems:
        print("Nessun dato perso: ogni elemento di prima è ancora presente e identico.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
