// Strutture delle mod Java generate nel mondo come in Java: posizioni a griglia (structure_set), biomi, altezza,
// pezzi jigsaw montati con lo stesso algoritmo (gruppi di pezzi, pesi, rotazioni, spazio libero, profondità),
// processori dei blocchi, adattamento del terreno, creature. Scritto da Minecraft Mod Installer.
import { system, world, BlockPermutation, BlockVolume, StructureRotation } from "@minecraft/server";
import { D } from "./mmi_structures_data.js";

const EMPTY = "minecraft:empty";
const DIMS = ["minecraft:overworld", "minecraft:nether", "minecraft:the_end"];
const R = 3; // chunk attorno al giocatore in cui si cercano gli inizi delle strutture
const PROTECT = 512; // mondi già esistenti: niente strutture entro questa distanza da dove erano i giocatori
const ROT = [StructureRotation.None, StructureRotation.Rotate90, StructureRotation.Rotate180, StructureRotation.Rotate270];
const STEP = [[0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]];
const HORIZ = [2, 5, 3, 4]; // nord, est, sud, ovest (senso orario)
const NETHER = new Set(["minecraft:hell", "minecraft:crimson_forest", "minecraft:warped_forest", "minecraft:soulsand_valley", "minecraft:basalt_deltas"]);
const FOLIAGE = /leaves|_log$|_wood$|_stem$|hyphae|mushroom|vine|(^|:|_)grass$|fern|flower|bush|sapling|bamboo|sugar_cane|cactus|snow_layer|tulip|orchid|allium|bluet|daisy|poppy|dandelion|cornflower|lily|rose|peony|lilac|pumpkin|melon|bee_nest|cocoa|kelp|seagrass|sea_pickle|coral|dripleaf|azalea|carpet$|propagule|roots|lichen|pitcher|torchflower|petals|wildflowers|leaf_litter|deadbush|berry/;
let DEBUG = false; // /scriptevent mmi:strutture_log: scrive nel registro cosa nasce e dove
const log = (m) => console.warn("[mmi strutture] " + m);
const debug = (m) => { if (DEBUG) log(m); };
const PLACED = []; // pezzi piazzati (solo con DEBUG), per /scriptevent mmi:strutture_verifica

// ---- java.util.Random (stessi numeri di Java)
const MUL = 0x5deece66dn, MASK = (1n << 48n) - 1n;
class JRandom {
  constructor(seed) { this.setSeed(seed); }
  setSeed(seed) { this.s = (BigInt.asUintN(64, seed) ^ MUL) & MASK; }
  next(bits) { this.s = (this.s * MUL + 0xbn) & MASK; return Number(BigInt.asIntN(32, this.s >> BigInt(48 - bits))); }
  nextInt(n) {
    if (n <= 0) return 0;
    if ((n & -n) === n) return Number((BigInt(n) * BigInt(this.next(31))) >> 31n);
    let u = this.next(31), r = u % n;
    while (u - r + (n - 1) > 2147483647) { u = this.next(31); r = u % n; }
    return r;
  }
  nextLong() { return BigInt.asIntN(64, (BigInt(this.next(32)) << 32n) + BigInt(this.next(32))); }
  nextFloat() { return this.next(24) / 16777216; }
  nextDouble() { return (this.next(26) * 134217728 + this.next(27)) / 9007199254740992; }
  largeFeature(seed, x, z) {
    this.setSeed(seed);
    const a = this.nextLong(), b = this.nextLong();
    this.setSeed(BigInt.asIntN(64, (BigInt(x) * a) ^ (BigInt(z) * b) ^ seed));
    return this;
  }
  withSalt(seed, x, z, salt) { this.setSeed(BigInt.asIntN(64, BigInt(x) * 341873128712n + BigInt(z) * 132897987541n + seed + BigInt(salt))); return this; }
}
function posRandom(x, y, z) {
  let i = BigInt.asIntN(64, BigInt(Math.imul(x, 3129871)) ^ (BigInt(z) * 116129781n) ^ BigInt(y));
  i = BigInt.asIntN(64, i * i * 42317861n + i * 11n);
  return new JRandom(i >> 16n);
}
function shuffle(list, rnd) {
  for (let j = list.length; j > 1; j--) { const k = rnd.nextInt(j); const t = list[j - 1]; list[j - 1] = list[k]; list[k] = t; }
  return list;
}
const between = (rnd, a, b) => rnd.nextInt(b - a + 1) + a;
const mthInt = (rnd, a, b) => (a >= b ? a : rnd.nextInt(b - a + 1) + a);

// ---- dati
const TPL = D.t;
const EL = [];
const POOLS = new Map();
for (const [name, p] of Object.entries(D.p)) {
  const els = p.e.map(([k, a, w, pr, pj]) => { const el = { k, a, w, pr, pj, i: EL.length }; EL.push(el); return el; });
  const weighted = [];
  for (const el of els) for (let n = 0; n < el.w; n++) weighted.push(el);
  POOLS.set(name, { f: p.f, els, weighted });
}
if (!POOLS.has(EMPTY)) POOLS.set(EMPTY, { f: EMPTY, els: [], weighted: [] });
const STRUCTS = D.s.map((s) => ({ ...s, bset: new Set(s.b) }));
const SETS = D.g.map((g) => {
  const dims = new Set();
  for (const [si] of g.s) for (const b of STRUCTS[si].b) dims.add(NETHER.has(b) ? 1 : b === "minecraft:the_end" ? 2 : 0);
  return { ...g, dims };
});
const BY_ID = new Map(STRUCTS.map((s, i) => [s.id, i]));

