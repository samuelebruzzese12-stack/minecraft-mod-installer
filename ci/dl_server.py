#!/usr/bin/env python3
"""Piccolo server HTTP per la prova del DOWNLOAD MANAGER sull'emulatore Android (solo libreria standard).

L'emulatore raggiunge il computer che lo ospita all'indirizzo 10.0.2.2, quindi l'app (build di prova) scarica da
http://10.0.2.2:8765/... Il server serve file con ETag e Last-Modified, supporta «Range» e «If-Range» come un server
vero e registra ogni richiesta, così lo script può controllare COSA ha chiesto l'app (ripresa con Range, tentativi).

Percorsi:
  /ok.mcpack, /wrong-hash.mcpack   il texture pack di prova (veloce); stessi byte, nomi diversi
  /mod.jar                         (solo con --jar) una mod Java di prova, per il passaggio al MOD MANAGER
  /hop.mcpack                      risponde 302 verso /ok.mcpack (reindirizzamento sullo stesso sito)
  /slow.bin                        2 MiB lenti: i primi 320 KiB subito, poi 32 KiB/s; una ripresa (Range) va veloce
  /slow2.bin, /stuck.bin           2 MiB «incagliati»: i primi 320 KiB subito, poi 512 byte ogni 2 secondi, cioè quasi fermi ma
                                   senza mai tacere abbastanza da far scattare il tempo massimo dell'app; una ripresa (Range)
                                   va veloce. Servono a tenere un download «In corso» per tutto il tempo che serve a un
                                   emulatore lento (la prova non può dipendere dalla velocità con cui tocca i pulsanti)
  /drop.bin                        3 MiB: la PRIMA richiesta senza Range si interrompe a 1 MiB (rete che cade)
  /gone.bin                        404
  /away.mcpack                     302 verso un sito https non consentito (.invalid non esiste mai)

Con --modrinth CARTELLA (la cartella dei file di prova) imita anche l'API pubblica di Modrinth (v2) e il suo CDN, con
pochi progetti inventati, per provare il catalogo dell'app senza internet (vedi «finto Modrinth» più sotto):
  /modrinth/v2/search, /projects, /project/<id>/version, /versions        l'API
  /modrinth-cdn/data/<progetto>/versions/<versione>/<file>                i file (al posto di cdn.modrinth.com)
  e, se nella stessa cartella c'è modpack-server.json (lo scrive ci/make_samples.py), i file dei modpack di prova: ognuno con il
  suo percorso e il suo modo («normal»; «fail_first»: la PRIMA richiesta risponde 404, le successive servono il file)

Uso: dl_server.py --port 8765 --payload CARTELLA/Texture_Verde.mcpack [--jar CARTELLA/Mod_Scaricata.jar]
                  [--modrinth CARTELLA] --out CARTELLA-DI-USCITA
Scrive in --out: dl-expected.txt (NOME DIMENSIONE SHA256), dl-server.log (una riga JSON per evento: «start» con percorso,
parametri della richiesta e User-Agent; «end» con esito), e con --modrinth anche modrinth-expected.txt.
"""
import argparse
import base64
import hashlib
import json
import os
import random
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HEAD_FAST = 320 * 1024
SLOW_CHUNK = 16 * 1024
SLOW_PAUSE = 0.5
STUCK_CHUNK = 512
STUCK_PAUSE = 2.0
DROP_AT = 1024 * 1024
LAST_MODIFIED = "Tue, 03 Nov 2026 10:00:00 GMT"
AWAY = "https://cdn.consent-test.invalid/away.mcpack"

LOG = None
LOCK = threading.Lock()
FILES = {}
DROPPED = set()
FAILED_ONCE = set()


def deterministic(size, seed):
    return random.Random(seed).getrandbits(size * 8).to_bytes(size, "big")


def log(**event):
    event["t"] = round(time.time(), 3)
    line = json.dumps(event, sort_keys=True)
    with LOCK:
        LOG.write(line + "\n")
        LOG.flush()


# ---------------------------------------------------------------------------------------------------- finto Modrinth
#
# Imita l'API pubblica di Modrinth (v2, https://docs.modrinth.com/api/) con pochi progetti inventati. I campi e le forme
# delle risposte sono quelli dello schema ufficiale (per la ricerca «project_id», «versions», «follows»; per i progetti «id»,
# «game_versions», «followers»; per le versioni «files[].hashes», «dependencies[].dependency_type»…). I filtri funzionano
# come quelli veri: le «facets» sono gruppi in E, i valori dentro un gruppo in O; «loaders» e «game_versions» delle versioni
# sono elenchi JSON. Due ricerche speciali provocano errori: «errore500» e «limite429» (con Retry-After: 7).

