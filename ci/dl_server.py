#!/usr/bin/env python3
"""Piccolo server HTTP per la prova del DOWNLOAD MANAGER sull'emulatore Android (solo libreria standard).

L'emulatore raggiunge il computer che lo ospita all'indirizzo 10.0.2.2, quindi l'app (build di prova) scarica da
http://10.0.2.2:8765/... Il server serve file con ETag e Last-Modified, supporta «Range» e «If-Range» come un server
vero e registra ogni richiesta, così lo script può controllare COSA ha chiesto l'app (ripresa con Range, tentativi).

Percorsi:
  /ok.mcpack, /wrong-hash.mcpack   il texture pack di prova (veloce); stessi byte, nomi diversi
  /hop.mcpack                      risponde 302 verso /ok.mcpack (reindirizzamento sullo stesso sito)
  /slow.bin, /slow2.bin            2 MiB lenti: i primi 320 KiB subito, poi 64 KiB/s; una ripresa (Range) va veloce
  /drop.bin                        3 MiB: la PRIMA richiesta senza Range si interrompe a 1 MiB (rete che cade)
  /gone.bin                        404
  /away.mcpack                     302 verso un sito https non consentito (.invalid non esiste mai)

Uso: dl_server.py --port 8765 --payload CARTELLA/Texture_Verde.mcpack --out CARTELLA-DI-USCITA
Scrive in --out: dl-expected.txt (NOME DIMENSIONE SHA256), dl-server.log (una riga JSON per evento).
"""
import argparse
import hashlib
import json
import random
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HEAD_FAST = 320 * 1024
SLOW_CHUNK = 16 * 1024
SLOW_PAUSE = 0.25
DROP_AT = 1024 * 1024
LAST_MODIFIED = "Tue, 03 Nov 2026 10:00:00 GMT"
AWAY = "https://cdn.consent-test.invalid/away.mcpack"

LOG = None
LOCK = threading.Lock()
FILES = {}
DROPPED = set()


def deterministic(size, seed):
    return random.Random(seed).getrandbits(size * 8).to_bytes(size, "big")


def log(**event):
    event["t"] = round(time.time(), 3)
    line = json.dumps(event, sort_keys=True)
    with LOCK:
        LOG.write(line + "\n")
        LOG.flush()


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
        path = self.path.split("?")[0]
        rng = self.headers.get("Range")
        ifr = self.headers.get("If-Range")
        log(event="start", path=path, range=rng, if_range=ifr)
        sent = 0
        status = 0
        try:
            status, sent = self.serve(path, rng, ifr)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            status = status or -1
        finally:
            log(event="end", path=path, range=rng, status=status, sent=sent)
        self.close_connection = True

    def serve(self, path, rng, ifr):
        if path == "/gone.bin":
            self.reply(404, body=b"no")
            return 404, 0
        if path == "/away.mcpack":
            self.reply(302, {"Location": AWAY})
            return 302, 0
        if path == "/hop.mcpack":
            self.reply(302, {"Location": "/ok.mcpack"})
            return 302, 0
        f = FILES.get(path)
        if f is None:
            self.reply(404, body=b"no")
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
        slow = mode == "slow" and not partial
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
                step = SLOW_CHUNK
            if drop:
                step = min(step, DROP_AT - sent)
            chunk = data[pos:min(pos + step, limit)]
            self.wfile.write(chunk)
            self.wfile.flush()
            pos += len(chunk)
            sent += len(chunk)
            if slow and sent >= HEAD_FAST:
                time.sleep(SLOW_PAUSE)
        return status, sent


def main():
    global LOG
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--payload", required=True, help="file servito come ok.mcpack")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    pack = open(args.payload, "rb").read()
    big = deterministic(2 * 1024 * 1024, 20261104)
    drop = deterministic(3 * 1024 * 1024, 20261105)
    for path, data, mode in (
        ("/ok.mcpack", pack, "normal"),
        ("/wrong-hash.mcpack", pack, "normal"),
        ("/slow.bin", big, "slow"),
        ("/slow2.bin", big, "slow"),
        ("/drop.bin", drop, "drop"),
    ):
        FILES[path] = {"data": data, "mode": mode, "etag": '"' + hashlib.sha256(data).hexdigest()[:16] + '"'}

    with open(args.out + "/dl-expected.txt", "w") as out:
        for path, f in FILES.items():
            out.write("%s %d %s\n" % (path.lstrip("/"), len(f["data"]), hashlib.sha256(f["data"]).hexdigest()))
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
