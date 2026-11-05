#!/usr/bin/env python3
"""Genera la tabella dei nomi di Minecraft usata da BLOCKBRIDGE per leggere il bytecode delle mod (strumento di sviluppo).

Le mod Fabric/Quilt usano i nomi «intermediary» (class_1792, method_7889…), quelle Forge 1.17–1.20 i nomi delle classi di
Mojang con i membri «SRG» (m_41487_…), quelle NeoForge 1.20.5+ direttamente i nomi di Mojang. Per riconoscere le chiamate
(Item.Properties.stacksTo, Registry.register…) l'app porta tutto ai nomi di Mojang con questa tabella.

Uso: python3 -I ci/make_mc_names.py MAPPE_MOJANG_DIR INTERMEDIARY_DIR MCPCONFIG_DIR USCITA
  MAPPE_MOJANG_DIR: client-<versione>.txt (mappe ufficiali di Mojang, solo per lo sviluppo: non si ridistribuiscono intere)
  INTERMEDIARY_DIR: <versione>.tiny (FabricMC/intermediary, CC0)
  MCPCONFIG_DIR:    versions/release/<versione>/joined.tsrg (MinecraftForge/MCPConfig)
La tabella contiene solo le classi dei pacchetti che servono alla conversione (oggetti, blocchi, creature, suoni, modelli…) e
i loro membri: è un estratto modificato, non le mappe intere.
"""
import os
import re
import sys

VERSIONS_INTERMEDIARY = ["1.20.1", "1.21.1", "1.21.4"]
VERSIONS_SRG = ["1.20.1"]

PACKAGES = [
    "net/minecraft/world/item/", "net/minecraft/world/level/block/", "net/minecraft/world/level/material/",
    "net/minecraft/world/food/", "net/minecraft/world/effect/", "net/minecraft/world/entity/", "net/minecraft/core/",
    "net/minecraft/resources/", "net/minecraft/sounds/", "net/minecraft/tags/", "net/minecraft/world/inventory/",
    "net/minecraft/client/model/", "net/minecraft/client/animation/", "net/minecraft/client/renderer/entity/",
    "net/minecraft/client/renderer/blockentity/", "net/minecraft/client/renderer/RenderType",
    "net/minecraft/client/renderer/ItemBlockRenderTypes", "net/minecraft/util/Mth", "net/minecraft/util/valueproviders/",
    "net/minecraft/util/RandomSource", "net/minecraft/world/level/Level", "net/minecraft/world/level/ItemLike",
    "net/minecraft/world/level/LevelReader", "net/minecraft/world/level/BlockGetter", "net/minecraft/world/level/LevelAccessor",
    "net/minecraft/world/InteractionResult", "net/minecraft/world/InteractionHand", "net/minecraft/world/ItemInteractionResult",
    "net/minecraft/world/damagesource/", "net/minecraft/world/phys/", "net/minecraft/network/chat/Component",
    "net/minecraft/world/level/levelgen/feature/", "net/minecraft/world/level/storage/loot/LootTable",
    "net/minecraft/world/Difficulty", "net/minecraft/world/level/GameRules", "net/minecraft/server/level/ServerLevel",
    "net/minecraft/world/level/biome/Biome", "net/minecraft/world/level/dimension/", "net/minecraft/world/MenuProvider",
    "net/minecraft/world/Container", "net/minecraft/world/SimpleContainer", "net/minecraft/world/level/Explosion",
    "net/minecraft/client/particle/", "net/minecraft/world/level/ClipContext", "net/minecraft/world/level/EntityGetter",
]

PRIMS = {"int": "I", "long": "J", "float": "F", "double": "D", "boolean": "Z", "byte": "B", "short": "S", "char": "C", "void": "V"}


def wanted(cls):
    return any(cls.startswith(p) for p in PACKAGES)


def read_mojang(path):
    """deobf → obf delle classi; per ogni classe obf: metodi (nome obf, desc deobf) → nome, campi nome obf → nome."""
    classes, methods, fields = {}, {}, {}
    cur = None
    cls_re = re.compile(r"^(\S+) -> (\S+):$")
    mem_re = re.compile(r"^\s+(?:\d+:\d+:)?(\S+) ([^\s(]+)(?:\(([^)]*)\))?(?::\d+:\d+)? -> (\S+)$")
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            m = cls_re.match(line.rstrip("\n"))
            if m:
                cur = m.group(2)
                classes[m.group(1).replace(".", "/")] = cur
                continue
            m = mem_re.match(line.rstrip("\n"))
            if m and cur:
                typ, name, params, obf = m.groups()
                if params is None:
                    fields[(cur, obf)] = name
                else:
                    methods.setdefault(cur, []).append((obf, typ, params, name))
    return classes, methods, fields