// ---- geometria (come StructureTemplate di Java, perno 0,0,0)
function tf(x, y, z, r) {
  switch (r) { case 1: return [-z, y, x]; case 2: return [-x, y, -z]; case 3: return [z, y, -x]; default: return [x, y, z]; }
}
const rotDir = (d, r) => (d < 2 ? d : HORIZ[(HORIZ.indexOf(d) + r) % 4]);
function tbox(s, p, r) {
  const a = tf(0, 0, 0, r), b = tf(s[0] - 1, s[1] - 1, s[2] - 1, r);
  return [p[0] + Math.min(a[0], b[0]), p[1], p[2] + Math.min(a[2], b[2]), p[0] + Math.max(a[0], b[0]), p[1] + s[1] - 1, p[2] + Math.max(a[2], b[2])];
}
function parts(el) { return el.k === 0 ? [TPL[el.a]] : el.k === 1 ? el.a.map((i) => TPL[i]) : []; }
function elBox(el, p, r) {
  if (el.k >= 2) return [p[0], p[1], p[2], p[0], p[1], p[2]];
  let out = null;
  for (const t of parts(el)) {
    const b = tbox(t.s, p, r);
    out = out ? [Math.min(out[0], b[0]), Math.min(out[1], b[1]), Math.min(out[2], b[2]), Math.max(out[3], b[3]), Math.max(out[4], b[4]), Math.max(out[5], b[5])] : b;
  }
  return out;
}
function jigsaws(el, p, r, rnd) {
  if (el.k === 3) return [];
  if (el.k === 2) return [{ p: [p[0], p[1], p[2]], f: 0, t: 3, pool: EMPTY, name: "minecraft:bottom", target: EMPTY, roll: true, sel: 0, plc: 0 }];
  const t = el.k === 0 ? TPL[el.a] : TPL[el.a[0]];
  const list = t.j.map(([x, y, z, f, tp, pool, name, target, roll, sel, plc]) => {
    const q = tf(x, y, z, r);
    return { p: [p[0] + q[0], p[1] + q[1], p[2] + q[2]], f: rotDir(f, r), t: rotDir(tp, r), pool, name, target, roll: roll === 1, sel, plc };
  });
  shuffle(list, rnd);
  list.sort((a, b) => b.sel - a.sel);
  return list;
}
const canAttach = (a, b) => a.f === (b.f ^ 1) && (a.roll || a.t === b.t) && a.target === b.name;
const inBox = (b, p) => p[0] >= b[0] && p[0] <= b[3] && p[1] >= b[1] && p[1] <= b[4] && p[2] >= b[2] && p[2] <= b[5];
const hits = (a, b) => a[0] <= b[3] && a[3] >= b[0] && a[1] <= b[4] && a[4] >= b[1] && a[2] <= b[5] && a[5] >= b[2];
function fits(free, b) {
  const o = free.o;
  if (b[0] < o[0] || b[1] < o[1] || b[2] < o[2] || b[3] > o[3] || b[4] > o[4] || b[5] > o[5]) return false;
  for (const c of free.occ) if (hits(b, c)) return false;
  return true;
}
function maxSize(name) {
  const pool = POOLS.get(name);
  if (!pool) return 0;
  if (pool.m === undefined) { let m = 0; for (const el of pool.els) { const b = elBox(el, [0, 0, 0], 0); if (b) m = Math.max(m, b[4] - b[1] + 1); } pool.m = m; }
  return pool.m;
}

// ---- terreno
class NotLoaded extends Error {}
function info(dim) { const h = dim.heightRange; return { min: h.min, max: h.max }; }
function surface(dim, x, z, mode) {
  if (!dim.isChunkLoaded({ x, y: 0, z })) throw new NotLoaded();
  let b = dim.getTopmostBlock({ x, z });
  if (!b) return info(dim).min;
  for (let n = 0; b && n < 96; n++) {
    if (b.isLiquid ? mode === 0 : !(b.isAir || FOLIAGE.test(b.typeId))) break;
    b = b.below();
  }
  return b ? b.y + 1 : info(dim).min;
}
const OCEAN_FLOOR = 1;
function biomeOk(st, dim, p) {
  if (st.force) return true;
  if (!dim.isChunkLoaded({ x: p[0], y: 0, z: p[2] })) throw new NotLoaded();
  const d = info(dim);
  const b = dim.getBiome({ x: p[0], y: Math.min(d.max - 1, Math.max(d.min, p[1])), z: p[2] });
  return st.bset.has(typeof b === "string" ? b : b?.id);
}

// ---- altezze di Java (HeightProvider)
function anchor(a, d) { return a[0] === "a" ? a[1] : a[0] === "b" ? d.min + a[1] : d.max - 1 - a[1]; }
function height(h, rnd, d) {
  switch (h[0]) {
    case "u": { const lo = anchor(h[1], d), hi = anchor(h[2], d); return lo > hi ? lo : between(rnd, lo, hi); }
    case "bb": { const lo = anchor(h[1], d), hi = anchor(h[2], d), inner = h[3]; if (hi - lo - inner + 1 <= 0) return lo; const k = rnd.nextInt(hi - lo - inner + 1); return rnd.nextInt(k + inner) + lo; }
    case "vb": { const lo = anchor(h[1], d), hi = anchor(h[2], d), inner = h[3]; if (hi - lo - inner + 1 <= 0) return lo; const k = mthInt(rnd, lo + inner, hi); const l = mthInt(rnd, lo, k - 1); return mthInt(rnd, lo, l - 1 + inner); }
    case "tz": { const lo = anchor(h[1], d), hi = anchor(h[2], d), pl = h[3]; if (lo > hi) return lo; const k = hi - lo; if (pl >= k) return between(rnd, lo, hi); const l = Math.trunc((k - pl) / 2); return lo + between(rnd, 0, k - l) + between(rnd, 0, l); }
    case "w": { let total = 0; for (const [, w] of h[1]) total += w; let j = rnd.nextInt(total); for (const [spec, w] of h[1]) { j -= w; if (j < 0) return height(spec, rnd, d); } return height(h[1][0][0], rnd, d); }
    default: return anchor(h[1], d);
  }
}

