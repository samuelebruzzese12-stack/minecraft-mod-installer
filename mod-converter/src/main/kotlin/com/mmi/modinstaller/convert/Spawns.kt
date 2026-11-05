package com.mmi.modinstaller.convert

import com.mmi.modinstaller.convert.code.SpawnFacts
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/**
 * Comparsa naturale delle creature (BLOCKBRIDGE): le regole Java diventano le «spawn rules» di Bedrock.
 *
 * Fonti, dalla più precisa: i modificatori di bioma standard di Forge/NeoForge (`add_spawns`, con peso e gruppi veri), le
 * chiamate a `BiomeModifications.addSpawn` di Fabric lette dal codice, e i gruppi di biomi con il nome della creatura
 * (`has_<creatura>`, `spawns_<creatura>`, come fa Naturalist: lì il peso sta nella configurazione e si stima).
 * I biomi Java si traducono nei gruppi di biomi di Bedrock; i biomi delle mod (che in Bedrock non ci sono) si saltano.
 */
internal object SpawnRules {

    class Rule(val entity: Rl, val category: String?, val weight: Int, val min: Int, val max: Int, val biomes: List<String>, val estimated: Boolean)

    /** Gruppi di biomi Java → gruppi di biomi Bedrock. */
    private val TAGS = mapOf(
        "is_overworld" to "overworld", "is_nether" to "nether", "is_end" to "the_end", "is_forest" to "forest", "is_taiga" to "taiga",
        "is_jungle" to "jungle", "is_savanna" to "savanna", "is_badlands" to "mesa", "is_beach" to "beach", "is_ocean" to "ocean",
        "is_deep_ocean" to "deep", "is_river" to "river", "is_mountain" to "mountains", "is_hill" to "hills", "is_plains" to "plains",
        "is_desert" to "desert", "is_swamp" to "swamp", "is_snowy" to "frozen", "is_cold" to "cold", "is_mushroom" to "mushroom_island",
        "is_birch_forest" to "birch", "is_dark_forest" to "roofed", "is_flower_forest" to "flower_forest", "is_icy" to "frozen",
        "is_snowy_plains" to "ice_plains", "is_lush" to "lush_caves", "is_cave" to "lush_caves", "is_peak" to "mountains",
        "is_mangrove_swamp" to "mangrove_swamp", "is_wet" to "swamp", "is_dry" to "desert", "is_hot" to "desert", "is_spooky" to "roofed",
        "is_cherry_grove" to "cherry_grove", "is_meadow" to "meadow", "is_grove" to "grove", "is_stony_shores" to "stone",
        "plains" to "plains", "forest" to "forest", "desert" to "desert", "swamp" to "swamp", "jungle" to "jungle", "ocean" to "ocean",
        "savanna" to "savanna", "taiga" to "taiga", "mountain" to "mountains", "beach" to "beach", "river" to "river", "badlands" to "mesa",
        "snowy" to "frozen", "mushroom" to "mushroom_island", "nether" to "nether", "end" to "the_end", "overworld" to "overworld"
    )

