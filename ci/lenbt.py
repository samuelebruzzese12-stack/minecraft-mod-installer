"""NBT little-endian di Bedrock: lettura e scrittura (level.dat, .mcstructure). Solo per prove e CI."""
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Tag:
    def __init__(self, t, v):
        self.t, self.v = t, v

    def __repr__(self):
        return "Tag(%d,%r)" % (self.t, self.v)


def _rs(b, o):
    n = struct.unpack_from("<H", b, o)[0]
    return b[o + 2:o + 2 + n].decode("utf-8", "replace"), o + 2 + n


def _read(t, b, o):
    if t == BYTE: return struct.unpack_from("<b", b, o)[0], o + 1
    if t == SHORT: return struct.unpack_from("<h", b, o)[0], o + 2
    if t == INT: return struct.unpack_from("<i", b, o)[0], o + 4
    if t == LONG: return struct.unpack_from("<q", b, o)[0], o + 8
    if t == FLOAT: return struct.unpack_from("<f", b, o)[0], o + 4
    if t == DOUBLE: return struct.unpack_from("<d", b, o)[0], o + 8
    if t == BYTE_ARRAY:
        n = struct.unpack_from("<i", b, o)[0]; return bytes(b[o + 4:o + 4 + n]), o + 4 + n
    if t == STRING: return _rs(b, o)
    if t == LIST:
        et = b[o]; n = struct.unpack_from("<i", b, o + 1)[0]; o += 5; out = []
        for _ in range(n):
            v, o = _read(et, b, o); out.append(Tag(et, v))
        return (et, out), o
    if t == COMPOUND:
        out = {}
        while True:
            tt = b[o]; o += 1
            if tt == END: return out, o
            name, o = _rs(b, o)
            v, o = _read(tt, b, o)
            out[name] = Tag(tt, v)
    if t == INT_ARRAY:
        n = struct.unpack_from("<i", b, o)[0]; return list(struct.unpack_from("<%di" % n, b, o + 4)), o + 4 + 4 * n
    if t == LONG_ARRAY:
        n = struct.unpack_from("<i", b, o)[0]; return list(struct.unpack_from("<%dq" % n, b, o + 4)), o + 4 + 8 * n
    raise ValueError("tag %d" % t)


def _ws(s):
    e = s.encode("utf-8"); return struct.pack("<H", len(e)) + e


def _write(tag):
    t, v = tag.t, tag.v
    if t == BYTE: return struct.pack("<b", v)
    if t == SHORT: return struct.pack("<h", v)
    if t == INT: return struct.pack("<i", v)
    if t == LONG: return struct.pack("<q", v)
    if t == FLOAT: return struct.pack("<f", v)
    if t == DOUBLE: return struct.pack("<d", v)
    if t == BYTE_ARRAY: return struct.pack("<i", len(v)) + bytes(v)
    if t == STRING: return _ws(v)
    if t == LIST:
        et, items = v; return bytes([et]) + struct.pack("<i", len(items)) + b"".join(_write(i) for i in items)
    if t == COMPOUND:
        return b"".join(bytes([x.t]) + _ws(k) + _write(x) for k, x in v.items()) + bytes([END])
    if t == INT_ARRAY: return struct.pack("<i", len(v)) + struct.pack("<%di" % len(v), *v)
    if t == LONG_ARRAY: return struct.pack("<i", len(v)) + struct.pack("<%dq" % len(v), *v)
    raise ValueError(t)


def read_root(b, o=0):
    t = b[o]; name, o = _rs(b, o + 1); v, o = _read(t, b, o); return name, Tag(t, v)


def write_root(name, tag):
    return bytes([tag.t]) + _ws(name) + _write(tag)


def read_level_dat(path):
    b = open(path, "rb").read()
    ver = struct.unpack_from("<i", b, 0)[0]
    name, root = read_root(b, 8)
    return ver, name, root


def write_level_dat(path, ver, name, root):
    body = write_root(name, root)
    open(path, "wb").write(struct.pack("<ii", ver, len(body)) + body)


def set_experiments(path, keys):
    ver, name, root = read_level_dat(path)
    exp = root.v.get("experiments")
    if exp is None or exp.t != COMPOUND:
        exp = Tag(COMPOUND, {}); root.v["experiments"] = exp
    for k in keys: exp.v[k] = Tag(BYTE, 1)
    exp.v["experiments_ever_used"] = Tag(BYTE, 1)
    exp.v["saved_with_toggled_experiments"] = Tag(BYTE, 1)
    write_level_dat(path, ver, name, root)


def mcstructure(size, blocks, palette, entities=()):
    """size=(x,y,z); blocks: lista di indici nella palette in ordine x,y,z (z più veloce); palette: [(nome, {stato: Tag}), ...]."""
    pal = [Tag(COMPOUND, {"name": Tag(STRING, n), "states": Tag(COMPOUND, dict(s)), "version": Tag(INT, 18168865)}) for n, s in palette]
    root = Tag(COMPOUND, {
        "format_version": Tag(INT, 1),
        "size": Tag(LIST, (INT, [Tag(INT, size[0]), Tag(INT, size[1]), Tag(INT, size[2])])),
        "structure": Tag(COMPOUND, {
            "block_indices": Tag(LIST, (LIST, [Tag(LIST, (INT, [Tag(INT, i) for i in blocks])),
                                               Tag(LIST, (INT, [Tag(INT, -1) for _ in blocks]))])),
            "entities": Tag(LIST, (COMPOUND, list(entities))),
            "palette": Tag(COMPOUND, {"default": Tag(COMPOUND, {
                "block_palette": Tag(LIST, (COMPOUND, pal)),
                "block_position_data": Tag(COMPOUND, {})})}),
        }),
        "structure_world_origin": Tag(LIST, (INT, [Tag(INT, 0), Tag(INT, 0), Tag(INT, 0)])),
    })
    return write_root("", root)