// ---- montaggio dei pezzi (JigsawPlacement di Java)
function startStub(st, dim, cx, cz, rnd) {
  const d = info(dim);
  const y = height(st.h, rnd, d);
  const pos = [cx * 16, y, cz * 16];
  const rot = rnd.nextInt(4);
  const pool = POOLS.get(st.p);
  if (!pool || pool.weighted.length === 0) return null;
  const el = pool.weighted[rnd.nextInt(pool.weighted.length)];
  if (el.k === 3) return null;
  let jp = pos;
  if (st.n) {
    const j = jigsaws(el, pos, rot, rnd).find((x) => x.name === st.n);
    if (!j) return null;
    jp = j.p;
  }
  const v = [jp[0] - pos[0], jp[1] - pos[1], jp[2] - pos[2]];
  const p1 = [pos[0] - v[0], pos[1] - v[1], pos[2] - v[2]];
  let bb = elBox(el, p1, rot);
  const i = Math.trunc((bb[3] + bb[0]) / 2), j = Math.trunc((bb[5] + bb[2]) / 2);
  const k = st.m !== null && st.m !== undefined ? pos[1] + surface(dim, i, j, st.m) : p1[1];
  const dy = k - (bb[1] + 1);
  p1[1] += dy;
  bb = [bb[0], bb[1] + dy, bb[2], bb[3], bb[4] + dy, bb[5]];
  return { piece: { el, pos: p1, rot, bb, gd: 1, depth: 0 }, center: [i, k + v[1], j] };
}
function assemble(st, dim, stub, rnd) {
  const start = stub.piece;
  const pieces = [start];
  if (st.d <= 0) return pieces;
  const d = info(dim);
  const [i, i1, j] = stub.center;
  const md = st.x, mv = st.xv ?? st.x;
  start.free = { o: [i - md, Math.max(i1 - mv, d.min), j - md, i + md, Math.min(i1 + mv, d.max - 1), j + md], occ: [start.bb] };
  const queue = new Map(); // priorità → lista (si prende dalla priorità più alta, in ordine di arrivo)
  const add = (ps, pr) => { if (!queue.has(pr)) queue.set(pr, []); queue.get(pr).push(ps); };
  const take = () => { let best = null; for (const [pr, l] of queue) if (l.length && (best === null || pr > best)) best = pr; return best === null ? null : queue.get(best).shift(); };
  children(st, dim, start, start.free, 0, rnd, pieces, add);
  for (let ps = take(); ps; ps = take()) children(st, dim, ps, ps.free, ps.depth, rnd, pieces, add);
  return pieces;
}
function children(st, dim, piece, free, depth, rnd, pieces, add) {
  const el = piece.el, rigid = el.pj === 0, bb = piece.bb, i = bb[1];
  let interior = null;
  next: for (const J of jigsaws(el, piece.pos, piece.rot, rnd)) {
    const dir = J.f, p1 = J.p, s = STEP[dir], p2 = [p1[0] + s[0], p1[1] + s[1], p1[2] + s[2]];
    const jj = p1[1] - i;
    let k = null;
    const pool = POOLS.get(J.pool);
    if (!pool || (pool.weighted.length === 0 && J.pool !== EMPTY)) continue;
    const fb = POOLS.get(pool.f);
    if (!fb || (fb.weighted.length === 0 && pool.f !== EMPTY)) continue;
    let target;
    if (inBox(bb, p2)) { if (!interior) interior = { o: bb.slice(), occ: [] }; target = interior; } else target = free;
    const cands = [];
    if (depth !== st.d) cands.push(...shuffle(pool.weighted.slice(), rnd));
    cands.push(...shuffle(fb.weighted.slice(), rnd));
    for (const el1 of cands) {
      if (el1.k === 3) break;
      for (const r1 of shuffle([0, 1, 2, 3], rnd)) {
        const js1 = jigsaws(el1, [0, 0, 0], r1, rnd);
        const bb1 = elBox(el1, [0, 0, 0], r1);
        let hack = 0;
        if (st.e && bb1[4] - bb1[1] + 1 <= 16) {
          for (const q of js1) {
            const t = STEP[q.f];
            if (!inBox(bb1, [q.p[0] + t[0], q.p[1] + t[1], q.p[2] + t[2]])) continue;
            const qp = POOLS.get(q.pool);
            hack = Math.max(hack, maxSize(q.pool), qp ? maxSize(qp.f) : 0);
          }
        }
        for (const J1 of js1) {
          if (!canAttach(J, J1)) continue;
          const p3 = J1.p, p4 = [p2[0] - p3[0], p2[1] - p3[1], p2[2] - p3[2]];
          const bb2 = elBox(el1, p4, r1);
          const rigid1 = el1.pj === 0, k1 = p3[1], l1 = jj - k1 + s[1];
          let i2;
          if (rigid && rigid1) i2 = i + l1;
          else { if (k === null) k = surface(dim, p1[0], p1[2], 0); i2 = k - k1; }
          const j2 = i2 - bb2[1];
          const bb3 = [bb2[0], bb2[1] + j2, bb2[2], bb2[3], bb2[4] + j2, bb2[5]];
          const p5 = [p4[0], p4[1] + j2, p4[2]];
          if (hack > 0) bb3[4] = Math.max(bb3[4], bb3[1] + Math.max(hack + 1, bb3[4] - bb3[1]));
          if (!fits(target, bb3)) continue;
          target.occ.push(bb3);
          const gd = rigid1 ? piece.gd - l1 : 1;
          const child = { el: el1, pos: p5, rot: r1, bb: bb3, gd, depth: depth + 1, free: target };
          pieces.push(child);
          if (depth + 1 <= st.d) add(child, J1.sel);
          continue next;
        }
      }
    }
  }
}