    /** Biomi di Minecraft Java → gruppo di biomi Bedrock che li identifica. */
    private val BIOMES = mapOf(
        "plains" to "plains", "sunflower_plains" to "plains", "forest" to "forest", "flower_forest" to "flower_forest",
        "birch_forest" to "birch", "old_growth_birch_forest" to "birch", "dark_forest" to "roofed", "taiga" to "taiga",
        "old_growth_pine_taiga" to "mega", "old_growth_spruce_taiga" to "mega", "snowy_taiga" to "taiga", "snowy_plains" to "ice_plains",
        "ice_spikes" to "ice_plains", "desert" to "desert", "swamp" to "swamp", "mangrove_swamp" to "mangrove_swamp", "jungle" to "jungle",
        "sparse_jungle" to "jungle", "bamboo_jungle" to "bamboo", "savanna" to "savanna", "savanna_plateau" to "savanna",
        "windswept_savanna" to "savanna", "badlands" to "mesa", "eroded_badlands" to "mesa", "wooded_badlands" to "mesa",
        "mushroom_fields" to "mushroom_island", "river" to "river", "frozen_river" to "river", "beach" to "beach", "snowy_beach" to "beach",
        "stony_shore" to "stone", "ocean" to "ocean", "deep_ocean" to "ocean", "warm_ocean" to "warm", "lukewarm_ocean" to "lukewarm",
        "deep_lukewarm_ocean" to "lukewarm", "cold_ocean" to "cold", "deep_cold_ocean" to "cold", "frozen_ocean" to "frozen",
        "deep_frozen_ocean" to "frozen", "meadow" to "meadow", "cherry_grove" to "cherry_grove", "grove" to "grove",
        "snowy_slopes" to "snowy_slopes", "jagged_peaks" to "jagged_peaks", "frozen_peaks" to "frozen_peaks", "stony_peaks" to "stony_peaks",
        "windswept_hills" to "extreme_hills", "windswept_forest" to "extreme_hills", "windswept_gravelly_hills" to "extreme_hills",
        "lush_caves" to "lush_caves", "dripstone_caves" to "dripstone_caves", "deep_dark" to "deep_dark", "pale_garden" to "pale_garden",
        "nether_wastes" to "nether", "crimson_forest" to "crimson_forest", "warped_forest" to "warped_forest",
        "soul_sand_valley" to "soulsand_valley", "basalt_deltas" to "basalt_deltas", "the_end" to "the_end", "end_highlands" to "the_end",
        "end_midlands" to "the_end", "small_end_islands" to "the_end", "end_barrens" to "the_end"
    )

    /** Le regole di una mod per le creature convertite. */
    fun read(mod: JavaModContent, code: List<SpawnFacts>, converted: Set<Rl>): List<Rule> {
        val out = ArrayList<Rule>()
        val names = mod.jar.allNames().toList()
        // 1) modificatori di bioma standard (Forge e NeoForge)
        for (n in names) {
            if (!(n.contains("/biome_modifier/") || n.contains("/biome_modifiers/")) || !n.endsWith(".json")) continue
            val o = mod.jar.json(n, 1 shl 20).asJsonObject() ?: continue
            val type = o["type"] as? String ?: continue
            if (!type.endsWith(":add_spawns")) continue
            val biomes = biomeList(o["biomes"])
            val spawners = when (val s = o["spawners"]) { is List<*> -> s.mapNotNull { it.asJsonObject() }; is Map<*, *> -> listOfNotNull(s.asJsonObject()); else -> emptyList() }
            for (sp in spawners) {
                val id = Rl.parse(sp["type"] as? String) ?: continue
                if (id !in converted) continue
                out.add(Rule(id, null, (sp["weight"] as? Number)?.toInt() ?: 10, (sp["minCount"] as? Number)?.toInt() ?: 1, (sp["maxCount"] as? Number)?.toInt() ?: 1, biomes, false))
            }
        }
        // 2) Fabric, dal codice
        for (s in code) if (s.entity in converted && out.none { it.entity == s.entity }) out.add(Rule(s.entity, s.category, s.weight, s.min, s.max, s.biomes, false))
        // 3) gruppi di biomi con il nome della creatura (il peso è nella configurazione della mod: si stima)
        for (id in converted) {
            if (out.any { it.entity == id }) continue
            val p = id.path.substringAfterLast('/')
            val tag = listOf("has_$p", "spawns_$p", "${p}_spawns", "${p}_biomes", "spawn_$p").firstNotNullOfOrNull { t ->
                listOf("worldgen/biome", "worldgen/biomes", "biome").firstNotNullOfOrNull { f -> "data/${id.ns}/tags/$f/$t.json".takeIf { it in names } }
            } ?: continue
            val biomes = tagValues(mod, tag, 0)
            if (biomes.isNotEmpty()) out.add(Rule(id, null, 8, 1, 3, biomes, true))
        }
        return out
    }

    private fun biomeList(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is List<*> -> v.mapNotNull { it as? String }
        is Map<*, *> -> listOfNotNull(v["id"] as? String)
        else -> emptyList()
    }

