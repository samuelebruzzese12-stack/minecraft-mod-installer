package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

/** Strutture di una mod Java: pezzi .nbt → .mcstructure, gruppi di pezzi, processori, biomi e posizioni per lo script. */
class StructuresTest {

    // ---- NBT di Java (big-endian, gzip) per costruire i pezzi di prova
    private sealed class T {
        class I(val v: Int) : T()
        class D(val v: Double) : T()
        class S(val v: String) : T()
        class L(val type: Int, val items: List<T>) : T()
        class C(val m: Map<String, T>) : T()
    }

    private fun c(vararg p: Pair<String, T>) = T.C(linkedMapOf(*p))
    private fun s(v: String) = T.S(v)
    private fun i(v: Int) = T.I(v)
    private fun ints(vararg v: Int) = T.L(3, v.map { T.I(it) })
    private fun list(vararg v: T.C) = T.L(10, v.toList())

    private fun nbt(root: T.C): ByteArray {
        val raw = ByteArrayOutputStream()
        val d = DataOutputStream(raw)
        fun id(t: T): Int = when (t) { is T.I -> 3; is T.D -> 6; is T.S -> 8; is T.L -> 9; is T.C -> 10 }
        fun body(t: T) {
            when (t) {
                is T.I -> d.writeInt(t.v)
                is T.D -> d.writeDouble(t.v)
                is T.S -> d.writeUTF(t.v)
                is T.L -> { d.writeByte(if (t.items.isEmpty()) t.type else id(t.items[0])); d.writeInt(t.items.size); t.items.forEach { body(it) } }
                is T.C -> { for ((k, v) in t.m) { d.writeByte(id(v)); d.writeUTF(k); body(v) }; d.writeByte(0) }
            }
        }
        d.writeByte(10); d.writeUTF(""); body(root)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw.toByteArray()) }
        return out.toByteArray()
    }

    private fun state(name: String, vararg props: Pair<String, String>): T.C =
        if (props.isEmpty()) c("Name" to s(name)) else c("Name" to s(name), "Properties" to T.C(props.associate { it.first to s(it.second) }))

    private fun block(x: Int, y: Int, z: Int, st: Int, nbt: T.C? = null): T.C =
        if (nbt == null) c("pos" to ints(x, y, z), "state" to i(st)) else c("pos" to ints(x, y, z), "state" to i(st), "nbt" to nbt)

    private fun jar(): JavaJar {
        val e = LinkedHashMap<String, ByteArray>()
        fun put(n: String, text: String) { e[n] = text.trimIndent().toByteArray() }
        put("fabric.mod.json", """{"schemaVersion": 1, "id": "testmod", "name": "Struct Mod", "version": "1.0"}""")
        // Pezzo iniziale 3×3×3: pavimento di pietra, scala, letto rosso, cartello, lastra nell'acqua, cassa col bottino, connettore.
        val start = c(
            "DataVersion" to i(3955),
            "size" to ints(3, 3, 3),
            "palette" to list(
                state("minecraft:stone"),
                state("minecraft:oak_stairs", "facing" to "east", "half" to "bottom", "shape" to "straight", "waterlogged" to "false"),
                state("minecraft:red_bed", "facing" to "north", "occupied" to "false", "part" to "head"),
                state("minecraft:oak_sign", "rotation" to "0", "waterlogged" to "false"),
                state("minecraft:oak_slab", "type" to "bottom", "waterlogged" to "true"),
                state("minecraft:chest", "facing" to "north", "type" to "single", "waterlogged" to "false"),
                state("minecraft:jigsaw", "orientation" to "east_up"),
                state("minecraft:air"),
                state("othermod:strange_block")
            ),
            "blocks" to T.L(10, buildList {
                for (x in 0..2) for (z in 0..2) add(block(x, 0, z, 0))
                add(block(0, 1, 0, 1))
                add(block(1, 1, 0, 2, c("id" to s("minecraft:bed"))))
                add(block(2, 1, 0, 3, c("id" to s("minecraft:sign"), "front_text" to c("messages" to T.L(8, listOf(s("\"Casa\""), s("{\"text\":\"di prova\"}"), s("\"\""), s("\"\"")))))))
                add(block(0, 1, 1, 4))
                add(block(1, 1, 1, 5, c("id" to s("minecraft:chest"), "LootTable" to s("testmod:chests/house"), "Items" to T.L(10, emptyList()))))
                add(block(2, 1, 1, 6, c("name" to s("testmod:door"), "target" to s("testmod:door"), "pool" to s("testmod:house/annex"), "final_state" to s("stone_bricks"), "joint" to s("aligned"))))
                add(block(0, 2, 2, 8))
                add(block(1, 2, 2, 7))
            }),
            "entities" to T.L(10, listOf(c("pos" to T.L(6, listOf(T.D(0.5), T.D(1.0), T.D(0.5))), "blockPos" to ints(0, 1, 0), "nbt" to c("id" to s("minecraft:villager")))))
        )
        val annex = c(
            "DataVersion" to i(3955),
            "size" to ints(2, 2, 2),
            "palette" to list(state("minecraft:stone"), state("minecraft:jigsaw", "orientation" to "west_up")),
            "blocks" to T.L(10, listOf(block(0, 0, 0, 0), block(1, 0, 0, 0), block(0, 1, 0, 1, c("name" to s("testmod:door"), "target" to s("testmod:door"), "pool" to s("minecraft:empty"), "final_state" to s("minecraft:air")))))
        )
        e["data/testmod/structure/house/start.nbt"] = nbt(start)
        e["data/testmod/structure/house/annex.nbt"] = nbt(annex)
        e["data/testmod/structure/statue.nbt"] = nbt(c("size" to ints(1, 1, 1), "palette" to list(state("minecraft:gold_block")), "blocks" to T.L(10, listOf(block(0, 0, 0, 0)))))
        put("data/testmod/worldgen/template_pool/house/start.json", """{"fallback": "minecraft:empty", "elements": [{"weight": 1, "element": {"element_type": "minecraft:single_pool_element", "location": "testmod:house/start", "processors": "testmod:cracks", "projection": "rigid"}}]}""")
        put("data/testmod/worldgen/template_pool/house/annex.json", """{"fallback": "minecraft:empty", "elements": [{"weight": 3, "element": {"element_type": "minecraft:single_pool_element", "location": "testmod:house/annex", "processors": {"processors": [{"processor_type": "minecraft:block_rot", "integrity": 0.5}]}, "projection": "terrain_matching"}}, {"weight": 1, "element": {"element_type": "minecraft:empty_pool_element"}}]}""")
        put("data/testmod/worldgen/processor_list/cracks.json", """{"processors": [{"processor_type": "minecraft:rule", "rules": [{"input_predicate": {"predicate_type": "minecraft:random_block_match", "block": "minecraft:stone", "probability": 0.25}, "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": {"Name": "minecraft:cobblestone"}}]}, {"processor_type": "othermod:magic"}]}""")
        put("data/testmod/worldgen/structure/house.json", """{"type": "minecraft:jigsaw", "biomes": "#testmod:has_house", "start_pool": "testmod:house/start", "size": 2, "start_height": {"absolute": 0}, "project_start_to_heightmap": "WORLD_SURFACE_WG", "max_distance_from_center": 64, "step": "surface_structures", "terrain_adaptation": "beard_thin", "spawn_overrides": {}}""")
        put("data/testmod/worldgen/structure/dungeon.json", """{"type": "testmod:dungeon", "biomes": "#minecraft:is_overworld", "spawn_overrides": {}, "step": "underground_structures"}""")
        put("data/testmod/worldgen/structure_set/house.json", """{"structures": [{"structure": "testmod:house", "weight": 1}], "placement": {"type": "minecraft:random_spread", "spacing": 20, "separation": 8, "salt": 123}}""")
        put("data/testmod/tags/worldgen/biome/has_house.json", """{"values": ["#minecraft:is_forest", "minecraft:plains", {"id": "othermod:magic_forest", "required": false}]}""")
        put("data/testmod/loot_table/chests/house.json", """{"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:diamond"}]}]}""")
        // Progressi: una radice (subito), uno con due strade (diamanti o uno zombi ucciso), uno dato solo dal codice, uno del libro delle ricette.
        put("assets/testmod/lang/en_us.json", """{"advancements.testmod.root": "Struct Mod", "advancements.testmod.diamond.desc": "Get three diamonds"}""")
        put("assets/testmod/lang/it_it.json", """{"advancements.testmod.root": "Mod delle strutture", "advancements.testmod.diamond.desc": "Prendi tre diamanti"}""")
        put("data/testmod/advancement/root.json", """{"display": {"title": {"translate": "advancements.testmod.root"}, "description": {"text": "Prova"}, "icon": {"id": "minecraft:stone"}, "frame": "task", "announce_to_chat": false}, "criteria": {"t": {"trigger": "minecraft:tick"}}}""")
        put("data/testmod/advancement/diamond.json", """{"parent": "testmod:root", "display": {"title": {"text": "Diamanti!"}, "description": {"translate": "advancements.testmod.diamond.desc"}, "icon": {"id": "minecraft:diamond"}, "frame": "goal"}, "criteria": {"has": {"trigger": "minecraft:inventory_changed", "conditions": {"items": [{"items": "minecraft:diamond", "count": {"min": 3}}]}}, "kill": {"trigger": "minecraft:player_killed_entity", "conditions": {"entity": [{"condition": "minecraft:entity_properties", "entity": "this", "predicate": {"type": "minecraft:zombie"}}]}}}, "requirements": [["has", "kill"]], "rewards": {"experience": 50}}""")
        put("data/testmod/advancement/code.json", """{"parent": "testmod:diamond", "display": {"title": {"text": "Solo dal codice"}, "description": {"text": "x"}, "icon": {"id": "minecraft:stone"}}, "criteria": {"x": {"trigger": "testmod:custom"}}}""")
        put("data/testmod/advancement/recipes/stone.json", """{"parent": "minecraft:recipes/root", "criteria": {"has": {"trigger": "minecraft:recipe_unlocked", "conditions": {"recipe": "minecraft:stone"}}}, "rewards": {"recipes": ["minecraft:stone"]}}""")
        return JavaJar.ofEntries("structmod.jar", e, classCount = 0)
    }

    // ---- NBT di Bedrock (little-endian) per leggere i .mcstructure scritti
    private class Le(val b: ByteArray) {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun i16() = u8() or (u8() shl 8)
        fun i32() = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
        fun str(): String { val n = i16(); val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
        fun value(t: Int): Any? = when (t) {
            1 -> b[p++].toInt(); 2 -> i16(); 3 -> i32(); 4 -> { val lo = i32().toLong() and 0xFFFFFFFFL; val hi = i32().toLong(); (hi shl 32) or lo }
            5 -> java.lang.Float.intBitsToFloat(i32()); 8 -> str()
            9 -> { val et = u8(); val n = i32(); List(n) { value(et) } }
            10 -> { val m = LinkedHashMap<String, Any?>(); while (true) { val tt = u8(); if (tt == 0) break; val k = str(); m[k] = value(tt) }; m }
            else -> throw IllegalStateException("tag $t")
        }
        @Suppress("UNCHECKED_CAST")
        fun root(): Map<String, Any?> { u8(); str(); return value(10) as Map<String, Any?> }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun structuresBecomeBedrockStructuresAndScriptData() {
        val conv = ModConverter()
        conv.analyzeJars(listOf(jar())).use { plan ->
            val files = conv.preview(plan, "Struct")
            val r = plan.reports.single()
            assertTrue(r.scripted.toString(), r.scripted.any { it.startsWith("Strutture che nascono nel mondo come in Java") && it.contains("1 (house)") && it.contains("2 pezzi") })
            assertTrue(r.notConverted.toString(), r.notConverted.any { it.startsWith("Strutture fatte dal codice Java della mod") && it.contains("dungeon") })
            assertTrue(r.notConverted.toString(), r.notConverted.any { it.startsWith("Blocchi delle strutture che non ci sono") && it.contains("strange_block") })
            assertTrue(r.notConverted.toString(), r.notConverted.any { it.startsWith("Modifiche ai blocchi delle strutture scritte nel codice") && it.contains("magic") })
            assertTrue(r.simplified.toString(), r.simplified.any { it.startsWith("Pezzi delle strutture convertiti: 3") })
            assertFalse(r.notConverted.any { it.startsWith("Strutture:") })

            // Il pezzo iniziale in formato Bedrock
            val bp = "Struct BP/"
            val mc = Le(files.getValue(bp + "structures/testmod/house/start.mcstructure")).root()
            assertEquals(listOf(3, 3, 3), mc["size"])
            val structure = mc["structure"] as Map<String, Any?>
            val pal = ((structure["palette"] as Map<String, Any?>)["default"] as Map<String, Any?>)
            val names = (pal["block_palette"] as List<Map<String, Any?>>).map { it["name"] as String }
            val layers = structure["block_indices"] as List<List<Int>>
            fun at(x: Int, y: Int, z: Int, layer: Int = 0): String? = layers[layer][(x * 3 + y) * 3 + z].let { if (it < 0) null else names[it] }
            assertEquals("minecraft:stone", at(1, 0, 1))
            assertEquals("minecraft:oak_stairs", at(0, 1, 0))
            val stairs = (pal["block_palette"] as List<Map<String, Any?>>).first { it["name"] == "minecraft:oak_stairs" }["states"] as Map<String, Any?>
            assertEquals(0, stairs["weirdo_direction"]) // est
            assertEquals("minecraft:bed", at(1, 1, 0))
            assertEquals("minecraft:water", at(0, 1, 1, 1)) // lastra «waterlogged»
            assertEquals("minecraft:stone_bricks", at(2, 1, 1)) // connettore jigsaw → il suo blocco finale
            assertEquals("minecraft:air", at(0, 2, 2)) // blocco di un'altra mod
            assertEquals("minecraft:structure_void", at(2, 2, 2)) // niente blocco nel pezzo: resta il mondo
            val pos = pal["block_position_data"] as Map<String, Map<String, Any?>>
            fun be(x: Int, y: Int, z: Int) = pos[((x * 3 + y) * 3 + z).toString()]?.get("block_entity_data") as Map<String, Any?>
            assertEquals("Chest", be(1, 1, 1)["id"])
            assertEquals("loot_tables/testmod/chests/house.json", be(1, 1, 1)["LootTable"])
            assertEquals(14, be(1, 1, 0)["color"]) // letto rosso
            assertEquals("Casa\ndi prova", (be(2, 1, 0)["FrontText"] as Map<String, Any?>)["Text"])
            assertTrue(files.containsKey(bp + "loot_tables/testmod/chests/house.json"))
            assertTrue(files.containsKey(bp + "structures/testmod/statue.mcstructure"))

            // I dati dello script: pezzi, gruppi, processori, struttura e posizioni come in Java
            val js = String(files.getValue(bp + "scripts/mmi_structures_data.js"), Charsets.UTF_8)
            val d = Json.parse(js.substringAfter("export const D = ").substringBeforeLast(";")) as Map<String, Any?>
            val tpl = d["t"] as List<Map<String, Any?>>
            val startTpl = tpl.first { it["i"] == "testmod:house/start" }
            assertEquals(listOf(listOf(2L, 1L, 1L, 5L, 1L, "testmod:house/annex", "testmod:door", "testmod:door", 0L, 0L, 0L)), startTpl["j"])
            assertEquals(listOf(listOf(0.5, 1.0, 0.5, "minecraft:villager_v2")), startTpl["e"])
            val annexTpl = tpl.first { it["i"] == "testmod:house/annex" }
            assertNotNull("il pezzo che segue il terreno ha i blocchi uno per uno", annexTpl["b"])
            val pools = d["p"] as Map<String, Map<String, Any?>>
            assertEquals(setOf("testmod:house/start", "testmod:house/annex"), pools.keys)
            val annexEls = pools.getValue("testmod:house/annex")["e"] as List<List<Any?>>
            assertEquals(listOf(0L, 3L, 1L), listOf(annexEls[0][0], annexEls[0][2], annexEls[0][4])) // pezzo, peso 3, segue il terreno
            assertEquals(3L, annexEls[1][0]) // vuoto
            val procs = d["r"] as List<List<Map<String, Any?>>>
            val rule = procs.flatten().first { it["k"] == "r" }
            assertTrue(Json.stringify(rule), Json.stringify(rule).contains("\"b\":[\"minecraft:stone\"],\"p\":0.25"))
            assertTrue(Json.stringify(rule).contains("\"o\":[\"minecraft:cobblestone\",{}]"))
            assertTrue(procs.flatten().any { it["k"] == "rot" && it["v"] == 0.5 })
            val st = (d["s"] as List<Map<String, Any?>>).single()
            assertEquals("testmod:house", st["id"])
            assertEquals("testmod:house/start", st["p"])
            assertEquals(2L, st["d"])
            assertEquals(0L, st["m"]) // superficie
            assertEquals(1L, st["a"]) // beard_thin
            val biomes = st["b"] as List<String>
            assertTrue(biomes.toString(), biomes.containsAll(listOf("minecraft:plains", "minecraft:forest", "minecraft:birch_forest", "minecraft:roofed_forest")))
            assertFalse(biomes.any { it.startsWith("othermod") })
            val set = (d["g"] as List<Map<String, Any?>>).single()
            assertEquals(listOf(20L, 8L, 123L, false), listOf(set["sp"], set["se"], set["sa"], set["tr"]))
            assertTrue(String(files.getValue(bp + "scripts/main.js"), Charsets.UTF_8).contains("import \"./mmi_structures.js\""))
            assertTrue(files.containsKey(bp + "scripts/mmi_structures.js"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun advancementsBecomeScripts() {
        val conv = ModConverter()
        conv.analyzeJars(listOf(jar())).use { plan ->
            val files = conv.preview(plan, "Struct")
            val r = plan.reports.single()
            assertTrue(r.scripted.toString(), r.scripted.any { it.startsWith("Progressi rifatti con lo script: 2 su 3") })
            assertTrue(r.notConverted.toString(), r.notConverted.any { it.startsWith("Progressi che si ottengono solo con eventi") && it.contains("Solo dal codice") })
            assertFalse(r.notConverted.any { it.startsWith("Progressi:") })
            val bp = "Struct BP/"
            val js = String(files.getValue(bp + "scripts/mmi_advancements_data.js"), Charsets.UTF_8)
            val a = (Json.parse(js.substringAfter("export const A = ").substringBeforeLast(";")) as Map<String, Any?>)["a"] as List<Map<String, Any?>>
            assertEquals(listOf("testmod:code", "testmod:diamond", "testmod:root"), a.map { it["id"] })
            val diamond = a.first { it["id"] == "testmod:diamond" }
            assertEquals("testmod:root", diamond["p"])
            assertEquals("goal", diamond["f"])
            assertEquals(50L, diamond["x"])
            assertEquals(listOf(listOf("has", "kill")), diamond["r"])
            val crit = diamond["c"] as Map<String, Map<String, Any?>>
            assertEquals(mapOf("k" to "inv", "it" to listOf(mapOf("i" to listOf("minecraft:diamond"), "n" to listOf(3L, 999L)))), crit["has"])
            assertEquals(mapOf("k" to "kill", "e" to listOf("minecraft:zombie")), crit["kill"])
            assertEquals(false, a.first { it["id"] == "testmod:code" }["ok"])
            assertEquals(mapOf("t" to mapOf("k" to "tick")), a.first { it["id"] == "testmod:root" }["c"])
            val en = String(files.getValue("Struct RP/texts/en_US.lang"), Charsets.UTF_8)
            val it = String(files.getValue("Struct RP/texts/it_IT.lang"), Charsets.UTF_8)
            assertTrue(en.contains("advancements.testmod.root=Struct Mod\n"))
            assertTrue(it.contains("advancements.testmod.root=Mod delle strutture\n"))
            assertTrue(en.contains("mmi.adv.testmod.diamond.t=Diamanti!\n"))
            assertTrue(it.contains("mmi.adv.toast.goal=Obiettivo raggiunto!\n"))
            assertTrue(String(files.getValue(bp + "scripts/main.js"), Charsets.UTF_8).contains("import \"./mmi_advancements.js\""))
            val manifest = String(files.getValue(bp + "manifest.json"), Charsets.UTF_8)
            assertTrue(manifest.contains("@minecraft/server-ui"))
        }
    }
}
