package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un pacchetto di armi di TACZ in miniatura (come nella mod: dati, aspetto, modello Bedrock, suoni, banco e ricette). */
class TaczTest {

    private fun s(text: String) = text.trimIndent().toByteArray(Charsets.UTF_8)

    private fun pack(): Map<String, ByteArray> {
        val root = "assets/tacz/custom/prova_pack/"
        val e = LinkedHashMap<String, ByteArray>()
        e["META-INF/mods.toml"] = s(
            """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="GPL-3.0"
            [[mods]]
            modId="tacz"
            version="1.1.7"
            displayName="Timeless & Classics Guns: Zero"
            """
        )
        e[root + "gunpack.meta.json"] = s("""{ "namespace": "tacz" }""")
        e[root + "data/tacz/index/guns/pistola.json"] = s(
            """{ // commenti come nei pacchetti veri
                 "name": "tacz.gun.pistola.name", "display": "tacz:pistola_display", "data": "tacz:pistola_data", "type": "pistol", "sort": 1 }"""
        )
        e[root + "data/tacz/data/guns/pistola_data.json"] = s(
            """{
              "ammo": "tacz:9mm", "ammo_amount": 15, "rpm": 400, "bolt": "closed_bolt", "fire_mode": ["semi"],
              "bullet": { "life": 0.8, "bullet_amount": 1, "damage": 6, "speed": 150, "pierce": 1,
                "extra_damage": { "head_shot_multiplier": 2, "damage_adjust": [ {"distance": 20, "damage": 6}, {"distance": "infinite", "damage": 4} ] } },
              "reload": { "type": "magazine", "feed": { "empty": 1.6, "tactical": 1.0 }, "cooldown": { "empty": 1.9, "tactical": 1.4 } },
              "inaccuracy": { "stand": 2, "move": 3, "sneak": 1, "aim": 0.1 },
              "recoil": { "pitch": [ {"time": 0, "value": [1.2, 1.2]} ] },
              "builtin_attachments": { "stock": { "attachments": ["tacz:calcio_a", "tacz:calcio_b"], "default_attached": "tacz:calcio_a" } }
            }"""
        )
        e[root + "assets/tacz/display/guns/pistola_display.json"] = s(
            """{ "model": "tacz:gun/pistola_geo", "texture": "tacz:gun/uv/pistola", "slot": "tacz:gun/slot/pistola", "animation": "tacz:pistola",
                 "transform": { "scale": { "thirdperson": [0.5, 0.5, 0.5] } },
                 "sounds": { "shoot": "tacz:pistola/sparo", "dry_fire": "tacz:dry_fire" } }"""
        )
        e[root + "assets/tacz/geo_models/gun/pistola_geo.json"] = s(
            """{ "format_version": "1.12.0", "minecraft:geometry": [ { "description": { "identifier": "geometry.pistola", "texture_width": 64, "texture_height": 64 },
               "bones": [
                 { "name": "root", "pivot": [0, 8, 0], "cubes": [ { "origin": [-1, 6, -6], "size": [2, 3, 10], "uv": { "north": { "uv": [0, 0], "uv_size": [2, 3] } } } ] },
                 { "name": "idle_view", "parent": "root", "pivot": [2, 11, 10] },
                 { "name": "thirdperson_hand", "pivot": [0, 6, 2] },
                 { "name": "mag_extended_1", "parent": "root", "pivot": [0, 4, 0], "cubes": [ { "origin": [-1, 2, -1], "size": [2, 4, 2], "uv": [0, 10] } ] },
                 { "name": "attachment_adapter", "parent": "root", "pivot": [0, 8, 4] },
                 { "name": "calcio_a", "parent": "attachment_adapter", "pivot": [0, 8, 4], "cubes": [ { "origin": [-1, 6, 4], "size": [2, 2, 4], "uv": [0, 20] } ] },
                 { "name": "calcio_b", "parent": "attachment_adapter", "pivot": [0, 8, 4], "cubes": [ { "origin": [-1, 6, 4], "size": [2, 2, 6], "uv": [0, 30] } ] },
                 { "name": "lefthand_pos", "parent": "root", "pivot": [0, 8, 0], "cubes": [ { "origin": [-7, 8, -2], "size": [3, 12, 4], "uv": [0, 40] } ] }
               ] } ] }"""
        )
        e[root + "assets/tacz/animations/pistola.animation.json"] = s(
            """{ "format_version": "1.8.0", "animations": {
                 "shoot": { "animation_length": 0.3, "bones": { "root": { "position": { "0.0": [0, 0, 0], "0.05": [0, 0, 1.5], "0.3": [0, 0, 0] } } } },
                 "draw": { "animation_length": 0.4, "bones": { "root": { "rotation": { "0.0": [-40, 0, 0], "0.4": [0, 0, 0] } } } },
                 "reload_empty": { "animation_length": 1.9, "sound_effects": { "0.2": { "effect": "tacz:pistola/caricatore_fuori" }, "1.1": { "effect": "tacz:pistola/caricatore_dentro" } } }
               } }"""
        )
        e[root + "assets/tacz/textures/gun/slot/pistola.png"] = TestMod.png(64, 64)
        e[root + "assets/tacz/textures/gun/uv/pistola.png"] = TestMod.png(64, 64)
        for (snd in listOf("pistola/sparo", "pistola/caricatore_fuori", "pistola/caricatore_dentro", "dry_fire")) e[root + "assets/tacz/tacz_sounds/$snd.ogg"] = byteArrayOf(79, 103, 103, 83)
        e[root + "data/tacz/index/ammo/9mm.json"] = s("""{ "name": "tacz.ammo.9mm.name", "display": "tacz:9mm_display", "stack_size": 60 }""")
        e[root + "assets/tacz/display/ammo/9mm_display.json"] = s("""{ "slot": "tacz:ammo/slot/9mm" }""")
        e[root + "assets/tacz/textures/ammo/slot/9mm.png"] = TestMod.png(32, 32)
        e[root + "data/tacz/index/blocks/banco.json"] = s("""{ "name": "tacz.block.banco.name", "display": "tacz:banco", "data": "tacz:banco", "id": "tacz:workbench_c" }""")
        e[root + "data/tacz/data/blocks/banco.json"] = s("""{ "filter": "tacz:default", "tabs": [ { "id": "tacz:pistol", "name": "tacz.type.pistol.name" }, { "id": "tacz:ammo", "name": "tacz.type.ammo.name" } ] }""")
        e[root + "assets/tacz/display/blocks/banco.json"] = s("""{ "model": "tacz:block/banco", "texture": "tacz:block/uv/banco" }""")
        e[root + "assets/tacz/geo_models/block/banco.json"] = s(
            """{ "format_version": "1.12.0", "minecraft:geometry": [ { "description": { "identifier": "geometry.banco", "texture_width": 64, "texture_height": 64 },
               "bones": [ { "name": "banco", "pivot": [0, 0, 0], "cubes": [ { "origin": [-8, 0, -8], "size": [32, 32, 16], "uv": { "up": { "uv": [0, 0], "uv_size": [16, 16] } } } ] } ] } ] }"""
        )
        e[root + "assets/tacz/textures/block/uv/banco.png"] = TestMod.png(64, 64)
        e[root + "data/tacz/recipes/gun/pistola.json"] = s(
            """{ "type": "tacz:gun_smith_table_crafting", "result": { "type": "gun", "id": "tacz:pistola" },
                 "materials": [ { "item": { "tag": "forge:ingots/iron" }, "count": 20 }, { "item": { "tag": "minecraft:logs" }, "count": 2 } ] }"""
        )
        e[root + "data/tacz/recipes/ammo/9mm.json"] = s(
            """{ "type": "tacz:gun_smith_table_crafting", "result": { "type": "ammo", "id": "tacz:9mm", "count": 30 },
                 "materials": [ { "item": { "tag": "forge:ingots/copper" }, "count": 5 }, { "item": { "item": "minecraft:gunpowder" }, "count": 2 } ] }"""
        )
        e[root + "data/tacz/recipes/attachments/mirino.json"] = s(
            """{ "type": "tacz:gun_smith_table_crafting", "result": { "type": "attachment", "id": "tacz:mirino" }, "materials": [ { "item": { "item": "minecraft:glass" }, "count": 1 } ] }"""
        )
        e[root + "assets/tacz/lang/en_us.json"] = s("""{ "tacz.gun.pistola.name": "Test Pistol", "tacz.ammo.9mm.name": "9mm", "tacz.block.banco.name": "Gun Bench" }""")
        e[root + "assets/tacz/lang/it_it.json"] = s("""{ "tacz.gun.pistola.name": "Pistola di prova" }""")
        // Fuori dal pacchetto: la ricetta del banco nel banco da lavoro (in Java un banco generico con il nome nei dati).
        e["data/tacz/recipes/banco.json"] = s(
            """{ "type": "minecraft:crafting_shaped", "result": { "item": "tacz:workbench_c", "nbt": { "BlockId": "tacz:banco" } },
                 "pattern": [ "II", "IB" ], "key": { "I": { "tag": "forge:ingots/iron" }, "B": { "item": "tacz:ammo_box" } } }"""
        )
        return e
    }