MR_API = "/modrinth/v2"
MR_CDN = "/modrinth-cdn"
MR_PROJECTS = []
MR_VERSIONS = []
MR_SERVED = []  # (nome, SHA-256, dimensione) dei file che il CDN finto serve


def mr_day(n):
    return "2026-10-%02dT10:00:00Z" % n


def build_modrinth(directory):
    """Registra progetti, versioni e file. I file sono quelli creati da ci/make_samples.py (stessa cartella)."""
    def blob(name):
        with open(os.path.join(directory, name), "rb") as f:
            return f.read()

    def project(pid, slug, title, description, author, downloads, ptype, loaders, day):
        MR_PROJECTS.append({"id": pid, "slug": slug, "title": title, "description": description, "author": author,
                            "downloads": downloads, "ptype": ptype, "loaders": loaders, "games": [], "created": mr_day(day)})

    def version(vid, pid, number, vtype, games, loaders, files, deps, day):
        """files: [(nome, byte, byte-dichiarati)]: i byte dichiarati danno l'impronta pubblicata; se differiscono da quelli
        serviti il file è «alterato» e l'app deve rifiutarlo. deps: [(tipo, progetto)]."""
        entries = []
        for i, (fname, data, declared) in enumerate(files):
            path = "%s/data/%s/versions/%s/%s" % (MR_CDN, pid, vid, fname)
            FILES[path] = {"data": data, "mode": "normal", "etag": '"' + hashlib.sha256(data).hexdigest()[:16] + '"'}
            MR_SERVED.append((fname, hashlib.sha256(data).hexdigest(), len(data)))
            entries.append({"path": path, "filename": fname, "primary": i == 0, "size": len(data),
                            "sha1": hashlib.sha1(declared).hexdigest(), "sha512": hashlib.sha512(declared).hexdigest()})
        MR_VERSIONS.append({"id": vid, "project_id": pid, "number": number, "type": vtype, "games": games, "loaders": loaders,
                            "files": entries, "deps": deps, "published": mr_day(day)})
        owner = next(p for p in MR_PROJECTS if p["id"] == pid)
        for g in games:
            if g not in owner["games"]:
                owner["games"].append(g)

    fabric, forge = blob("Mod_Fabric_Esempio.jar"), blob("Mod_Forge_Esempio.jar")
    catalog_mod, catalog_lib, texture = blob("Mod_Catalogo.jar"), blob("Libreria_Catalogo.jar"), blob("Texture_Catalogo.zip")
    dep_mod, example_lib = blob("Mod_Con_Dipendenza.jar"), blob("Libreria_Esempio.jar")
    altered = b"questo non e' il contenuto che Modrinth ha pubblicato"

    # Una mod con una libreria obbligatoria, una dipendenza facoltativa e una incompatibile; la versione beta è la più
    # recente ma l'app deve preferire la stabile; la 1.0.0 è per un'altra versione di Minecraft.
    project("ModCatal1", "mod-dal-catalogo", "Mod dal Catalogo", "Mod di prova che richiede una libreria.",
            "Autore Uno", 2500000, "mod", ["fabric"], 4)
    version("VerModC11", "ModCatal1", "1.5.0", "release", ["1.20.1"], ["fabric"], [("Mod_Catalogo.jar", catalog_mod, catalog_mod)],
            [("required", "LibCatal1"), ("optional", "ExtraOpz1"), ("incompatible", "SoloForg1")], 3)
    version("VerModC12", "ModCatal1", "2.0.0-beta", "beta", ["1.20.1"], ["fabric"],
            [("Mod_Catalogo-2.0.0-beta.jar", fabric, fabric)], [("required", "LibCatal1")], 8)
    version("VerModC13", "ModCatal1", "1.0.0", "release", ["1.19.2"], ["fabric"],
            [("Mod_Catalogo-1.0.0.jar", fabric, fabric)], [], 1)

    project("LibCatal1", "libreria-dal-catalogo", "Libreria dal Catalogo", "La libreria che serve a «Mod dal Catalogo».",
            "Autore Due", 41000, "mod", ["fabric"], 2)
    version("VerLibC11", "LibCatal1", "1.2.0", "release", ["1.20.1"], ["fabric", "quilt"],
            [("Libreria_Catalogo.jar", catalog_lib, catalog_lib)], [], 2)

    project("ExtraOpz1", "extra-opzionale", "Extra Opzionale", "Un'aggiunta facoltativa.", "Autore Tre", 900, "mod", ["fabric"], 5)
    version("VerExtr11", "ExtraOpz1", "0.3.0", "release", ["1.20.1"], ["fabric"], [("Extra_Opzionale.jar", fabric, fabric)], [], 5)

    project("SoloForg1", "mod-solo-forge", "Mod Solo Forge", "Esiste solo per Forge.", "Autore Quattro", 77000, "mod", ["forge"], 6)
    version("VerSolo11", "SoloForg1", "3.1.0", "release", ["1.20.1"], ["forge"], [("Mod_Solo_Forge.jar", forge, forge)], [], 6)

    # Il file servito non è quello di cui Modrinth ha pubblicato l'impronta: l'app deve scartarlo.
    project("ImprSbag1", "impronta-sbagliata", "Mod con Impronta Sbagliata", "Il file non corrisponde all'impronta pubblicata.",
            "Autore Cinque", 15, "mod", ["fabric"], 7)
    version("VerImpr11", "ImprSbag1", "1.0.0", "release", ["1.20.1"], ["fabric"], [("Mod_Impronta_Sbagliata.jar", fabric, altered)], [], 7)

    # Gli stessi file che la fase MOD MANAGER ha già installato: il piano deve dire che sono già nel profilo.
    project("ConLibr01", "mod-con-libreria", "Mod con Libreria", "Mod già presente nel profilo di prova.",
            "Autore Sei", 1234, "mod", ["fabric"], 3)
    version("VerConL11", "ConLibr01", "1.2.0", "release", ["1.20.1"], ["fabric"], [("Mod_Con_Dipendenza.jar", dep_mod, dep_mod)],
            [("required", "LibEsemp1")], 3)
    project("LibEsemp1", "libreria-esempio", "Libreria Esempio", "La libreria della mod già presente.", "Autore Sei", 56, "mod", ["fabric"], 3)
    version("VerLibE11", "LibEsemp1", "2.1.0", "release", ["1.20.1"], ["fabric"], [("Libreria_Esempio.jar", example_lib, example_lib)], [], 3)

    # Un resource pack: niente caricatore.
    project("TexCatal1", "texture-dal-catalogo", "Texture dal Catalogo", "Un resource pack di prova.", "Autore Sette",
            123456, "resourcepack", [], 9)
    version("VerTexC11", "TexCatal1", "1.0.0", "release", ["1.20.1"], ["minecraft"], [("Texture_Catalogo.zip", texture, texture)], [], 9)


