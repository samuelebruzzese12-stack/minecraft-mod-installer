// Progressi delle mod Java rifatti con la Script API: le stesse condizioni (oggetti, creature, blocchi, cibi, posti,
// effetti, cavalcature, dimensioni), l'avviso e il messaggio in chat quando si completano, le ricompense e l'elenco con
// il comando /mmi:progressi. Scritto da Minecraft Mod Installer.
import { system, world, CommandPermissionLevel, CustomCommandStatus, EquipmentSlot } from "@minecraft/server";
import { ActionFormData, MessageFormData } from "@minecraft/server-ui";
import { A } from "./mmi_advancements_data.js";

const ADV = A.a;
const BY = new Map(ADV.map((a, i) => [a.id, i]));
const KIND = new Map(); // tipo di condizione → [indice del progresso, nome della condizione, condizione]
ADV.forEach((a, i) => { for (const [name, c] of Object.entries(a.c)) { if (!a.ok) continue; if (!KIND.has(c.k)) KIND.set(c.k, []); KIND.get(c.k).push([i, name, c]); } });
const has = (k) => KIND.has(k) && KIND.get(k).length > 0;
const short = (id) => (id ?? "").replace(/^minecraft:/, "");

// ---- avanzamento di ogni giocatore (proprietà del giocatore)
const CACHE = new Map();
function prog(p) {
  let pr = CACHE.get(p.id);
  if (!pr) {
    try { pr = JSON.parse(p.getDynamicProperty("mmi_adv") ?? "{}"); } catch (e) { pr = {}; }
    pr.d ??= {}; pr.c ??= {};
    CACHE.set(p.id, pr);
  }
  return pr;
}
function store(p, pr) { try { p.setDynamicProperty("mmi_adv", JSON.stringify(pr)); } catch (e) {} }

