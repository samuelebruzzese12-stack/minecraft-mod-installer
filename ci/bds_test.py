#!/usr/bin/env python3
"""Prova gli add-on convertiti da JAVA TO BEDROCK nel server Bedrock ufficiale di Mojang (Bedrock Dedicated Server).

Uso: python3 -I ci/bds_test.py CARTELLA_BDS USCITA FILE.mcaddon [FILE2.mcaddon ...]

Per ogni add-on: un mondo nuovo con l'add-on e un pacchetto di prova (GameTest, esperimento «Beta APIs» solo nel mondo di
prova), il server parte, carica i pacchetti e fa le prove dentro il gioco vero:
  * registrazione: ogni blocco, oggetto e creatura dell'add-on esiste per il gioco (BlockTypes/ItemTypes/EntityTypes);
  * blocchi: ognuno si piazza nel mondo e resta quel blocco;
  * oggetti: pila massima, durata e cibo hanno nel gioco i valori scritti dalla conversione;
  * creature: ognuna nasce, è ancora viva dopo un secondo e ha la vita prevista;
  * comportamenti ricostruiti con la Script API, con un giocatore simulato: gli attrezzi si consumano scavando, i cibi danno
    i loro effetti, i minerali lasciano esperienza (alcuni per tipo);
  * blocchi con più stati, piazzati dal giocatore simulato (uno per famiglia): la porta mette la metà di sopra, si apre e
    si rompe tutta; la lastra sopra un'altra diventa doppia; botole e cancelli si aprono e chiudono; staccionate, muretti e
    pannelli si collegano; il pulsante dà il segnale di pietrarossa; la pedana si accende sotto una creatura; la coltura
    cresce con la farina d'ossa;
  * registro dei contenuti (content log) senza errori.
Scrive USCITA/<add-on>.json con gli esiti e USCITA/riepilogo.txt. Esce con 1 se qualcosa non va.
Usare il server Bedrock implica accettare l'EULA di Minecraft e l'informativa sulla privacy di Microsoft.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lenbt  # noqa: E402

BATCH = 180          # blocchi per prova (una prova = una struttura 14×14 con 1+ strati)
TIMEOUT = 240        # secondi al massimo per add-on


def read_addon(path):
    """Cartelle BP/RP dell'add-on, manifest e contenuto (identificatori e valori attesi)."""
    z = zipfile.ZipFile(path)
    names = z.namelist()
    packs = {}
    for n in names:
        if n.endswith("manifest.json") and n.count("/") == 1:
            m = json.loads(z.read(n))
            kind = m["modules"][0]["type"]
            packs[kind] = (n.split("/")[0], m)
    blocks, items, entities = [], [], []
    behaviors = {"tools": [], "foods": [], "ores": [], "stateful": []}
    bp = packs.get("data", (None,))[0]
    for n in names:
        if not bp or not n.startswith(bp + "/") or not n.endswith(".json"):
            continue
        rel = n[len(bp) + 1:]
        try:
            d = json.loads(z.read(n))
        except Exception:
            continue
        if rel.startswith("blocks/") and "minecraft:block" in d:
            bid = d["minecraft:block"]["description"]["identifier"]
            blocks.append(bid)
            comps = d["minecraft:block"].get("components", {})
            traits = d["minecraft:block"]["description"].get("traits", {})
            kind = None
            if "mmi:openable" in comps:
                kind = "door" if comps["mmi:openable"].get("pair") else ("gate" if comps["mmi:openable"].get("gate") else "trapdoor")
                if comps["mmi:openable"].get("hand") is False:
                    kind = None   # ferro: si apre solo con la pietrarossa
            elif "mmi:slab" in comps:
                kind = "slab"
            elif "mmi:button" in comps:
                kind = "button"
            elif "mmi:pressure_plate" in comps:
                kind = "plate"
            elif "mmi:crop" in comps:
                kind = "crop"
            elif "minecraft:connection" in traits:
                kind = "connect"
            elif "minecraft:corner_and_cardinal_direction" in str(traits):
                kind = "stairs"
            if kind and not any(x["kind"] == kind for x in behaviors["stateful"]):
                behaviors["stateful"].append({"id": bid, "kind": kind})
            xp = comps.get("mmi:xp_on_break")
            mining = comps.get("minecraft:destructible_by_mining")
            secs = mining.get("seconds_to_destroy", 1) if isinstance(mining, dict) else 1
            if xp and isinstance(mining, dict):
                # con un piccone di netherite (velocità 9) un blocco duro N secondi si rompe in circa N·1,5/9 secondi
                ticks = int(min(400, max(40, secs * 20 * 1.5 / 9 * 1.5 + 20)))
                behaviors["ores"].append({"id": bid, "min": xp.get("min", 0), "max": xp.get("max", 0), "ticks": ticks})
        elif rel.startswith("items/") and "minecraft:item" in d:
            it = d["minecraft:item"]
            c = it.get("components", {})
            items.append({
                "id": it["description"]["identifier"],
                "stack": c.get("minecraft:max_stack_size"),
                "durability": (c.get("minecraft:durability") or {}).get("max_durability"),
                "nutrition": (c.get("minecraft:food") or {}).get("nutrition"),
            })
            if "mmi:tool_wear" in c:
                behaviors["tools"].append({"id": it["description"]["identifier"], "mine": c["mmi:tool_wear"].get("mine", 1)})
            if "mmi:food_effects" in c:
                fx = [e.split(",") for e in c["mmi:food_effects"].get("effects", "").split(";") if e]
                sure = [e[0] for e in fx if float(e[3]) >= 1]
                if sure:
                    behaviors["foods"].append({"id": it["description"]["identifier"], "effect": sure[0],
                                               "ticks": int(round(20 * (c.get("minecraft:use_modifiers") or {}).get("use_duration", 1.6)))})
        elif rel.startswith("entities/") and "minecraft:entity" in d:
            e = d["minecraft:entity"]
            h = (e.get("components", {}).get("minecraft:health") or {}).get("max")
            entities.append({"id": e["description"]["identifier"], "health": h})
    # prima i minerali che danno sempre esperienza (minimo > 0): lì un risultato zero è un errore vero
    behaviors["ores"].sort(key=lambda o: (o["min"] == 0, o["ticks"]))
    return z, packs, blocks, items, entities, behaviors


