// Scritto da Minecraft Mod Installer (JAVA TO BEDROCK): le armi da fuoco dei pacchetti di TACZ ricostruite con la Script API.
// Comandi: tieni premuto «usa» per sparare (raffica per le armi automatiche); accovacciati per mirare (colpi più precisi);
// accovacciati e salta per ricaricare (si ricarica anche da sola quando il caricatore è vuoto); accovacciati e colpisci
// l'aria per cambiare modalità di fuoco. I banchi della mod (armaiolo, munizioni) si usano toccandoli.
import { system, world, ItemStack, GameMode } from "@minecraft/server";
import { ActionFormData, MessageFormData } from "@minecraft/server-ui";
import { GUNS, AMMO, TABLES, RECIPES } from "./mmi_tacz_data.js";

const AMMO_PROP = "mmi:ammo";
const MODE_PROP = "mmi:mode";
const SKIP = new Set(["minecraft:item", "minecraft:xp_orb", "minecraft:arrow", "minecraft:snowball", "minecraft:egg", "minecraft:thrown_trident",
  "minecraft:ender_pearl", "minecraft:fireball", "minecraft:small_fireball", "minecraft:wind_charge_projectile", "minecraft:fishing_hook",
  "minecraft:painting", "minecraft:leash_knot", "minecraft:area_effect_cloud", "minecraft:lightning_bolt", "minecraft:tnt", "minecraft:falling_block"]);
const state = new Map();

function warn(e) { console.warn("[mmi tacz] " + e); }

function st(p) {
  let s = state.get(p.id);
  if (!s) {
    s = { firing: false, slot: -1, gun: null, mag: 0, dirty: false, nextAt: 0, burst: 0, mode: null, reload: null, drawUntil: 0, lastSlot: -1, lastGun: null, hud: 0 };
    state.set(p.id, s);
  }
  return s;
}

function inv(p) { return p.getComponent("minecraft:inventory")?.container; }

function held(p) {
  const c = inv(p);
  if (!c) return null;
  const slot = p.selectedSlotIndex;
  const item = c.getItem(slot);
  if (!item) return null;
  const g = GUNS[item.typeId];
  return g ? { c, slot, item, g } : null;
}

function creative(p) {
  try { return p.getGameMode() === GameMode.Creative; } catch (e) { return false; }
}

function readMag(item) {
  const v = item.getDynamicProperty(AMMO_PROP);
  return typeof v === "number" ? v : 0;
}

function modeOf(item, g) {
  const v = item.getDynamicProperty(MODE_PROP);
  return typeof v === "string" && g.modes.includes(v) ? v : g.modes[0];
}

/** Scrive nell'oggetto le munizioni rimaste (solo quando non si spara: cambiare l'oggetto in mano a metà raffica non serve). */
function commit(p, s) {
  if (!s.dirty || s.slot < 0) return;
  s.dirty = false;
  const c = inv(p);
  if (!c) return;
  const item = c.getItem(s.slot);
  if (!item || item.typeId !== s.gun) return;
  const g = GUNS[s.gun];
  item.setDynamicProperty(AMMO_PROP, s.mag);
  if (s.mode) item.setDynamicProperty(MODE_PROP, s.mode);
  if (!g.inv) item.setLore(["§7Munizioni: §f" + s.mag + "§7/" + g.mag]);
  c.setItem(s.slot, item);
}

function countAmmo(p, id) {
  const c = inv(p);
  if (!c || !id) return 0;
  let n = 0;
  for (let i = 0; i < c.size; i++) {
    const it = c.getItem(i);
    if (it && it.typeId === id) n += it.amount;
  }
  return n;
}

function takeAmmo(p, id, want) {
  const c = inv(p);
  if (!c || !id || want <= 0) return 0;
  let got = 0;
  for (let i = 0; i < c.size && got < want; i++) {
    const it = c.getItem(i);
    if (!it || it.typeId !== id) continue;
    const take = Math.min(it.amount, want - got);
    got += take;
    if (take >= it.amount) c.setItem(i, undefined);
    else { it.amount -= take; c.setItem(i, it); }
  }
  return got;
}

