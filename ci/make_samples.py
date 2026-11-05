#!/usr/bin/env python3
"""Crea file Minecraft di prova (validi e non) per provare l'app sull'emulatore.

Uso: python3 ci/make_samples.py CARTELLA_DI_DESTINAZIONE
Solo libreria standard. Riproduce le stesse strutture usate dai test del motore di analisi.
"""
import base64
import gzip
import hashlib
import json
import os
import random
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


# ---------------------------------------------------------------------------------------------------- modpack Modrinth (MODPACK MANAGER)

CDN = "http://10.0.2.2:8765/modrinth-cdn/data/"


def mrpack_bytes(name, version, summary, files, overrides, minecraft="1.20.1", loader=("fabric-loader", "0.15.11")):
    """Un modpack Modrinth (.mrpack, formato 1): modrinth.index.json più le cartelle overrides.

    files: elenco di dizionari {path, data, url, client, server, declared}. «declared» sono i byte di cui si pubblica
    l'impronta (se diversi da «data», il server serve un file alterato e l'app deve scartarlo).
    overrides: {percorso nello ZIP: byte}."""
    entries = []
    for f in files:
        declared = f.get("declared", f["data"])
        entries.append({
            "path": f["path"],
            "hashes": {"sha1": hashlib.sha1(declared).hexdigest(), "sha512": hashlib.sha512(declared).hexdigest()},
            "env": {"client": f.get("client", "required"), "server": f.get("server", "required")},
            "downloads": [f["url"]],
            "fileSize": f.get("size", len(declared)),
        })
    index = {"formatVersion": 1, "game": "minecraft", "versionId": version, "name": name, "summary": summary,
             "files": entries, "dependencies": {"minecraft": minecraft, loader[0]: loader[1]}}
    parts = {"modrinth.index.json": json.dumps(index, indent=2).encode("utf-8")}
    parts.update(overrides)
    return zip_bytes(parts)