TEST_JS = r'''
import { system, world, BlockTypes, ItemTypes, EntityTypes, ItemStack, GameMode, Direction } from "@minecraft/server";
import * as gt from "@minecraft/server-gametest";
const DATA = __DATA__;
function log(o) { console.warn("BBTEST " + JSON.stringify(o)); }

gt.register("bb", "registrazione", (test) => {
  const mb = DATA.blocks.filter((id) => !BlockTypes.get(id));
  const mi = DATA.items.filter((i) => !ItemTypes.get(i.id)).map((i) => i.id);
  const me = DATA.entities.filter((e) => !EntityTypes.get(e.id)).map((e) => e.id);
  log({ test: "registrazione", blocks: DATA.blocks.length, items: DATA.items.length, entities: DATA.entities.length, missingBlocks: mb, missingItems: mi, missingEntities: me });
  if (mb.length + mi.length + me.length > 0) test.fail("non registrati: " + (mb.length + mi.length + me.length)); else test.succeed();
}).structureName("bb:plat").maxTicks(40).tag("bb");

gt.register("bb", "oggetti", (test) => {
  const bad = [];
  for (const it of DATA.items) {
    try {
      const s = new ItemStack(it.id, 1);
      if (it.stack != null && s.maxAmount !== it.stack) bad.push(it.id + " pila " + s.maxAmount + "≠" + it.stack);
      if (it.durability != null) {
        const d = s.getComponent("minecraft:durability");
        if (!d || d.maxDurability !== it.durability) bad.push(it.id + " durata " + (d ? d.maxDurability : "-") + "≠" + it.durability);
      }
      if (it.nutrition != null) {
        const f = s.getComponent("minecraft:food");
        if (!f || f.nutrition !== it.nutrition) bad.push(it.id + " cibo " + (f ? f.nutrition : "-") + "≠" + it.nutrition);
      }
    } catch (e) { bad.push(it.id + ": " + e); }
  }
  log({ test: "oggetti", checked: DATA.items.length, bad: bad });
  if (bad.length) test.fail(bad.length + " oggetti diversi"); else test.succeed();
}).structureName("bb:plat").maxTicks(40).tag("bb");

for (let k = 0; k * DATA.batch < DATA.blocks.length; k++) {
  const part = DATA.blocks.slice(k * DATA.batch, (k + 1) * DATA.batch);
  gt.register("bb", "blocchi_" + k, (test) => {
    const bad = [];
    part.forEach((id, i) => {
      const loc = { x: 1 + (i % 14), y: 1 + Math.floor(i / 196), z: 1 + (Math.floor(i / 14) % 14) };
      try {
        test.setBlockType(id, loc);
        const b = test.getBlock(loc);
        if (!b || b.typeId !== id) bad.push(id + " → " + (b ? b.typeId : "niente"));
      } catch (e) { bad.push(id + ": " + e); }
    });
    log({ test: "blocchi_" + k, placed: part.length - bad.length, total: part.length, bad: bad });
    if (bad.length) test.fail(bad.length + " blocchi non piazzati"); else test.succeed();
  }).structureName("bb:plat").maxTicks(60).tag("bb");
}

if (DATA.entities.length) gt.register("bb", "creature", (test) => {
  const spawned = [];
  const bad = [];
  DATA.entities.forEach((e, i) => {
    try { spawned.push([e, test.spawn(e.id, { x: 1 + (i % 7) * 2, y: 2, z: 1 + (Math.floor(i / 7) % 7) * 2 })]); }
    catch (err) { bad.push(e.id + " non nasce: " + err); }
  });
  test.runAfterDelay(20, () => {
    for (const [e, ent] of spawned) {
      if (!ent.isValid) { bad.push(e.id + " sparita"); continue; }
      const h = ent.getComponent("minecraft:health");
      if (e.health != null && h && Math.abs(h.effectiveMax - e.health) > 0.01) bad.push(e.id + " vita " + h.effectiveMax + "≠" + e.health);
    }
    log({ test: "creature", spawned: spawned.length, total: DATA.entities.length, bad: bad });
    if (bad.length) test.fail(bad.length + " creature con problemi"); else test.succeed();
  });
}).structureName("bb:plat").maxTicks(200).tag("bb");

// Il pavimento della struttura di prova è a y=1: il giocatore simulato sta sopra (y=2).
const B = DATA.behaviors;
if (B.tools.length + B.foods.length + B.ores.length) gt.register("bb", "comportamenti", (test) => {
  const bad = [], ok = [];
  const p = test.spawnSimulatedPlayer({ x: 6, y: 2, z: 6 }, "Prova", GameMode.Survival);
  const inv = () => p.getComponent("minecraft:inventory").container;
  const loc = { x: 7, y: 2, z: 6 };
  let orbs = 0;
  const sub = world.afterEvents.entitySpawn.subscribe((ev) => { try { if (ev.entity.typeId === "minecraft:xp_orb") orbs++; } catch (e) {} });
  let t = 5;
  for (const tool of B.tools.slice(0, 4)) {
    test.runAfterDelay(t, () => { test.setBlockType("minecraft:dirt", loc); p.setItem(new ItemStack(tool.id, 1), 0, true); p.breakBlock(loc, Direction.West); });
    test.runAfterDelay(t + 30, () => {
      const it = inv().getItem(0), d = it && it.getComponent("minecraft:durability");
      const gone = test.getBlock(loc).typeId !== "minecraft:dirt";
      if (!gone) bad.push(tool.id + ": non ha rotto la terra");
      else if (!it) ok.push(tool.id + " rotto dall'usura");
      else if (!d || d.damage !== tool.mine) bad.push(tool.id + " usura " + (d ? d.damage : "-") + "≠" + tool.mine);
      else ok.push(tool.id + " usura " + d.damage);
      p.stopBreakingBlock && p.stopBreakingBlock();
    });
    t += 35;
  }
  for (const food of B.foods.slice(0, 4)) {
    test.runAfterDelay(t, () => {
      try { p.getComponent("minecraft:player.hunger").setCurrentValue(2); } catch (e) {}
      for (const fx of p.getEffects()) p.removeEffect(fx.typeId);
      p.setItem(new ItemStack(food.id, 4), 1, true);
      p.useItemInSlot(1);
    });
    test.runAfterDelay(t + food.ticks + 12, () => {
      const left = inv().getItem(1);
      const eaten = !left || left.typeId !== food.id || left.amount < 4;
      if (!eaten) bad.push(food.id + ": non si è mangiato");
      else if (!p.getEffect(food.effect)) bad.push(food.id + ": manca l'effetto " + food.effect);
      else ok.push(food.id + " → " + food.effect);
      p.stopUsingItem();
    });
    t += food.ticks + 17;
  }
  for (const ore of B.ores.slice(0, 4)) {
    let before = 0;
    test.runAfterDelay(t, () => { test.setBlockType(ore.id, loc); p.setItem(new ItemStack("minecraft:netherite_pickaxe", 1), 0, true); before = orbs; p.breakBlock(loc, Direction.West); });
    test.runAfterDelay(t + ore.ticks, () => {
      const gone = test.getBlock(loc).typeId !== ore.id;
      const n = orbs - before;
      if (!gone) bad.push(ore.id + ": non si è rotto");
      else if (n < ore.min || n > ore.max) bad.push(ore.id + ": esperienza " + n + " (attesa " + ore.min + "–" + ore.max + ")");
      else ok.push(ore.id + " esperienza " + n);
    });
    t += ore.ticks + 5;
  }
  test.runAfterDelay(t + 2, () => {
    world.afterEvents.entitySpawn.unsubscribe(sub);
    log({ test: "comportamenti", ok: ok, bad: bad });
    if (bad.length) test.fail(bad.length + " comportamenti diversi"); else test.succeed();
  });
}).structureName("bb:plat").maxTicks(2000).tag("bb");

// Blocchi con più stati: il giocatore simulato (in creativa) li piazza e li usa come farebbe un giocatore vero.
if (B.stateful.length) gt.register("bb", "stati", (test) => {
  const ok = [], bad = [];
  const p = test.spawnSimulatedPlayer({ x: 7, y: 2, z: 7 }, "Prova", GameMode.Creative);
  const spots = [[5, 5], [5, 7], [5, 9], [9, 5], [9, 7], [9, 9], [7, 5], [7, 9], [3, 7], [11, 7], [7, 3], [7, 11]];
  const steps = [];
  const st = (loc, name) => test.getBlock(loc).permutation.getState(name);
  B.stateful.slice(0, spots.length).forEach((b, k) => {
    const [x, z] = spots[k];
    const floor = { x, y: 1, z }, at = { x, y: 2, z }, above = { x, y: 3, z }, east = { x: x + 1, y: 2, z }, floorEast = { x: x + 1, y: 1, z };
    const is = (loc) => test.getBlock(loc).typeId === b.id;
    const check = (cond, what) => (cond ? ok : bad).push(b.id + ": " + what + (cond ? "" : " NO"));
    const place = (loc) => p.useItemOnBlock(new ItemStack(b.id, 1), loc, Direction.Up);
    if (b.kind === "door") {
      steps.push(() => place(floor));
      steps.push(() => { check(is(at) && is(above) && st(above, "minecraft:multi_block_part") === 1, "si piazza con la metà di sopra"); p.interactWithBlock(at, Direction.Up); });
      steps.push(() => { check(st(at, "mmi:open") === true && st(above, "mmi:open") === true, "si apre (tutte e due le metà)"); p.breakBlock(above, Direction.Up); });
      steps.push(() => check(!is(at) && !is(above), "si rompe tutta"));
    } else if (b.kind === "slab") {
      steps.push(() => place(floor));
      steps.push(() => { check(is(at) && st(at, "mmi:double") === false, "si piazza"); place(at); });
      steps.push(() => check(is(at) && st(at, "mmi:double") === true && !is(above), "due lastre fanno una lastra doppia"));
    } else if (b.kind === "trapdoor" || b.kind === "gate") {
      steps.push(() => place(floor));
      steps.push(() => { check(is(at) && st(at, "mmi:open") === false, "si piazza chiusa"); p.interactWithBlock(at, Direction.Up); });
      steps.push(() => { check(st(at, "mmi:open") === true, "si apre"); p.interactWithBlock(at, Direction.Up); });
      steps.push(() => check(st(at, "mmi:open") === false, "si richiude"));
    } else if (b.kind === "connect") {
      steps.push(() => place(floor));
      steps.push(() => place(floorEast));
      steps.push(() => check(is(at) && is(east) && st(at, "minecraft:connection_east") === true && st(east, "minecraft:connection_west") === true, "due vicini si collegano"));
    } else if (b.kind === "stairs") {
      steps.push(() => place(floor));
      steps.push(() => check(is(at) && st(at, "minecraft:corner") !== undefined && st(at, "minecraft:cardinal_direction") !== undefined, "si piazza con verso e angolo"));
    } else if (b.kind === "button") {
      steps.push(() => place(floor));
      steps.push(() => { check(is(at), "si piazza"); p.interactWithBlock(at, Direction.Up); });
      steps.push(() => check(test.getBlock(at).getRedstonePower() === 15, "premuto dà il segnale di pietrarossa"));
      steps.push(() => {});
      steps.push(() => check(st(at, "mmi:powered") === false, "torna su da solo"));
    } else if (b.kind === "plate") {
      steps.push(() => place(floor));
      let pig;
      steps.push(() => { check(is(at), "si piazza"); pig = test.spawn("minecraft:pig", at); });
      steps.push(() => check(st(at, "mmi:powered") === true && test.getBlock(at).getRedstonePower() === 15, "si accende sotto una creatura"));
      steps.push(() => { pig.remove(); });
      steps.push(() => check(st(at, "mmi:powered") === false, "si spegne quando se ne va"));
    } else if (b.kind === "crop") {
      steps.push(() => { test.setBlockType("minecraft:farmland", floor); test.setBlockType(b.id, at); });
      steps.push(() => { check(is(at) && st(at, "mmi:age") === 0, "si pianta sulla terra arata"); p.setItem(new ItemStack("minecraft:bone_meal", 8), 0, true); p.interactWithBlock(at, Direction.Up); });
      steps.push(() => check(st(at, "mmi:age") > 0, "cresce con la farina d'ossa"));
    }
  });
  steps.forEach((f, i) => test.runAfterDelay(5 + i * 12, () => { try { f(); } catch (e) { bad.push("errore: " + e); } }));
  test.runAfterDelay(10 + steps.length * 12, () => {
    log({ test: "stati", ok: ok, bad: bad });
    if (bad.length) test.fail(bad.length + " prove sui blocchi con stati non riuscite"); else test.succeed();
  });
}).structureName("bb:plat").maxTicks(2000).tag("bb");

system.runTimeout(() => log({ test: "pronto" }), 20);
'''