// ---- posizioni delle strutture (structure_set)
let SEED = null;
function seed() {
  if (SEED === null) { try { SEED = BigInt.asIntN(64, BigInt(world.seed)); } catch (e) { SEED = 0n; } }
  return SEED;
}
function potential(S, rx, rz) {
  const r = new JRandom(0n).withSalt(seed(), rx, rz, S.sa);
  const lim = S.sp - S.se;
  const a = S.tr ? Math.trunc((r.nextInt(lim) + r.nextInt(lim)) / 2) : r.nextInt(lim);
  const b = S.tr ? Math.trunc((r.nextInt(lim) + r.nextInt(lim)) / 2) : r.nextInt(lim);
  return [rx * S.sp + a, rz * S.sp + b];
}
let RINGS = new Map();
function rings(S, si) {
  if (RINGS.has(si)) return RINGS.get(si);
  const out = [], r = new JRandom(seed());
  let ang = r.nextDouble() * Math.PI * 2, l = 0, ring = 0, spread = S.rs;
  for (let n = 0; n < S.rc; n++) {
    const dist = 4 * S.rd + S.rd * ring * 6 + (r.nextDouble() - 0.5) * S.rd * 2.5;
    out.push([Math.round(Math.cos(ang) * dist), Math.round(Math.sin(ang) * dist)]);
    ang += (Math.PI * 2) / spread;
    if (++l === spread) { ring++; l = 0; spread += Math.trunc((2 * spread) / (ring + 1)); spread = Math.min(spread, S.rc - n); ang += r.nextDouble() * Math.PI * 2; }
  }
  RINGS.set(si, out);
  return out;
}
function frequencyOk(S, cx, cz) {
  if (S.f >= 1) return true;
  const s = seed();
  switch (S.fm) {
    case 1: { const r = new JRandom(BigInt.asIntN(64, BigInt((cx >> 4) ^ ((cz >> 4) << 4)) ^ s)); r.nextInt(); return r.nextInt(Math.trunc(1 / S.f)) === 0; }
    case 2: return new JRandom(0n).withSalt(s, cx, cz, 10387320).nextFloat() < S.f;
    case 3: return new JRandom(0n).largeFeature(s, cx, cz).nextDouble() < S.f;
    default: return new JRandom(0n).withSalt(s, S.sa, cx, cz).nextFloat() < S.f;
  }
}
function isStart(si, cx, cz, depth = 0) {
  const S = SETS[si];
  if (S.t === 1) { if (!rings(S, si).some((c) => c[0] === cx && c[1] === cz)) return false; }
  else { const c = potential(S, Math.floor(cx / S.sp), Math.floor(cz / S.sp)); if (c[0] !== cx || c[1] !== cz) return false; }
  if (!frequencyOk(S, cx, cz)) return false;
  if (S.ex && depth < 2) {
    const [other, n] = S.ex, O = SETS[other];
    for (let x = cx - n; x <= cx + n; x++) for (let z = cz - n; z <= cz + n; z++) {
      if (O.t === 0) { const c = potential(O, Math.floor(x / O.sp), Math.floor(z / O.sp)); if (c[0] !== x || c[1] !== z) continue; }
      if (isStart(other, x, z, depth + 1)) return false;
    }
  }
  return true;
}
function candidates(S, si, cx, cz) {
  const out = [];
  if (S.t === 1) { for (const c of rings(S, si)) if (Math.abs(c[0] - cx) <= R && Math.abs(c[1] - cz) <= R) out.push(c); return out; }
  for (let rx = Math.floor((cx - R) / S.sp); rx <= Math.floor((cx + R) / S.sp); rx++)
    for (let rz = Math.floor((cz - R) / S.sp); rz <= Math.floor((cz + R) / S.sp); rz++) {
      const c = potential(S, rx, rz);
      if (Math.abs(c[0] - cx) <= R && Math.abs(c[1] - cz) <= R) out.push(c);
    }
  return out;
}

