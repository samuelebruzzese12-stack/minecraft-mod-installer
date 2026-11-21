#!/usr/bin/env python3
"""Genera mod-converter/src/main/resources/com/mmi/modinstaller/convert/java_blocks.txt.gz: ogni stato di blocco di Java
con il blocco e gli stati di Bedrock corrispondenti, per convertire le strutture.

Fonte: PrismarineJS minecraft-data (licenza MIT), data/bedrock/<versione>/blocksJ2B.json.
Uso: python3 -I tools/gen_java_blocks.py PERCORSO/blocksJ2B.json
Formato di ogni riga: nome_java [proprietà java separate da virgole] TAB nome_bedrock TAB stati (nome=tipo:valore separati da
virgole; tipo b=booleano, i=intero, s=testo). I tipi sono quelli che il server Bedrock 1.26.62 accetta (verificato con
BlockPermutation.resolve su tutti gli 11.553 stati della tabella).
"""
import gzip, json, re, sys

src = json.load(open(sys.argv[1]))
out = []
for j, b in src.items():
    jm = re.match(r"^([^\[]+)(?:\[(.*)\])?$", j)
    bm = re.match(r"^([^\[]+)(?:\[(.*)\])?$", b)
    states = []
    for kv in (bm.group(2) or "").split(","):
        if not kv:
            continue
        k, v = kv.split("=", 1)
        t = "b" if v in ("true", "false") else ("i" if re.fullmatch(r"-?\d+", v) else "s")
        states.append("%s=%s:%s" % (k, t, v))
    out.append("%s\t%s\t%s\t%s" % (jm.group(1), jm.group(2) or "", bm.group(1), ",".join(states)))
dest = "mod-converter/src/main/resources/com/mmi/modinstaller/convert/java_blocks.txt.gz"
with gzip.open(dest, "wt", encoding="utf-8") as f:
    f.write("# Java -> Bedrock (stati dei blocchi). Fonte: PrismarineJS minecraft-data (MIT), bedrock 1.26.30 blocksJ2B.json\n")
    f.write("\n".join(out) + "\n")
print(len(out), "righe ->", dest)