def build_modpacks(directory):
    """Registra i file dei modpack di prova: ci/make_samples.py scrive in modpack-server.json cosa servire a quale percorso
    («source»: un file della cartella; «b64» o «text»: i byte scritti lì; «mode»). Senza quel file non fa nulla."""
    spec_path = os.path.join(directory, "modpack-server.json")
    if not os.path.isfile(spec_path):
        return
    with open(spec_path) as f:
        spec = json.load(f)
    for e in spec["files"]:
        if "source" in e:
            with open(os.path.join(directory, e["source"]), "rb") as src:
                data = src.read()
        elif "b64" in e:
            data = base64.b64decode(e["b64"])
        else:
            data = e["text"].encode("utf-8")
        digest = hashlib.sha256(data).hexdigest()
        FILES[e["path"]] = {"data": data, "mode": e.get("mode", "normal"), "etag": '"' + digest[:16] + '"'}


def mr_hit(p):
    return {"project_id": p["id"], "project_type": p["ptype"], "slug": p["slug"], "author": p["author"], "title": p["title"],
            "description": p["description"], "categories": p["loaders"], "display_categories": p["loaders"],
            "versions": p["games"], "downloads": p["downloads"], "follows": p["downloads"] // 20, "icon_url": "",
            "date_created": p["created"], "date_modified": p["created"], "latest_version": p["games"][-1],
            "license": "MIT", "client_side": "required", "server_side": "required"}


def mr_project(p):
    return {"id": p["id"], "slug": p["slug"], "project_type": p["ptype"], "team": "Squadra" + p["id"], "title": p["title"],
            "description": p["description"], "categories": p["loaders"], "loaders": p["loaders"], "game_versions": p["games"],
            "downloads": p["downloads"], "followers": p["downloads"] // 20, "icon_url": None, "status": "approved",
            "published": p["created"], "updated": p["created"]}


def mr_version(v, host):
    return {"id": v["id"], "project_id": v["project_id"], "author_id": "AutoreProva", "name": "Versione " + v["number"],
            "version_number": v["number"], "changelog": "", "game_versions": v["games"], "version_type": v["type"],
            "loaders": v["loaders"], "featured": v["type"] == "release", "status": "listed", "requested_status": None,
            "date_published": v["published"], "downloads": 100,
            "dependencies": [{"version_id": None, "project_id": pid, "file_name": None, "dependency_type": kind}
                             for kind, pid in v["deps"]],
            "files": [{"hashes": {"sha1": f["sha1"], "sha512": f["sha512"]}, "url": "http://%s%s" % (host, f["path"]),
                       "filename": f["filename"], "primary": f["primary"], "size": f["size"], "file_type": None}
                      for f in v["files"]]}


