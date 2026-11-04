#!/usr/bin/env python3
"""Crea file Minecraft di prova (validi e non) per provare l'app sull'emulatore.

Uso: python3 ci/make_samples.py CARTELLA_DI_DESTINAZIONE
Solo libreria standard. Riproduce le stesse strutture usate dai test del motore di analisi.
"""
import json
import os
import struct
import sys
import uuid
import zipfile
import zlib


def uid(seed):
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, "mmi-sample-" + seed))


def png(w=64, h=64, a=(52, 168, 83), b=(30, 110, 50)):
    """PNG a scacchi (così l'anteprima nell'app si riconosce)."""
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            row += bytes(a if ((x // 8) + (y // 8)) % 2 == 0 else b)
        rows.append(bytes(row))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows)))
            + chunk(b"IEND", b""))


def manifest(name, pack_uuid, ptype="resources", deps=None, version=(1, 0, 0), min_engine=(1, 16, 0),
             description="Pacchetto di prova", authors=("Tester",)):
    header = {"name": name, "description": description, "uuid": pack_uuid, "version": list(version)}
    if min_engine:
        header["min_engine_version"] = list(min_engine)
    root = {
        "format_version": 2,
        "header": header,
        "modules": [{"type": ptype, "uuid": uid("module-" + pack_uuid), "version": list(version)}],
    }
    if deps:
        root["dependencies"] = deps
    root["metadata"] = {"authors": list(authors)}
    return json.dumps(root, indent=2).encode("utf-8")


def pack_entries(prefix, name, pack_uuid, ptype="resources", deps=None, icon=None, **kw):
    e = {}
    e[prefix + "manifest.json"] = manifest(name, pack_uuid, ptype, deps, **kw)
    e[prefix + "pack_icon.png"] = icon or png()
    if ptype == "resources":
        e[prefix + "textures/blocks/stone.png"] = png(16, 16)
        e[prefix + "textures/blocks/dirt.png"] = png(16, 16, (120, 85, 50), (90, 60, 35))
    else:
        e[prefix + "entities/cosa.json"] = b'{"format_version":"1.16.0"}'
    return e


def level_dat(name, version):
    def tag(t, n):
        nb = n.encode("utf-8")
        return bytes([t]) + struct.pack("<H", len(nb)) + nb
    body = bytearray()
    body += tag(10, "")
    body += tag(8, "LevelName") + struct.pack("<H", len(name.encode())) + name.encode()
    body += tag(3, "StorageVersion") + struct.pack("<i", 10)
    body += tag(3, "GameType") + struct.pack("<i", 0)
    body += tag(9, "lastOpenedWithVersion") + bytes([3]) + struct.pack("<i", len(version))
    for v in version:
        body += struct.pack("<i", v)
    body += bytes([0])
    return struct.pack("<ii", 10, len(body)) + bytes(body)


def world_entries(prefix, name, version=(1, 21, 0)):
    e = {}
    e[prefix + "level.dat"] = level_dat(name, version)
    e[prefix + "levelname.txt"] = name.encode()
    e[prefix + "db/CURRENT"] = b"MANIFEST-000001\n"
    e[prefix + "db/MANIFEST-000001"] = bytes(range(64))
    e[prefix + "db/000003.ldb"] = bytes((i * 7) % 256 for i in range(2048))
    e[prefix + "world_icon.jpeg"] = png(96, 96, (70, 130, 220), (40, 90, 170))  # PNG con nome .jpeg: Android lo legge lo stesso
    return e


def zip_bytes(entries):
    import io
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return buf.getvalue()


def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            z.writestr(name, data)


