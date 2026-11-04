#!/usr/bin/env python3
"""Controlla il file che il (finto) Minecraft ha RICEVUTO partendo da uno ZIP creato «alla Windows».

Uso: python3 ci/verify_windows.py RICEVUTO.mcpack SORGENTE.zip
Il sorgente usa la barra rovesciata come separatore e scrive le cartelle come voci vuote. Nel file consegnato:
  - nessun nome deve contenere la barra rovesciata;
  - nessun file deve avere lo stesso nome di una cartella (es. «textures» accanto a «textures/blocks/stone.png»);
  - ogni file del sorgente deve esserci, con lo stesso contenuto, alla radice del pacchetto.
Esce con 0 se tutto torna, 1 altrimenti (stampa ogni differenza).
"""
import sys
import zipfile
import zlib


def main(received, source):
    problems = []
    rec = zipfile.ZipFile(received)
    bad = rec.testzip()
    if bad:
        problems.append("ZIP danneggiato: " + bad)
    got = {i.filename: rec.read(i.filename) for i in rec.infolist() if not i.filename.endswith("/")}

    for n in got:
        if "\\" in n:
            problems.append("barra rovesciata nel nome: %r" % n)
        if any(m != n and m.startswith(n + "/") for m in got):
            problems.append("«%s» è sia un file sia una cartella" % n)

    src = zipfile.ZipFile(source)
    expected = {}
    for i in src.infolist():
        if i.filename.endswith("\\") or i.filename.endswith("/"):
            continue
        rel = i.filename.replace("\\", "/").split("/", 1)[1]  # toglie la cartella «Texture Windows»
        expected[rel] = src.read(i.filename)

    missing = sorted(set(expected) - set(got))
    extra = sorted(set(got) - set(expected))
    if missing:
        problems.append("%d file mancanti: %r" % (len(missing), missing))
    if extra:
        problems.append("%d file in più: %r" % (len(extra), extra))
    for name in sorted(set(expected) & set(got)):
        if zlib.crc32(expected[name]) != zlib.crc32(got[name]):
            problems.append("contenuto diverso: " + name)

    print("file ricevuti: %d, attesi: %d" % (len(got), len(expected)))
    for p in problems:
        print("PROBLEMA:", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
