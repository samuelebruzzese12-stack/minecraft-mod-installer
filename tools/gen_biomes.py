#!/usr/bin/env python3
"""Genera mod-converter/src/main/resources/com/mmi/modinstaller/convert/biomes.json: i gruppi di biomi di Java (tag di
minecraft, già espansi) e i nomi dei biomi Bedrock corrispondenti, per decidere dove compaiono le strutture delle mod.

Fonte dei gruppi: dati del gioco estratti da misode/mcmeta (ramo «data»), data/minecraft/tags/worldgen/biome.
I nomi Bedrock sono verificati contro BiomeTypes.getAll() del server Bedrock 1.26.62.
Uso: python3 -I tools/gen_biomes.py PERCORSO/mcmeta/data/minecraft/tags/worldgen/biome
"""
import json, os, sys

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
# Java → Bedrock (solo i nomi diversi; gli altri sono uguali senza «minecraft:»)
rename = {
    "snowy_plains": "ice_plains", "ice_spikes": "ice_plains_spikes", "snowy_taiga": "cold_taiga", "snowy_beach": "cold_beach",
    "old_growth_pine_taiga": "mega_taiga", "old_growth_spruce_taiga": "redwood_taiga_mutated",
    "old_growth_birch_forest": "birch_forest_mutated", "windswept_hills": "extreme_hills",
    "windswept_forest": "extreme_hills_plus_trees", "windswept_gravelly_hills": "extreme_hills_mutated",
    "windswept_savanna": "savanna_mutated", "sparse_jungle": "jungle_edge", "stony_shore": "stone_beach",
    "wooded_badlands": "mesa_plateau_stone", "badlands": "mesa", "eroded_badlands": "mesa_bryce",
    "mushroom_fields": "mushroom_island", "nether_wastes": "hell", "dark_forest": "roofed_forest", "swamp": "swampland",
    "soul_sand_valley": "soulsand_valley", "end_barrens": "the_end", "end_highlands": "the_end", "end_midlands": "the_end", "small_end_islands": "the_end",
}
biomes = sorted({b for v in tags.values() for b in v} | {"minecraft:" + k for k in rename})
# Biomi di Bedrock (BiomeTypes.getAll() del server 1.26.62): quelli «vecchi» (colline, varianti) esistono ancora nei mondi
# creati prima della 1.18 e valgono come il biomi di Java da cui derivano.
bedrock = set(open(os.path.join(os.path.dirname(__file__), "bedrock_biomes.txt")).read().split())
names = {}
for b in biomes:
    p = b.split(":", 1)[1]
    main = "minecraft:" + rename.get(p, p)
    if main not in bedrock:
        continue   # biomi di Java che Bedrock non ha (es. the_void, biomi delle anteprime)
    base = main.split(":", 1)[1]
    names[b] = [main] + sorted(x for x in bedrock if x != main and x.split(":", 1)[1] in (base + "_hills", base + "_mutated", base + "_hills_mutated"))
dest = "mod-converter/src/main/resources/com/mmi/modinstaller/convert/biomes.json"
json.dump({"source": "misode/mcmeta (dati di Minecraft Java), nomi Bedrock verificati nel server 1.26.62", "tags": tags, "bedrock": names},
          open(dest, "w"), separators=(",", ":"), sort_keys=True)
print(len(tags), "gruppi,", len(names), "biomi ->", dest)