def to_desc(typ, deobf2obf):
    dims = 0
    while typ.endswith("[]"):
        dims += 1
        typ = typ[:-2]
    if typ in PRIMS:
        base = PRIMS[typ]
    else:
        internal = typ.replace(".", "/")
        base = "L" + deobf2obf.get(internal, internal) + ";"
    return "[" * dims + base


def method_desc(ret, params, deobf2obf):
    ps = [p for p in params.split(",") if p] if params else []
    return "(" + "".join(to_desc(p, deobf2obf) for p in ps) + ")" + to_desc(ret, deobf2obf)


def build(mojang_path):
    deobf2obf, methods, fields = read_mojang(mojang_path)
    obf2deobf = {v: k for k, v in deobf2obf.items()}
    mkey = {}
    for owner, lst in methods.items():
        for obf, ret, params, name in lst:
            mkey[(owner, obf, method_desc(ret, params, deobf2obf))] = name
    return obf2deobf, mkey, fields


def main():
    moj_dir, int_dir, mcp_dir, out = sys.argv[1:5]
    rows_c, rows_m, rows_f, rows_sm, rows_sf = {}, {}, {}, {}, {}
    for v in VERSIONS_INTERMEDIARY:
        obf2deobf, mkey, fkey = build(os.path.join(moj_dir, "client-%s.txt" % v))
        with open(os.path.join(int_dir, "%s.tiny" % v), encoding="utf-8") as fh:
            for line in fh:
                p = line.rstrip("\n").split("\t")
                if p[0] == "CLASS":
                    deobf = obf2deobf.get(p[1])
                    if deobf and wanted(deobf):
                        rows_c[p[2]] = deobf
                elif p[0] == "METHOD":
                    owner = obf2deobf.get(p[1])
                    if owner and wanted(owner):
                        name = mkey.get((p[1], p[3], p[2]))
                        if name and not name.startswith("lambda$"):
                            rows_m[p[4]] = name
                elif p[0] == "FIELD":
                    owner = obf2deobf.get(p[1])
                    if owner and wanted(owner):
                        name = fkey.get((p[1], p[3]))
                        if name:
                            rows_f[p[4]] = name
    for v in VERSIONS_SRG:
        obf2deobf, mkey, fkey = build(os.path.join(moj_dir, "client-%s.txt" % v))
        cur = None
        with open(os.path.join(mcp_dir, "versions", "release", v, "joined.tsrg"), encoding="utf-8") as fh:
            next(fh)
            for line in fh:
                line = line.rstrip("\n")
                if not line.startswith("\t"):
                    cur = line.split(" ")[0]
                    continue
                if line.startswith("\t\t"):
                    continue  # parametri
                p = line.strip().split(" ")
                owner = obf2deobf.get(cur)
                if not owner or not wanted(owner):
                    continue
                if len(p) == 4 and p[1].startswith("("):
                    name = mkey.get((cur, p[0], p[1]))
                    if name and p[2].startswith("m_"):
                        rows_sm[p[2]] = name
                elif len(p) == 3 and p[1].startswith("f_"):
                    name = fkey.get((cur, p[0]))
                    if name:
                        rows_sf[p[1]] = name
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("# Nomi di Minecraft per BLOCKBRIDGE: C/m/f = intermediary (Fabric) → Mojang, M/F = SRG (Forge) → Mojang.\n")
        fh.write("# Estratto generato da ci/make_mc_names.py (intermediary %s, SRG %s).\n" % (",".join(VERSIONS_INTERMEDIARY), ",".join(VERSIONS_SRG)))
        for k in sorted(rows_c):
            fh.write("C\t%s\t%s\n" % (k[len("net/minecraft/"):], rows_c[k]))
        for tag, rows in (("m", rows_m), ("f", rows_f), ("M", rows_sm), ("F", rows_sf)):
            for k in sorted(rows, key=lambda s: int(re.sub(r"\D", "", s) or 0)):
                fh.write("%s\t%s\t%s\n" % (tag, re.sub(r"\D", "", k), rows[k]))
    print("classi %d, metodi %d, campi %d, metodi SRG %d, campi SRG %d" % (len(rows_c), len(rows_m), len(rows_f), len(rows_sm), len(rows_sf)))


if __name__ == "__main__":
    main()
