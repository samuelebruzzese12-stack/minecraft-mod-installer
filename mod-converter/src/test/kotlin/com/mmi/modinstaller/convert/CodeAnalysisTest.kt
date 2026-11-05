package com.mmi.modinstaller.convert

import com.mmi.modinstaller.convert.code.CodeAnalyzer
import com.mmi.modinstaller.convert.code.CodeFactsReader
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BLOCKBRIDGE: lettura del codice di mod vere compilate con javac (NeoForge con i nomi di Mojang, Fabric con «intermediary»). */
class CodeAnalysisTest {

    private val neo by lazy { JavaJar.ofEntries("testcode.jar", JavaTestMods.neoforge()) }
    private val facts by lazy { CodeFactsReader.read(CodeAnalyzer.analyze(neo)) }

    @Test fun registrationsFromDeferredRegister() {
        val ids = facts.analysis.unique.map { it.registry + " " + it.id }.toSet()
        for (want in listOf("item testcode:ruby", "item testcode:ruby_sword", "item testcode:tomato", "item testcode:ruby_helmet", "item testcode:ruby_block",
            "block testcode:ruby_block", "block testcode:ruby_ore", "entity_type testcode:goblin")) {
            assertTrue("manca $want in $ids", want in ids)
        }
        // Il ciclo sui colori si legge elemento per elemento.
        for (c in listOf("red", "green", "blue")) assertTrue(ids.toString(), "item testcode:${c}_gem" in ids)
    }

    @Test fun realItemValues() {
        val sword = facts.items.getValue(Rl("testcode", "ruby_sword"))
        assertEquals("sword", sword.role)
        assertEquals(1200, sword.durability)
        assertEquals(6.0, sword.attackDamage!!, 1e-6) // 3 dell'arma + 3 del materiale
        assertEquals(-2.4, sword.attackSpeed!!, 1e-4)
        assertEquals(7.5, sword.tier!!.speed!!, 1e-6)
        assertEquals(18, sword.enchantability)
        val ruby = facts.items.getValue(Rl("testcode", "ruby"))
        assertEquals(16, ruby.maxStack)
        val tomato = facts.items.getValue(Rl("testcode", "tomato"))
        assertEquals(4, tomato.food!!.nutrition)
        assertEquals(0.3, tomato.food!!.saturation!!, 1e-6)
        assertEquals("minecraft:regeneration", tomato.food!!.effects.single().effect)
        assertEquals(100, tomato.food!!.effects.single().duration)
        val helmet = facts.items.getValue(Rl("testcode", "ruby_helmet"))
        assertEquals("armor", helmet.role)
        assertEquals("helmet", helmet.armorSlot)
        assertEquals(3, helmet.armorDefense)
        assertEquals(11 * 30, helmet.durability)
        assertEquals(1.5, helmet.armorToughness!!, 1e-6)
        assertEquals(Rl("testcode", "ruby_block"), facts.items.getValue(Rl("testcode", "ruby_block")).blockId)
    }

    @Test fun realBlockValues() {
        val b = facts.blocks.getValue(Rl("testcode", "ruby_block"))
        assertEquals(5.0, b.hardness!!, 1e-6)
        assertEquals(6.0, b.resistance!!, 1e-6)
        assertEquals(7, b.light)
        assertEquals("metal", b.sound)
        assertTrue(b.requiresTool)
        assertEquals(0.98, b.friction!!, 1e-6)
        val ore = facts.blocks.getValue(Rl("testcode", "ruby_ore"))
        assertEquals("ore", ore.role)
        assertEquals(3 to 7, ore.xp)
    }

    @Test fun creatureFromCode() {
        val g = facts.entities.getValue(Rl("testcode", "goblin"))
        assertEquals("MONSTER", g.category)
        assertEquals(0.6, g.width!!, 1e-6)
        assertEquals(1.95, g.height!!, 1e-6)
        assertEquals(24.0, g.attributes["max_health"]!!, 1e-6)
        assertEquals(0.3, g.attributes["movement_speed"]!!, 1e-6)
        assertEquals(4.0, g.attributes["attack_damage"]!!, 1e-6)
        assertEquals(listOf("FloatGoal", "MeleeAttackGoal", "WaterAvoidingRandomStrollGoal", "LookAtPlayerGoal", "HurtByTargetGoal", "NearestAttackableTargetGoal"), g.goals.map { it.goal })
        assertEquals(Rl("testcode", "textures/entity/goblin.png"), g.texture)
        val m = g.model!!
        assertEquals(64, m.texWidth)
        assertEquals(listOf("body", "left_leg"), m.root.children.map { it.name })
        assertEquals(listOf("walk", "idle"), m.animations.map { it.role })
    }