// ---- salvataggio (proprietà del mondo)
const DONE = new Set();
const ACTIVE = new Map(); // chiave → { s: struttura, di, p: pezzi da piazzare, pb: scatole dei pezzi }
const BOXES = []; // strutture nate: { s, di, pb } (per «sei dentro la struttura X?», come in Java)
// Le strutture (id di Java) che contengono un punto: per i progressi «trova la struttura».
globalThis.mmiStructureAt = (dimId, loc) => {
  const di = DIMS.indexOf(dimId), out = [];
  const x = Math.floor(loc.x), y = Math.floor(loc.y), z = Math.floor(loc.z);
  const inside = (pb) => pb.some((b) => x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]);
  for (const e of BOXES) if (e.di === di && inside(e.pb)) out.push(STRUCTS[e.s].id);
  for (const job of ACTIVE.values()) if (job.di === di && job.pb && inside(job.pb)) out.push(STRUCTS[job.s].id);
  return out;
};
function save(key, v) {
  try {
    const text = typeof v === "string" ? v : JSON.stringify(v);
    for (let n = 1; world.getDynamicProperty(key + "#" + n) !== undefined; n++) world.setDynamicProperty(key + "#" + n, undefined);
    if (text.length <= 30000) { world.setDynamicProperty(key, text); return; }
    const parts = Math.ceil(text.length / 30000);
    world.setDynamicProperty(key, "#" + parts);
    for (let n = 1; n <= parts; n++) world.setDynamicProperty(key + "#" + n, text.slice((n - 1) * 30000, n * 30000));
  } catch (e) { log("salvataggio non riuscito: " + e); }
}
function load(key) {
  const v = world.getDynamicProperty(key);
  if (typeof v !== "string" || !v.startsWith("#")) return v;
  let text = "";
  for (let n = 1; n <= Number(v.slice(1)); n++) text += world.getDynamicProperty(key + "#" + n) ?? "";
  return text;
}
let PROT = null;
function protectedAt(di, x, z) {
  for (const [d, px, pz] of PROT ?? []) if (d === di && Math.abs(px - x) < PROTECT && Math.abs(pz - z) < PROTECT) return true;
  return false;
}
function init() {
  if (PROT !== null) return true;
  const players = world.getAllPlayers();
  if (!players.length) return false;
  const raw = world.getDynamicProperty("mmi_s_init");
  if (typeof raw === "string") PROT = JSON.parse(raw);
  else {
    // In un mondo che esisteva già, le zone già esplorate restano come sono (in Java le strutture nascono solo nei chunk nuovi).
    PROT = [];
    if (world.getAbsoluteTime() > 6000) {
      for (const p of players) PROT.push([DIMS.indexOf(p.dimension.id), Math.floor(p.location.x), Math.floor(p.location.z)]);
      const sp = world.getDefaultSpawnLocation();
      PROT.push([0, sp.x, sp.z]);
    }
    world.setDynamicProperty("mmi_s_init", JSON.stringify(PROT));
  }
  for (const id of world.getDynamicPropertyIds()) {
    if (!id.startsWith("mmi_s:") || id.includes("#")) continue;
    const v = load(id);
    if (v === "0" || v === "1") DONE.add(id);
    else if (typeof v === "string") {
      try {
        const job = JSON.parse(v);
        if (job.p && job.p.length) ACTIVE.set(id, job);
        else { DONE.add(id); if (job.pb && STRUCTS[job.s]) BOXES.push({ s: job.s, di: job.di, pb: job.pb }); }
      } catch (e) { DONE.add(id); }
    }
  }
  return true;
}

// ---- generazione
function generate(dim, di, si, cx, cz, key, force) {
  const S = SETS[si];
  const order = [];
  if (S.s.length === 1) order.push(S.s[0][0]);
  else {
    const r = new JRandom(0n).largeFeature(seed(), cx, cz), list = S.s.slice();
    let total = list.reduce((a, e) => a + e[1], 0);
    while (list.length) {
      let j = r.nextInt(total), k = 0;
      for (const e of list) { j -= e[1]; if (j < 0) break; k++; }
      order.push(list[k][0]); total -= list[k][1]; list.splice(k, 1);
    }
  }
  for (const idx of order) {
    const st = force ? { ...STRUCTS[idx], force: true } : STRUCTS[idx];
    if (!build(st, idx, dim, di, cx, cz, key)) continue;
    return true;
  }
  return false;
}
function build(st, idx, dim, di, cx, cz, key) {
  const rnd = new JRandom(0n).largeFeature(seed(), cx, cz);
  const stub = startStub(st, dim, cx, cz, rnd);
  if (!stub || !biomeOk(st, dim, stub.center)) return false;
  const pieces = assemble(st, dim, stub, rnd);
  const job = { s: idx, di, p: pieces.map((pc) => [pc.el.i, pc.pos[0], pc.pos[1], pc.pos[2], pc.rot]), pb: pieces.map((pc) => pc.bb) };
  ACTIVE.set(key, job);
  save(key, job);
  debug("inizio " + st.id + " a " + stub.center.join(" ") + " (" + pieces.length + " pezzi)");
  return true;
}
function loaded(dim, bb) {
  for (let x = Math.floor(bb[0] / 16); x <= Math.floor(bb[3] / 16); x++)
    for (let z = Math.floor(bb[2] / 16); z <= Math.floor(bb[5] / 16); z++)
      if (!dim.isChunkLoaded({ x: x * 16, y: 0, z: z * 16 })) return false;
  return true;
}

