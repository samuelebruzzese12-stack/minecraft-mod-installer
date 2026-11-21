// Scritto da Minecraft Mod Installer (JAVA TO BEDROCK): i bottini dei blocchi eseguiti con le regole di Java.
// Bedrock da solo non applica la fortuna ai blocchi delle add-on e, con il tocco di velluto, fa cadere sempre il blocco
// stesso. Per questi blocchi la tabella di Bedrock è vuota: quando un giocatore rompe il blocco o un'esplosione lo
// distrugge, qui si calcola il risultato di Java. Con il tocco di velluto Bedrock fa comunque cadere il blocco: se anche
// Java dà il blocco, si tiene quello (niente oggetti tolti, così non si perde nulla quando gli oggetti a terra si uniscono).
import { system, world, ItemStack, GameMode } from "@minecraft/server";
import { LOOT, TABLES } from "./mmi_loot_data.js";

function warn(e) { console.warn("[mmi loot] " + e); }

const before = new Map();
const key = (d, l) => d.id + ":" + l.x + "," + l.y + "," + l.z;
const center = (l) => ({ x: l.x + 0.5, y: l.y + 0.5, z: l.z + 0.5 });

world.beforeEvents.playerBreakBlock.subscribe((ev) => {
  try {
    if (!LOOT[ev.block.typeId]) return;
    const ids = ev.dimension.getEntities({ type: "minecraft:item", location: center(ev.block.location), maxDistance: 2.5 }).map((e) => e.id);
    before.set(key(ev.dimension, ev.block.location), new Set(ids));
  } catch (e) { warn(e); }
});

world.afterEvents.playerBreakBlock.subscribe((ev) => {
  const perm = ev.brokenBlockPermutation;
  const spec = LOOT[perm.type.id];
  if (!spec) return;
  const k = key(ev.dimension, ev.block.location);
  const seen = before.get(k) || new Set();
  before.delete(k);
  try {
    const pl = ev.player;
    if (pl && pl.getGameMode() === GameMode.Creative) return;
    const ctx = context(ev.itemStackBeforeBreak, javaState(spec, perm), ev.dimension);
    const drops = runTable(TABLES[spec.t], ctx);
    const at = center(ev.block.location);
    if ((ctx.ench.silk_touch ?? 0) > 0) {
      // Il blocco che Bedrock ha fatto cadere da sé vale come uno di quelli di Java; se Java non lo dà, si toglie.
      const i = drops.findIndex((d) => d.id === perm.type.id && !d.name && !d.lore && !d.damage && d.count > 0);
      if (i >= 0) drops[i] = { ...drops[i], count: drops[i].count - 1 };
      else for (const e of ev.dimension.getEntities({ type: "minecraft:item", location: at, maxDistance: 2.5 })) {
        if (!seen.has(e.id) && e.getComponent("minecraft:item")?.itemStack.typeId === perm.type.id) { e.remove(); break; }
      }
    }
    for (const d of drops) if (d.count > 0) spawn(ev.dimension, at, d);
  } catch (e) { warn(e); }
});

// Esplosioni: la tabella di Java con «survives_explosion» (il TNT non perde nulla, come in Java 1.21; le creature e i
// blocchi che esplodono sì: uno su raggio).
world.afterEvents.blockExplode.subscribe((ev) => {
  try {
    const perm = ev.explodedBlockPermutation;
    const spec = LOOT[perm.type.id];
    if (!spec) return;
    const src = ev.source?.typeId ?? "";
    const ctx = context(undefined, javaState(spec, perm), ev.dimension);
    ctx.boom = src === "minecraft:tnt" || src === "minecraft:tnt_minecart" ? 1 : src ? 1 / 3 : 1 / 5;
    for (const d of runTable(TABLES[spec.t], ctx)) if (d.count > 0) spawn(ev.dimension, center(ev.block.location), d);
  } catch (e) { warn(e); }
});

// ---------------------------------------------------------------- contesto

function context(tool, state, dim) {
  const ench = {};
  let tags = [];
  try {
    for (const x of tool?.getComponent("minecraft:enchantable")?.getEnchantments() || []) ench[x.type.id.replace("minecraft:", "")] = x.level;
  } catch (e) {}
  try { tags = tool ? tool.getTags() : []; } catch (e) {}
  return { tool: tool?.typeId, tags, ench, state, dim };
}

function javaState(spec, perm) {
  if (!spec.st) return spec.js || {};
  const all = perm.getAllStates();
  for (const [b, j] of spec.st) {
    let ok = true;
    for (const n in b) if (all[n] !== b[n]) { ok = false; break; }
    if (ok) return j;
  }
  return spec.js || {};
}

