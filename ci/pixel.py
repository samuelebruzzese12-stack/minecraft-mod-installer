#!/usr/bin/env python3
"""Legge il colore di un pixel di un PNG (8 bit, RGB o RGBA, senza interlacciamento), solo libreria standard.

Uso: pixel.py FILE X Y   → stampa «R G B» (0-255). Serve a verificare che il tema scuro/chiaro sia davvero applicato.
"""
import struct
import sys
import zlib


def pixel(path, x, y):
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("non è un PNG")
    pos, idat = 8, b""
    width = height = depth = ctype = interlace = None
    while pos < len(data):
        length, = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, ctype, _c, _f, interlace = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if depth != 8 or ctype not in (2, 6) or interlace != 0:
        raise ValueError("formato PNG non gestito (profondità %s, tipo %s)" % (depth, ctype))
    if not (0 <= x < width and 0 <= y < height):
        raise ValueError("pixel fuori dall'immagine (%sx%s)" % (width, height))
    bpp = 3 if ctype == 2 else 4
    raw = zlib.decompress(idat)
    stride = width * bpp
    prev = bytearray(stride)
    i = 0
    for row in range(y + 1):
        ftype = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        if ftype == 1:
            for k in range(bpp, stride):
                line[k] = (line[k] + line[k - bpp]) & 255
        elif ftype == 2:
            for k in range(stride):
                line[k] = (line[k] + prev[k]) & 255
        elif ftype == 3:
            for k in range(stride):
                left = line[k - bpp] if k >= bpp else 0
                line[k] = (line[k] + ((left + prev[k]) >> 1)) & 255
        elif ftype == 4:
            for k in range(stride):
                a = line[k - bpp] if k >= bpp else 0
                b = prev[k]
                c = prev[k - bpp] if k >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[k] = (line[k] + pr) & 255
        elif ftype != 0:
            raise ValueError("filtro PNG sconosciuto %s" % ftype)
        prev = line
    off = x * bpp
    return prev[off], prev[off + 1], prev[off + 2]


if __name__ == "__main__":
    r, g, b = pixel(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]))
    print(r, g, b)