    /** I valori di un gruppo di biomi della mod, aprendo i gruppi della mod stessa (fino a 4 livelli). */
    private fun tagValues(mod: JavaModContent, entry: String, depth: Int): List<String> {
        if (depth > 4) return emptyList()
        val o = mod.jar.json(entry, 1 shl 20).asJsonObject() ?: return emptyList()
        val out = ArrayList<String>()
        for (v in o["values"].asJsonList().orEmpty()) {
            val s = (v as? String) ?: (v as? Map<*, *>)?.get("id") as? String ?: continue
            if (s.startsWith("#")) {
                val rl = Rl.parse(s.substring(1)) ?: continue
                val own = "data/${rl.ns}/tags/worldgen/biome/${rl.path}.json"
                if (mod.jar.has(own) && rl.ns != "minecraft" && rl.ns != "c" && rl.ns != "forge") out.addAll(tagValues(mod, own, depth + 1)) else out.add(s)
            } else out.add(s)
        }
        return out
    }

    /** I gruppi di biomi Bedrock per un elenco di biomi o gruppi Java (vuoto se nessuno ha un equivalente). */
    fun bedrockBiomes(java: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (b in java) {
            when {
                b == "@overworld" -> out.add("overworld")
                b == "@nether" -> out.add("nether")
                b == "@end" -> out.add("the_end")
                b.startsWith("#") -> Rl.parse(b.substring(1))?.let { rl -> if (rl.ns in setOf("minecraft", "c", "forge", "neoforge", "fabric")) TAGS[rl.path.substringAfterLast('/')]?.let { out.add(it) } }
                else -> Rl.parse(b)?.let { rl -> if (rl.ns == "minecraft") BIOMES[rl.path]?.let { out.add(it) } }
            }
        }
        return out.toList()
    }

    /** La regola Bedrock (null se la categoria o i biomi non hanno un equivalente). */
    fun doc(bedrockId: String, r: Rule, category: String?): Map<String, Any?>? {
        val biomes = bedrockBiomes(r.biomes)
        if (biomes.isEmpty()) return null
        val cat = (r.category ?: category ?: "CREATURE").uppercase()
        val c = LinkedHashMap<String, Any?>()
        val population: String
        when {
            cat == "MONSTER" -> {
                population = "monster"
                c["minecraft:spawns_on_surface"] = emptyMap<String, Any?>()
                c["minecraft:spawns_underground"] = emptyMap<String, Any?>()
                c["minecraft:brightness_filter"] = linkedMapOf("min" to 0L, "max" to 7L, "adjust_for_weather" to true)
                c["minecraft:difficulty_filter"] = linkedMapOf("min" to "easy", "max" to "hard")
            }
            cat.contains("WATER") || cat == "AXOLOTLS" -> {
                population = "water_animal"
                c["minecraft:spawns_underwater"] = emptyMap<String, Any?>()
            }
            cat == "AMBIENT" -> {
                population = "ambient"
                c["minecraft:spawns_underground"] = emptyMap<String, Any?>()
                c["minecraft:brightness_filter"] = linkedMapOf("min" to 0L, "max" to 4L, "adjust_for_weather" to true)
            }
            cat == "CREATURE" -> {
                population = "animal"
                c["minecraft:spawns_on_surface"] = emptyMap<String, Any?>()
                c["minecraft:brightness_filter"] = linkedMapOf("min" to 7L, "max" to 15L, "adjust_for_weather" to false)
            }
            else -> return null
        }
        c["minecraft:weight"] = linkedMapOf("default" to r.weight.coerceIn(1, 100).toLong())
        val min = r.min.coerceIn(1, 8); val max = r.max.coerceIn(min, 8)
        c["minecraft:herd"] = linkedMapOf("min_size" to min.toLong(), "max_size" to max.toLong())
        c["minecraft:biome_filter"] = if (biomes.size == 1) linkedMapOf("test" to "has_biome_tag", "operator" to "==", "value" to biomes[0])
        else linkedMapOf("any_of" to biomes.map { linkedMapOf("test" to "has_biome_tag", "operator" to "==", "value" to it) })
        return linkedMapOf(
            "format_version" to "1.8.0",
            "minecraft:spawn_rules" to linkedMapOf(
                "description" to linkedMapOf("identifier" to bedrockId, "population_control" to population),
                "conditions" to listOf(c)
            )
        )
    }
}