// ---------------------------------------------------------------- numeri e caso (come Java)

const rnd = () => Math.random();
const nextInt = (n) => Math.floor(rnd() * n);

function num(v, asInt) {
  if (v === null || v === undefined) return 0;
  if (typeof v === "number") return v;
  if (v.u) {
    const [a, b] = v.u;
    if (asInt) { const lo = Math.floor(a), hi = Math.floor(b); return lo >= hi ? lo : lo + nextInt(hi - lo + 1); }
    return a + rnd() * (b - a);
  }
  if (v.b) {
    const [n, p] = v.b;
    let c = 0;
    for (let i = 0; i < Math.floor(n); i++) if (rnd() < p) c++;
    return c;
  }
  return 0;
}

// ---------------------------------------------------------------- condizioni

function test(list, ctx) {
  if (!list) return true;
  for (const c of list) if (!cond(c, ctx)) return false;
  return true;
}

function cond(c, ctx) {
  switch (c.k) {
    case "boom": return ctx.boom === undefined || Math.random() < ctx.boom;
    case "true": return true;
    case "not": return !cond(c.c, ctx);
    case "any": return c.c.some((x) => cond(x, ctx));
    case "all": return c.c.every((x) => cond(x, ctx));
    case "state": {
      for (const p in c.p) {
        const want = c.p[p], have = ctx.state[p];
        if (have === undefined) return false;
        if (Array.isArray(want)) {
          const n = Number(have);
          if (want[0] !== null && n < want[0]) return false;
          if (want[1] !== null && n > want[1]) return false;
        } else if (String(have) !== want) return false;
      }
      return true;
    }
    case "chance": return rnd() < num(c.v, false);
    case "echance": {
      const lv = ctx.ench[c.e] || 0;
      return rnd() < (lv > 0 ? c.base + c.per * (lv - 1) : c.un);
    }
    case "table": {
      const lv = ctx.ench[c.e] || 0;
      return rnd() < c.ch[Math.min(lv, c.ch.length - 1)];
    }
    case "tool": {
      if (c.ids || c.tags) {
        if (!ctx.tool) return false;
        const byId = (c.ids || []).includes(ctx.tool);
        const byTag = (c.tags || []).some((t) => ctx.tags.includes(t));
        if (!byId && !byTag) return false;
      }
      for (const e of c.ench || []) {
        const ok = e.e.some((name) => {
          const lv = name === "*" ? Math.max(0, ...Object.values(ctx.ench)) : (ctx.ench[name] || 0);
          return lv >= e.min && lv <= e.max;
        });
        if (!ok) return false;
      }
      return true;
    }
    case "weather": {
      let w = "Clear";
      try { w = ctx.dim.getWeather(); } catch (e) {}
      if (c.rain !== null && c.rain !== undefined && (w === "Rain" || w === "Thunder") !== c.rain) return false;
      if (c.thunder !== null && c.thunder !== undefined && (w === "Thunder") !== c.thunder) return false;
      return true;
    }
    case "time": {
      let t = world.getAbsoluteTime ? world.getAbsoluteTime() : world.getTimeOfDay();
      if (c.period) t = t % c.period;
      return t >= c.min && t <= c.max;
    }
  }
  return true;
}

// ---------------------------------------------------------------- funzioni

function apply(fl, stack, ctx) {
  if (!fl) return stack;
  for (const f of fl) {
    if (!test(f.c, ctx)) continue;
    switch (f.k) {
      case "count": {
        const n = Math.floor(num(f.n, true));
        stack.count = f.add ? stack.count + n : n;
        break;
      }
      case "bonus": {
        const lv = ctx.ench[f.e] || 0;
        if (f.fo === "ore_drops") {
          if (lv > 0) { let i = nextInt(lv + 2) - 1; if (i < 0) i = 0; stack.count *= i + 1; }
        } else if (f.fo === "uniform_bonus_count") {
          stack.count += nextInt(Math.round((f.m || 1) * lv) + 1);
        } else if (f.fo === "binomial_with_bonus_count") {
          for (let i = 0; i < lv + (f.x || 0); i++) if (rnd() < (f.pr || 0)) stack.count++;
        }
        break;
      }
      case "limit": {
        if (f.min !== null && f.min !== undefined) stack.count = Math.max(stack.count, Math.floor(num(f.min, true)));
        if (f.max !== null && f.max !== undefined) stack.count = Math.min(stack.count, Math.floor(num(f.max, true)));
        break;
      }
      case "decay": break; // rotto da un giocatore: nessuna esplosione
      case "damage": stack.damage = { n: num(f.n, false), add: f.add }; break;
      case "name": if (f.s) stack.name = f.s; break;
      case "lore": stack.lore = f.l; break;
    }
  }
  return stack;
}