function grant(p, ai, crit) {
  const a = ADV[ai], pr = prog(p);
  if (pr.d[a.id]) return;
  const got = new Set(pr.c[a.id] ?? []);
  if (got.has(crit)) return;
  got.add(crit);
  pr.c[a.id] = [...got];
  if (a.r.every((g) => g.some((c) => got.has(c)))) complete(p, a, pr);
  store(p, pr);
}
function complete(p, a, pr) {
  pr.d[a.id] = 1;
  delete pr.c[a.id];
  const frame = a.f === "goal" || a.f === "challenge" ? a.f : "task";
  if (a.s) {
    p.onScreenDisplay.setActionBar({ rawtext: [{ text: frame === "challenge" ? "§5" : "§e" }, { translate: "mmi.adv.toast." + frame }, { text: " §f" }, { translate: a.t }] });
    try { p.playSound("random.levelup", { volume: 0.6, pitch: frame === "challenge" ? 0.8 : 1 }); } catch (e) {}
  }
  if (a.a) world.sendMessage({ translate: "mmi.adv.chat." + frame, with: { rawtext: [{ text: p.name }, { rawtext: [{ text: frame === "challenge" ? "§5[" : "§a[" }, { translate: a.t }, { text: "]§r" }] }] } });
  if (a.x > 0) p.addExperience(a.x);
  for (const t of a.l ?? []) { try { p.runCommand('loot give @s loot "' + t.replace(/^loot_tables\//, "").replace(/\.json$/, "") + '"'); } catch (e) {} }
}
function check(p, kind, test) {
  const list = KIND.get(kind);
  if (!list) return;
  const pr = prog(p);
  for (const [ai, name, c] of list) {
    const a = ADV[ai];
    if (pr.d[a.id] || (pr.c[a.id] ?? []).includes(name)) continue;
    let ok = false;
    try { ok = test(c); } catch (e) { ok = false; }
    if (ok) grant(p, ai, name);
  }
}

// ---- confronti come in Java
function itemOk(q, stack) {
  if (!q) return true;
  if (!stack) return q.i === null && !q.n;
  if (q.i && !q.i.includes(stack.typeId)) return false;
  if (q.n && (stack.amount < q.n[0] || stack.amount > q.n[1])) return false;
  return true;
}
const entityOk = (list, ent) => !list || list.includes(null) || (ent && list.includes(ent.typeId));
const blockOk = (list, block) => !list || list.length === 0 || (block && list.includes(block.typeId));
function stacks(p) {
  const out = [];
  const inv = p.getComponent("minecraft:inventory")?.container;
  if (inv) for (let i = 0; i < inv.size; i++) { const it = inv.getItem(i); if (it) out.push(it); }
  const eq = p.getComponent("minecraft:equippable");
  if (eq) for (const s of [EquipmentSlot.Head, EquipmentSlot.Chest, EquipmentSlot.Legs, EquipmentSlot.Feet, EquipmentSlot.Offhand]) { const it = eq.getEquipment(s); if (it) out.push(it); }
  return out;
}
function inventory(p) {
  if (!has("inv")) return;
  const all = stacks(p);
  check(p, "inv", (c) => c.it.every((q) => all.some((s) => itemOk(q, s))));
}
function where(p) {
  const dim = p.dimension, loc = p.location;
  let biome = null;
  const at = (dy) => { try { return dim.getBlock({ x: Math.floor(loc.x), y: Math.floor(loc.y) + dy, z: Math.floor(loc.z) }); } catch (e) { return undefined; } };
  if (has("loc")) check(p, "loc", (c) => {
    if (c.dm && dim.id !== c.dm) return false;
    if (c.y && (loc.y < c.y[0] || loc.y > c.y[1])) return false;
    if (c.b && !blockOk(c.b, at(0))) return false;
    if (c.bi) { if (biome === null) { const b = dim.getBiome(loc); biome = typeof b === "string" ? b : b?.id; } if (!c.bi.includes(biome)) return false; }
    if (c.st) { const ids = globalThis.mmiStructureAt?.(dim.id, loc) ?? []; if (!c.st.some((id) => ids.includes(id))) return false; }
    return true;
  });
  if (has("enter")) check(p, "enter", (c) => blockOk(c.b, at(0)) || blockOk(c.b, at(1)));
  if (has("eff")) { const effs = p.getEffects().map((e) => short(e.typeId)); check(p, "eff", (c) => c.ef.every((e) => effs.includes(short(e)))); }
  if (has("ride")) { const v = p.getComponent("minecraft:riding")?.entityRidingOn; if (v) check(p, "ride", (c) => entityOk(c.e, v)); }
  if (has("tick")) check(p, "tick", () => true);
}

// ---- eventi di Bedrock al posto degli eventi di Java
const isPlayer = (e) => e && e.typeId === "minecraft:player";
if (has("kill")) world.afterEvents.entityDie.subscribe((ev) => { const p = ev.damageSource?.damagingEntity; if (isPlayer(p)) check(p, "kill", (c) => entityOk(c.e, ev.deadEntity)); });
if (has("hurt")) world.afterEvents.entityHurt.subscribe((ev) => { const p = ev.damageSource?.damagingEntity; if (isPlayer(p)) check(p, "hurt", (c) => entityOk(c.e, ev.hurtEntity)); });
if (has("eat")) world.afterEvents.itemCompleteUse.subscribe((ev) => { if (isPlayer(ev.source)) check(ev.source, "eat", (c) => itemOk(c.it, ev.itemStack)); });
if (has("using")) world.afterEvents.itemStartUse.subscribe((ev) => { if (isPlayer(ev.source)) check(ev.source, "using", (c) => itemOk(c.it, ev.itemStack)); });
if (has("place")) world.afterEvents.playerPlaceBlock.subscribe((ev) => check(ev.player, "place", (c) => blockOk(c.b, ev.block) && (!c.it?.i || c.it.i.includes(ev.block.typeId))));
if (has("useon")) world.afterEvents.playerInteractWithBlock.subscribe((ev) => check(ev.player, "useon", (c) => blockOk(c.b, ev.block) && itemOk(c.it, ev.itemStack)));
if (has("interact")) world.afterEvents.playerInteractWithEntity.subscribe((ev) => check(ev.player, "interact", (c) => entityOk(c.e, ev.target) && itemOk(c.it, ev.itemStack)));
if (has("dim")) world.afterEvents.playerDimensionChange.subscribe((ev) => check(ev.player, "dim", (c) => (!c.to || ev.toDimension.id === c.to) && (!c.from || ev.fromDimension.id === c.from)));
if (has("inv")) world.afterEvents.playerInventoryItemChange.subscribe((ev) => inventory(ev.player));
system.runInterval(() => { for (const p of world.getAllPlayers()) { inventory(p); where(p); } }, 20);

// ---- elenco dei progressi: /mmi:progressi (o /scriptevent mmi:progressi)
function show(p) {
  const pr = prog(p);
  const list = ADV.map((a, i) => i).filter((i) => !ADV[i].h || pr.d[ADV[i].id]);
  const form = new ActionFormData().title({ translate: "mmi.adv.title" });
  const done = list.filter((i) => pr.d[ADV[i].id]).length;
  form.body(done + " / " + list.length);
  for (const i of list) {
    const a = ADV[i];
    const mark = pr.d[a.id] ? "§2✔ " : a.ok ? "§8• " : "§8✖ ";
    form.button({ rawtext: [{ text: mark }, { translate: a.t }] });
  }
  form.show(p).then((r) => {
    if (r.canceled || r.selection === undefined) return;
    const a = ADV[list[r.selection]];
    const got = (pr.c[a.id] ?? []).length, need = a.r.length;
    const state = pr.d[a.id] ? "§2✔" : a.ok ? "§7" + got + " / " + need : "§c✖";
    new MessageFormData().title({ translate: a.t })
      .body({ rawtext: [{ translate: a.d }, { text: "\n\n" + state }] })
      .button1({ translate: "mmi.adv.title" }).button2("OK")
      .show(p).then((m) => { if (m.selection === 0) show(p); });
  }).catch(() => {});
}
system.afterEvents.scriptEventReceive.subscribe((ev) => {
  const p = ev.sourceEntity;
  if (ev.id === "mmi:progressi" && isPlayer(p)) show(p);
  if (ev.id === "mmi:progressi_stato" && isPlayer(p)) console.warn("[mmi progressi] " + JSON.stringify(prog(p)));
});
try {
  system.beforeEvents.startup.subscribe((ev) => {
    ev.customCommandRegistry.registerCommand(
      { name: "mmi:progressi", description: "Progressi delle mod", permissionLevel: CommandPermissionLevel.Any, cheatsRequired: false },
      (origin) => { const p = origin.sourceEntity; if (isPlayer(p)) system.run(() => show(p)); return { status: CustomCommandStatus.Success }; }
    );
  });
} catch (e) {}