def modpack_samples(out):
    """Modpack per provare il MODPACK MANAGER. I file che i modpack chiedono di scaricare sono quelli di java_samples(), serviti dal
    finto Modrinth di ci/dl_server.py: l'elenco di cosa servire a quale indirizzo si scrive in modpack-server.json (lo legge il
    server). Il test «EmulatorModpackSamplesTest» controlla questi file con il lettore, il pianificatore e l'installatore veri."""
    def read(name):
        with open(os.path.join(out, name), "rb") as f:
            return f.read()

    def put(name, data):
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)

    serve = []   # cosa deve servire il finto CDN: {path, source | text, mode}

    def cdn(project, version, name, source=None, text=None, data=None, mode="normal"):
        path = "/modrinth-cdn/data/%s/versions/%s/%s" % (project, version, name)
        entry = {"path": path, "mode": mode}
        if source is not None:
            entry["source"] = source
        elif data is not None:
            entry["b64"] = base64.b64encode(data).decode("ascii")
        else:
            entry["text"] = text
        serve.append(entry)
        return CDN + "%s/versions/%s/%s" % (project, version, name)

    # 1) completo: due mod, un resource pack, un file di impostazioni da scaricare, una mod solo per server (da non scaricare) e
    #    tre file di impostazioni (comuni, solo client, solo server).
    solo_server = fabric_mod("soloserver", "Solo Server", "1.0.0")
    config = b'{"modpack": "prova", "valore": 42}\n'
    p, v = "ModpackPr1", "VerPack01"
    put("Modpack_Prova.mrpack", mrpack_bytes(
        "Modpack di Prova", "1.0.0", "Un modpack inventato per la prova automatica dell'app.",
        [
            {"path": "mods/Mod_Fabric_Esempio.jar", "data": read("Mod_Fabric_Esempio.jar"),
             "url": cdn(p, v, "Mod_Fabric_Esempio.jar", source="Mod_Fabric_Esempio.jar")},
            {"path": "mods/Libreria_Esempio.jar", "data": read("Libreria_Esempio.jar"),
             "url": cdn(p, v, "Libreria_Esempio.jar", source="Libreria_Esempio.jar")},
            {"path": "resourcepacks/Texture_Java.zip", "data": read("Texture_Java.zip"), "client": "optional",
             "url": cdn(p, v, "Texture_Java.zip", source="Texture_Java.zip")},
            {"path": "config/prova-modpack.json", "data": config,
             "url": cdn(p, v, "Config_Modpack.json", data=config)},
            {"path": "mods/Solo_Server.jar", "data": solo_server, "client": "unsupported", "server": "required",
             "url": cdn(p, v, "Solo_Server.jar", data=solo_server)},
        ],
        {
            "overrides/config/impostazioni-comuni.txt": b"impostazione comune a tutti\n",
            "client-overrides/options.txt": b"guiScale:2\n",
            "server-overrides/server.properties": b"motd=prova\n",
        }))

    # 2) incompleto: una mod arriva, una libreria non si trova la prima volta (404) ma la seconda sì, e una mod viene servita
    #    alterata (l'impronta pubblicata è un'altra: l'app deve scartarla ogni volta).
    p, v = "ModpackPr2", "VerPack02"
    altered = b"questo non e' il file di cui il modpack ha pubblicato l'impronta"
    put("Modpack_Incompleto.mrpack", mrpack_bytes(
        "Modpack Incompleto", "0.1.0", "Un modpack a cui all'inizio manca qualcosa.",
        [
            {"path": "mods/Mod_Catalogo.jar", "data": read("Mod_Catalogo.jar"),
             "url": cdn(p, v, "Mod_Catalogo.jar", source="Mod_Catalogo.jar")},
            {"path": "mods/Libreria_Catalogo.jar", "data": read("Libreria_Catalogo.jar"),
             "url": cdn(p, v, "Libreria_Catalogo.jar", source="Libreria_Catalogo.jar", mode="fail_first")},
            {"path": "mods/Mod_Impronta_Sbagliata.jar", "data": altered, "declared": read("Mod_Fabric_Esempio.jar"),
             "url": cdn(p, v, "Mod_Impronta_Sbagliata.jar", text=altered.decode("ascii"))},
        ],
        {}))

    # 3) lento: una mod veloce e un file «incagliato» (/stuck.bin del server di prova: quasi fermo), per annullare a metà.
    stuck_declared = b"il contenuto vero non importa: l'installazione viene annullata prima della fine"
    put("Modpack_Lento.mrpack", mrpack_bytes(
        "Modpack Lento", "1.0.0", "Un modpack con un file che non finisce mai di arrivare.",
        [
            {"path": "mods/Mod_Catalogo.jar", "data": read("Mod_Catalogo.jar"),
             "url": cdn("ModpackPr3", "VerPack03", "Mod_Catalogo.jar", source="Mod_Catalogo.jar")},
            {"path": "mods/Lenta.jar", "data": stuck_declared, "size": 2 * 1024 * 1024,
             "url": "http://10.0.2.2:8765/stuck.bin"},
        ],
        {}))

    # 4) un sito che l'utente non ha ancora consentito (https, ma non nell'elenco): serve il consenso. Il nome non esiste mai
    #    (.invalid): dopo il consenso il file non arriva e il modpack risulta incompleto.
    foreign = fabric_mod("altrosito", "Altro Sito", "1.0.0")
    put("Modpack_Altro_Sito.mrpack", mrpack_bytes(
        "Modpack Altro Sito", "1.0.0", "Un modpack che scarica da un sito non ancora consentito.",
        [{"path": "mods/Mod_Altro_Sito.jar", "data": foreign, "url": "https://cdn.modpack-test.invalid/Mod_Altro_Sito.jar"}],
        {}))

    # 5) piccolo e completo: una mod e un'impostazione. Il finto Modrinth lo elenca tra i modpack del catalogo (con Modpack di Prova):
    #    serve a provare in locale OnlineModpackTest (MMI_ONLINE_FAKE) prima di usarlo contro il servizio vero.
    put("Modpack_Piccolo.mrpack", mrpack_bytes(
        "Modpack Piccolo", "1.0.0", "Un modpack con una sola mod.",
        [{"path": "mods/Mod_Catalogo.jar", "data": read("Mod_Catalogo.jar"),
          "url": cdn("ModpackPr4", "VerPack04", "Mod_Catalogo.jar", source="Mod_Catalogo.jar")}],
        {"overrides/config/piccolo.txt": b"piccolo\n"}))

    # 6) i modpack stessi, scaricabili dal DOWNLOAD MANAGER (per il pulsante «APRI NEL MODPACK MANAGER»).
    for name in ("Modpack_Prova.mrpack",):
        serve.append({"path": "/modrinth-cdn/data/ModpackFile/" + name, "source": name, "mode": "normal"})

    with open(os.path.join(out, "modpack-server.json"), "w") as f:
        json.dump({"files": serve}, f, indent=2)