function play(p, id, others) {
  if (!id) return;
  try { p.playSound(id, { volume: 1 }); } catch (e) {}
  if (!others) return;
  try {
    for (const o of p.dimension.getPlayers({ location: p.location, maxDistance: 96 })) {
      if (o.id !== p.id) o.playSound(others, { location: p.location, volume: 1 });
    }
  } catch (e) {}
}

function timed(p, list, token) {
  if (!list) return;
  for (const [t, id] of list) {
    system.runTimeout(() => {
      if (!p.isValid) return;
      const s = st(p);
      if (token !== undefined && (!s.reload || s.reload.token !== token)) return;
      play(p, id, null);
    }, Math.max(0, Math.round(t * 20)));
  }
}

function name(g) { return { translate: g.name }; }

function hud(p, s, h) {
  const g = h.g;
  const parts = [name(g)];
  if (s.reload && s.reload.gun === h.item.typeId) {
    const left = Math.max(0, Math.ceil((s.reload.end - system.currentTick) / 2) / 10);
    parts.push({ text: "  §eRicarica… " + left.toFixed(1) + " s" });
  } else {
    const mag = s.gun === h.item.typeId && s.slot === h.slot ? s.mag : readMag(h.item);
    const mode = s.gun === h.item.typeId && s.mode ? s.mode : modeOf(h.item, g);
    const reserve = g.ammo ? countAmmo(p, g.ammo) : 0;
    if (g.inv) parts.push({ text: "  §f" + (creative(p) ? "∞" : reserve) });
    else parts.push({ text: "  §f" + mag + "§7/" + g.mag + "  §8" + (creative(p) ? "∞" : reserve) });
    parts.push({ text: "  §7" + (mode === "auto" ? "Auto" : mode === "burst" ? "Raffica" : "Colpo singolo") });
  }
  try { p.onScreenDisplay.setActionBar({ rawtext: parts }); } catch (e) {}
}

function startFire(p, h) {
  const s = st(p);
  if (s.reload) return;
  if (system.currentTick < s.drawUntil) return;
  if (s.gun !== h.item.typeId || s.slot !== h.slot) {
    commit(p, s);
    s.gun = h.item.typeId; s.slot = h.slot; s.mag = readMag(h.item); s.mode = modeOf(h.item, h.g); s.dirty = false;
  }
  s.firing = true;
  s.burst = s.mode === "burst" ? (h.g.burst ? h.g.burst.count : 3) : 0;
  if (system.currentTick >= s.nextAt) shoot(p, s, h);
}

function stopFire(p, s) {
  s.firing = false;
  s.burst = 0;
  commit(p, s);
}

function spread(dir, inacc) {
  const dev = 0.0172275 * inacc;
  const x = dir.x + dev * (Math.random() - Math.random());
  const y = dir.y + dev * (Math.random() - Math.random());
  const z = dir.z + dev * (Math.random() - Math.random());
  const l = Math.hypot(x, y, z) || 1;
  return { x: x / l, y: y / l, z: z / l };
}

function damageAt(g, d) {
  if (!g.falloff) return g.dmg;
  for (const [dist, dmg] of g.falloff) if (d <= dist) return dmg;
  return g.falloff.length ? g.falloff[g.falloff.length - 1][1] : g.dmg;
}

function hurt(p, e, dmg) {
  try { return e.applyDamage(Math.max(0, dmg), { cause: "override", damagingEntity: p }); } catch (x) { return false; }
}

function explode(p, g, at) {
  const ex = g.expl;
  try {
    for (const e of p.dimension.getEntities({ location: at, maxDistance: ex.r })) {
      if (SKIP.has(e.typeId) || !e.getComponent("minecraft:health")) continue;
      const l = e.location;
      const d = Math.hypot(l.x - at.x, l.y - at.y, l.z - at.z);
      hurt(p, e, ex.dmg * Math.max(0, 1 - d / ex.r));
    }
  } catch (x) { warn(x); }
  const opts = { breaksBlocks: !!ex.destroy, causesFire: false, allowUnderwater: true };
  try { p.dimension.createExplosion(at, Math.min(ex.r, 6), Object.assign({ source: p }, opts)); }
  catch (x) { try { p.dimension.createExplosion(at, Math.min(ex.r, 6), opts); } catch (y) { warn(y); } }
}