def main(out):
    os.makedirs(out, exist_ok=True)

    # 1) texture pack valido
    write_zip(os.path.join(out, "Texture_Verde.mcpack"),
              pack_entries("", "Texture Verde HD", uid("texture-verde"), "resources"))

    # 2) add-on con behavior + resource pack che dipendono l'uno dall'altro
    bp, rp = uid("addon-bp"), uid("addon-rp")
    e = {}
    e.update(pack_entries("Super Addon BP/", "Super Addon BP", bp, "data", deps=[{"uuid": rp, "version": [1, 0, 0]}]))
    e.update(pack_entries("Super Addon RP/", "Super Addon RP", rp, "resources", deps=[{"uuid": bp, "version": [1, 0, 0]}]))
    write_zip(os.path.join(out, "Super_Addon.mcaddon"), e)

    # 3) mondo
    write_zip(os.path.join(out, "Mondo_di_Prova.mcworld"), world_entries("", "Mondo di Prova"))

    # 4) mod Java (non installabile in Bedrock)
    write_zip(os.path.join(out, "Mod_Java_Fabric.zip"), {
        "fabric.mod.json": b'{"schemaVersion":1,"id":"esempio","version":"1.0.0"}',
        "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
        "com/esempio/Mod.class": b"\xca\xfe\xba\xbe" + bytes(200),
    })

    # 5) archivio danneggiato (troncato)
    full = open(os.path.join(out, "Texture_Verde.mcpack"), "rb").read()
    with open(os.path.join(out, "Pacchetto_Rotto.mcpack"), "wb") as f:
        f.write(full[: int(len(full) * 0.6)])

    # 6) dipendenza mancante
    write_zip(os.path.join(out, "Behavior_Dipendenza_Mancante.mcpack"),
              pack_entries("", "Behavior Senza Dipendenza", uid("senza-dip"), "data",
                           deps=[{"uuid": uid("non-esiste"), "version": [1, 0, 0]}]))

    # 7) «mod» come si trova in giro: uno ZIP con DUE .mcpack separati (comportamento + risorse) che dipendono
    #    l'uno dall'altro, più oggetti, bandiere e un aereo con nomi che differiscono solo per le maiuscole.
    #    È il caso che prima veniva consegnato a Minecraft un pacchetto alla volta.
    gbp, grp = uid("gunmod-bp"), uid("gunmod-rp")
    gun_bp = pack_entries("", "Gun Mod BP", gbp, "data", deps=[{"uuid": grp, "version": [1, 0, 0]}])
    gun_bp["items/pistola.json"] = b'{"format_version":"1.20.0","minecraft:item":{"description":{"identifier":"gun:pistola"}}}'
    gun_bp["items/bandiera.json"] = b'{"format_version":"1.20.0","minecraft:item":{"description":{"identifier":"gun:bandiera"}}}'
    gun_bp["entities/aereo.json"] = b'{"format_version":"1.16.0"}'
    gun_bp["scripts/main.js"] = b"console.log('gun mod');\n"
    gun_rp = pack_entries("", "Gun Mod RP", grp, "resources")
    gun_rp["textures/items/pistola.png"] = png(16, 16, (90, 90, 90), (40, 40, 40))
    gun_rp["textures/items/bandiera.png"] = png(16, 16, (200, 40, 40), (160, 20, 20))
    gun_rp["textures/items/Aereo.png"] = png(16, 16, (60, 90, 200), (30, 50, 150))
    gun_rp["textures/items/aereo.png"] = png(16, 16, (70, 100, 210), (40, 60, 160))
    gun_rp["textures/item_texture.json"] = b'{"resource_pack_name":"gun","texture_name":"atlas.items","texture_data":{}}'
    write_zip(os.path.join(out, "Gun_Mod.zip"), {
        "Gun Mod BP.mcpack": zip_bytes(gun_bp),
        "Gun Mod RP.mcpack": zip_bytes(gun_rp),
        "LEGGIMI.txt": b"Installa prima il pacchetto risorse.\n",
    })

    # 9) ZIP «alla Windows» (PowerShell 5 «Compress-Archive»): separatori con la barra rovesciata e cartelle scritte
    #    come voci vuote che finiscono con «\\». Prima diventavano file vuoti accanto alle cartelle omonime.
    win = {"Texture Windows\\": b"", "Texture Windows\\textures\\": b"", "Texture Windows\\textures\\blocks\\": b""}
    for name, data in pack_entries("", "Texture Windows", uid("texture-windows"), "resources").items():
        win["Texture Windows\\" + name.replace("/", "\\")] = data
    write_zip(os.path.join(out, "Texture_Windows.zip"), win)

    # 8) file che non è un pacchetto
    with open(os.path.join(out, "Appunti.txt"), "wb") as f:
        f.write(b"Questo non e' un pacchetto Minecraft.\n")

    for n in sorted(os.listdir(out)):
        print("%-40s %8d byte" % (n, os.path.getsize(os.path.join(out, n))))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "samples")