// ---- piazzamento di un pezzo
const PERMS = new Map();
function perm(name, states) {
  const key = name + JSON.stringify(states);
  let p = PERMS.get(key);
  if (p === undefined) { try { p = BlockPermutation.resolve(name, states); } catch (e) { p = null; } PERMS.set(key, p); }
  return p;
}
const CARD = ["north", "east", "south", "west"]; // senso orario
function rotStates(states, r) {
  if (!r) return states;
  const s = { ...states };
  for (const [k, v] of Object.entries(states)) {
    if (typeof v === "string" && CARD.includes(v) && (k === "minecraft:cardinal_direction" || k === "minecraft:facing_direction" || k === "minecraft:block_face" || k === "torch_facing_direction")) s[k] = CARD[(CARD.indexOf(v) + r) % 4];
    else if (k === "weirdo_direction" && typeof v === "number") s[k] = [3, 0, 2, 1][([1, 3, 2, 0][v] + r) % 4];
    else if (k === "direction" && typeof v === "number" && v < 4) s[k] = [2, 3, 0, 1][([2, 3, 0, 1][v] + r) % 4];
    else if (k === "facing_direction" && typeof v === "number" && v >= 2 && v <= 5) s[k] = HORIZ[(HORIZ.indexOf(v) + r) % 4];
    else if (k === "ground_sign_direction" && typeof v === "number") s[k] = (v + 4 * r) % 16;
    else if (k === "pillar_axis" && r % 2 === 1 && v !== "y") s[k] = v === "x" ? "z" : "x";
  }
  return s;
}
function placePiece(dim, st, pc) {
  const [e, x, y, z, r] = pc;
  const el = EL[e], pos = [x, y, z];
  const bb = elBox(el, pos, r);
  if (!loaded(dim, bb)) return false;
  const d = info(dim);
  if (bb[4] < d.min || bb[1] >= d.max) return true;
  const procs = el.pr >= 0 ? D.r[el.pr] : [];
  const keep = protectedBlocks(dim, bb, procs);
  const cells = el.pj === 1 || procs.some((p) => p.k === "g");
  for (const t of parts(el)) {
    if (cells && t.b) placeCells(dim, t, pos, r, procs.find((p) => p.k === "g"));
    else {
      const b = tbox(t.s, pos, r);
      const rot = procs.find((p) => p.k === "rot" && !p.b);
      try {
        world.structureManager.place(t.i, dim, { x: b[0], y: b[1], z: b[2] }, {
          rotation: ROT[r], waterlogged: st.w, includeEntities: false,
          ...(rot ? { integrity: rot.v, integritySeed: String(x * 31 + z) } : {}),
        });
        if (DEBUG) PLACED.push({ i: t.i, s: t.s, pos, r, dim: dim.id, pr: el.pr });
      } catch (err) { log("pezzo " + t.i + " non piazzato: " + err); }
    }
    if (el.pj === 0 && (st.a === 1 || st.a === 2)) beard(dim, tbox(t.s, pos, r));
    entities(dim, t, pos, r);
  }
  for (const p of procs) applyProcessor(dim, p, bb, pos);
  for (const [loc, p] of keep) { try { dim.getBlock(loc)?.setPermutation(p); } catch (err) {} }
  return true;
}
function placeCells(dim, t, pos, r, grav) {
  const mode = grav ? grav.h : 0, off = grav ? grav.o : -1;
  const cols = new Map();
  const L = t.b.l;
  for (let n = 0; n < L.length; n += 4) {
    const q = tf(L[n], L[n + 1], L[n + 2], r), x = pos[0] + q[0], z = pos[2] + q[2];
    const ck = x + "," + z;
    if (!cols.has(ck)) cols.set(ck, surface(dim, x, z, mode));
    const [name, states] = t.b.p[L[n + 3]];
    const p = perm(name, rotStates(states, r));
    if (!p) continue;
    try { dim.getBlock({ x, y: cols.get(ck) + off + L[n + 1], z })?.setPermutation(p); } catch (err) {}
  }
}
function beard(dim, b) {
  // Come «beard» di Java: il terreno sale fino a sotto il pezzo (qui colonna per colonna).
  for (let x = b[0]; x <= b[3]; x++) for (let z = b[2]; z <= b[5]; z++) {
    try {
      const base = dim.getBlock({ x, y: b[1], z });
      if (!base || base.isAir || base.isLiquid || base.typeId === "minecraft:structure_void") continue;
      let y = b[1] - 1, n = 0, g = null;
      for (; n < 48 && y > dim.heightRange.min; n++, y--) {
        g = dim.getBlock({ x, y, z });
        if (!g) break;
        if (!(g.isAir || g.isLiquid || FOLIAGE.test(g.typeId))) break;
      }
      if (!g || n === 0 || n >= 48) continue;
      const id = g.typeId;
      const fill = /grass_block|dirt|podzol|mycelium|mud$|moss_block/.test(id) ? "minecraft:dirt" : id;
      const p = perm(fill, {});
      if (p) dim.fillBlocks(new BlockVolume({ x, y: y + 1, z }, { x, y: b[1] - 1, z }), p);
    } catch (err) {}
  }
}
function entities(dim, t, pos, r) {
  for (const [ex, ey, ez, id] of t.e ?? []) {
    const q = r === 1 ? [1 - ez, ey, ex] : r === 2 ? [1 - ex, ey, 1 - ez] : r === 3 ? [ez, ey, 1 - ex] : [ex, ey, ez];
    try { dim.spawnEntity(id, { x: pos[0] + q[0], y: pos[1] + q[1], z: pos[2] + q[2] }); } catch (err) {}
  }
}
function protectedBlocks(dim, bb, procs) {
  const out = [];
  for (const p of procs) {
    if (p.k !== "pr" || !p.b.length) continue;
    try {
      const vol = dim.getBlocks(new BlockVolume({ x: bb[0], y: bb[1], z: bb[2] }, { x: bb[3], y: bb[4], z: bb[5] }), { includeTypes: p.b }, true);
      for (const loc of vol.getBlockLocationIterator()) { const b = dim.getBlock(loc); if (b) out.push([{ ...loc }, b.permutation]); }
    } catch (err) {}
  }
  return out;
}
function predicate(q, block, rnd) {
  if (!q) return true;
  if (q.t === "b") return q.b.includes(block.typeId) && (q.p === undefined || rnd.nextFloat() < q.p);
  if (q.t === "s") {
    if (block.typeId !== q.n) return false;
    const st = block.permutation.getAllStates();
    for (const [k, v] of Object.entries(q.s)) if (st[k] !== v) return false;
    return q.p === undefined || rnd.nextFloat() < q.p;
  }
  return true;
}
function posPredicate(q, loc, piecePos, rnd) {
  if (!q) return true;
  let dist;
  if (q.t === "ax") dist = Math.abs([loc.x - piecePos[0], loc.y - piecePos[1], loc.z - piecePos[2]][q.a]);
  else dist = Math.abs(loc.x - piecePos[0]) + Math.abs(loc.y - piecePos[1]) + Math.abs(loc.z - piecePos[2]);
  const f = rnd.nextFloat();
  const t = q.d1 === q.d0 ? (dist >= q.d1 ? 1 : 0) : Math.min(1, Math.max(0, (dist - q.d0) / (q.d1 - q.d0)));
  return f <= q.c0 + (q.c1 - q.c0) * t;
}
function ruleOutput(p, block, loc, piecePos) {
  const rnd = posRandom(loc.x, loc.y, loc.z);
  for (const rule of p.r) {
    if (!predicate(rule.i, block, rnd)) continue;
    if (!predicate(rule.l, block, rnd)) continue;
    if (!posPredicate(rule.q, loc, piecePos, rnd)) continue;
    return perm(rule.o[0], rule.o[1]);
  }
  return null;
}
function inputTypes(p) {
  if (p.k === "r") { const s = new Set(); for (const rule of p.r) { if (!rule.i || rule.i.t === "*") return null; for (const b of rule.i.t === "s" ? [rule.i.n] : rule.i.b) s.add(b); } return [...s]; }
  if (p.k === "rot") return p.b ?? null;
  if (p.k === "cap") return inputTypes(p.d);
  return null;
}
function applyProcessor(dim, p, bb, piecePos) {
  if (p.k !== "r" && p.k !== "cap" && !(p.k === "rot" && p.b)) return;
  const types = inputTypes(p);
  let locs = [];
  try {
    const vol = new BlockVolume({ x: bb[0], y: bb[1], z: bb[2] }, { x: bb[3], y: bb[4], z: bb[5] });
    const list = types ? dim.getBlocks(vol, { includeTypes: types }, true) : dim.getBlocks(vol, { excludeTypes: ["minecraft:air"] }, true);
    for (const loc of list.getBlockLocationIterator()) locs.push({ ...loc });
  } catch (err) { return; }
  if (p.k === "cap") {
    const rnd = posRandom(piecePos[0], piecePos[1], piecePos[2]);
    shuffle(locs, rnd);
    let n = typeof p.n === "number" ? p.n : between(rnd, p.n[1], p.n[2]);
    for (const loc of locs) { if (n <= 0) break; if (applyAt(dim, p.d, loc, piecePos)) n--; }
    return;
  }
  for (const loc of locs) applyAt(dim, p, loc, piecePos);
}
function applyAt(dim, p, loc, piecePos) {
  const block = dim.getBlock(loc);
  if (!block) return false;
  if (p.k === "rot") {
    if (posRandom(loc.x, loc.y, loc.z).nextFloat() <= p.v) return false;
    block.setType("minecraft:air");
    return true;
  }
  const out = ruleOutput(p, block, loc, piecePos);
  if (!out) return false;
  block.setPermutation(out);
  return true;
}

