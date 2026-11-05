package com.mmi.modinstaller.convert

/** Un oggetto di Bedrock come lo scrivono le ricette: identificatore e, per pochi oggetti (letti, stendardi), il dato. */
data class BedrockItem(val id: String, val data: Int? = null)

/**
 * Quello che si sa degli oggetti di Minecraft (non delle mod): i nomi Java che in Bedrock sono diversi, l'elenco degli oggetti
 * di Bedrock, i gruppi («tag») delle ricette che esistono in entrambe le edizioni e i gruppi comuni delle mod (`c:`, `forge:`).
 */
object Vanilla {

    private fun lines(name: String): List<String> {
        val input = Vanilla::class.java.getResourceAsStream(name) ?: return emptyList()
        return input.bufferedReader(Charsets.UTF_8).use { r -> r.readLines() }.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /** Oggetti di Bedrock (senza «minecraft:»). */
    val bedrockItems: Set<String> by lazy { HashSet(lines("bedrock_items.txt")) }

    private val javaToBedrock: Map<String, BedrockItem> by lazy {
        val m = HashMap<String, BedrockItem>()
        for (l in lines("java_to_bedrock_items.txt")) {
            val p = l.split(' ')
            if (p.size >= 2) m[p[0]] = BedrockItem("minecraft:" + p[1], p.getOrNull(2)?.toIntOrNull())
        }
        m
    }

    /** L'oggetto Bedrock per un oggetto di Minecraft Java (`minecraft:…`), o null se Bedrock non lo ha. */
    fun item(java: Rl): BedrockItem? {
        if (java.ns != "minecraft") return null
        javaToBedrock[java.path]?.let { return it }
        return if (java.path in bedrockItems) BedrockItem("minecraft:" + java.path) else null
    }

    /** Gruppi di oggetti di Java che hanno lo stesso nome nelle ricette di Bedrock. */
    private val SAME_TAGS = setOf(
        "planks", "logs", "logs_that_burn", "wool", "wooden_slabs", "coals", "stone_tool_materials", "stone_crafting_materials",
        "soul_fire_base_blocks"
    )

    fun bedrockTag(java: Rl): String? = if (java.ns == "minecraft" && java.path in SAME_TAGS) "minecraft:" + java.path else null

    /** Gruppi di Java (vanilla) senza equivalente in Bedrock: si usa un oggetto rappresentativo. */
    private val VANILLA_TAG_ITEM = mapOf(
        "sticks" to "stick", "wooden_buttons" to "wooden_button", "buttons" to "stone_button", "wooden_fences" to "oak_fence",
        "fences" to "oak_fence", "wooden_doors" to "wooden_door", "doors" to "wooden_door", "sand" to "sand", "smelts_to_glass" to "sand",
        "anvil" to "anvil", "beds" to "bed", "candles" to "candle", "carpets" to "white_carpet", "terracotta" to "hardened_clay",
        "flowers" to "dandelion", "small_flowers" to "dandelion", "saplings" to "oak_sapling", "leaves" to "oak_leaves",
        "boats" to "oak_boat", "chest_boats" to "oak_chest_boat", "stone_bricks" to "stone_bricks", "fishes" to "cod",
        "arrows" to "arrow", "coal_ores" to "coal_ore", "iron_ores" to "iron_ore", "gold_ores" to "gold_ore",
        "diamond_ores" to "diamond_ore", "copper_ores" to "copper_ore", "slabs" to "smooth_stone_slab", "stairs" to "oak_stairs",
        "walls" to "cobblestone_wall", "trapdoors" to "trapdoor", "wooden_trapdoors" to "trapdoor", "wooden_stairs" to "oak_stairs",
        "wooden_pressure_plates" to "wooden_pressure_plate", "banners" to "banner", "music_discs" to "music_disc_13",
        "oak_logs" to "oak_log", "birch_logs" to "birch_log", "spruce_logs" to "spruce_log", "jungle_logs" to "jungle_log",
        "acacia_logs" to "acacia_log", "dark_oak_logs" to "dark_oak_log", "mangrove_logs" to "mangrove_log", "cherry_logs" to "cherry_log",
        "crimson_stems" to "crimson_stem", "warped_stems" to "warped_stem", "bamboo_blocks" to "bamboo_block", "gold_tool_materials" to "gold_ingot",
        "iron_tool_materials" to "iron_ingot", "diamond_tool_materials" to "diamond", "netherite_tool_materials" to "netherite_ingot",
        "wooden_tool_materials" to "oak_planks", "repairs_iron_armor" to "iron_ingot", "repairs_gold_armor" to "gold_ingot",
        "repairs_diamond_armor" to "diamond", "repairs_netherite_armor" to "netherite_ingot", "repairs_leather_armor" to "leather",
        "eggs" to "egg", "egg" to "egg", "bundles" to "bundle", "shulker_boxes" to "undyed_shulker_box", "glass_panes" to "glass_pane"
    )

    private val GEMS = mapOf(
        "diamond" to "diamond", "emerald" to "emerald", "lapis" to "lapis_lazuli", "quartz" to "quartz", "amethyst" to "amethyst_shard",
        "prismarine" to "prismarine_crystals"
    )

    private val SIMPLE_COMMON = mapOf(
        "stones" to "stone", "stone" to "stone", "cobblestones" to "cobblestone", "cobblestone" to "cobblestone",
        "cobblestones/normal" to "cobblestone", "cobblestones/deepslate" to "cobbled_deepslate", "obsidians" to "obsidian",
        "obsidian" to "obsidian", "glass_blocks" to "glass", "glass_blocks/colorless" to "glass", "glass" to "glass",
        "glass/colorless" to "glass", "glass_panes" to "glass_pane", "glass_panes/colorless" to "glass_pane", "sands" to "sand",
        "sand" to "sand", "sands/colorless" to "sand", "sands/red" to "red_sand", "gravels" to "gravel", "gravel" to "gravel",
        "strings" to "string", "string" to "string", "leathers" to "leather", "leather" to "leather", "feathers" to "feather",
        "feather" to "feather", "slime_balls" to "slime_ball", "slimeballs" to "slime_ball", "gunpowders" to "gunpowder",
        "gunpowder" to "gunpowder", "bones" to "bone", "bone" to "bone", "eggs" to "egg", "egg" to "egg", "ender_pearls" to "ender_pearl",
        "ender_pearl" to "ender_pearl", "chests" to "chest", "chests/wooden" to "chest", "chest" to "chest", "chest/wooden" to "chest",
        "fences/wooden" to "oak_fence", "fence_gates/wooden" to "fence_gate", "rods/wooden" to "stick", "rods/wood" to "stick",
        "rods" to "stick", "rods/blaze" to "blaze_rod", "rods/breeze" to "breeze_rod", "crops/wheat" to "wheat", "crops/carrot" to "carrot",
        "crops/potato" to "potato", "crops/beetroot" to "beetroot", "crops" to "wheat", "seeds/wheat" to "wheat_seeds", "seeds" to "wheat_seeds",
        "seeds/pumpkin" to "pumpkin_seeds", "seeds/melon" to "melon_seeds", "seeds/beetroot" to "beetroot_seeds",
        "foods/bread" to "bread", "bread" to "bread", "bread/wheat" to "bread", "foods/raw_beef" to "beef", "foods/cooked_beef" to "cooked_beef",
        "foods/raw_chicken" to "chicken", "foods/cooked_chicken" to "cooked_chicken", "foods/raw_pork" to "porkchop",
        "foods/cooked_pork" to "cooked_porkchop", "foods/raw_mutton" to "mutton", "foods/cooked_mutton" to "cooked_mutton",
        "foods/raw_fish" to "cod", "foods/cooked_fish" to "cooked_cod", "foods/raw_fishes" to "cod", "foods/cooked_fishes" to "cooked_cod",
        "foods/raw_cod" to "cod", "foods/raw_salmon" to "salmon", "foods/cooked_cod" to "cooked_cod", "foods/cooked_salmon" to "cooked_salmon",
        "foods/milk" to "milk_bucket", "drinks/milk" to "milk_bucket", "milk" to "milk_bucket", "buckets/milk" to "milk_bucket",
        "buckets/water" to "water_bucket", "buckets/lava" to "lava_bucket", "buckets/empty" to "bucket", "buckets" to "bucket",
        "foods/golden" to "golden_apple", "foods/fruit" to "apple", "foods/fruits" to "apple", "foods/apple" to "apple",
        "mushrooms" to "brown_mushroom", "foods/egg" to "egg", "foods/raw_eggs" to "egg", "foods/cooked_egg" to "egg",
        "bricks/normal" to "brick", "bricks/nether" to "netherbrick", "bricks" to "brick", "tools/shear" to "shears", "tools/shears" to "shears",
        "shears" to "shears", "tools/fishing_rod" to "fishing_rod", "tools/bow" to "bow", "tools/crossbow" to "crossbow",
        "tools/shield" to "shield", "dusts/redstone" to "redstone", "dusts/glowstone" to "glowstone_dust", "dusts/prismarine" to "prismarine_shard",
        "gems" to "diamond", "ingots" to "iron_ingot", "nuggets" to "iron_nugget", "sugar" to "sugar", "sugarcane" to "sugar_cane",
        "sugar_cane" to "sugar_cane", "honeycombs" to "honeycomb", "honey" to "honey_bottle", "flowers" to "dandelion",
        "dyes" to "white_dye", "wools" to "white_wool", "workbenches" to "crafting_table", "workbench" to "crafting_table",
        "player_workstations/crafting_tables" to "crafting_table", "player_workstations/furnaces" to "furnace", "furnaces" to "furnace",
        "barrels" to "barrel", "barrels/wooden" to "barrel", "bookshelves" to "bookshelf", "clay" to "clay_ball", "clay_balls" to "clay_ball",
        "netherracks" to "netherrack", "netherrack" to "netherrack", "end_stones" to "end_stone", "end_stone" to "end_stone",
        "heads" to "skeleton_skull", "nether_stars" to "nether_star", "nether_star" to "nether_star", "ores" to "iron_ore",
        "storage_blocks" to "iron_block", "raw_materials" to "raw_iron", "torches" to "torch", "ropes" to "lead", "pumpkins" to "pumpkin",
        "pumpkins/normal" to "pumpkin", "pumpkins/carved" to "carved_pumpkin", "melons" to "melon_block", "crops/pumpkin" to "pumpkin",
        "crops/melon" to "melon_slice", "crops/cocoa_bean" to "cocoa_beans", "crops/cocoa_beans" to "cocoa_beans",
        "crops/nether_wart" to "nether_wart", "crops/sugar_cane" to "sugar_cane", "crops/cactus" to "cactus", "foods/berry" to "sweet_berries",
        "foods/berries" to "sweet_berries", "foods/vegetable" to "carrot", "foods/vegetables" to "carrot", "foods/raw_meat" to "beef",
        "foods/raw_meats" to "beef", "foods/cooked_meat" to "cooked_beef", "foods/cooked_meats" to "cooked_beef", "foods/soup" to "mushroom_stew",
        "foods/soups" to "mushroom_stew", "foods/cookie" to "cookie", "foods/cookies" to "cookie", "foods/pie" to "pumpkin_pie",
        "foods/carrot" to "carrot", "foods/potato" to "potato", "foods/baked_potato" to "baked_potato", "foods/beetroot" to "beetroot",
        "concretes" to "white_concrete", "concrete_powders" to "white_concrete_powder", "glazed_terracottas" to "white_glazed_terracotta",
        "terracottas" to "hardened_clay", "stripped_logs" to "stripped_oak_log", "stripped_woods" to "stripped_oak_wood",
        "logs" to "oak_log", "planks" to "oak_planks", "wooden_slabs" to "oak_slab", "slabs" to "oak_slab", "stairs" to "oak_stairs",
        "gravels/normal" to "gravel"
    )

    /**
     * L'oggetto Bedrock che rappresenta un gruppo comune delle mod (`c:ingots/iron`, `forge:rods/wooden`…) o di Minecraft Java,
     * quando non c'è un gruppo equivalente in Bedrock. Null se il gruppo non si sa tradurre.
     */
    fun tagItem(tag: Rl): BedrockItem? {
        val p = tag.path
        if (tag.ns == "minecraft") return VANILLA_TAG_ITEM[p]?.let { check(it) }
        if (tag.ns != "c" && tag.ns != "forge" && tag.ns != "neoforge" && tag.ns != "fabric" && tag.ns != "conventional") return null
        SIMPLE_COMMON[p]?.let { return check(it) }
        val parts = p.split('/')
        if (parts.size == 2) {
            val (kind, what) = parts
            val name = when (kind) {
                "ingots" -> if (what == "brick") "brick" else if (what == "nether_brick") "netherbrick" else what + "_ingot"
                "nuggets" -> what + "_nugget"
                "gems" -> GEMS[what] ?: what
                "dusts" -> what + "_dust"
                "raw_materials", "raw_ores" -> "raw_$what"
                "storage_blocks" -> if (what.startsWith("raw_")) what + "_block" else if (what == "wheat") "hay_block" else what + "_block"
                "ores" -> what + "_ore"
                "dyes" -> what + "_dye"
                "dyed" -> null
                "wools" -> what + "_wool"
                "crops" -> what
                "seeds" -> what + "_seeds"
                "foods" -> what
                "glass_blocks", "glass" -> what + "_stained_glass"
                "glass_panes" -> what + "_stained_glass_pane"
                else -> null
            } ?: return null
            return check(name)
        }
        return null
    }

    private fun check(name: String): BedrockItem? = javaToBedrock[name] ?: if (name in bedrockItems) BedrockItem("minecraft:$name") else null

    /** Blocchi che un minerale può sostituire, per i gruppi di Java usati nella generazione dei minerali. */
    fun replaceables(tag: Rl): List<String>? = when (tag.toString()) {
        "minecraft:stone_ore_replaceables" -> listOf("stone", "granite", "diorite", "andesite")
        "minecraft:deepslate_ore_replaceables" -> listOf("deepslate", "tuff")
        "minecraft:base_stone_overworld" -> listOf("stone", "granite", "diorite", "andesite", "deepslate", "tuff")
        "minecraft:base_stone_nether" -> listOf("netherrack", "basalt", "blackstone")
        "minecraft:nether_carver_replaceables" -> listOf("netherrack", "basalt", "blackstone", "soul_sand", "soul_soil")
        "minecraft:overworld_carver_replaceables" -> listOf("stone", "granite", "diorite", "andesite", "deepslate", "tuff", "dirt", "gravel")
        "minecraft:dirt" -> listOf("dirt", "grass_block", "podzol", "coarse_dirt", "mycelium", "rooted_dirt")
        "minecraft:sand" -> listOf("sand", "red_sand")
        "c:stones", "forge:stone", "c:stone" -> listOf("stone")
        "c:netherracks", "forge:netherrack" -> listOf("netherrack")
        "c:end_stones", "forge:end_stones" -> listOf("end_stone")
        else -> null
    }
}