def mr_find(ident):
    return next((p for p in MR_PROJECTS if ident in (p["id"], p["slug"])), None)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MMI-test"

    def log_message(self, fmt, *args):  # niente rumore sul terminale
        pass

    def reply(self, status, headers=None, body=b""):
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        path, _, query = self.path.partition("?")
        rng = self.headers.get("Range")
        ifr = self.headers.get("If-Range")
        log(event="start", path=path, query=query, range=rng, if_range=ifr, ua=self.headers.get("User-Agent"))
        sent = 0
        status = 0
        try:
            status, sent = self.serve(path, rng, ifr, query)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            status = status or -1
        finally:
            log(event="end", path=path, range=rng, status=status, sent=sent)
        self.close_connection = True

    def serve(self, path, rng, ifr, query=""):
        if path == "/gone.bin":
            self.reply(404, body=b"no")
            return 404, 0
        if path == "/away.mcpack":
            self.reply(302, {"Location": AWAY})
            return 302, 0
        if path == "/hop.mcpack":
            self.reply(302, {"Location": "/ok.mcpack"})
            return 302, 0
        if path.startswith(MR_API + "/"):
            return self.modrinth(path[len(MR_API):], query)
        f = FILES.get(path)
        if f is None:
            self.reply(404, body=b"no")
            return 404, 0
        if f["mode"] == "fail_first":
            with LOCK:
                first = path not in FAILED_ONCE
                FAILED_ONCE.add(path)
            if first:
                self.reply(404, body=b"non ancora")
                return 404, 0
        data = f["data"]
        size = len(data)
        start = 0
        end = size - 1
        partial = False
        if rng and (ifr is None or ifr in (f["etag"], LAST_MODIFIED)):
            try:
                unit, _, spec = rng.partition("=")
                first, _, last = spec.partition("-")
                start = int(first)
                if last:
                    end = min(int(last), size - 1)
                if unit.strip().lower() != "bytes" or start < 0:
                    raise ValueError
            except ValueError:
                self.reply(416, {"Content-Range": "bytes */%d" % size})
                return 416, 0
            if start >= size:
                self.reply(416, {"Content-Range": "bytes */%d" % size})
                return 416, 0
            partial = True
        length = end - start + 1
        headers = {
            "Content-Type": "application/octet-stream",
            "ETag": f["etag"],
            "Last-Modified": LAST_MODIFIED,
            "Accept-Ranges": "bytes",
        }
        if partial:
            headers["Content-Range"] = "bytes %d-%d/%d" % (start, end, size)
        status = 206 if partial else 200
        self.send_response(status)
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(length))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.flush()

        mode = f["mode"]
        sent = 0
        pos = start
        limit = end + 1
        slow = mode in ("slow", "stuck") and not partial
        stuck = mode == "stuck" and not partial
        drop = False
        if mode == "drop" and not partial:
            with LOCK:
                if path not in DROPPED:
                    DROPPED.add(path)
                    drop = True
        while pos < limit:
            if drop and sent >= DROP_AT:
                self.wfile.flush()
                return status, sent  # chiude senza aver mandato tutto: la rete «cade»
            step = 64 * 1024
            if slow and sent >= HEAD_FAST:
                step = STUCK_CHUNK if stuck else SLOW_CHUNK
            if drop:
                step = min(step, DROP_AT - sent)
            chunk = data[pos:min(pos + step, limit)]
            self.wfile.write(chunk)
            self.wfile.flush()
            pos += len(chunk)
            sent += len(chunk)
            if slow and sent >= HEAD_FAST:
                time.sleep(STUCK_PAUSE if stuck else SLOW_PAUSE)
        return status, sent


    def modrinth(self, sub, query):
        """Le risposte del finto Modrinth. Restituisce (stato, byte inviati) come serve()."""
        host = self.headers.get("Host") or "10.0.2.2:8765"

        def send(status, body, extra=None):
            raw = json.dumps(body).encode("utf-8")
            headers = {"Content-Type": "application/json"}
            headers.update(extra or {})
            self.reply(status, headers, raw)
            return status, len(raw)

        def failure(status, error, description, extra=None):
            return send(status, {"error": error, "description": description}, extra)

        try:
            params = urllib.parse.parse_qs(query, keep_blank_values=True)

            def one(name, default=""):
                return (params.get(name) or [default])[0]

            def json_list(name):
                if name not in params:
                    return None
                value = json.loads(params[name][0])
                if not isinstance(value, list):
                    raise ValueError(name)
                return value

            if sub == "/search":
                text = one("query").strip().lower()
                if text == "errore500":
                    return failure(500, "internal_error", "errore di prova")
                if text == "limite429":
                    return failure(429, "ratelimit_error", "troppe richieste", {"Retry-After": "7"})
                groups = json_list("facets") or []
                if not all(isinstance(g, list) and all(isinstance(x, str) for x in g) for g in groups):
                    raise ValueError("facets")
                offset = max(0, int(one("offset", "0")))
                limit = min(100, max(1, int(one("limit", "10"))))
                hits = []
                for p in MR_PROJECTS:
                    if text and not (text in p["title"].lower() or text in p["slug"] or text in p["description"].lower()):
                        continue
                    facts = {"project_type:" + p["ptype"]} | {"categories:" + x for x in p["loaders"]} | {"versions:" + x for x in p["games"]}
                    if all(any(x in facts for x in g) for g in groups):
                        hits.append(p)
                index = one("index", "relevance")
                if index == "downloads":
                    hits.sort(key=lambda p: -p["downloads"])
                elif index in ("newest", "updated"):
                    hits.sort(key=lambda p: p["created"], reverse=True)
                return send(200, {"hits": [mr_hit(p) for p in hits[offset:offset + limit]], "offset": offset, "limit": limit,
                                  "total_hits": len(hits)})

            if sub == "/projects":
                ids = json_list("ids") or []
                found = [mr_find(i) for i in ids if isinstance(i, str)]
                return send(200, [mr_project(p) for p in found if p is not None])

            if sub == "/versions":
                ids = json_list("ids") or []
                return send(200, [mr_version(v, host) for v in MR_VERSIONS if v["id"] in ids])

            parts = sub.strip("/").split("/")
            if len(parts) == 3 and parts[0] == "project" and parts[2] == "version":
                owner = mr_find(urllib.parse.unquote(parts[1]))
                if owner is None:
                    return failure(404, "not_found", "The requested item(s) were not found or no authorization to access the requested item(s)")
                loaders, games = json_list("loaders"), json_list("game_versions")
                chosen = [v for v in MR_VERSIONS if v["project_id"] == owner["id"]
                          and (not loaders or any(x in v["loaders"] for x in loaders))
                          and (not games or any(x in v["games"] for x in games))]
                chosen.sort(key=lambda v: v["published"], reverse=True)
                return send(200, [mr_version(v, host) for v in chosen])
        except (ValueError, TypeError, KeyError):
            return failure(400, "invalid_input", "Error while parsing the request")
        return failure(404, "not_found", "the requested route does not exist")