// ---- ciclo: cerca gli inizi vicino ai giocatori, poi piazza i pezzi nelle zone caricate
let busy = false;
let PAUSED = false; // /scriptevent mmi:strutture_pausa (solo per le prove automatiche): niente strutture nuove da sole
function* work() {
  busy = true;
  try {
    const t0 = Date.now();
    for (const pl of PAUSED ? [] : world.getAllPlayers()) {
      const dim = pl.dimension, di = DIMS.indexOf(dim.id);
      if (di < 0) continue;
      const cx = Math.floor(pl.location.x / 16), cz = Math.floor(pl.location.z / 16);
      for (let si = 0; si < SETS.length; si++) {
        const S = SETS[si];
        if (!S.dims.has(di)) continue;
        for (const c of candidates(S, si, cx, cz)) {
          const key = "mmi_s:" + di + ":" + si + ":" + c[0] + ":" + c[1];
          if (DONE.has(key) || ACTIVE.has(key)) continue;
          if (!dim.isChunkLoaded({ x: c[0] * 16 + 8, y: 0, z: c[1] * 16 + 8 })) continue;
          try {
            if (protectedAt(di, c[0] * 16 + 8, c[1] * 16 + 8) || !isStart(si, c[0], c[1]) || !generate(dim, di, si, c[0], c[1], key, false)) {
              DONE.add(key); save(key, "0");
            }
          } catch (e) {
            if (!(e instanceof NotLoaded)) { log("struttura non generata: " + e + " " + (e?.stack ?? "")); DONE.add(key); save(key, "0"); }
          }
          yield;
        }
      }
    }
    for (const [key, job] of ACTIVE) {
      const dim = world.getDimension(DIMS[job.di]);
      const st = STRUCTS[job.s];
      const left = [];
      let changed = false;
      for (const pc of job.p) {
        if (Date.now() - t0 > 40) { left.push(pc); continue; }
        let ok = false;
        try { ok = placePiece(dim, st, pc); } catch (e) { if (!(e instanceof NotLoaded)) { log("pezzo non piazzato: " + e); ok = true; } }
        if (ok) changed = true; else left.push(pc);
        yield;
      }
      if (!changed) continue;
      if (left.length) { job.p = left; save(key, job); }
      else {
        ACTIVE.delete(key); DONE.add(key);
        BOXES.push({ s: job.s, di: job.di, pb: job.pb ?? [] });
        save(key, { s: job.s, di: job.di, pb: job.pb ?? [] });
        debug("finita " + st.id);
      }
    }
  } finally { busy = false; }
}
system.runInterval(() => {
  if (busy || !init()) return;
  system.runJob(work());
}, 10);

