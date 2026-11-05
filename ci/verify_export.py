#!/usr/bin/env python3
"""Controlla lo ZIP che il MOD MANAGER ha esportato: stesse cartelle di Minecraft e stessi byte dei file messi nel profilo.

Uso: python3 -I ci/verify_export.py ESPORTATO.zip cartella:sha256 [cartella:sha256 ...]
Ogni coppia dice «in questa cartella dello ZIP ci deve essere un file con questa impronta SHA-256» (mods, resourcepacks…).
Controlla anche che nello ZIP non ci sia altro (nessun file in più, nessun file fuori dalle cartelle) e che l'archivio
non sia danneggiato. Esce con 0 se tutto torna, 1 altrimenti (stampa ogni differenza).
"""
import hashlib
import sys
import zipfile


def main(path, wanted):
    problems = []
    try:
        z = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        print("lo ZIP non si apre: %s" % e)
        return 1
    bad = z.testzip()
    if bad:
        problems.append("voce danneggiata: %s" % bad)

    found = []  # (cartella, nome, sha)
    for info in z.infolist():
        if info.filename.endswith("/"):
            continue
        folder, _, name = info.filename.partition("/")
        if not name:
            problems.append("file fuori dalle cartelle: %r" % info.filename)
            continue
        found.append((folder, name, hashlib.sha256(z.read(info.filename)).hexdigest()))

    pool = list(found)
    for item in wanted:
        folder, _, sha = item.partition(":")
        match = next((f for f in pool if f[0] == folder and f[2] == sha), None)
        if match is None:
            problems.append("manca un file in «%s/» con impronta %s…" % (folder, sha[:16]))
        else:
            pool.remove(match)
    for folder, name, sha in pool:
        problems.append("file in più nello ZIP: %s/%s (%s…)" % (folder, name, sha[:16]))

    print("voci dello ZIP: %s" % ", ".join("%s/%s" % (f, n) for f, n, _ in found))
    for p in problems:
        print("DIFFERENZA: " + p)
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2:]))