// ---------------------------------------------------------------- tabelle, gruppi e voci

function runTable(t, ctx) {
  const out = [];
  if (!t) return out;
  for (const p of t.p) {
    if (!test(p.c, ctx)) continue;
    const rolls = Math.floor(num(p.r, true));
    for (let i = 0; i < rolls; i++) {
      const picked = pick(p.e, ctx);
      if (!picked) continue;
      for (const s of produce(picked, ctx)) {
        apply(p.f, s, ctx);
        apply(t.f, s, ctx);
        out.push(s);
      }
    }
  }
  return out.filter((s) => s.count > 0);
}

/** Le voci che si possono scegliere (alternative, gruppi e sequenze aperti come fa Java). */
function expand(entries, ctx, into) {
  for (const e of entries) {
    if (!test(e.c, ctx)) continue;
    if (e.t === "a") {
      for (const ch of e.ch) {
        if (test(ch.c, ctx)) { expand([ch], ctx, into); break; }
      }
    } else if (e.t === "g") {
      expand(e.ch, ctx, into);
    } else if (e.t === "s") {
      for (const ch of e.ch) {
        if (!test(ch.c, ctx)) break;
        expand([ch], ctx, into);
      }
    } else if (e.t === "tag" && e.x) {
      for (const id of e.ids) into.push({ t: "i", id, w: e.w, f: e.f });
    } else into.push(e);
  }
  return into;
}

function pick(entries, ctx) {
  const list = expand(entries, ctx, []);
  if (!list.length) return null;
  let total = 0;
  for (const e of list) total += Math.max(0, e.w === undefined ? 1 : e.w);
  if (total <= 0) return null;
  let r = rnd() * total;
  for (const e of list) {
    r -= Math.max(0, e.w === undefined ? 1 : e.w);
    if (r < 0) return e;
  }
  return list[list.length - 1];
}

function produce(e, ctx) {
  if (e.t === "e") return [];
  if (e.t === "l") return runTable(e.tab, ctx);
  if (e.t === "tag") return e.ids.map((id) => apply(e.f, { id, count: 1 }, ctx));
  return [apply(e.f, { id: e.id, count: 1 }, ctx)];
}

// ---------------------------------------------------------------- oggetti nel mondo

function spawn(dim, at, d) {
  let left = d.count;
  while (left > 0) {
    let stack;
    try { stack = new ItemStack(d.id, 1); } catch (e) { warn("oggetto sconosciuto " + d.id); return; }
    const n = Math.min(left, stack.maxAmount);
    stack.amount = n;
    if (d.name) try { stack.nameTag = d.name; } catch (e) {}
    if (d.lore) try { stack.setLore(d.lore); } catch (e) {}
    if (d.damage) {
      try {
        const dur = stack.getComponent("minecraft:durability");
        if (dur) {
          const keep = Math.max(0, Math.min(1, d.damage.n));
          dur.damage = Math.max(0, Math.min(dur.maxDurability, Math.round(dur.maxDurability * (1 - keep))));
        }
      } catch (e) {}
    }
    dim.spawnItem(stack, at);
    left -= n;
  }
}

// Per le prove: /scriptevent mmi:loot_roll <blocco> <attrezzo> <incantesimo:livello,...> <volte>
system.afterEvents.scriptEventReceive.subscribe((ev) => {
  if (ev.id !== "mmi:loot_roll") return;
  const [block, tool, ench, times] = ev.message.trim().split(/\s+/);
  const spec = LOOT[block];
  if (!spec) { console.warn("[mmi loot] nessuna tabella per " + block); return; }
  const en = {};
  for (const x of (ench && ench !== "-" ? ench.split(",") : [])) { const [n, l] = x.split(":"); en[n] = Number(l); }
  const sum = {};
  const n = Number(times) || 100;
  for (let i = 0; i < n; i++) {
    for (const d of runTable(TABLES[spec.t], { tool: tool === "-" ? undefined : tool, tags: [], ench: en, state: spec.js || {}, dim: ev.sourceEntity?.dimension })) {
      sum[d.id] = (sum[d.id] || 0) + d.count;
    }
  }
  console.warn("[mmi loot] " + JSON.stringify({ block, tool, ench: en, rolls: n, totals: sum }));
});