// ---- comandi: /scriptevent mmi:struttura <id> (piazza una struttura qui), mmi:strutture_ovunque (anche nelle zone già esplorate)
// Come /place structure di Java: la struttura nasce nel chunk del giocatore (senza guardare il bioma).
function placeCommand(p, idx, rot, tries) {
  const dim = p.dimension, di = DIMS.indexOf(dim.id);
  const cx = Math.floor(p.location.x / 16), cz = Math.floor(p.location.z / 16);
  const key = "mmi_s:" + di + ":c:" + idx + ":" + cx + ":" + cz + ":" + system.currentTick;
  const st = { ...STRUCTS[idx], force: true };
  try {
    const rnd = new JRandom(0n).largeFeature(seed(), cx, cz);
    const stub = startStub(st, dim, cx, cz, rnd);
    if (!stub) { p.sendMessage?.("Struttura " + st.id + ": nessun pezzo iniziale"); log("struttura " + st.id + ": nessun pezzo iniziale"); return; }
    if (rot !== undefined) stub.piece.rot = Number(rot) & 3;
    const pieces = assemble(st, dim, stub, rnd);
    const job = { s: idx, di, p: pieces.map((pc) => [pc.el.i, pc.pos[0], pc.pos[1], pc.pos[2], pc.rot]), pb: pieces.map((pc) => pc.bb) };
    ACTIVE.set(key, job);
    save(key, job);
    p.sendMessage?.("Struttura " + st.id + ": " + pieces.length + " pezzi, a " + stub.center.join(" "));
    debug("comando " + st.id + " a " + stub.center.join(" ") + " (" + pieces.length + " pezzi)");
  } catch (e) {
    if (e instanceof NotLoaded && tries < 20) { system.runTimeout(() => placeCommand(p, idx, rot, tries + 1), 20); return; }
    log("struttura " + st.id + " non piazzata: " + (e instanceof NotLoaded ? "zona non caricata" : e));
  }
}
// I cambi che i processori del pezzo possono fare (blocco di partenza → blocchi possibili), per la verifica.
function changes(pr) {
  const out = new Map();
  const add = (ins, o) => { for (const i of ins) { if (!out.has(i)) out.set(i, new Set()); out.get(i).add(o); } };
  const walk = (p) => {
    if (p.k === "r") for (const rule of p.r) add(!rule.i ? ["*"] : rule.i.t === "s" ? [rule.i.n] : rule.i.b, rule.o[0]);
    else if (p.k === "cap") walk(p.d);
    else if (p.k === "rot") add(p.b ?? ["*"], "minecraft:air");
  };
  for (const p of pr >= 0 ? D.r[pr] : []) walk(p);
  return out;
}
function verify() {
  // Ogni pezzo piazzato confrontato con il suo file: blocco per blocco, con la rotazione di Java.
  let same = 0, total = 0, pieces = 0, processed = 0;
  const bad = [];
  for (const pc of PLACED) {
    const st = world.structureManager.get(pc.i);
    if (!st) continue;
    pieces++;
    const ch = changes(pc.pr);
    const dim = world.getDimension(pc.dim);
    for (let x = 0; x < pc.s[0]; x++) for (let y = 0; y < pc.s[1]; y++) for (let z = 0; z < pc.s[2]; z++) {
      const bp = st.getBlockPermutation({ x, y, z });
      const id = bp?.type.id;
      if (!id || id === "minecraft:air" || id === "minecraft:structure_void") continue;
      const q = tf(x, y, z, pc.r);
      const b = dim.getBlock({ x: pc.pos[0] + q[0], y: pc.pos[1] + q[1], z: pc.pos[2] + q[2] });
      if (!b) continue;
      total++;
      if (b.typeId === id) same++;
      else if (ch.get(id)?.has(b.typeId) || ch.get("*")?.has(b.typeId)) { same++; processed++; }
      else if (bad.length < 5) bad.push(pc.i + "@" + [x, y, z].join(",") + " r" + pc.r + ": " + id + " → " + b.typeId);
    }
  }
  log("verifica " + same + "/" + total + " blocchi uguali in " + pieces + " pezzi (cambiati dai processori come in Java: " + processed + ")" +
    (bad.length ? " (diversi: " + bad.join("; ") + ")" : ""));
}
system.afterEvents.scriptEventReceive.subscribe((ev) => {
  const p = ev.sourceEntity;
  if (ev.id === "mmi:strutture_ovunque") { PROT = []; world.setDynamicProperty("mmi_s_init", "[]"); p?.sendMessage?.("Le strutture delle mod ora nascono anche nelle zone già esplorate."); debug("strutture anche nelle zone già esplorate"); return; }
  if (ev.id === "mmi:strutture_log") { DEBUG = true; log("registro attivo"); return; }
  if (ev.id === "mmi:strutture_pausa") { PAUSED = true; return; }
  if (ev.id === "mmi:strutture_verifica") { verify(); return; }
  if (ev.id === "mmi:strutture_prova") {
    // Solo per le prove: una possibile struttura ogni 6 chunk, in qualunque bioma.
    DEBUG = true;
    for (const S of SETS) { S.t = 0; S.sp = 6; S.se = 0; S.f = 1; S.ex = null; }
    for (const st of STRUCTS) st.force = true;
    PAUSED = false;
    log("prova: una possibile struttura ogni 6 chunk");
    return;
  }
  if (ev.id !== "mmi:struttura" || !p) return;
  const [id, rot] = ev.message.trim().split(/\s+/);
  const idx = BY_ID.get(id) ?? BY_ID.get([...BY_ID.keys()].find((k) => k.endsWith(":" + id)));
  if (idx === undefined) { p.sendMessage?.("Struttura sconosciuta: " + id + ". Ci sono: " + [...BY_ID.keys()].join(", ")); log("struttura sconosciuta: " + id); return; }
  placeCommand(p, idx, rot, 0);
});
