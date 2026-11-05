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


# ---------------------------------------------------------------------------------------------------- file Java (MOD MANAGER)


def class_file(major=61):
    """Un .class minimo: solo l'intestazione (CAFEBABE + versione). Non è eseguibile, all'analisi basta."""
    return bytes([0xCA, 0xFE, 0xBA, 0xBE, 0, 0, (major >> 8) & 0xFF, major & 0xFF, 0, 0])


def fabric_mod(mod_id, name, version, extra_depends=None, major=61):
    depends = {"fabricloader": ">=0.14.0", "minecraft": "~1.20.1", "java": ">=17"}
    depends.update(extra_depends or {})
    descriptor = {
        "schemaVersion": 1, "id": mod_id, "version": version, "name": name,
        "description": "Mod di prova per la verifica automatica.", "authors": ["Tester"],
        "license": "MIT", "environment": "*", "entrypoints": {"main": ["prova." + mod_id + ".Main"]},
        "depends": depends,
    }
    return zip_bytes({
        "fabric.mod.json": json.dumps(descriptor, indent=2).encode("utf-8"),
        "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
        "prova/" + mod_id + "/Main.class": class_file(major),
    })


def forge_mod(mod_id, name, version):
    toml = (
        'modLoader="javafml"\nloaderVersion="[47,)"\nlicense="MIT"\n'
        '[[mods]]\nmodId="%s"\nversion="%s"\ndisplayName="%s"\nauthors="Tester"\ndescription="Mod di prova."\n'
        '[[dependencies.%s]]\n    modId="forge"\n    mandatory=true\n    versionRange="[47.1.0,)"\n    ordering="NONE"\n    side="BOTH"\n'
        '[[dependencies.%s]]\n    modId="minecraft"\n    mandatory=true\n    versionRange="[1.20.1,1.21)"\n    side="BOTH"\n'
    ) % (mod_id, version, name, mod_id, mod_id)
    return zip_bytes({
        "META-INF/mods.toml": toml.encode("utf-8"),
        "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
        "prova/" + mod_id + "/Main.class": class_file(61),
    })


def java_samples(out):
    """File per provare il MOD MANAGER. Sono veri archivi con le stesse descrizioni delle mod vere (fabric.mod.json,
    mods.toml, pack.mcmeta) ma senza codice eseguibile. Il test «EmulatorSamplesTest» li controlla con l'analizzatore e il
    gestore dei profili veri (in CI, prima dell'emulatore), così le attese dello script non dipendono da come li scrivo qui."""
    def put(name, data):
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)

    # Fabric 1.20.1: va bene in un profilo «Fabric 1.20.1».
    put("Mod_Fabric_Esempio.jar", fabric_mod("esempiofabric", "Esempio Fabric", "1.0.0"))
    # Forge 1.20.1: in un profilo Fabric deve essere bloccata (caricatore sbagliato).
    put("Mod_Forge_Esempio.jar", forge_mod("esempioforge", "Esempio Forge", "1.0.0"))
    # Mod che richiede una libreria che il profilo non ha, e la libreria che la soddisfa.
    put("Mod_Con_Dipendenza.jar", fabric_mod("condipendenza", "Con Dipendenza", "1.2.0", {"libesempio": ">=2.0.0"}))
    put("Libreria_Esempio.jar", fabric_mod("libesempio", "Libreria Esempio", "2.1.0"))
    # Una mod che arriva dal DOWNLOAD MANAGER (servita dal server di prova come /mod.jar).
    put("Mod_Scaricata.jar", fabric_mod("scaricata", "Mod Scaricata", "3.0.0"))
    # Raccolta: tre mod in uno ZIP, più un file di testo. Ognuna deve diventare un file da controllare per conto suo.
    put("Raccolta_Mod.zip", zip_bytes({
        "mods/Uno.jar": fabric_mod("raccoltauno", "Raccolta Uno", "1.0.0"),
        "mods/Due.jar": fabric_mod("raccoltadue", "Raccolta Due", "1.0.0"),
        "mods/Tre.jar": fabric_mod("raccoltatre", "Raccolta Tre", "1.0.0"),
        "LEGGIMI.txt": b"Tre mod in un solo archivio.\n",
    }))
    # Resource pack Java (pack_format 15 = Minecraft 1.20 e 1.20.1).
    put("Texture_Java.zip", zip_bytes({
        "pack.mcmeta": json.dumps({"pack": {"pack_format": 15, "description": "Texture Java di prova"}}).encode("utf-8"),
        "assets/minecraft/textures/block/stone.png": png(16, 16),
        "assets/minecraft/textures/block/dirt.png": png(16, 16, (120, 85, 50), (90, 60, 35)),
    }))

    # Quelli che il finto Modrinth del server di prova (ci/dl_server.py --modrinth) offre nel catalogo: una mod che richiede una
    # libreria, la libreria, e un resource pack. Sono diversi da quelli sopra, così il catalogo si prova su un profilo già pieno.
    put("Mod_Catalogo.jar", fabric_mod("dalcatalogo", "Mod dal Catalogo", "1.5.0", {"libcatalogo": ">=1.0.0"}))
    put("Libreria_Catalogo.jar", fabric_mod("libcatalogo", "Libreria dal Catalogo", "1.2.0"))
    put("Texture_Catalogo.zip", zip_bytes({
        "pack.mcmeta": json.dumps({"pack": {"pack_format": 15, "description": "Texture dal catalogo"}}).encode("utf-8"),
        "assets/minecraft/textures/block/stone.png": png(16, 16, (90, 90, 160), (60, 60, 120)),
    }))


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

    java_samples(out)

    for n in sorted(os.listdir(out)):
        print("%-40s %8d byte" % (n, os.path.getsize(os.path.join(out, n))))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "samples")