    @Test fun gunPackBecomesWorkingBedrockGuns() {
        val conv = ModConverter()
        conv.analyzeJars(listOf(JavaJar.ofEntries("tacz.jar", pack()))).use { plan ->
            val r = plan.reports.single()
            assertTrue(r.items.any { it.bedrockId == "tacz:pistola" && it.name == "Pistola di prova" })
            assertTrue(r.items.any { it.bedrockId == "tacz:ammo_9mm" })
            assertTrue(r.blocks.any { it.bedrockId == "tacz:banco" })
            assertTrue(r.scripted.any { it.startsWith("Armi da fuoco di TACZ: 1") })
            assertTrue(r.recipeSkips.keys.any { it.startsWith("accessorio") })
            val files = conv.preview(plan, "Prova TACZ")
            fun json(p: String) = Json.parse(String(files[p] ?: error("manca $p"), Charsets.UTF_8)) as Map<*, *>
            val item = (json("Prova TACZ BP/items/tacz/pistola.json")["minecraft:item"] as Map<*, *>)["components"] as Map<*, *>
            assertNotNull(item["minecraft:use_modifiers"])
            assertEquals(1L, item["minecraft:max_stack_size"])
            // Il modello in mano: osso legato alla mano, accessori non montati nascosti, quello di serie visibile.
            val geo = (json("Prova TACZ RP/models/entity/mmi_tacz/tacz/pistola.geo.json")["minecraft:geometry"] as List<*>)[0] as Map<*, *>
            val bones = (geo["bones"] as List<*>).map { it as Map<*, *> }
            assertEquals("q.item_slot_to_bone_name(c.item_slot)", bones[0]["binding"])
            assertEquals("mmi_gun", bones.first { it["name"] == "root" }["parent"])
            val vis = ((json("Prova TACZ RP/render_controllers/mmi_tacz/tacz/pistola.render.json")["render_controllers"] as Map<*, *>).values.first() as Map<*, *>)["part_visibility"] as List<*>
            val hidden = vis.flatMap { (it as Map<*, *>).filterValues { v -> v == false }.keys }
            assertTrue(hidden.containsAll(listOf("mag_extended_1", "calcio_b", "lefthand_pos")))
            assertTrue("calcio_a" !in hidden)
            val anims = (json("Prova TACZ RP/animations/mmi_tacz/tacz/pistola.animation.json")["animations"] as Map<*, *>)
            assertTrue(anims.keys.containsAll(listOf("animation.mmi.tacz.pistola.hold1", "animation.mmi.tacz.pistola.hold3", "animation.mmi.tacz.pistola.shoot")))
            // Dati per lo script: caricatore, munizione, danni per distanza e suoni della ricarica (dalle animazioni).
            val data = String(files["Prova TACZ BP/scripts/mmi_tacz_data.js"]!!, Charsets.UTF_8)
            assertTrue(data.contains("\"tacz:pistola\":{\"name\":\"tacz.gun.pistola.name\",\"ammo\":\"tacz:ammo_9mm\",\"mag\":15"))
            assertTrue(data.contains("[0.2,\"tacz.pistola.caricatore_fuori\"]"))
            assertTrue(data.contains("\"result\":\"tacz:ammo_9mm\",\"count\":30"))
            assertTrue(files.containsKey("Prova TACZ RP/sounds/mmi/tacz/pistola/sparo.ogg"))
            assertTrue(files.containsKey("Prova TACZ BP/scripts/mmi_tacz.js"))
            assertTrue(String(files["Prova TACZ BP/scripts/main.js"]!!, Charsets.UTF_8).contains("import \"./mmi_tacz.js\""))
            val manifest = String(files["Prova TACZ BP/manifest.json"]!!, Charsets.UTF_8)
            assertTrue(manifest.contains("@minecraft/server-ui"))
            // Il banco: modello ridotto entro 30 pixel, ricetta nel banco da lavoro (la cassa di munizioni diventa una cassa).
            val bgeo = (json("Prova TACZ RP/models/blocks/mmi_tacz/tacz/banco.geo.json")["minecraft:geometry"] as List<*>)[0] as Map<*, *>
            val cube = (((bgeo["bones"] as List<*>)[0] as Map<*, *>)["cubes"] as List<*>)[0] as Map<*, *>
            assertEquals(listOf(30.0, 30.0, 15.0), (cube["size"] as List<*>).map { (it as Number).toDouble() })
            val craft = json("Prova TACZ BP/recipes/mmi_tacz/banco.json")["minecraft:recipe_shaped"] as Map<*, *>
            assertEquals("tacz:banco", (craft["result"] as Map<*, *>)["item"])
            assertEquals("minecraft:chest", ((craft["key"] as Map<*, *>)["B"] as Map<*, *>)["item"])
        }
    }

