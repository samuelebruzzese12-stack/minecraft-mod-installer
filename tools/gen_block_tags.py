#!/usr/bin/env python3
"""Genera mod-converter/src/main/resources/com/mmi/modinstaller/convert/java_block_tags.json.gz: i gruppi di blocchi di Java
(tag di minecraft, già espansi), per i processori delle strutture («tag_match», «protected_blocks», «block_rot»).

Fonte: dati del gioco estratti da misode/mcmeta (ramo «data»), data/minecraft/tags/block.
Uso: python3 -I tools/gen_block_tags.py PERCORSO/mcmeta/data/minecraft/tags/block
"""
import gzip, json, os, sys

root = sys.argv[1]
raw = {}
for dp, _, files in os.walk(root):
    for f in files:
        if f.endswith(".json"):
            rel = os.path.relpath(os.path.join(dp, f), root)[:-5].replace(os.sep, "/")
            raw["minecraft:" + rel] = json.load(open(os.path.join(dp, f)))["values"]

def expand(tag, seen=()):
    out = []
    for v in raw.get(tag, []):
        v = v["id"] if isinstance(v, dict) else v
        if v.startswith("#"):
            if v[1:] not in seen:
                out += expand(v[1:], seen + (tag,))
        else:
            out.append(v)
    return sorted(set(out))

tags = {t: expand(t) for t in sorted(raw)}
dest = "mod-converter/src/main/resources/com/mmi/modinstaller/convert/java_block_tags.json.gz"
with gzip.open(dest, "wt", encoding="utf-8") as f:
    json.dump({"source": "misode/mcmeta (dati di Minecraft Java)", "tags": tags}, f, separators=(",", ":"), sort_keys=True)
print(len(tags), "gruppi ->", dest, os.path.getsize(dest), "byte")