def test_pack(dest, blocks, items, entities, behaviors=None):
    os.makedirs(os.path.join(dest, "scripts"), exist_ok=True)
    os.makedirs(os.path.join(dest, "structures", "bb"), exist_ok=True)
    pid = str(uuid.uuid4())
    manifest = {
        "format_version": 2,
        "header": {"name": "Prova BLOCKBRIDGE", "description": "prove automatiche", "uuid": pid, "version": [1, 0, 0], "min_engine_version": [1, 21, 90]},
        "modules": [
            {"type": "data", "uuid": str(uuid.uuid4()), "version": [1, 0, 0]},
            {"type": "script", "language": "javascript", "uuid": str(uuid.uuid4()), "version": [1, 0, 0], "entry": "scripts/main.js"},
        ],
        "dependencies": [{"module_name": "@minecraft/server", "version": "2.0.0"}, {"module_name": "@minecraft/server-gametest", "version": "1.0.0-beta"}],
    }
    json.dump(manifest, open(os.path.join(dest, "manifest.json"), "w"))
    layers = max(1, min(4, (min(len(blocks), BATCH) + 195) // 196))
    size = (16, layers + 3, 16)
    data = []
    for x in range(size[0]):
        for y in range(size[1]):
            for z in range(size[2]):
                data.append(1 if y == 0 else 0)
    open(os.path.join(dest, "structures", "bb", "plat.mcstructure"), "wb").write(
        lenbt.mcstructure(size, data, [("minecraft:air", {}), ("minecraft:stone", {})]))
    payload = json.dumps({"blocks": blocks, "items": items, "entities": entities, "batch": BATCH,
                          "behaviors": behaviors or {"tools": [], "foods": [], "ores": [], "stateful": []}})
    open(os.path.join(dest, "scripts", "main.js"), "w", encoding="utf-8").write(TEST_JS.replace("__DATA__", payload))
    return pid


def set_props(bds, level):
    p = os.path.join(bds, "server.properties")
    s = open(p).read()
    repl = {"level-name": level, "online-mode": "false", "allow-list": "false", "allow-cheats": "true", "enable-lan-visibility": "false",
            "content-log-console-output-enabled": "true", "content-log-file-enabled": "false", "content-log-level": "info"}
    for k, v in repl.items():
        s = re.sub(r"^%s=.*$" % re.escape(k), "%s=%s" % (k, v), s, flags=re.M)
    open(p, "w").write(s)


def run_server(bds, level, deadline):
    """Avvia il server, lancia le prove quando è pronto, ferma quando le prove hanno finito. Restituisce le righe di uscita."""
    set_props(bds, level)
    env = dict(os.environ, LD_LIBRARY_PATH=bds)
    proc = subprocess.Popen(["./bedrock_server"], cwd=bds, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, text=True, bufsize=1)
    lines = []
    state = {"started": False, "launched": False, "done": 0, "expected": None}

    def reader():
        for line in proc.stdout:
            lines.append(line.rstrip("\n"))
    t = threading.Thread(target=reader, daemon=True)
    t.start()
    try:
        while time.time() < deadline and proc.poll() is None:
            time.sleep(0.5)
            text = "\n".join(lines)
            if not state["launched"] and "Server started." in text and "BBTEST" in text:
                proc.stdin.write("gametest runset bb\n"); proc.stdin.flush()
                state["launched"] = True
            m = re.search(r"Running (\d+) tests? with tag 'bb'", text)
            if m:
                state["expected"] = int(m.group(1))
            done = text.count("onTestPassed:") + text.count("onTestFailed:")
            if state["expected"] is not None and done >= state["expected"]:
                break
            if "No tests found" in text:
                break
    finally:
        if proc.poll() is None:
            try:
                proc.stdin.write("stop\n"); proc.stdin.flush()
            except Exception:
                pass
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
        t.join(timeout=5)
    return lines


def prepare_template(bds):
    """Un mondo vuoto creato dal server, con l'esperimento delle GameTest acceso (solo per il mondo di prova)."""
    tpl = os.path.join(bds, "worlds", "bb_template")
    if os.path.exists(os.path.join(tpl, "level.dat")):
        return tpl
    os.makedirs(os.path.join(bds, "worlds"), exist_ok=True)
    set_props(bds, "bb_template")
    env = dict(os.environ, LD_LIBRARY_PATH=bds)
    p = subprocess.Popen(["./bedrock_server"], cwd=bds, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, text=True)
    out = []
    start = time.time()
    while time.time() - start < 90:
        line = p.stdout.readline()
        if not line:
            break
        out.append(line)
        if "Server started." in line:
            break
    p.stdin.write("stop\n"); p.stdin.flush()
    try:
        p.wait(timeout=30)
    except subprocess.TimeoutExpired:
        p.kill()
    lenbt.set_experiments(os.path.join(tpl, "level.dat"), ["gametest"])
    return tpl


ERROR_RE = re.compile(r"(\[[A-Za-z ]+\]\[(error|warning)\])|( ERROR\] )", re.I)


def test_addon(bds, addon, out_dir):
    name = os.path.basename(addon).rsplit(".", 1)[0]
    z, packs, blocks, items, entities, behaviors = read_addon(addon)
    tpl = prepare_template(bds)
    level = "bb_" + re.sub(r"[^a-zA-Z0-9]", "_", name)[:40]
    world = os.path.join(bds, "worlds", level)
    shutil.rmtree(world, ignore_errors=True)
    shutil.copytree(tpl, world)
    wbp, wrp = [], []
    for kind, (folder, m) in packs.items():
        sub = "behavior_packs" if kind in ("data", "script") else "resource_packs"
        dest = os.path.join(world, sub, folder.replace(" ", "_"))
        for n in z.namelist():
            if n.startswith(folder + "/") and not n.endswith("/"):
                target = os.path.join(dest, n[len(folder) + 1:])
                os.makedirs(os.path.dirname(target), exist_ok=True)
                open(target, "wb").write(z.read(n))
        (wbp if sub == "behavior_packs" else wrp).append({"pack_id": m["header"]["uuid"], "version": m["header"]["version"]})
    tid = test_pack(os.path.join(world, "behavior_packs", "bb_test"), blocks, items, entities, behaviors)
    wbp.append({"pack_id": tid, "version": [1, 0, 0]})
    json.dump(wbp, open(os.path.join(world, "world_behavior_packs.json"), "w"))
    json.dump(wrp, open(os.path.join(world, "world_resource_packs.json"), "w"))
    lines = run_server(bds, level, time.time() + TIMEOUT)
    results = []
    for l in lines:
        if "BBTEST " in l:
            try:
                results.append(json.loads(l.split("BBTEST ", 1)[1]))
            except ValueError:
                pass
    passed = [l.split("onTestPassed:", 1)[1].strip() for l in lines if "onTestPassed:" in l]
    failed = [l.split("onTestFailed:", 1)[1].strip() for l in lines if "onTestFailed:" in l]
    loaded = any("Pack Stack" in l and "bb_test" in l for l in lines) or any("Prova BLOCKBRIDGE" in l for l in lines)
    errors = [l for l in lines if ERROR_RE.search(l) and "BBTEST" not in l and "onTestFailed" not in l]
    report = {
        "addon": os.path.basename(addon), "blocks": len(blocks), "items": len(items), "entities": len(entities),
        "passed": passed, "failed": failed, "results": results, "content_log": errors[:200], "content_log_count": len(errors),
        "packs_loaded": loaded,
    }
    json.dump(report, open(os.path.join(out_dir, name + ".json"), "w"), indent=1, ensure_ascii=False)
    open(os.path.join(out_dir, name + ".log"), "w").write("\n".join(lines))
    shutil.rmtree(world, ignore_errors=True)
    return report


def main():
    bds, out_dir = sys.argv[1], sys.argv[2]
    addons = sys.argv[3:]
    os.makedirs(out_dir, exist_ok=True)
    summary = []
    bad = 0
    for a in addons:
        r = test_addon(bds, a, out_dir)
        ok = not r["failed"] and r["passed"] and r["content_log_count"] == 0
        if not ok:
            bad += 1
        line = "== %s: %s — blocchi %d, oggetti %d, creature %d; prove superate %d, fallite %d; errori nel registro dei contenuti %d" % (
            r["addon"], "OK" if ok else "PROBLEMI", r["blocks"], r["items"], r["entities"], len(r["passed"]), len(r["failed"]), r["content_log_count"])
        summary.append(line)
        for res in r["results"]:
            if res.get("ok"):
                summary.append("   %s ok: %s" % (res.get("test"), "; ".join(map(str, res["ok"][:12]))))
            for key in ("missingBlocks", "missingItems", "missingEntities", "bad"):
                if res.get(key):
                    summary.append("   %s %s: %s" % (res.get("test"), key, "; ".join(map(str, res[key][:8]))))
        for e in r["content_log"][:8]:
            summary.append("   registro: " + e[:300])
        print(summary[-1] if len(summary) == 1 else "\n".join(summary[-(1 + len(r["content_log"][:8])):]), flush=True)
    open(os.path.join(out_dir, "riepilogo.txt"), "w").write("\n".join(summary) + "\n")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