# ---------------------------------------------------------------------------------------------------- mondi Java (WORLD MANAGER)


def _nbt_str(text):
    b = text.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _nbt_tag(tag_type, name):
    return bytes([tag_type]) + _nbt_str(name)


def java_level_dat(name, version="1.20.1", data_version=3465, game_type=0):
    """Un level.dat di Minecraft Java: NBT big-endian compresso con gzip, con tutto dentro il compound «Data»."""
    body = _nbt_tag(10, "") + _nbt_tag(10, "Data")
    body += _nbt_tag(8, "LevelName") + _nbt_str(name)
    body += _nbt_tag(3, "DataVersion") + struct.pack(">i", data_version)
    body += _nbt_tag(10, "Version") + _nbt_tag(8, "Name") + _nbt_str(version) + _nbt_tag(3, "Id") + struct.pack(">i", data_version)
    body += _nbt_tag(1, "Snapshot") + b"\x00" + b"\x00"
    body += _nbt_tag(3, "GameType") + struct.pack(">i", game_type)
    body += _nbt_tag(1, "hardcore") + b"\x00"
    body += b"\x00" + b"\x00"   # fine di «Data» e della radice
    return gzip.compress(body, mtime=0)


def java_world_entries(prefix, name, version="1.20.1", regions=2, region_bytes=8192, seed=1):
    """I file di un mondo Java: regioni del mondo principale, Nether ed End, un giocatore, un data pack, icona e session.lock.
    Le «regioni» sono byte casuali: per l'app conta che si copino identiche, non il loro contenuto."""
    rnd = random.Random(seed)
    p = prefix + "/"
    e = {p + "level.dat": java_level_dat(name, version), p + "session.lock": b"\x00\x00\x00\x00", p + "icon.png": png()}
    for i in range(regions):
        e[p + "region/r.%d.0.mca" % i] = rnd.randbytes(region_bytes)
    e[p + "DIM-1/region/r.0.0.mca"] = rnd.randbytes(4096)
    e[p + "DIM1/region/r.0.0.mca"] = rnd.randbytes(4096)
    e[p + "playerdata/1b2c3d4e-0000-1111-2222-333344445555.dat"] = gzip.compress(b"giocatore di prova", mtime=0)
    e[p + "datapacks/pacchetto_di_prova.zip"] = zip_bytes({"pack.mcmeta": b'{"pack":{"pack_format":15,"description":"prova"}}'})
    return e


def world_samples(out):
    """Mondi per provare il WORLD MANAGER. Il test «EmulatorWorldSamplesTest» li legge con il motore vero (in CI, prima
    dell'emulatore), così le attese dello script ci/worlds_phase.sh non dipendono da come li scrivo qui."""
    # 1) un mondo Java nella sua cartella, con un file fuori dal mondo
    e = java_world_entries("Mondo Java di Prova", "Mondo Java di Prova")
    e["LEGGIMI.txt"] = b"Estrai la cartella in saves.\n"
    write_zip(os.path.join(out, "Mondo_Java.zip"), e)
    # 2) salvato con una versione più recente del profilo di prova (Fabric 1.20.1): l'app deve avvisare
    write_zip(os.path.join(out, "Mondo_Java_Recente.zip"), java_world_entries("Mondo Recente", "Mondo Recente", version="1.21.1", seed=2))
    # 3) abbastanza grande (circa 20 MB in 160 regioni) da poter chiudere l'app a metà dell'importazione
    write_zip(os.path.join(out, "Mondo_Java_Grande.zip"),
              java_world_entries("Mondo Grande", "Mondo Grande", regions=160, region_bytes=128 * 1024, seed=3))

