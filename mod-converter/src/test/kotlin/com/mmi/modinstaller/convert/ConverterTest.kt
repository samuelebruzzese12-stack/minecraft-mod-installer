package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.AnalyzeContext
import com.mmi.modinstaller.core.ContentAnalyzer
import com.mmi.modinstaller.core.ContentKind
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ConverterTest {

    private lateinit var dir: File
    private lateinit var plan: ConversionPlan
    private lateinit var files: Map<String, ByteArray>
    private val conv = ModConverter()

    @Before fun setUp() {
        dir = TestMod.tempDir()
        plan = conv.analyzeJars(listOf(TestMod.jar()))
        files = conv.preview(plan, "Test Mod")
    }

    @After fun tearDown() {
        plan.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(path: String): Map<String, Any?> {
        val b = files[path] ?: throw AssertionError("manca $path; ci sono: " + files.keys.filter { it.contains(path.substringBeforeLast('/')) })
        return Json.parseObject(String(b, Charsets.UTF_8))
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.o(k: String) = this[k] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.l(k: String) = this[k] as List<Any?>

    private fun text(path: String) = String(files[path]!!, Charsets.UTF_8)

    @Test fun reportIsHonest() {
        val r = plan.reports.single()
        assertEquals("Test Mod", r.title)
        assertEquals("Fabric", r.loader)
        assertEquals(
            listOf("ruby", "ruby_door", "ruby_helmet", "ruby_pickaxe", "ruby_sign", "ruby_sword", "tomato"),
            r.items.map { it.javaId.substringAfter(':') }.sorted()
        )
        assertEquals(
            listOf("pedestal", "ruby_block", "ruby_crystal", "ruby_lamp", "ruby_leaves", "ruby_ore", "ruby_slab", "ruby_trapdoor", "tall_lamp", "tomatoes"),
            r.blocks.map { it.javaId.substringAfter(':') }.sorted()
        )
        assertEquals(listOf("Critter"), r.entities.map { it.name.replace("Bestiolina", "Critter") })
        assertEquals(13, r.recipesTotal)
        assertEquals(9, r.recipesConverted)
        assertEquals(1, r.recipeSkips["ricetta di una macchina o di un sistema della mod (funziona solo con il suo codice)"])
        assertEquals(1, r.recipeSkips["vale solo se c'è anche la mod «othermod»"])
        assertEquals(1, r.recipeSkips["usa oggetti di un'altra mod non convertita (es. «othermod:gear»)"])
        assertEquals(1, r.recipeSkips["schema più grande di 3×3"])
        assertEquals(2, r.lootTables)
        assertEquals(1, r.ores)
        assertEquals(1, r.armorModels)
        assertTrue(r.notConverted.toString(), r.notConverted.contains("Blocchi disegnati dal codice (cartelli, casse, letti…): 1"))
        assertTrue(r.notConverted.toString(), r.notConverted.contains("Blocchi con forme che l'app non sa ancora riprodurre: 1, es. ruby_door (door_bottom_left)"))
        assertTrue(r.simplified.toString(), r.simplified.contains("Blocchi che restano solo come oggetti (non si piazzano): 2"))
        assertEquals("solo oggetto", r.items.single { it.javaId == "testmod:ruby_door" }.detail)
        assertEquals("Ruby Door", r.items.single { it.javaId == "testmod:ruby_door" }.name)
        assertTrue(r.notConverted.toString(), r.notConverted.contains("Uova delle creature: 1"))
        assertTrue(r.notConverted.toString(), r.notConverted.contains("Oggetti senza un'icona leggibile: 1"))
        assertTrue(r.notConverted.toString(), r.notConverted.any { it.startsWith("Suoni") })
        assertTrue(r.notConverted.toString(), r.notConverted.contains("Modifiche al motore del gioco (mixin)"))
        assertTrue(r.estimates.toString(), r.estimates.contains("Danni, velocità e durata degli attrezzi stimati dal materiale"))
        assertTrue(r.simplified.toString(), r.simplified.any { it.startsWith("Creature: aspetto e animazioni") })
        assertEquals("Convertita in parte: il contenuto sì, il comportamento in codice no", r.verdict)
    }

    @Test fun manifestsAndPackLinks() {
        val bp = json("Test Mod BP/manifest.json")
        val rp = json("Test Mod RP/manifest.json")
        assertEquals(2L, bp["format_version"])
        assertEquals("data", (bp.l("modules")[0] as Map<*, *>)["type"])
        assertEquals("resources", (rp.l("modules")[0] as Map<*, *>)["type"])
        assertEquals(rp.o("header")["uuid"], (bp.l("dependencies")[0] as Map<*, *>)["uuid"])
        assertEquals(listOf(1L, 21L, 80L), bp.o("header")["min_engine_version"])
        // Stesso nome → stessi identificatori: riconvertire aggiorna l'add-on invece di aggiungerne un altro
        val again = conv.preview(plan, "Test Mod")
        assertEquals(bp.o("header")["uuid"], Json.parseObject(String(again["Test Mod BP/manifest.json"]!!)).o("header")["uuid"])
        assertTrue(Png.isPng(files["Test Mod RP/pack_icon.png"]!!))
    }

    @Test fun itemsHaveIconsNamesAndBehaviour() {
        val sword = json("Test Mod BP/items/testmod/ruby_sword.json").o("minecraft:item")
        assertEquals("testmod:ruby_sword", sword.o("description")["identifier"])
        val c = sword.o("components")
        assertEquals("testmod_item_ruby_sword", c["minecraft:icon"])
        assertEquals("item.testmod.ruby_sword", c.o("minecraft:display_name")["value"])
        assertEquals(1L, c["minecraft:max_stack_size"])
        assertEquals(true, c["minecraft:hand_equipped"])
        assertEquals(250L, c.o("minecraft:durability")["max_durability"])
        assertEquals(7L, c["minecraft:damage"])
        assertTrue(c.o("minecraft:tags").l("tags").containsAll(listOf("minecraft:is_sword", "minecraft:is_tool")))

        val pick = json("Test Mod BP/items/testmod/ruby_pickaxe.json").o("minecraft:item").o("components")
        val digger = pick.o("minecraft:digger")
        assertEquals(true, digger["use_efficiency"])
        assertTrue(Json.stringify(digger).contains("minecraft:is_pickaxe_item_destructible"))

        val tomato = json("Test Mod BP/items/testmod/tomato.json").o("minecraft:item").o("components")
        assertEquals(4L, tomato.o("minecraft:food")["nutrition"])
        assertEquals("eat", tomato["minecraft:use_animation"])

        val helmet = json("Test Mod BP/items/testmod/ruby_helmet.json").o("minecraft:item").o("components")
        assertEquals("slot.armor.head", helmet.o("minecraft:wearable")["slot"])
        val att = json("Test Mod RP/attachables/testmod/ruby_helmet.json").o("minecraft:attachable").o("description")
        assertEquals("textures/mmi/testmod/entity/equipment/humanoid/ruby", att.o("textures")["default"])
        assertEquals("geometry.humanoid.armor.helmet", att.o("geometry")["default"])
        assertNotNull(files["Test Mod RP/textures/mmi/testmod/entity/equipment/humanoid/ruby.png"])

        val atlas = json("Test Mod RP/textures/item_texture.json")
        assertEquals("atlas.items", atlas["texture_name"])
        assertEquals("textures/mmi/testmod/item/ruby", atlas.o("texture_data").o("testmod_item_ruby")["textures"])
        assertNotNull(files["Test Mod RP/textures/mmi/testmod/item/ruby.png"])
        // Oggetto animato: si tiene il primo fotogramma (16×16)
        assertEquals(Pair(16, 16), Png.size(files["Test Mod RP/textures/mmi/testmod/item/ruby_pickaxe.png"]!!))
        // Né la texture mancante né l'uovo diventano oggetti
        assertNull(files["Test Mod BP/items/testmod/ghost.json"])
        assertNull(files["Test Mod BP/items/testmod/critter_spawn_egg.json"])
        // Il blocco non diventa anche un oggetto separato
        assertNull(files["Test Mod BP/items/testmod/ruby_block.json"])
    }

    @Test fun blocksKeepTheirLook() {
        val block = json("Test Mod BP/blocks/testmod/ruby_block.json").o("minecraft:block")
        val c = block.o("components")
        assertEquals("minecraft:geometry.full_block", c["minecraft:geometry"])
        assertEquals("block.testmod.ruby_block", c["minecraft:display_name"])
        assertEquals(mapOf("*" to mapOf("texture" to "testmod_block_ruby_block", "render_method" to "opaque")), c["minecraft:material_instances"])
        assertEquals("loot_tables/blocks/testmod/ruby_block.json", c["minecraft:loot"])
        assertTrue(c.containsKey("tag:minecraft:is_pickaxe_item_destructible"))

        val lamp = json("Test Mod BP/blocks/testmod/ruby_lamp.json").o("minecraft:block").o("components").o("minecraft:material_instances")
        assertEquals("testmod_block_lamp_front", (lamp["*"] as Map<*, *>)["texture"])
        assertEquals("testmod_block_lamp_top", (lamp["up"] as Map<*, *>)["texture"])
        assertEquals("testmod_block_lamp_top", (lamp["down"] as Map<*, *>)["texture"])
        assertEquals("testmod_block_lamp_side", (lamp["south"] as Map<*, *>)["texture"])

        val crop = json("Test Mod BP/blocks/testmod/tomatoes.json").o("minecraft:block").o("components")
        assertEquals("minecraft:geometry.cross", crop["minecraft:geometry"])
        assertEquals("alpha_test_single_sided", (crop.o("minecraft:material_instances")["*"] as Map<*, *>)["render_method"])
        assertEquals(false, crop["minecraft:collision_box"])
        assertEquals(0.0, crop.o("minecraft:destructible_by_mining")["seconds_to_destroy"])

        val leaves = json("Test Mod BP/blocks/testmod/ruby_leaves.json").o("minecraft:block").o("components").o("minecraft:material_instances")
        assertEquals("default_foliage", (leaves["*"] as Map<*, *>)["tint_method"])
        assertEquals("alpha_test", (leaves["*"] as Map<*, *>)["render_method"])

        // Lastra: geometria propria, metà altezza, collisione a metà
        val slab = json("Test Mod BP/blocks/testmod/ruby_slab.json").o("minecraft:block").o("components")
        assertEquals("geometry.testmod.ruby_slab", slab["minecraft:geometry"])
        assertEquals(mapOf("origin" to listOf(-8L, 0L, -8L), "size" to listOf(16L, 8L, 16L)), slab["minecraft:collision_box"])
        val geo = json("Test Mod RP/models/blocks/testmod/ruby_slab.geo.json")
        val cube = (((geo.l("minecraft:geometry")[0] as Map<*, *>)["bones"] as List<*>)[0] as Map<*, *>)["cubes"] as List<*>
        assertEquals(listOf(-8L, 0L, -8L), (cube[0] as Map<*, *>)["origin"])

        // Texture animata di un blocco: resta intera e va in flipbook_textures.json
        assertEquals(Pair(16, 32), Png.size(files["Test Mod RP/textures/mmi/testmod/block/ruby_crystal.png"]!!))
        assertTrue(text("Test Mod RP/textures/flipbook_textures.json").contains("\"ticks_per_frame\": 4"))
        val terrain = json("Test Mod RP/textures/terrain_texture.json")
        assertEquals("atlas.terrain", terrain["texture_name"])
        assertTrue(text("Test Mod RP/blocks.json").contains("\"testmod:ruby_block\""))
        // Forma troppo grande per Bedrock: cubo, e il rapporto lo dice
        assertEquals("minecraft:geometry.full_block", json("Test Mod BP/blocks/testmod/tall_lamp.json").o("minecraft:block").o("components")["minecraft:geometry"])
        assertTrue(plan.reports.single().simplified.toString(), plan.reports.single().simplified.any { it.contains("forma più grande di quella che Bedrock accetta") })
        // Il cartello (disegnato dal codice) e la porta non diventano blocchi, ma oggetti con il loro nome
        assertNull(files["Test Mod BP/blocks/testmod/ruby_sign.json"])
        assertNull(files["Test Mod BP/blocks/testmod/ruby_door.json"])
        assertEquals("block.testmod.ruby_door", json("Test Mod BP/items/testmod/ruby_door.json").o("minecraft:item").o("components").o("minecraft:display_name")["value"])
        // Botola: lastra sottile di 3 pixel
        val trap = json("Test Mod BP/blocks/testmod/ruby_trapdoor.json").o("minecraft:block").o("components")
        assertEquals(mapOf("origin" to listOf(-8L, 0L, -8L), "size" to listOf(16L, 3L, 16L)), trap["minecraft:collision_box"])
    }

    @Test fun shapedGeometryFollowsBedrockConventions() {
        @Suppress("UNCHECKED_CAST")
        val cubes = ((((json("Test Mod RP/models/blocks/testmod/pedestal.geo.json").l("minecraft:geometry")[0] as Map<*, *>)["bones"] as List<*>)[0] as Map<*, *>)["cubes"] as List<Map<String, Any?>>)
        // Base: da 2 a 14 → origine X = 8 − 14 = −6, Z = 2 − 8 = −6
        assertEquals(listOf(-6L, 0L, -6L), cubes[0]["origin"])
        assertEquals(listOf(12L, 4L, 12L), cubes[0]["size"])
        val north = cubes[0].o("uv").o("north")
        assertEquals(listOf(2L, 12L), north["uv"]); assertEquals(listOf(12L, 4L), north["uv_size"])
        assertEquals("t_0", north["material_instance"])
        // Le facce sopra e sotto hanno la texture capovolta; la rotazione della texture resta
        val up = cubes[0].o("uv").o("up")
        assertEquals(listOf(14L, 14L), up["uv"]); assertEquals(listOf(-12L, -12L), up["uv_size"]); assertEquals(90L, up["uv_rotation"])
        // Elemento ruotato sull'asse Y: X specchiato, angolo di segno opposto, perno spostato
        assertEquals(listOf(-8L, 4L, -2L), cubes[1]["origin"])
        assertEquals(listOf(0L, -22.5, 0L), cubes[1]["rotation"])
        assertEquals(listOf(0L, 8L, 0L), cubes[1]["pivot"])
        // Texture semitrasparente → «blend» per tutti i materiali del blocco
        val mi = json("Test Mod BP/blocks/testmod/pedestal.json").o("minecraft:block").o("components").o("minecraft:material_instances")
        assertEquals(setOf("blend"), mi.values.map { (it as Map<*, *>)["render_method"] }.toSet())
        assertTrue(mi.containsKey("t_0"))
    }

    private fun recipe(name: String): Map<String, Any?> = json("Test Mod BP/recipes/testmod/$name.json")

    @Test fun recipesUseBedrockNames() {
        val sword = recipe("ruby_sword").o("minecraft:recipe_shaped")
        assertEquals(listOf("R", "R", "S"), sword["pattern"])
        assertEquals(mapOf("item" to "testmod:ruby"), sword.o("key")["R"])
        assertEquals(mapOf("item" to "minecraft:stick"), sword.o("key")["S"])
        assertEquals(listOf("crafting_table"), sword["tags"])
        assertEquals(mapOf("context" to "AlwaysUnlocked"), sword["unlock"])
        assertEquals(mapOf("item" to "testmod:ruby_sword"), sword["result"])

        assertEquals(mapOf("item" to "testmod:ruby", "count" to 9L), recipe("ruby_from_block").o("minecraft:recipe_shapeless")["result"])
        // Due ingredienti per la fornace → due ricette
        assertEquals("testmod:ruby_ore", recipe("ruby_smelting_1").o("minecraft:recipe_furnace")["input"])
        assertEquals("testmod:ruby_leaves", recipe("ruby_smelting_2").o("minecraft:recipe_furnace")["input"])
        assertEquals(listOf("furnace"), recipe("ruby_smelting_1").o("minecraft:recipe_furnace")["tags"])
        assertEquals(listOf("blast_furnace"), recipe("ruby_blasting").o("minecraft:recipe_furnace")["tags"])
        val cut = recipe("slab_cutting").o("minecraft:recipe_shapeless")
        assertEquals(listOf("stonecutter"), cut["tags"])
        assertEquals(mapOf("item" to "testmod:ruby_slab", "count" to 2L), cut["result"])
        val up = recipe("upgrade").o("minecraft:recipe_smithing_transform")
        assertEquals("testmod:ruby", up["addition"])
        assertEquals("minecraft:netherite_upgrade_smithing_template", up["template"])
        // Nomi Java diversi in Bedrock: letto rosso → bed con il dato 14, mattoni → brick_block
        val bed = recipe("bed_from_ruby").o("minecraft:recipe_shapeless")
        assertTrue(bed.l("ingredients").contains(mapOf("item" to "minecraft:bed", "data" to 14L)))
        assertTrue(bed.l("ingredients").contains(mapOf("item" to "minecraft:oak_planks")))
        assertEquals(mapOf("item" to "minecraft:brick_block"), bed["result"])
        // Gruppo con oggetti della mod e di Minecraft → oggetto di Minecraft; gruppo uguale in Bedrock → tag
        val any = recipe("gems_any").o("minecraft:recipe_shapeless").l("ingredients")
        assertEquals(listOf(mapOf("item" to "minecraft:diamond"), mapOf("tag" to "minecraft:planks")), any)
    }

    @Test fun lootAndOres() {
        val loot = json("Test Mod BP/loot_tables/blocks/testmod/ruby_ore.json")
        val entry = ((loot.l("pools")[0] as Map<*, *>)["entries"] as List<*>)[0] as Map<*, *>
        assertEquals("testmod:ruby", entry["name"])
        assertEquals(listOf(mapOf("function" to "set_count", "count" to mapOf("min" to 1L, "max" to 3L))), entry["functions"])
        assertTrue(plan.reports.single().simplified.contains("Bottini dei blocchi senza tocco di velluto, fortuna e condizioni speciali"))

        val feature = json("Test Mod BP/features/ore_ruby_feature.json").o("minecraft:ore_feature")
        assertEquals("testmod:ore_ruby_feature", feature.o("description")["identifier"])
        assertEquals(6L, feature["count"])
        val rule = (feature.l("replace_rules")[0] as Map<*, *>)
        assertEquals("testmod:ruby_ore", rule["places_block"])
        assertEquals(listOf("minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite"), rule["may_replace"])
        val fr = json("Test Mod BP/feature_rules/ore_ruby_rule.json").o("minecraft:feature_rules")
        assertEquals("testmod:ore_ruby_feature", fr.o("description")["places_feature"])
        assertEquals(4L, fr.o("distribution")["iterations"])
        assertEquals(mapOf("distribution" to "triangle", "extent" to listOf(-54L, 30L)), fr.o("distribution")["y"])
    }

    @Test fun languagesAndCreatures() {
        val en = text("Test Mod RP/texts/en_US.lang")
        assertTrue(en.contains("item.testmod.ruby=Ruby\n"))
        assertTrue(en.contains("block.testmod.ruby_block=Block of Ruby\n"))
        assertTrue(en.contains("block.testmod.ruby_slab=Ruby Slab\n")) // senza traduzione: dal nome
        assertTrue(en.contains("entity.testmod:critter.name=Critter\n"))
        assertTrue(en.contains("item.spawn_egg.entity.testmod:critter.name=Critter Spawn Egg\n"))
        val it = text("Test Mod RP/texts/it_IT.lang")
        assertTrue(it.contains("item.testmod.ruby=Rubino\n"))
        assertTrue(it.contains("item.spawn_egg.entity.testmod:critter.name=Uovo di Bestiolina\n"))
        assertEquals(listOf("en_US", "it_IT"), Json.parse(text("Test Mod RP/texts/languages.json")))

        val bp = json("Test Mod BP/entities/testmod/critter.json").o("minecraft:entity")
        assertEquals("testmod:critter", bp.o("description")["identifier"])
        assertEquals(mapOf("width" to 0.75, "height" to 0.38), bp.o("components")["minecraft:collision_box"])
        val client = json("Test Mod RP/entity/testmod/critter.entity.json").o("minecraft:client_entity").o("description")
        assertEquals("geometry.testmod.critter", client.o("geometry")["default"])
        assertEquals("animation.critter.walk", client.o("animations")["walk"])
        assertEquals("#64c832", client.o("spawn_egg")["base_color"])
        // I fotogrammi GeckoLib {"vector": …, "easing": …} diventano vettori
        val anim = text("Test Mod RP/animations/testmod/critter.animation.json")
        assertFalse(anim.contains("vector")); assertFalse(anim.contains("easing"))
        assertNotNull(files["Test Mod RP/animation_controllers/testmod/critter.controller.json"])
        assertNotNull(files["Test Mod RP/models/entity/testmod/critter.geo.json"])
        assertNotNull(files["Test Mod RP/textures/mmi/testmod/entity/critter.png"])
    }

    /** L'add-on scritto è un .mcaddon che l'INSTALLER BEDROCK di questa app riconosce e può installare. */
    @Test fun writtenAddonIsAcceptedByTheBedrockInstaller() {
        val jar = TestMod.jarFile(dir)
        val out = File(dir, "Test Mod.mcaddon")
        conv.analyze(listOf(ConvertSource(jar, "testmod.jar"))).use { p ->
            val w = conv.write(p, "Test Mod", out)
            assertEquals(out, w.file)
            assertTrue(w.fileCount > 30)
            ZipFile(out).use { z -> assertNotNull(z.getEntry("Test Mod BP/manifest.json")) }
        }
        assertFalse(File(dir, "Test Mod.mcaddon.part").exists())
        val report = ContentAnalyzer(AnalyzeContext(File(dir, "work"))).analyze(out, "Test Mod.mcaddon")
        val item = report.items.single()
        assertEquals(ContentKind.ADDON, item.kind)
        assertEquals(2, item.uuids.size)
        assertTrue(report.items.flatMap { it.findings }.none { it.code == FindingCode.DEPENDENCY_MISSING })
        assertTrue(report.hasInstallableItems)
    }

    @Test fun jarWithoutContentSaysSo() {
        val code = TestMod.jarFile(dir, "lib.jar", mapOf("fabric.mod.json" to """{"id":"lib","name":"Una Libreria"}""".toByteArray()))
        conv.analyze(listOf(ConvertSource(code, "lib.jar"))).use { p ->
            assertFalse(p.hasContent)
            assertEquals("Niente da convertire: è una mod di solo codice (o una libreria)", p.reports.single().verdict)
            try {
                conv.write(p, "x", File(dir, "x.mcaddon")); throw AssertionError("doveva rifiutare")
            } catch (e: java.io.IOException) {
                assertEquals("Non c'è niente da convertire in Bedrock", e.message)
            }
        }
        val broken = File(dir, "rotto.jar").apply { writeText("non è uno zip") }
        conv.analyze(listOf(ConvertSource(broken, "rotto.jar"))).use { p ->
            assertNotNull(p.reports.single().error)
            assertTrue(p.reports.single().verdict.startsWith("Non letta"))
        }
    }

    @Test fun twoModsTogetherResolveEachOther() {
        val other = mapOf(
            "fabric.mod.json" to """{"id":"othermod","name":"Other"}""".toByteArray(),
            "assets/othermod/models/item/gear.json" to """{"parent":"item/generated","textures":{"layer0":"othermod:item/gear"}}""".toByteArray(),
            "assets/othermod/textures/item/gear.png" to TestMod.png()
        )
        conv.analyzeJars(listOf(TestMod.jar(), JavaJar.ofEntries("other.jar", other))).use { p ->
            val r = p.reports.first()
            assertNull(r.recipeSkips["usa oggetti di un'altra mod non convertita (es. «othermod:gear»)"])
            assertNull(r.recipeSkips["vale solo se c'è anche la mod «othermod»"])
            assertEquals(11, r.recipesConverted)
        }
    }

    @Test fun cleanNames() {
        assertEquals("Le mie mod 1.20", conv.cleanName("  Le mie / mod: 1.20 "))
        assertEquals("Mod Java", conv.cleanName("///"))
    }
}
