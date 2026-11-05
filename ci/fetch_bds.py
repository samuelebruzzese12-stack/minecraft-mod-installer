#!/usr/bin/env python3
"""Scarica il server Bedrock ufficiale di Mojang (Bedrock Dedicated Server, Linux) e lo estrae in CARTELLA.

Uso: python3 -I ci/fetch_bds.py CARTELLA

Serve alle prove nel gioco vero di ci/bds_test.py. L'indirizzo del file si legge dal servizio dei download di Minecraft
(lo stesso della pagina ufficiale, che vuole un browser come «User-Agent»). Usare il server Bedrock implica accettare
l'EULA di Minecraft e l'informativa sulla privacy di Microsoft.
"""
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile

LINKS = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links"
BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"


def get(url, timeout=600):
    req = urllib.request.Request(url, headers={"User-Agent": BROWSER_UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def main():
    dest = sys.argv[1]
    links = json.loads(get(LINKS, 120))
    url = next(l["downloadUrl"] for l in links["result"]["links"] if l["downloadType"] == "serverBedrockLinux")
    data = get(url)
    print("server Bedrock: %s (%d byte, sha256 %s)" % (url.rsplit("/", 1)[-1], len(data), hashlib.sha256(data).hexdigest()))
    os.makedirs(dest, exist_ok=True)
    root = os.path.realpath(dest)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for info in z.infolist():
            target = os.path.realpath(os.path.join(dest, info.filename))
            if not target.startswith(root + os.sep) and target != root:
                raise SystemExit("percorso non ammesso nello zip: " + info.filename)
            if info.is_dir():
                os.makedirs(target, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                out.write(src.read())
    os.chmod(os.path.join(dest, "bedrock_server"), 0o755)


if __name__ == "__main__":
    main()