function bullet(p, g, origin, dir) {
  const dim = p.dimension;
  let max = g.range;
  let blockAt = null;
  try {
    const b = dim.getBlockFromRay(origin, dir, { maxDistance: g.range, includeLiquidBlocks: false, includePassableBlocks: false });
    if (b) {
      blockAt = { x: b.block.location.x + b.faceLocation.x, y: b.block.location.y + b.faceLocation.y, z: b.block.location.z + b.faceLocation.z };
      max = Math.hypot(blockAt.x - origin.x, blockAt.y - origin.y, blockAt.z - origin.z);
    }
  } catch (x) {}
  let hits = [];
  try { hits = dim.getEntitiesFromRay(origin, dir, { maxDistance: Math.max(0.1, max) }); } catch (x) {}
  let left = Math.max(1, g.pierce);
  let end = blockAt;
  for (const h of hits) {
    const e = h.entity;
    if (!e || e.id === p.id || SKIP.has(e.typeId) || !e.getComponent("minecraft:health")) continue;
    const at = { x: origin.x + dir.x * h.distance, y: origin.y + dir.y * h.distance, z: origin.z + dir.z * h.distance };
    let dmg = damageAt(g, h.distance);
    let head = false;
    try { head = at.y >= e.getHeadLocation().y - 0.25; } catch (x) {}
    if (head) dmg *= g.head;
    const before = e.getComponent("minecraft:health")?.currentValue ?? 0;
    hurt(p, e, dmg);
    const dead = !e.isValid || (e.getComponent("minecraft:health")?.currentValue ?? 0) <= 0;
    play(p, dead ? g.snd.kill : head ? g.snd.head : g.snd.flesh, null);
    if (g.kb > 0 && e.isValid) { try { e.applyKnockback({ x: dir.x * g.kb, z: dir.z * g.kb }, 0.1); } catch (x) {} }
    if (g.fire > 0 && e.isValid && before > 0) { try { e.setOnFire(g.fire, true); } catch (x) {} }
    end = at;
    if (--left <= 0) break;
  }
  if (!end) end = { x: origin.x + dir.x * g.range, y: origin.y + dir.y * g.range, z: origin.z + dir.z * g.range };
  if (g.expl) explode(p, g, end);
  else if (blockAt && end === blockAt) { try { dim.spawnParticle("minecraft:basic_smoke_particle", blockAt); } catch (x) {} }
  if (g.tracer) {
    const d = Math.hypot(end.x - origin.x, end.y - origin.y, end.z - origin.z);
    const n = Math.min(8, Math.floor(d / 3));
    for (let i = 1; i <= n; i++) {
      const k = (i * 3) / d;
      try { dim.spawnParticle("minecraft:basic_crit_particle", { x: origin.x + (end.x - origin.x) * k, y: origin.y + (end.y - origin.y) * k, z: origin.z + (end.z - origin.z) * k }); } catch (x) {}
    }
  }
}