    @Test fun heldPoseMatchesTheChainOfBones() {
        // Terza persona: l'impugnatura finisce nella mano (punto «rightitem» del giocatore, col braccio piegato di 18°).
        val (r3, p3) = TaczWriter.holdThirdPerson(doubleArrayOf(0.0, 6.825, 5.425), 0.6)
        assertEquals(listOf(18.0, 0.0, 0.0), r3.map { (it as Number).toDouble() + 0.0 })
        val bone = Mat.Bone(doubleArrayOf(0.0, 24.0, 0.0), r3.map { (it as Number).toDouble() }.toDoubleArray(), p3.map { (it as Number).toDouble() }.toDoubleArray(), null)
        val arm = Mat.Bone(doubleArrayOf(-5.0, 22.0, 0.0), doubleArrayOf(-18.0, 0.0, 0.0), DoubleArray(3), null)
        fun world(v: DoubleArray): DoubleArray {
            // scala 0,6 attorno al perno, poi l'osso, poi lo spostamento del legame (rightitem − 0,24,0) e il braccio
            val scaled = Mat.add(doubleArrayOf(0.0, 24.0, 0.0), Mat.scale(Mat.sub(v, doubleArrayOf(0.0, 24.0, 0.0)), 0.6))
            return arm.apply(Mat.add(bone.apply(scaled), doubleArrayOf(-6.0, -9.0, 1.0)))
        }
        val hand = arm.apply(doubleArrayOf(-6.0, 15.0, 1.0))
        val grip = world(doubleArrayOf(0.0, 6.825, 5.425))
        for (i in 0..2) assertEquals(hand[i], grip[i], 0.01)
        // La canna resta orizzontale e punta in avanti (-Z).
        val muzzle = world(doubleArrayOf(0.0, 8.55, -21.3))
        assertEquals(hand[1] + 0.6 * (8.55 - 6.825), muzzle[1], 0.05)
        assertTrue(muzzle[2] < hand[2] - 10)
    }
}
