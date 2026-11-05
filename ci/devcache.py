#!/usr/bin/env python3
"""Materiale di sviluppo per BLOCKBRIDGE (JAVA TO BEDROCK), raccolto in CI perché il server di sviluppo non raggiunge Internet.

Uso: python3 -I ci/devcache.py CARTELLA

Scarica, con controllo delle impronte dove il servizio le dichiara:
  - mod Java vere da Modrinth (API ufficiale v2), scelte per coprire caricatori, versioni e casi complessi;
  - le mappe dei nomi di Mojang (client_mappings) di alcune versioni, solo per lo sviluppo (non si ridistribuiscono);
  - il server Bedrock ufficiale (Bedrock Dedicated Server per Linux) per le prove vere degli add-on;
  - i pacchetti npm delle Script API di Bedrock e di TypeScript (per controllare gli script generati);
  - CFR (decompilatore, licenza MIT) per la ricostruzione avanzata.
Scrive in CARTELLA/indice.txt cosa ha preso e cosa no. Ogni errore viene registrato e non ferma il resto.
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
UA = "MinecraftModInstaller-CI/1.0 (cache di sviluppo BLOCKBRIDGE)"
BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

# (progetto Modrinth, caricatore, versione di Minecraft)
MODS = [
    ("create", "neoforge", "1.21.1"),
    ("create", "forge", "1.20.1"),
    ("mekanism", "neoforge", "1.21.1"),
    ("aether", "neoforge", "1.21.1"),
    ("the-twilight-forest", "neoforge", "1.21.1"),
    ("alexs-mobs", "forge", "1.20.1"),
    ("citadel", "forge", "1.20.1"),
    ("alexs-caves", "forge", "1.20.1"),
    ("farmers-delight", "neoforge", "1.21.1"),
    ("farmers-delight-refabricated", "fabric", "1.21.1"),
    ("botania", "fabric", "1.20.1"),
    ("friends-and-foes", "fabric", "1.21.1"),
    ("naturalist", "fabric", "1.21.1"),
    ("mythicmetals", "fabric", "1.21.1"),
    ("biomes-o-plenty", "neoforge", "1.21.1"),
    ("deeper-and-darker", "neoforge", "1.21.1"),
    ("irons-spells-n-spellbooks", "neoforge", "1.21.1"),
    ("ars-nouveau", "neoforge", "1.21.1"),
    ("the-graveyard", "fabric", "1.21.1"),
    ("mowzies-mobs", "forge", "1.20.1"),
    ("supplementaries", "neoforge", "1.21.1"),
    ("croptopia", "fabric", "1.21.1"),
    ("quark", "forge", "1.20.1"),
    ("ice-and-fire-dragons", "forge", "1.20.1"),
    ("geckolib", "neoforge", "1.21.1"),
    ("sodium", "fabric", "1.21.1"),
]
MAX_MOD_BYTES = 150 * 1024 * 1024
MAPPING_VERSIONS = ["1.20.1", "1.21.1", "1.21.4"]
NPM = [
    "@minecraft/server@2.0.0", "@minecraft/server@latest", "@minecraft/server-ui@2.0.0", "@minecraft/server-ui@latest",
    "@minecraft/server-gametest@beta", "@minecraft/common@latest", "typescript@5",
]
CFR = "https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar"


def get(url, ua=UA, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def save(path, data):
    with open(path, "wb") as fh:
        fh.write(data)


def mods(out, log):
    d = os.path.join(out, "mods")
    os.makedirs(d, exist_ok=True)
    for slug, loader, mc in MODS:
        try:
            q = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([mc])})
            versions = json.loads(get("%s/project/%s/version?%s" % (API, slug, q)))
            version = next((v for v in versions if v.get("version_type") == "release"), versions[0] if versions else None)
            if version is None:
                log.append("mod %s %s %s: nessuna versione" % (slug, loader, mc))
                continue
            f = next((x for x in version["files"] if x.get("primary")), version["files"][0])
            if not f["filename"].endswith(".jar") or f.get("size", 0) > MAX_MOD_BYTES:
                log.append("mod %s: file non adatto (%s, %s byte)" % (slug, f["filename"], f.get("size")))
                continue
            data = get(f["url"])
            want = f.get("hashes", {}).get("sha512")
            if want and hashlib.sha512(data).hexdigest() != want:
                log.append("mod %s: impronta SHA-512 diversa, scartato" % slug)
                continue
            name = "%s__%s-%s__%s" % (slug, loader, mc, f["filename"])
            save(os.path.join(d, name), data)
            log.append("mod %s: %s (%s %s, %d byte)" % (slug, version.get("version_number"), loader, mc, len(data)))
        except Exception as e:
            log.append("mod %s %s %s: non scaricata (%s)" % (slug, loader, mc, e))


def mappings(out, log):
    d = os.path.join(out, "mojang-mappings")
    os.makedirs(d, exist_ok=True)
    try:
        manifest = json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    except Exception as e:
        log.append("mappe Mojang: indice non scaricato (%s)" % e)
        return
    for v in MAPPING_VERSIONS:
        try:
            entry = next(x for x in manifest["versions"] if x["id"] == v)
            meta = json.loads(get(entry["url"]))
            m = meta["downloads"]["client_mappings"]
            data = get(m["url"])
            if hashlib.sha1(data).hexdigest() != m["sha1"]:
                log.append("mappe %s: impronta diversa" % v)
                continue
            save(os.path.join(d, "client-%s.txt" % v), data)
            log.append("mappe %s: %d byte" % (v, len(data)))
        except Exception as e:
            log.append("mappe %s: non scaricate (%s)" % (v, e))


def bds(out, log):
    try:
        links = json.loads(get("https://net-secondary.web.minecraft-services.net/api/v1.0/download/links", ua=BROWSER_UA))
        url = next(l["downloadUrl"] for l in links["result"]["links"] if l["downloadType"] == "serverBedrockLinux")
        data = get(url, ua=BROWSER_UA, timeout=600)
        name = url.rsplit("/", 1)[-1]
        save(os.path.join(out, name), data)
        log.append("server Bedrock: %s (%d byte, sha256 %s)" % (name, len(data), hashlib.sha256(data).hexdigest()))
    except Exception as e:
        log.append("server Bedrock: non scaricato (%s)" % e)


def npm(out, log):
    d = os.path.join(out, "npm")
    os.makedirs(d, exist_ok=True)
    for spec in NPM:
        try:
            r = subprocess.run(["npm", "pack", spec, "--pack-destination", d], capture_output=True, text=True, timeout=300)
            log.append("npm %s: %s" % (spec, (r.stdout.strip().splitlines() or [r.stderr.strip()[:200]])[-1]))
        except Exception as e:
            log.append("npm %s: non scaricato (%s)" % (spec, e))


def cfr(out, log):
    try:
        data = get(CFR)
        want = get(CFR + ".sha1").decode().strip().split()[0]
        if hashlib.sha1(data).hexdigest() != want:
            log.append("CFR: impronta diversa")
            return
        save(os.path.join(out, "cfr-0.152.jar"), data)
        log.append("CFR: %d byte" % len(data))
    except Exception as e:
        log.append("CFR: non scaricato (%s)" % e)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "devcache"
    os.makedirs(out, exist_ok=True)
    log = []
    for step in (mods, mappings, bds, npm, cfr):
        step(out, log)
    with open(os.path.join(out, "indice.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(log) + "\n")
    print("\n".join(log))
    return 0


if __name__ == "__main__":
    sys.exit(main())