function shoot(p, s, h) {
  const g = h.g;
  const now = system.currentTick;
  const free = creative(p);
  if (g.inv) {
    if (!free && takeAmmo(p, g.ammo, 1) < 1) { play(p, g.snd.dry, null); stopFire(p, s); return; }
  } else {
    if (s.mag <= 0) {
      play(p, g.snd.dry, null);
      stopFire(p, s);
      startReload(p, h);
      return;
    }
    if (!free) { s.mag--; s.dirty = true; }
  }
  const origin = p.getHeadLocation();
  const view = p.getViewDirection();
  let inacc = g.inacc.stand;
  try {
    const v = p.getVelocity();
    if (p.isSneaking) inacc = g.inacc.aim;
    else if (Math.hypot(v.x, v.z) > 0.08) inacc = g.inacc.move;
  } catch (x) {}
  for (let i = 0; i < g.pellets; i++) bullet(p, g, origin, spread(view, inacc));
  play(p, g.snd.shoot, g.snd.shoot3p || g.snd.shoot);
  if (g.shake > 0) { try { p.runCommand("camerashake add @s " + g.shake + " 0.08 rotational"); } catch (x) {} }
  // Quando si può sparare di nuovo: il ritmo dell'arma (colpi al minuto) o il tempo per armare l'otturatore.
  let gap = 1200 / Math.max(1, g.rpm);
  if (s.mode === "burst" && g.burst && s.burst > 1) gap = 1200 / Math.max(1, g.burst.bpm);
  if (g.bolt > 0) gap = Math.max(gap, g.bolt * 20);
  s.nextAt = Math.max(s.nextAt, now - 1) + gap;
  if (s.nextAt < now) s.nextAt = now + gap;
  if (s.mode === "semi") s.firing = false;
  else if (s.mode === "burst") {
    s.burst--;
    if (s.burst <= 0) { s.firing = false; if (g.burst) s.nextAt = Math.max(s.nextAt, now + g.burst.interval * 20); }
  }
  if (!s.firing) commit(p, s);
  hud(p, s, h);
}

function startReload(p, h) {
  const s = st(p);
  const g = h.g;
  if (g.inv || s.reload) return;
  if (s.gun !== h.item.typeId || s.slot !== h.slot) {
    commit(p, s);
    s.gun = h.item.typeId; s.slot = h.slot; s.mag = readMag(h.item); s.mode = modeOf(h.item, g); s.dirty = false;
  }
  if (s.mag >= g.mag) return;
  const free = creative(p);
  if (!free && countAmmo(p, g.ammo) <= 0) {
    try { p.onScreenDisplay.setActionBar({ rawtext: [{ text: "§cNessuna munizione: " }, { translate: AMMO[g.ammo] ? AMMO[g.ammo].name : g.ammo }] }); } catch (x) {}
    return;
  }
  stopFire(p, s);
  const empty = s.mag <= 0;
  const r = g.reload;
  const now = system.currentTick;
  const token = now * 1000 + Math.floor(Math.random() * 1000);
  s.reload = { gun: h.item.typeId, slot: h.slot, feedAt: now + Math.round((empty ? r.ef : r.tf) * 20), end: now + Math.round((empty ? r.ec : r.tc) * 20), token, fed: false };
  if (s.reload.end < s.reload.feedAt) s.reload.end = s.reload.feedAt;
  timed(p, empty ? g.snd.re : g.snd.rt, token);
}

function tickReload(p, s) {
  const r = s.reload;
  if (!r) return;
  const h = held(p);
  if (!h || h.slot !== r.slot || h.item.typeId !== r.gun) { s.reload = null; return; }
  const now = system.currentTick;
  if (!r.fed && now >= r.feedAt) {
    r.fed = true;
    const g = h.g;
    const need = g.mag - s.mag;
    let got;
    if (creative(p)) got = need;
    else if (g.fuel) got = takeAmmo(p, g.ammo, 1) > 0 ? need : 0;
    else got = takeAmmo(p, g.ammo, need);
    s.mag += got;
    s.dirty = true;
    commit(p, s);
  }
  if (now >= r.end) s.reload = null;
}

function cycleMode(p, h) {
  const s = st(p);
  const g = h.g;
  if (g.modes.length < 2) return;
  if (s.gun !== h.item.typeId || s.slot !== h.slot) {
    commit(p, s);
    s.gun = h.item.typeId; s.slot = h.slot; s.mag = readMag(h.item); s.mode = modeOf(h.item, g);
  }
  s.mode = g.modes[(g.modes.indexOf(s.mode) + 1) % g.modes.length];
  s.dirty = true;
  stopFire(p, s);
  play(p, g.snd.mode, null);
  hud(p, s, h);
}