    @Test fun geometryMatchesBlockbenchConventions() {
        val g = facts.entities.getValue(Rl("testcode", "goblin"))
        val doc = CodeEntities.geometry("geometry.test", g.model!!)
        @Suppress("UNCHECKED_CAST")
        val bones = ((doc["minecraft:geometry"] as List<Map<String, Any?>>)[0]["bones"] as List<Map<String, Any?>>).associateBy { it["name"] }
        assertEquals(listOf(0L, 12L, 0L), bones.getValue("body")["pivot"])
        assertEquals(listOf(-4L, 12L, -2L), (bones.getValue("body")["cubes"] as List<Map<String, Any?>>)[0]["origin"])
        assertEquals("body", bones.getValue("head")["parent"])
        assertEquals(listOf(0L, 24L, 0L), bones.getValue("head")["pivot"])
        val head = (bones.getValue("head")["cubes"] as List<Map<String, Any?>>)[0]
        assertEquals(listOf(-4L, 24L, -4L), head["origin"])
        assertEquals(0.5, head["inflate"])
        val leg = bones.getValue("left_leg")
        assertEquals(listOf(2L, 12L, 0L), leg["pivot"])
        assertEquals(5.7296, ((leg["rotation"] as List<*>)[0] as Number).toDouble(), 1e-3)
        val legCube = (leg["cubes"] as List<Map<String, Any?>>)[0]
        assertEquals(listOf(0L, 0L, -2L), legCube["origin"])
        assertEquals(true, legCube["mirror"])
    }

    @Test fun animationsAndBehaviorInBedrock() {
        val g = facts.entities.getValue(Rl("testcode", "goblin"))
        val anims = CodeEntities.animations("mmi.testcode.goblin", g.model!!)
        @Suppress("UNCHECKED_CAST")
        val walk = (anims.doc["animations"] as Map<String, Map<String, Any?>>).getValue(anims.byRole.getValue("walk"))
        @Suppress("UNCHECKED_CAST")
        val rot = ((walk["bones"] as Map<String, Map<String, Any?>>).getValue("left_leg"))["rotation"] as Map<String, Any?>
        assertEquals(listOf(30L, 0L, 0L), rot["0.0"])
        assertEquals("catmullrom", (rot["0.5"] as Map<*, *>)["lerp_mode"])
        assertTrue(anims.byRole.containsKey("look"))
        val (doc, _) = CodeEntities.behaviorDoc("testcode:goblin", g, 0.6, 1.95, null)
        val c = doc["minecraft:entity"].asJsonObject()!!["components"].asJsonObject()!!
        assertEquals(24L, c["minecraft:health"].asJsonObject()!!["value"])
        assertEquals(4L, c["minecraft:attack"].asJsonObject()!!["damage"])
        assertEquals(0.3, c["minecraft:movement"].asJsonObject()!!["value"])
        assertEquals(1.2, c["minecraft:behavior.melee_attack"].asJsonObject()!!["speed_multiplier"])
        assertEquals(8.0, c["minecraft:behavior.look_at_player"].asJsonObject()!!["look_distance"])
        val target = c["minecraft:behavior.nearest_attackable_target"].asJsonObject()!!["entity_types"].asJsonList()!![0].asJsonObject()!!
        assertEquals("player", target["filters"].asJsonObject()!!["value"])
        assertTrue(c.containsKey("minecraft:behavior.hurt_by_target"))
        assertTrue((c["minecraft:type_family"].asJsonObject()!!["family"] as List<*>).contains("monster"))
    }

    @Test fun fabricIntermediaryNames() {
        val jar = JavaJar.ofEntries("fabmod.jar", JavaTestMods.fabric())
        val f = CodeFactsReader.read(CodeAnalyzer.analyze(jar))
        assertEquals(setOf(Rl("fabmod", "gem"), Rl("fabmod", "hammer")), f.items.keys)
        assertEquals(8, f.items.getValue(Rl("fabmod", "gem")).maxStack)
        assertEquals(500, f.items.getValue(Rl("fabmod", "hammer")).durability)
    }

    @Test fun converterUsesCodeValues() {
        // La conversione completa della mod di prova: la creatura passa con il suo modello, il cibo con i valori veri.
        val entries = LinkedHashMap(JavaTestMods.neoforge())
        entries["assets/testcode/models/item/tomato.json"] = """{"parent":"minecraft:item/generated","textures":{"layer0":"testcode:item/tomato"}}""".toByteArray()
        entries["assets/testcode/textures/item/tomato.png"] = TestMod.png()
        val plan = ModConverter().analyzeJars(listOf(JavaJar.ofEntries("testcode.jar", entries)))
        val files = ModConverter().preview(plan, "Code Test")
        val tomato = String(files.getValue("Code Test BP/items/testcode/tomato.json"))
        assertTrue(tomato, tomato.contains("\"nutrition\": 4"))
        assertNotNull(files["Code Test BP/entities/testcode/goblin.json"])
        assertNotNull(files["Code Test RP/models/entity/testcode/goblin.geo.json"])
        assertNotNull(files["Code Test RP/animations/testcode/goblin.animation.json"])
        val report = plan.reports.single()
        assertTrue(report.codeSummary.toString(), report.codeSummary!!.contains("creature"))
        assertTrue(report.entities.toString(), report.entities.any { it.javaId == "testcode:goblin" && it.detail.contains("IA") })
        plan.close()
    }
}
