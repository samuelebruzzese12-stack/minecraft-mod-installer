#!/usr/bin/env python3
"""Controlla un add-on Bedrock (.mcaddon) scritto da JAVA TO BEDROCK.

Uso: python3 -I ci/validate_addon.py CARTELLA_BEDROCK_SAMPLES FILE.mcaddon [FILE2.mcaddon ...]

CARTELLA_BEDROCK_SAMPLES è una copia di github.com/Mojang/bedrock-samples (basta la cartella metadata/). Si controllano:
  * oggetti e blocchi con gli schemi JSON ufficiali di Mojang (metadata/json_schemas), regole dei minerali comprese;
  * che ogni texture, geometria, nome (lingue), tabella dei bottini citata esista davvero nell'add-on (o in Minecraft);
  * che ogni oggetto usato da ricette e bottini esista: convertito nell'add-on o tra gli oggetti di Bedrock (vanilladata).
Esce con 1 e l'elenco dei problemi se qualcosa non va.
"""
import json
import os
import sys
import zipfile

try:
    import jsonschema
    from referencing import Registry, Resource
except ImportError:  # pragma: no cover
    print("serve il pacchetto python «jsonschema»", file=sys.stderr)
    sys.exit(2)


def any_of(node):
    """Negli schemi di Mojang «oneOf» ha spesso alternative che si sovrappongono (due oggetti senza campi obbligatori, o la
    stessa voce ripetuta): un valore giusto risulterebbe valido «per più di una» e quindi sbagliato. Si controlla con «anyOf»."""
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            out["anyOf" if k == "oneOf" else k] = any_of(v)
        return out
    if isinstance(node, list):
        return [any_of(x) for x in node]
    return node


def load_registry(samples):
    base = os.path.join(samples, "metadata", "json_schemas")
    resources = []
    for root, _, files in os.walk(base):
        for f in files:
            if not f.endswith(".json"):
                continue
            with open(os.path.join(root, f), encoding="utf-8") as fh:
                doc = any_of(json.load(fh))
            sid = doc.get("$id")
            if sid:
                resources.append((sid, Resource.from_contents(doc)))
    return Registry().with_resources(resources)


def validator(registry, sid):
    schema = registry.contents(sid)
    return jsonschema.Draft7Validator(schema, registry=registry)


def vanilla_items(samples):
    with open(os.path.join(samples, "metadata", "vanilladata_modules", "mojang-items.json"), encoding="utf-8") as fh:
        return {x["name"] for x in json.load(fh)["data_items"]}


VANILLA_TAGS = {
    "minecraft:planks", "minecraft:logs", "minecraft:logs_that_burn", "minecraft:wool", "minecraft:wooden_slabs", "minecraft:coals",
    "minecraft:stone_tool_materials", "minecraft:stone_crafting_materials", "minecraft:soul_fire_base_blocks",
}