// ---- il turno di gioco: raffiche, ricariche e il riquadro delle munizioni
system.runInterval(() => {
  const now = system.currentTick;
  for (const p of world.getAllPlayers()) {
    const s = state.get(p.id);
    const h = held(p);
    const key = h ? h.slot + ":" + h.item.typeId : "";
    if (s && key !== (s.lastKey ?? "")) {
      stopFire(p, s);
      s.reload = null;
      s.lastKey = key;
      if (h) {
        s.drawUntil = now + Math.round(h.g.draw * 20);
        timed(p, h.g.snd.draw);
      }
    } else if (!s && h) {
      const ns = st(p);
      ns.lastKey = key;
    }
    if (!s && !h) continue;
    const cur = st(p);
    if (cur.reload) tickReload(p, cur);
    if (h && cur.firing && cur.gun === h.item.typeId && cur.slot === h.slot && now >= cur.nextAt) {
      try { shoot(p, cur, h); } catch (x) { warn(x); stopFire(p, cur); }
    }
    if (h && (now - cur.hud >= 5)) { cur.hud = now; hud(p, cur, h); }
  }
}, 1);

world.afterEvents.itemStartUse.subscribe((ev) => {
  const p = ev.source;
  if (!p || p.typeId !== "minecraft:player" || !ev.itemStack || !GUNS[ev.itemStack.typeId]) return;
  const h = held(p);
  if (h) startFire(p, h);
});
function released(ev) {
  const p = ev.source;
  if (!p || p.typeId !== "minecraft:player") return;
  const s = state.get(p.id);
  if (s && s.firing && s.mode === "auto") stopFire(p, s);
}
world.afterEvents.itemStopUse.subscribe(released);
world.afterEvents.itemReleaseUse.subscribe(released);
try {
  world.afterEvents.playerButtonInput.subscribe((ev) => {
    const p = ev.player;
    if (ev.button !== "Jump" || ev.newButtonState !== "Pressed" || !p.isSneaking) return;
    const h = held(p);
    if (h) startReload(p, h);
  });
} catch (x) { warn("ricarica con salto non disponibile: " + x); }
try {
  world.afterEvents.playerSwingStart.subscribe((ev) => {
    const p = ev.player;
    if (!p.isSneaking) return;
    const h = held(p);
    if (h) cycleMode(p, h);
  });
} catch (x) { warn("cambio di modalità non disponibile: " + x); }
world.afterEvents.playerLeave.subscribe((ev) => state.delete(ev.playerId));

// ---- i banchi della mod: ricette con i materiali dell'inventario (in Java è una schermata della mod)
function matches(it, m) {
  if (!it) return false;
  if (m.items && m.items.includes(it.typeId)) return true;
  if (m.tag) { try { return it.hasTag(m.tag); } catch (x) { return false; } }
  return false;
}

function have(p, m) {
  const c = inv(p);
  let n = 0;
  if (!c) return 0;
  for (let i = 0; i < c.size; i++) { const it = c.getItem(i); if (matches(it, m)) n += it.amount; }
  return n;
}

function consume(p, m, want) {
  const c = inv(p);
  let got = 0;
  for (let i = 0; i < c.size && got < want; i++) {
    const it = c.getItem(i);
    if (!matches(it, m)) continue;
    const take = Math.min(it.amount, want - got);
    got += take;
    if (take >= it.amount) c.setItem(i, undefined); else { it.amount -= take; c.setItem(i, it); }
  }
}

function canCraft(p, r) { return creative(p) || r.materials.every((m) => have(p, m) >= m.count); }

function craft(p, r) {
  if (!canCraft(p, r)) return false;
  if (!creative(p)) for (const m of r.materials) consume(p, m, m.count);
  const stack = new ItemStack(r.result, Math.max(1, Math.min(r.count, 64)));
  const left = inv(p).addItem(stack);
  if (left) p.dimension.spawnItem(left, p.location);
  return true;
}