def main():
    global LOG
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--payload", required=True, help="file servito come ok.mcpack")
    ap.add_argument("--jar", help="file servito come mod.jar (facoltativo)")
    ap.add_argument("--modrinth", help="cartella dei file di prova (ci/make_samples.py): abilita il finto Modrinth")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    pack = open(args.payload, "rb").read()
    big = deterministic(2 * 1024 * 1024, 20261104)
    drop = deterministic(3 * 1024 * 1024, 20261105)
    for path, data, mode in (
        ("/ok.mcpack", pack, "normal"),
        ("/wrong-hash.mcpack", pack, "normal"),
        ("/slow.bin", big, "slow"),
        ("/slow2.bin", big, "stuck"),
        ("/stuck.bin", big, "stuck"),
        ("/drop.bin", drop, "drop"),
    ) + ((("/mod.jar", open(args.jar, "rb").read(), "normal"),) if args.jar else ()):
        FILES[path] = {"data": data, "mode": mode, "etag": '"' + hashlib.sha256(data).hexdigest()[:16] + '"'}

    with open(args.out + "/dl-expected.txt", "w") as out:
        for path, f in FILES.items():
            out.write("%s %d %s\n" % (path.lstrip("/"), len(f["data"]), hashlib.sha256(f["data"]).hexdigest()))
    if args.modrinth:
        build_modrinth(args.modrinth)
        build_modpacks(args.modrinth)
        with open(args.out + "/modrinth-expected.txt", "w") as out:
            for name, digest, size in MR_SERVED:
                out.write("%s %d %s\n" % (name, size, digest))
    LOG = open(args.out + "/dl-server.log", "a")

    # Solo su questo computer: l'emulatore lo raggiunge come 10.0.2.2.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print("IN ASCOLTO su 127.0.0.1:%d" % args.port, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