def check(path, registry, items_ok):
    problems = []
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    packs = {}
    for n in names:
        if n.endswith("manifest.json") and n.count("/") == 1:
            m = json.loads(z.read(n))
            mod_type = m["modules"][0]["type"]
            packs[mod_type] = (n.split("/")[0] + "/", m)
    if set(packs) != {"data", "resources"}:
        return ["mancano i pacchetti di comportamento o di risorse: " + str(sorted(packs))]
    bp, bpm = packs["data"]
    rp, rpm = packs["resources"]
    for prefix, m in (packs["data"], packs["resources"]):
        h = m.get("header", {})
        if m.get("format_version") != 2 or not h.get("uuid") or len(h.get("version", [])) != 3 or len(h.get("min_engine_version", [])) != 3:
            problems.append(prefix + "manifest.json: intestazione non valida")
    if not any(d.get("uuid") == rpm["header"]["uuid"] for d in bpm.get("dependencies", [])):
        problems.append("il pacchetto di comportamento non dipende da quello di risorse")

    def js(n):
        return json.loads(z.read(n).decode("utf-8"))

    item_v = validator(registry, "/server/item/1.26.50/ItemDocument.json")
    block_v = validator(registry, "/server/block/1.26.60/Blocks.json")
    rule_v = validator(registry, "/client_server/world/1.21.20/Feature%20Rule.json")
    entity_v = validator(registry, "/server/entity/1.26.60/ActorDocument.json")

    item_textures = js(rp + "textures/item_texture.json")["texture_data"] if rp + "textures/item_texture.json" in names else {}
    terrain = js(rp + "textures/terrain_texture.json")["texture_data"] if rp + "textures/terrain_texture.json" in names else {}
    lang = {}
    if rp + "texts/en_US.lang" in names:
        for line in z.read(rp + "texts/en_US.lang").decode("utf-8").splitlines():
            if "=" in line and not line.startswith("##"):
                k, v = line.split("=", 1)
                lang[k] = v
    geometries = set()
    bones_of = {}
    entity_geometries = set()
    animations = set()
    for n in names:
        if n.startswith(rp + "models/blocks/") and n.endswith(".geo.json"):
            for g in js(n)["minecraft:geometry"]:
                geometries.add(g["description"]["identifier"])
                bones_of[g["description"]["identifier"]] = {b.get("name") for b in g.get("bones", [])}
        if n.startswith(rp + "models/entity/") and n.endswith(".geo.json"):
            for g in js(n)["minecraft:geometry"]:
                entity_geometries.add(g["description"]["identifier"])
        if (n.startswith(rp + "animations/") or n.startswith(rp + "animation_controllers/")) and n.endswith(".json"):
            d = js(n)
            animations.update(d.get("animations", {}).keys())
            animations.update(d.get("animation_controllers", {}).keys())

    def texture_file_ok(p):
        if p.startswith("textures/mmi/"):
            return rp + p + ".png" in names
        return True  # texture di Minecraft

    defined = set()
    custom_tags = set()
    for n in sorted(names):
        if n.startswith(bp + "items/") and n.endswith(".json"):
            d = js(n)
            doc = d.get("minecraft:item")
            for e in item_v.iter_errors(doc):
                problems.append("%s: %s (in %s)" % (n, e.message[:160], "/".join(map(str, e.absolute_path))))
            ident = doc["description"]["identifier"]
            defined.add(ident)
            c = doc["components"]
            icon = c.get("minecraft:icon")
            if icon not in item_textures:
                problems.append(n + ": l'icona «%s» non è in item_texture.json" % icon)
            elif not texture_file_ok(item_textures[icon]["textures"]):
                problems.append(n + ": manca il file della texture " + item_textures[icon]["textures"])
            key = c.get("minecraft:display_name", {}).get("value")
            if key not in lang:
                problems.append(n + ": il nome «%s» non è in en_US.lang" % key)
            for t in c.get("minecraft:tags", {}).get("tags", []):
                custom_tags.add(t)
        elif n.startswith(bp + "blocks/") and n.endswith(".json"):
            d = js(n)
            doc = d.get("minecraft:block")
            # Gli add-on usano il formato 1.21.80, dove il riquadro di collisione è un oggetto; dalla 1.26.60 è un elenco di
            # riquadri. Per lo schema (che è quello dell'ultima versione) si controlla come elenco di un solo riquadro.
            checked = json.loads(json.dumps(doc))
            cb = checked.get("components", {}).get("minecraft:collision_box")
            if isinstance(cb, dict):
                checked["components"]["minecraft:collision_box"] = [cb]
            # Stati con valori vero/falso o numeri: il server Bedrock ufficiale (1.26.62) li accetta e li usa (provato con
            # ci/bds_test.py), lo schema di Mojang elenca solo i testi. Per lo schema si scrivono come testi.
            for k, v in list(checked.get("description", {}).get("states", {}).items()):
                if isinstance(v, list) and v and all(isinstance(x, (bool, int)) for x in v):
                    checked["description"]["states"][k] = [str(x).lower() for x in v]
            # 1.21.80: blocchi semplici; 1.21.90: con comportamenti della Script API; 1.26.40: blocchi con più stati (BLOCKBRIDGE)
            if d.get("format_version") not in ("1.21.80", "1.21.90", "1.26.40"):
                problems.append(n + ": format_version inatteso " + str(d.get("format_version")))
            for e in block_v.iter_errors(checked):
                problems.append("%s: %s (in %s)" % (n, e.message[:160], "/".join(map(str, e.absolute_path))))
            ident = doc["description"]["identifier"]
            defined.add(ident)
            c = doc["components"]
            g = c.get("minecraft:geometry")
            gid = g.get("identifier") if isinstance(g, dict) else g
            if gid not in ("minecraft:geometry.full_block", "minecraft:geometry.cross") and gid not in geometries:
                problems.append(n + ": la geometria «%s» non c'è" % gid)
            if isinstance(g, dict):
                for bone in g.get("bone_visibility", {}):
                    if bone not in bones_of.get(gid, set()):
                        problems.append(n + ": l'osso «%s» di bone_visibility non è nella geometria" % bone)
            for perm in doc.get("permutations", []):
                pl = perm.get("components", {}).get("minecraft:loot")
                if pl and bp + pl not in names:
                    problems.append(n + ": manca la tabella dei bottini " + pl + " (variante)")
            methods = set()
            for inst, mi in c.get("minecraft:material_instances", {}).items():
                tex = mi.get("texture")
                methods.add(mi.get("render_method"))
                if tex not in terrain:
                    problems.append(n + ": la texture «%s» non è in terrain_texture.json" % tex)
                elif not texture_file_ok(terrain[tex]["textures"]):
                    problems.append(n + ": manca il file della texture " + terrain[tex]["textures"])
            if len(methods) > 1:
                problems.append(n + ": i materiali usano modi di disegno diversi " + str(methods))
            if c.get("minecraft:display_name") not in lang:
                problems.append(n + ": il nome «%s» non è in en_US.lang" % c.get("minecraft:display_name"))
            loot = c.get("minecraft:loot")
            if loot and bp + loot not in names:
                problems.append(n + ": manca la tabella dei bottini " + loot)
    known = defined | items_ok

    def item_ok(i, where):
        if isinstance(i, dict):
            if "tag" in i:
                if i["tag"] not in VANILLA_TAGS and i["tag"] not in custom_tags:
                    problems.append(where + ": gruppo sconosciuto " + i["tag"])
                return
            i = i.get("item")
        if i not in known:
            problems.append(where + ": oggetto inesistente " + str(i))

    for n in sorted(names):
        if n.startswith(bp + "recipes/") and n.endswith(".json"):
            d = js(n)
            kind = [k for k in d if k.startswith("minecraft:recipe_")]
            if len(kind) != 1:
                problems.append(n + ": tipo di ricetta non valido")
                continue
            r = d[kind[0]]
            if not r.get("tags") or not r.get("description", {}).get("identifier"):
                problems.append(n + ": mancano identificatore o tag")
            if kind[0] == "minecraft:recipe_shaped":
                rows = r["pattern"]
                if len({len(x) for x in rows}) != 1:
                    problems.append(n + ": righe dello schema di lunghezza diversa")
                for ch in "".join(rows):
                    if ch != " " and ch not in r["key"]:
                        problems.append(n + ": il simbolo «%s» non ha ingrediente" % ch)
                for v in r["key"].values():
                    item_ok(v, n)
                item_ok(r["result"], n)
            elif kind[0] == "minecraft:recipe_shapeless":
                for v in r["ingredients"]:
                    item_ok(v, n)
                item_ok(r["result"], n)
            elif kind[0] == "minecraft:recipe_furnace":
                item_ok(r["input"] if isinstance(r["input"], str) else r["input"], n)
                item_ok(r["output"] if isinstance(r["output"], str) else r["output"], n)
            elif kind[0] == "minecraft:recipe_smithing_transform":
                for k in ("template", "base", "addition", "result"):
                    item_ok(r[k], n)
        elif n.startswith(bp + "loot_tables/") and n.endswith(".json"):
            d = js(n)
            for p in d.get("pools", []):
                for e in p.get("entries", []):
                    if e.get("type") == "item":
                        item_ok(e.get("name"), n)
        elif n.startswith(bp + "feature_rules/") and n.endswith(".json"):
            d = js(n)
            for e in rule_v.iter_errors(d["minecraft:feature_rules"]):
                problems.append("%s: %s" % (n, e.message[:160]))
            feat = d["minecraft:feature_rules"]["description"]["places_feature"]
            fname = bp + "features/" + feat.split(":")[-1] + ".json"
            if fname not in names:
                problems.append(n + ": manca la «feature» " + fname)
        elif n.startswith(bp + "features/") and n.endswith(".json"):
            d = js(n)
            ore = d.get("minecraft:ore_feature", {})
            if n.rsplit("/", 1)[1][:-5] != ore.get("description", {}).get("identifier", "").split(":")[-1]:
                problems.append(n + ": il nome del file non corrisponde all'identificatore")
            for rule in ore.get("replace_rules", []):
                item_ok(rule["places_block"], n)
        elif n.startswith(bp + "entities/") and n.endswith(".json"):
            d = js(n)
            for e in entity_v.iter_errors(d["minecraft:entity"]):
                problems.append("%s: %s (in %s)" % (n, e.message[:160], "/".join(map(str, e.absolute_path))))
            ident = d["minecraft:entity"]["description"]["identifier"]
            client = rp + "entity/" + ident.replace(":", "/") + ".entity.json"
            if client not in names:
                problems.append(n + ": manca la parte grafica " + client)
                continue
            cd = js(client)["minecraft:client_entity"]["description"]
            if cd["identifier"] != ident:
                problems.append(client + ": identificatore diverso")
            if cd["geometry"]["default"] not in entity_geometries:
                problems.append(client + ": il modello «%s» non c'è" % cd["geometry"]["default"])
            if not texture_file_ok(cd["textures"]["default"]):
                problems.append(client + ": manca la texture " + cd["textures"]["default"])
            for a in cd.get("animations", {}).values():
                if a not in animations:
                    problems.append(client + ": manca l'animazione " + a)
            if "entity.%s.name" % ident not in lang:
                problems.append(client + ": manca il nome nella lingua inglese")
        elif n.startswith(rp + "attachables/") and n.endswith(".json"):
            d = js(n)["minecraft:attachable"]["description"]
            if d["identifier"] not in defined:
                problems.append(n + ": l'armatura non corrisponde a un oggetto")
            if not texture_file_ok(d["textures"]["default"]):
                problems.append(n + ": manca la texture " + d["textures"]["default"])
    # Suoni (BLOCKBRIDGE): ogni file citato c'è, ogni verso delle creature è un suono definito.
    sd_name = rp + "sounds/sound_definitions.json"
    sounds = {}
    if sd_name in names:
        sounds = js(sd_name).get("sound_definitions", {})
        for sid, sdef in sounds.items():
            for f in sdef.get("sounds", []):
                fn = f if isinstance(f, str) else f.get("name")
                if rp + fn + ".ogg" not in names and rp + fn + ".fsb" not in names:
                    problems.append(sd_name + ": manca il file " + fn + ".ogg (suono " + sid + ")")
    if rp + "sounds.json" in names:
        ents = js(rp + "sounds.json").get("entity_sounds", {}).get("entities", {})
        for eid, e in ents.items():
            for ev, sid in e.get("events", {}).items():
                sid = sid if isinstance(sid, str) else sid.get("sound")
                if sid not in sounds:
                    problems.append("sounds.json: %s/%s usa il suono %s che non è definito" % (eid, ev, sid))
    return problems


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    samples = sys.argv[1]
    registry = load_registry(samples)
    items_ok = vanilla_items(samples)
    bad = 0
    for f in sys.argv[2:]:
        problems = check(f, registry, items_ok)
        if problems:
            bad += 1
            print("== %s: %d problemi" % (os.path.basename(f), len(problems)))
            for p in problems[:200]:
                print("   " + p)
        else:
            print("== %s: nessun problema" % os.path.basename(f))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