function materialText(p, r) {
  const out = [];
  for (const m of r.materials) {
    const ok = creative(p) || have(p, m) >= m.count;
    out.push({ text: (ok ? "§a" : "§c") + m.count + "× " });
    out.push(m.name ? { translate: m.name } : { text: m.label });
    out.push({ text: "§r\n" });
  }
  return { rawtext: out };
}

function openRecipe(p, table, tab, r) {
  new MessageFormData()
    .title({ rawtext: [{ translate: r.name }, { text: r.count > 1 ? " ×" + r.count : "" }] })
    .body(materialText(p, r))
    .button1("Crea")
    .button2("Indietro")
    .show(p)
    .then((res) => {
      if (res.canceled) return;
      if (res.selection === 0) {
        if (craft(p, r)) p.playSound("random.anvil_use", { volume: 0.6 });
        else p.onScreenDisplay.setActionBar("§cMancano dei materiali");
        system.run(() => openRecipe(p, table, tab, r));
      } else system.run(() => openTab(p, table, tab));
    })
    .catch(warn);
}

function openTab(p, table, tab) {
  const list = RECIPES.filter((r) => r.tab === tab.id);
  const form = new ActionFormData().title({ translate: tab.name });
  for (const r of list) {
    const label = { rawtext: [{ text: canCraft(p, r) ? "§2" : "§8" }, { translate: r.name }, { text: r.count > 1 ? " ×" + r.count : "" }] };
    form.button(label, r.icon);
  }
  form.button("Indietro");
  form.show(p).then((res) => {
    if (res.canceled) return;
    if (res.selection === list.length) system.run(() => openTable(p, table));
    else if (res.selection !== undefined) system.run(() => openRecipe(p, table, tab, list[res.selection]));
  }).catch(warn);
}

function openTable(p, table) {
  const t = TABLES[table];
  if (!t) return;
  const tabs = t.tabs.filter((tab) => RECIPES.some((r) => r.tab === tab.id));
  if (!tabs.length) { p.onScreenDisplay.setActionBar(t.empty || "§7Niente da creare qui"); return; }
  const form = new ActionFormData().title({ translate: t.name });
  for (const tab of tabs) form.button({ translate: tab.name }, tab.icon);
  form.show(p).then((res) => {
    if (res.canceled || res.selection === undefined) return;
    system.run(() => openTab(p, table, tabs[res.selection]));
  }).catch(warn);
}

system.beforeEvents.startup.subscribe((ev) => {
  ev.itemComponentRegistry.registerCustomComponent("mmi:tacz_gun", { onUse() {} });
  ev.blockComponentRegistry.registerCustomComponent("mmi:tacz_table", {
    onPlayerInteract(e) {
      const p = e.player;
      if (!p) return;
      system.run(() => openTable(p, e.block.typeId));
    }
  });
});

// Per le prove (e per chi ha i comandi attivi): /scriptevent mmi:tacz_craft <oggetto>, /scriptevent mmi:tacz_reload
system.afterEvents.scriptEventReceive.subscribe((ev) => {
  const p = ev.sourceEntity;
  if (!p || p.typeId !== "minecraft:player") return;
  if (ev.id === "mmi:tacz_craft") {
    const r = RECIPES.find((x) => x.result === ev.message.trim());
    console.warn("[mmi tacz] crea " + ev.message + ": " + (r ? craft(p, r) : "ricetta inesistente"));
  } else if (ev.id === "mmi:tacz_reload") {
    const h = held(p);
    if (h) startReload(p, h);
  } else if (ev.id === "mmi:tacz_state") {
    const h = held(p);
    const s = st(p);
    console.warn("[mmi tacz] stato " + JSON.stringify({ gun: h && h.item.typeId, mag: h ? (s.gun === h.item.typeId ? s.mag : readMag(h.item)) : null, saved: h ? readMag(h.item) : null, reserve: h && h.g.ammo ? countAmmo(p, h.g.ammo) : null, mode: s.mode, reload: !!s.reload }));
  }
});