def crash_samples(out):
    """Testi per l'analisi dei crash (DIAGNOSTICA). Le frasi sono quelle che scrive Fabric Loader; le mod nominate sono quelle
    che la fase MOD MANAGER lascia nel profilo Fabric. EmulatorCrashSamplesTest (mod-manager) li analizza prima con il codice vero."""
    report = "\n".join([
        "---- Minecraft Crash Report ----",
        "// Who set us up the TNT?",
        "",
        "Time: 2026-11-05 12:00:00",
        "Description: Mod loading failures have occurred; consult the issue messages for more details",
        "",
        "net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!",
        "More details:",
        "\t - Mod 'Con Dipendenza' (condipendenza) 1.2.0 requires version 3.0.0 or later of mod 'Libreria Esempio' (libesempio), but only the wrong version is present: 2.1.0!",
        "\t - Mod 'Esempio Fabric' (esempiofabric) 1.0.0 requires any version of sodium, which is missing!",
        "\tat net.fabricmc.loader.impl.FormattedException.ofLocalized(FormattedException.java:51)",
        "",
        "-- System Details --",
        "Details:",
        "\tMinecraft Version: 1.20.1",
        "\tJava Version: 17.0.8, Eclipse Adoptium",
        "\tSuspected Mods: Esempio Fabric (esempiofabric)",
        "\tFabric Mods:",
        "\t\tesempiofabric: Esempio Fabric 1.0.0",
        "",
    ])
    with open(os.path.join(out, "Crash_Fabric.txt"), "wb") as f:
        f.write(report.encode("utf-8"))
    log = "[12:00:00] [main/INFO]: Loading Minecraft 1.20.1 with Fabric Loader 0.15.11\n" * 2000
    log += "[12:05:00] [Render thread/ERROR]: Unreported exception thrown!\njava.lang.OutOfMemoryError: Java heap space\n"
    with open(os.path.join(out, "Log_Memoria.log.gz"), "wb") as f:
        # mtime fisso: lo stesso file a ogni esecuzione
        f.write(gzip.compress(log.encode("utf-8"), mtime=0))


