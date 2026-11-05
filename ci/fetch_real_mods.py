#!/usr/bin/env python3
"""Scarica da Modrinth (API ufficiale, sola lettura) alcune mod Java vere per provare JAVA TO BEDROCK.

Uso: python3 -I ci/fetch_real_mods.py CARTELLA

Prende l'ultima versione pubblicata di ogni mod dell'elenco (qualsiasi caricatore), scarica il file principale .jar dal CDN di
Modrinth e controlla l'impronta SHA-512 dichiarata. Le mod sono scelte per coprire i casi veri: contenuto (blocchi, oggetti,
cibo, minerali, armature), creature GeckoLib, mod di solo codice e librerie. Scrive in CARTELLA/mod-scaricate.txt cosa ha preso.
"""
import hashlib
import json
import os
import sys
import urllib.request

API = "https://api.modrinth.com/v2"
UA = "MinecraftModInstaller-CI/1.0 (prova automatica JAVA TO BEDROCK)"

MODS = [
    "farmers-delight-refabricated",  # cibo, colture, blocchi con forme proprie, ricette di macchine
    "mythicmetals",                  # minerali con generazione, attrezzi, armature
    "croptopia",                     # moltissimi cibi e colture
    "naturalist",                    # creature GeckoLib
    "biomes-o-plenty",               # blocchi, piante, legni
    "supplementaries",               # tanti blocchi decorativi disegnati dal codice
    "sodium",                        # solo codice
    "geckolib",                      # libreria
]
MAX_BYTES = 80 * 1024 * 1024


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "real-mods"
    os.makedirs(out, exist_ok=True)
    log = []
    for slug in MODS:
        try:
            versions = json.loads(get("%s/project/%s/version" % (API, slug)))
            version = next((v for v in versions if v.get("version_type") == "release"), versions[0] if versions else None)
            if version is None:
                log.append("%s: nessuna versione" % slug)
                continue
            f = next((x for x in version["files"] if x.get("primary")), version["files"][0])
            if not f["filename"].endswith(".jar") or f.get("size", 0) > MAX_BYTES:
                log.append("%s: file non adatto (%s, %s byte)" % (slug, f["filename"], f.get("size")))
                continue
            data = get(f["url"])
            want = f.get("hashes", {}).get("sha512")
            if want and hashlib.sha512(data).hexdigest() != want:
                log.append("%s: impronta SHA-512 diversa, scartato" % slug)
                continue
            name = slug + "-" + f["filename"]
            with open(os.path.join(out, name), "wb") as fh:
                fh.write(data)
            log.append("%s: %s %s (%s, %d byte)" % (slug, version.get("name"), version.get("version_number"),
                                                    "/".join(version.get("loaders", [])), len(data)))
        except Exception as e:  # una mod che non si scarica non ferma le altre
            log.append("%s: non scaricata (%s)" % (slug, e))
    with open(os.path.join(out, "mod-scaricate.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(log) + "\n")
    print("\n".join(log))
    return 0


if __name__ == "__main__":
    sys.exit(main())
