package com.mmi.modinstaller.convert

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Una mod Fabric finta, scritta come le mod vere (formati 1.20 e 1.21.4+ mescolati di proposito). */
object TestMod {

    fun png(w: Int = 16, h: Int = 16, alpha: (Int, Int) -> Int = { _, _ -> 255 }, color: (Int, Int) -> Triple<Int, Int, Int> = { _, _ -> Triple(200, 30, 40) }): ByteArray {
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            val (r, g, b) = color(x, y)
            px[o] = r.toByte(); px[o + 1] = g.toByte(); px[o + 2] = b.toByte(); px[o + 3] = alpha(x, y).toByte()
        }
        return Png.encode(Image(w, h, px))
    }

    private fun s(text: String) = text.trimIndent().toByteArray(Charsets.UTF_8)

    fun entries(): Map<String, ByteArray> {
        val e = LinkedHashMap<String, ByteArray>()
        e["fabric.mod.json"] = s("""{"schemaVersion":1,"id":"testmod","version":"2.0.0","name":"Test Mod","icon":"assets/testmod/icon.png"}""")
        e["assets/testmod/icon.png"] = png(32, 32)
        e["assets/testmod/lang/en_us.json"] = s(
            """{"item.testmod.ruby":"Ruby","item.testmod.ruby_sword":"Ruby Sword","item.testmod.tomato":"Tomato",
            "block.testmod.ruby_block":"Block of Ruby","block.testmod.ruby_ore":"Ruby Ore","entity.testmod.critter":"Critter",
            "item.testmod.ruby_helmet":"Ruby Helmet","item.testmod.ruby_pickaxe":"Ruby Pickaxe","block.testmod.ruby_door":"Ruby Door","entity.testmod.beetle":"Beetle"}"""
        )
        e["assets/testmod/lang/it_it.json"] = s("""{"item.testmod.ruby":"Rubino","block.testmod.ruby_block":"Blocco di rubino","entity.testmod.critter":"Bestiolina"}""")

        // ---- blocchi
        fun state(name: String, json: String) { e["assets/testmod/blockstates/$name.json"] = s(json) }
        fun model(path: String, json: String) { e["assets/testmod/models/$path.json"] = s(json) }
        fun tex(path: String, bytes: ByteArray = png()) { e["assets/testmod/textures/$path.png"] = bytes }
        state("ruby_block", """{"variants":{"":{"model":"testmod:block/ruby_block"}}}""")
        model("block/ruby_block", """{"parent":"minecraft:block/cube_all","textures":{"all":"testmod:block/ruby_block"}}""")
        tex("block/ruby_block")
        state("ruby_ore", """{"variants":{"":{"model":"testmod:block/ruby_ore"}}}""")
        model("block/ruby_ore", """{"parent":"block/cube_all","textures":{"all":"testmod:block/ruby_ore"}}""")
        tex("block/ruby_ore")
        state("ruby_lamp", """{"variants":{"facing=east":{"model":"testmod:block/ruby_lamp","y":90},"facing=north":{"model":"testmod:block/ruby_lamp"},
            "facing=south":{"model":"testmod:block/ruby_lamp","y":180},"facing=west":{"model":"testmod:block/ruby_lamp","y":270}}}""")
        model("block/ruby_lamp", """{"parent":"minecraft:block/orientable","textures":{"top":"testmod:block/lamp_top","front":"testmod:block/lamp_front","side":"testmod:block/lamp_side"}}""")
        tex("block/lamp_top"); tex("block/lamp_front"); tex("block/lamp_side")
        state("ruby_slab", """{"variants":{"type=bottom":{"model":"testmod:block/ruby_slab"},"type=double":{"model":"testmod:block/ruby_block"},"type=top":{"model":"testmod:block/ruby_slab_top"}}}""")
        model("block/ruby_slab", """{"parent":"minecraft:block/slab","textures":{"bottom":"testmod:block/ruby_block","top":"testmod:block/ruby_block","side":"testmod:block/ruby_block"}}""")
        model("block/ruby_slab_top", """{"parent":"minecraft:block/slab_top","textures":{"bottom":"testmod:block/ruby_block","top":"testmod:block/ruby_block","side":"testmod:block/ruby_block"}}""")
        // Coltura: pianta a X con gli stati «age», la texture ha i bordi trasparenti
        state("tomatoes", """{"variants":{"age=0":{"model":"testmod:block/tomatoes_stage0"},"age=1":{"model":"testmod:block/tomatoes_stage0"}}}""")
        model("block/tomatoes_stage0", """{"parent":"minecraft:block/crop","textures":{"crop":"testmod:block/tomatoes_stage0"}}""")
        tex("block/tomatoes_stage0", png(alpha = { x, _ -> if (x < 4) 0 else 255 }))
        // Forma propria con un elemento ruotato e una faccia con la texture ruotata
        state("pedestal", """{"variants":{"":{"model":"testmod:block/pedestal"}}}""")
        model(
            "block/pedestal",
            """{"textures":{"0":"testmod:block/pedestal","particle":"testmod:block/pedestal"},"elements":[
            {"from":[2,0,2],"to":[14,4,14],"faces":{"north":{"uv":[2,12,14,16],"texture":"#0"},"up":{"uv":[2,2,14,14],"texture":"#0","rotation":90},
             "down":{"texture":"#0"},"south":{"texture":"#0"},"east":{"texture":"#0"},"west":{"texture":"#0"}}},
            {"from":[12,4,6],"to":[16,10,10],"rotation":{"angle":22.5,"axis":"y","origin":[8,8,8]},"faces":{"north":{"texture":"#0"},"up":{"texture":"#0"}}}]}"""
        )
        tex("block/pedestal", png(alpha = { _, y -> if (y == 0) 128 else 255 }))
        // Forma troppo grande per Bedrock (alta 40 pixel): diventa un cubo
        state("tall_lamp", """{"variants":{"":{"model":"testmod:block/tall_lamp"}}}""")
        model("block/tall_lamp", """{"textures":{"0":"testmod:block/ruby_block"},"elements":[{"from":[4,0,4],"to":[12,40,12],"faces":{"north":{"texture":"#0"}}}]}""")
        // Cartello: lo disegna il codice; resta come oggetto (ha un'icona piatta)
        state("ruby_sign", """{"variants":{"":{"model":"testmod:block/ruby_sign"}}}""")
        model("block/ruby_sign", """{"textures":{"particle":"testmod:block/ruby_block"}}""")
        model("item/ruby_sign", """{"parent":"minecraft:item/generated","textures":{"layer0":"testmod:item/ruby_sign"}}""")
        tex("item/ruby_sign")
        // Porta: forma di Minecraft che l'app non riproduce; resta come oggetto
        state("ruby_door", """{"variants":{"facing=east,half=lower,hinge=left,open=false":{"model":"testmod:block/ruby_door_bottom_left"},
            "facing=east,half=upper,hinge=left,open=false":{"model":"testmod:block/ruby_door_top_left"}}}""")
        model("block/ruby_door_bottom_left", """{"parent":"minecraft:block/door_bottom_left","textures":{"bottom":"testmod:block/ruby_door_bottom","top":"testmod:block/ruby_door_top"}}""")
        model("block/ruby_door_top_left", """{"parent":"minecraft:block/door_top_left","textures":{"bottom":"testmod:block/ruby_door_bottom","top":"testmod:block/ruby_door_top"}}""")
        tex("block/ruby_door_bottom"); tex("block/ruby_door_top")
        model("item/ruby_door", """{"parent":"minecraft:item/generated","textures":{"layer0":"testmod:item/ruby_door"}}""")
        tex("item/ruby_door")
        // Botola: forma conosciuta (lastra sottile)
        state("ruby_trapdoor", """{"variants":{"facing=north,half=bottom,open=false":{"model":"testmod:block/ruby_trapdoor_bottom"},
            "facing=north,half=top,open=false":{"model":"testmod:block/ruby_trapdoor_top"}}}""")
        model("block/ruby_trapdoor_bottom", """{"parent":"minecraft:block/template_orientable_trapdoor_bottom","textures":{"texture":"testmod:block/ruby_block"}}""")
        model("block/ruby_trapdoor_top", """{"parent":"minecraft:block/template_orientable_trapdoor_top","textures":{"texture":"testmod:block/ruby_block"}}""")
        // Tronco: asse x/y/z → si orienta come i tronchi di Bedrock
        state("ruby_log", """{"variants":{"axis=x":{"model":"testmod:block/ruby_log","x":90,"y":90},"axis=y":{"model":"testmod:block/ruby_log"},"axis=z":{"model":"testmod:block/ruby_log","x":90}}}""")
        model("block/ruby_log", """{"parent":"minecraft:block/cube_column","textures":{"end":"testmod:block/lamp_top","side":"testmod:block/lamp_side"}}""")
        // Foglie grigie da colorare con il bioma
        state("ruby_leaves", """{"variants":{"":{"model":"testmod:block/ruby_leaves"}}}""")
        model("block/ruby_leaves", """{"parent":"minecraft:block/leaves","textures":{"all":"testmod:block/ruby_leaves"}}""")
        tex("block/ruby_leaves", png(alpha = { x, y -> if ((x + y) % 3 == 0) 0 else 255 }, color = { x, _ -> Triple(100 + x, 100 + x, 100 + x) }))
        // Blocco con una texture animata (striscia di 2 fotogrammi)
        state("ruby_crystal", """{"variants":{"":{"model":"testmod:block/ruby_crystal"}}}""")
        model("block/ruby_crystal", """{"parent":"minecraft:block/cube_all","textures":{"all":"testmod:block/ruby_crystal"}}""")
        tex("block/ruby_crystal", png(16, 32))
        e["assets/testmod/textures/block/ruby_crystal.png.mcmeta"] = s("""{"animation":{"frametime":4}}""")

        // ---- oggetti
        model("item/ruby", """{"parent":"minecraft:item/generated","textures":{"layer0":"testmod:item/ruby"}}""")
        tex("item/ruby")
        model("item/ruby_sword", """{"parent":"minecraft:item/handheld","textures":{"layer0":"testmod:item/ruby_sword"}}""")
        tex("item/ruby_sword")
        model("item/tomato", """{"parent":"item/generated","textures":{"layer0":"testmod:item/tomato"}}""")
        tex("item/tomato")
        model("item/ruby_helmet", """{"parent":"item/generated","textures":{"layer0":"testmod:item/ruby_helmet"}}""")
        tex("item/ruby_helmet")
        model("item/ruby_block", """{"parent":"testmod:block/ruby_block"}""")
        model("item/critter_spawn_egg", """{"parent":"minecraft:item/template_spawn_egg"}""")
        model("item/ghost", """{"parent":"item/generated","textures":{"layer0":"testmod:item/ghost"}}""") // texture assente
        // Formato 1.21.4+: definizione in assets/<ns>/items, modello in models/item
        e["assets/testmod/items/ruby_pickaxe.json"] = s("""{"model":{"type":"minecraft:model","model":"testmod:item/ruby_pickaxe"}}""")
        model("item/ruby_pickaxe", """{"parent":"minecraft:item/handheld","textures":{"layer0":"testmod:item/ruby_pickaxe"}}""")
        tex("item/ruby_pickaxe", png(16, 32))
        e["assets/testmod/textures/item/ruby_pickaxe.png.mcmeta"] = s("""{"animation":{}}""")
        // Armatura 1.21.2+
        e["assets/testmod/equipment/ruby.json"] = s("""{"layers":{"humanoid":[{"texture":"testmod:ruby"}],"humanoid_leggings":[{"texture":"testmod:ruby"}]}}""")
        tex("entity/equipment/humanoid/ruby", png(64, 32))

        // ---- gruppi
        e["data/minecraft/tags/item/swords.json"] = s("""{"values":["testmod:ruby_sword"]}""")
        e["data/c/tags/item/foods/vegetable.json"] = s("""{"values":["testmod:tomato"]}""")
        e["data/c/tags/item/gems/ruby.json"] = s("""{"values":["testmod:ruby"]}""")
        e["data/c/tags/item/gems.json"] = s("""{"values":["#c:gems/ruby","minecraft:diamond"]}""")
        e["data/minecraft/tags/block/mineable/pickaxe.json"] = s("""{"values":["testmod:ruby_block","testmod:ruby_ore"]}""")

        // ---- ricette (formati di versioni diverse)
        fun recipe(name: String, json: String) { e["data/testmod/recipe/$name.json"] = s(json) }
        recipe("ruby_sword", """{"type":"minecraft:crafting_shaped","pattern":["R","R","S"],"key":{"R":"#c:gems/ruby","S":"#c:rods/wooden"},"result":{"id":"testmod:ruby_sword"}}""")
        recipe("ruby_block", """{"type":"minecraft:crafting_shaped","pattern":["RRR","RRR","RRR"],"key":{"R":{"item":"testmod:ruby"}},"result":{"item":"testmod:ruby_block","count":1}}""")
        recipe("ruby_from_block", """{"type":"minecraft:crafting_shapeless","ingredients":[{"item":"testmod:ruby_block"}],"result":{"id":"testmod:ruby","count":9}}""")
        recipe("ruby_smelting", """{"type":"minecraft:smelting","ingredient":["testmod:ruby_ore","testmod:ruby_leaves"],"result":{"id":"testmod:ruby"},"experience":1.0}""")
        recipe("ruby_blasting", """{"type":"minecraft:blasting","ingredient":{"item":"testmod:ruby_ore"},"result":"testmod:ruby"}""")
        recipe("slab_cutting", """{"type":"minecraft:stonecutting","ingredient":{"item":"testmod:ruby_block"},"result":{"id":"testmod:ruby_slab","count":2}}""")
        recipe("upgrade", """{"type":"minecraft:smithing_transform","template":"minecraft:netherite_upgrade_smithing_template","base":"testmod:ruby_sword","addition":"#c:gems/ruby","result":{"id":"testmod:ruby_pickaxe"}}""")
        recipe("bed_from_ruby", """{"type":"minecraft:crafting_shapeless","ingredients":["testmod:ruby","minecraft:red_bed","minecraft:oak_planks"],"result":{"id":"minecraft:bricks"}}""")
        recipe("gems_any", """{"type":"minecraft:crafting_shapeless","ingredients":["#c:gems","#minecraft:planks"],"result":{"id":"minecraft:stick"}}""")
        recipe("machine", """{"type":"testmod:infusing","input":"testmod:ruby","result":"testmod:ruby_block"}""")
        recipe("compat", """{"fabric:load_conditions":[{"condition":"fabric:all_mods_loaded","values":["othermod"]}],"type":"minecraft:crafting_shapeless","ingredients":["testmod:ruby"],"result":{"id":"minecraft:stick"}}""")
        recipe("foreign", """{"type":"minecraft:crafting_shapeless","ingredients":["othermod:gear"],"result":{"id":"testmod:ruby"}}""")
        recipe("big", """{"type":"minecraft:crafting_shaped","pattern":["RRRR"],"key":{"R":"testmod:ruby"},"result":{"id":"testmod:ruby"}}""")

        // ---- bottini
        e["data/testmod/loot_table/blocks/ruby_ore.json"] = s(
            """{"type":"minecraft:block","pools":[{"rolls":1,"entries":[{"type":"minecraft:alternatives","children":[
            {"type":"minecraft:item","name":"testmod:ruby_ore","conditions":[{"condition":"minecraft:match_tool"}]},
            {"type":"minecraft:item","name":"testmod:ruby","functions":[{"function":"minecraft:set_count","count":{"type":"minecraft:uniform","min":1,"max":3}},
             {"function":"minecraft:apply_bonus","enchantment":"minecraft:fortune","formula":"minecraft:ore_drops"},{"function":"minecraft:explosion_decay"}]}]}]}]}"""
        )
        e["data/testmod/loot_table/blocks/ruby_block.json"] = s("""{"pools":[{"rolls":1.0,"entries":[{"type":"minecraft:item","name":"testmod:ruby_block"}],"conditions":[{"condition":"minecraft:survives_explosion"}]}]}""")

        // ---- minerale
        e["data/testmod/worldgen/configured_feature/ore_ruby.json"] = s(
            """{"type":"minecraft:ore","config":{"size":6,"targets":[{"target":{"predicate_type":"minecraft:tag_match","tag":"minecraft:stone_ore_replaceables"},"state":{"Name":"testmod:ruby_ore"}}]}}"""
        )
        e["data/testmod/worldgen/placed_feature/ore_ruby.json"] = s(
            """{"feature":"testmod:ore_ruby","placement":[{"type":"minecraft:count","count":4},{"type":"minecraft:in_square"},
            {"type":"minecraft:height_range","height":{"type":"minecraft:trapezoid","min_inclusive":{"above_bottom":10},"max_inclusive":{"absolute":30}}},{"type":"minecraft:biome"}]}"""
        )

        // ---- creatura GeckoLib
        e["assets/testmod/geo/entity/critter.geo.json"] = s(
            """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.testmod.critter","texture_width":32,"texture_height":32},
            "bones":[{"name":"body","pivot":[0,0,0],"cubes":[{"origin":[-4,0,-6],"size":[8,6,12],"uv":[0,0]}]}]}]}"""
        )
        e["assets/testmod/animations/critter.animation.json"] = s(
            """{"format_version":"1.8.0","animations":{"animation.critter.walk":{"loop":true,"bones":{"body":{"rotation":{"0.0":{"vector":[0,0,0],"easing":"easeInSine"},"0.5":{"vector":[5,0,0]}}}}},
            "animation.critter.idle":{"loop":true,"bones":{"body":{"position":[0,0,0]}}}}}"""
        )
        e["assets/testmod/textures/entity/critter.png"] = png(32, 32, color = { _, _ -> Triple(100, 200, 50) })
        // Creatura GeckoLib 5: assets/<ns>/geckolib/models e geckolib/animations, file .json
        e["assets/testmod/geckolib/models/entity/beetle.json"] = s(
            """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.testmod.beetle","texture_width":16,"texture_height":16},
            "bones":[{"name":"body","pivot":[0,0,0],"cubes":[{"origin":[-2,0,-3],"size":[4,2,6],"uv":[0,0]}]}]}]}"""
        )
        e["assets/testmod/geckolib/animations/entity/beetle.json"] = s("""{"format_version":"1.8.0","animations":{"animation.beetle.walk":{"loop":true,"bones":{}}}}""")
        e["assets/testmod/textures/entity/beetle.png"] = png(16, 16)
        e["assets/testmod/sounds/critter.ogg"] = ByteArray(10)
        e["testmod.mixins.json"] = s("{}")
        return e
    }

    fun jar(): JavaJar = JavaJar.ofEntries("testmod.jar", entries(), classCount = 42)

    fun jarFile(dir: File, name: String = "testmod.jar", entries: Map<String, ByteArray> = entries()): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            z.putNextEntry(ZipEntry("com/example/Main.class")); z.write(byteArrayOf(-54, -2, -70, -66)); z.closeEntry()
        }
        return f
    }

    fun tempDir(): File = Files.createTempDirectory("conv-test").toFile()

    fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }
}