def convert_samples(out):
    """Una mod Java con contenuto da portare in Bedrock (JAVA TO BEDROCK): oggetti, blocchi, minerale con generazione, ricette,
    bottini, spada, elmo con il disegno sul corpo, cibo, nomi in inglese e italiano e una creatura GeckoLib. Il test
    «EmulatorConvertSamplesTest» (mod-converter) la converte con il codice vero e controlla le attese di ci/convert_phase.sh."""
    def j(v):
        return json.dumps(v).encode("utf-8")
    ns = "rubini"
    e = {
        "fabric.mod.json": j({"schemaVersion": 1, "id": ns, "version": "1.0.0", "name": "Rubini e Pomodori",
                              "icon": "assets/rubini/icon.png", "environment": "*", "entrypoints": {"main": ["prova.rubini.Main"]},
                              "depends": {"fabricloader": ">=0.15.0", "minecraft": "~1.21.1"}}),
        "prova/rubini/Main.class": class_file(65),
        "assets/rubini/icon.png": png(64, 64, (200, 30, 40), (150, 20, 30)),
        "assets/rubini/lang/en_us.json": j({
            "item.rubini.ruby": "Ruby", "item.rubini.ruby_sword": "Ruby Sword", "item.rubini.ruby_helmet": "Ruby Helmet",
            "item.rubini.tomato": "Tomato", "block.rubini.ruby_block": "Block of Ruby", "block.rubini.ruby_ore": "Ruby Ore",
            "entity.rubini.ladybug": "Ladybug"}),
        "assets/rubini/lang/it_it.json": j({
            "item.rubini.ruby": "Rubino", "item.rubini.ruby_sword": "Spada di rubino", "item.rubini.ruby_helmet": "Elmo di rubino",
            "item.rubini.tomato": "Pomodoro", "block.rubini.ruby_block": "Blocco di rubino", "block.rubini.ruby_ore": "Minerale di rubino",
            "entity.rubini.ladybug": "Coccinella"}),
    }
    red, dark = (200, 30, 40), (120, 10, 20)
    for b in ("ruby_block", "ruby_ore"):
        e["assets/rubini/blockstates/%s.json" % b] = j({"variants": {"": {"model": "rubini:block/" + b}}})
        e["assets/rubini/models/block/%s.json" % b] = j({"parent": "minecraft:block/cube_all", "textures": {"all": "rubini:block/" + b}})
        e["assets/rubini/textures/block/%s.png" % b] = png(16, 16, red, dark) if b == "ruby_block" else png(16, 16, (120, 120, 120), red)
    for i, parent in (("ruby", "item/generated"), ("ruby_sword", "item/handheld"), ("ruby_helmet", "item/generated"), ("tomato", "item/generated")):
        e["assets/rubini/models/item/%s.json" % i] = j({"parent": "minecraft:" + parent, "textures": {"layer0": "rubini:item/" + i}})
        e["assets/rubini/textures/item/%s.png" % i] = png(16, 16, red, dark)
    e["assets/rubini/models/item/ruby_block.json"] = j({"parent": "rubini:block/ruby_block"})
    e["assets/rubini/textures/models/armor/ruby_layer_1.png"] = png(64, 32, red, dark)
    e["data/minecraft/tags/item/swords.json"] = j({"values": ["rubini:ruby_sword"]})
    e["data/minecraft/tags/item/head_armor.json"] = j({"values": ["rubini:ruby_helmet"]})
    e["data/c/tags/item/foods/vegetable.json"] = j({"values": ["rubini:tomato"]})
    e["data/minecraft/tags/block/mineable/pickaxe.json"] = j({"values": ["rubini:ruby_block", "rubini:ruby_ore"]})
    e["data/rubini/recipe/ruby_block.json"] = j({"type": "minecraft:crafting_shaped", "pattern": ["RRR", "RRR", "RRR"],
                                                  "key": {"R": "rubini:ruby"}, "result": {"id": "rubini:ruby_block"}})
    e["data/rubini/recipe/ruby_sword.json"] = j({"type": "minecraft:crafting_shaped", "pattern": ["R", "R", "S"],
                                                  "key": {"R": "rubini:ruby", "S": "#c:rods/wooden"}, "result": {"id": "rubini:ruby_sword"}})
    e["data/rubini/recipe/ruby_from_ore.json"] = j({"type": "minecraft:smelting", "ingredient": "rubini:ruby_ore",
                                                     "result": {"id": "rubini:ruby"}, "experience": 1.0, "cookingtime": 200})
    e["data/rubini/recipe/infusion.json"] = j({"type": "rubini:infusion", "input": "rubini:ruby", "result": "rubini:tomato"})
    e["data/rubini/loot_table/blocks/ruby_ore.json"] = j({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
        {"type": "minecraft:item", "name": "rubini:ruby", "functions": [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 2}}]}]}]})
    e["data/rubini/worldgen/configured_feature/ore_ruby.json"] = j({"type": "minecraft:ore", "config": {"size": 5, "targets": [
        {"target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"}, "state": {"Name": "rubini:ruby_ore"}}]}})
    e["data/rubini/worldgen/placed_feature/ore_ruby.json"] = j({"feature": "rubini:ore_ruby", "placement": [
        {"type": "minecraft:count", "count": 6}, {"type": "minecraft:in_square"},
        {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": {"absolute": -32}, "max_inclusive": {"absolute": 48}}},
        {"type": "minecraft:biome"}]})
    e["assets/rubini/geo/entity/ladybug.geo.json"] = j({"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.rubini.ladybug", "texture_width": 32, "texture_height": 32},
        "bones": [{"name": "body", "pivot": [0, 0, 0], "cubes": [{"origin": [-3, 0, -4], "size": [6, 3, 8], "uv": [0, 0]}]}]}]})
    e["assets/rubini/animations/ladybug.animation.json"] = j({"format_version": "1.8.0", "animations": {
        "animation.ladybug.walk": {"loop": True, "bones": {"body": {"rotation": {"0.0": {"vector": [0, 0, 0], "easing": "linear"}, "0.5": [0, 5, 0]}}}},
        "animation.ladybug.idle": {"loop": True, "bones": {"body": {"position": [0, 0, 0]}}}}})
    e["assets/rubini/textures/entity/ladybug.png"] = png(32, 32, red, (20, 20, 20))
    write_zip(os.path.join(out, "Mod_Da_Convertire.jar"), e)


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
    modpack_samples(out)
    world_samples(out)
    crash_samples(out)
    convert_samples(out)

    for n in sorted(os.listdir(out)):
        print("%-40s %8d byte" % (n, os.path.getsize(os.path.join(out, n))))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "samples")
