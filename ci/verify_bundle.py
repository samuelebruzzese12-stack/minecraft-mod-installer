#!/usr/bin/env python3
"""Controlla il file che il (finto) Minecraft ha RICEVUTO dall'app, confrontandolo con il contenuto originale.

Uso: python3 ci/verify_bundle.py RICEVUTO.mcaddon SORGENTE.zip
Verifica che ogni file dei due .mcpack annidati in SORGENTE.zip sia arrivato, byte per byte, nella cartella giusta
del .mcaddon, che nessun file sia rimasto fuori dalle cartelle dei pacchetti e che gli UUID corrispondano.
Esce con 0 se tutto torna, 1 altrimenti (stampa ogni differenza).
"""
import io
import json
import sys
import zipfile
import zlib

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from make_samples import uid  # noqa: E402


def read_zip(data):
    z = zipfile.ZipFile(io.BytesIO(data))
    bad = z.testzip()
    if bad:
        raise SystemExit("ZIP danneggiato: " + bad)
    return {i.filename: z.read(i.filename) for i in z.infolist() if not i.filename.endswith("/")}


def main(received, source):
    problems = []
    got = read_zip(open(received, "rb").read())
    src = read_zip(open(source, "rb").read())

    roots = sorted({n.split("/")[0] for n in got if "/" in n})
    outside = [n for n in got if "/" not in n]
    if outside:
        problems.append("file fuori dalle cartelle dei pacchetti: %r" % outside)
    if roots != ["Gun_Mod_BP", "Gun_Mod_RP"]:
        problems.append("cartelle attese Gun_Mod_BP e Gun_Mod_RP, trovate %r" % roots)

    expected = {}
    for nested, folder in (("Gun Mod BP.mcpack", "Gun_Mod_BP"), ("Gun Mod RP.mcpack", "Gun_Mod_RP")):
        for name, data in read_zip(src[nested]).items():
            expected[folder + "/" + name] = data

    missing = sorted(set(expected) - set(got))
    extra = sorted(set(got) - set(expected))
    if missing:
        problems.append("%d file mancanti: %r" % (len(missing), missing[:10]))
    if extra:
        problems.append("%d file in più: %r" % (len(extra), extra[:10]))
    for name in sorted(set(expected) & set(got)):
        if zlib.crc32(expected[name]) != zlib.crc32(got[name]):
            problems.append("contenuto diverso: " + name)

    for folder, want in (("Gun_Mod_BP", uid("gunmod-bp")), ("Gun_Mod_RP", uid("gunmod-rp"))):
        raw = got.get(folder + "/manifest.json")
        if raw is None:
            problems.append("manca " + folder + "/manifest.json")
            continue
        found = json.loads(raw)["header"]["uuid"]
        if found != want:
            problems.append("%s: UUID %s invece di %s" % (folder, found, want))

    # I nomi che differiscono solo per le maiuscole devono esserci tutti e due.
    for name in ("Gun_Mod_RP/textures/items/Aereo.png", "Gun_Mod_RP/textures/items/aereo.png"):
        if name not in got:
            problems.append("manca " + name)

    print("file attesi: %d, ricevuti: %d, cartelle: %s" % (len(expected), len(got), roots))
    if problems:
        print("PROBLEMI:")
        for p in problems:
            print(" -", p)
        return 1
    print("OK: tutti i file dei due pacchetti sono arrivati, identici, nelle cartelle giuste.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
