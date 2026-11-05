package com.mmi.modinstaller.convert.code

/**
 * Valori di Minecraft Java che le mod citano per nome (Tiers.IRON, ArmorMaterials.DIAMOND, Blocks.STONE, SoundType.METAL…):
 * l'app non ha il gioco, quindi li porta con sé. Sono i valori del gioco base (1.20–1.21).
 */
internal object JavaVanilla {

    class Tier(val uses: Int, val speed: Double, val attackBonus: Double, val level: Int, val enchantability: Int)

    val TIERS = mapOf(
        "WOOD" to Tier(59, 2.0, 0.0, 0, 15), "STONE" to Tier(131, 4.0, 1.0, 1, 5), "IRON" to Tier(250, 6.0, 2.0, 2, 14),
        "DIAMOND" to Tier(1561, 8.0, 3.0, 3, 10), "GOLD" to Tier(32, 12.0, 0.0, 0, 22), "NETHERITE" to Tier(2031, 9.0, 4.0, 4, 15)
    )

    class ArmorMat(val durabilityFactor: Int, val defense: List<Int> /* elmo, corazza, gambali, stivali */, val enchantability: Int, val toughness: Double, val knockback: Double)

    val ARMOR = mapOf(
        "LEATHER" to ArmorMat(5, listOf(1, 3, 2, 1), 15, 0.0, 0.0), "CHAIN" to ArmorMat(15, listOf(2, 5, 4, 1), 12, 0.0, 0.0),
        "CHAINMAIL" to ArmorMat(15, listOf(2, 5, 4, 1), 12, 0.0, 0.0), "IRON" to ArmorMat(15, listOf(2, 6, 5, 2), 9, 0.0, 0.0),
        "GOLD" to ArmorMat(7, listOf(2, 5, 3, 1), 25, 0.0, 0.0), "DIAMOND" to ArmorMat(33, listOf(3, 8, 6, 3), 10, 2.0, 0.0),
        "TURTLE" to ArmorMat(25, listOf(2, 6, 5, 2), 9, 0.0, 0.0), "NETHERITE" to ArmorMat(37, listOf(3, 8, 6, 3), 15, 3.0, 0.1),
        "ARMADILLO" to ArmorMat(4, listOf(3, 8, 6, 3), 10, 0.0, 0.0)
    )

    /** Durata base dei pezzi d'armatura (moltiplicata dal fattore del materiale). */
    val ARMOR_BASE = mapOf("HELMET" to 11, "CHESTPLATE" to 16, "LEGGINGS" to 15, "BOOTS" to 13, "BODY" to 16)
    val ARMOR_ORDER = listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "BODY")

    /** Enum di Minecraft di cui serve la posizione (ordinal) quando le mod indicizzano gli array con essa. */
    val ORDINALS: Map<String, List<String>> = mapOf(
        "net/minecraft/world/item/ArmorItem\$Type" to ARMOR_ORDER,
        "net/minecraft/world/item/equipment/ArmorType" to ARMOR_ORDER,
        "net/minecraft/world/entity/EquipmentSlot" to listOf("MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD", "BODY"),
        "net/minecraft/world/item/DyeColor" to listOf(
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"
        ),
        "net/minecraft/core/Direction" to listOf("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST")
    )

    /** Effetti: nome del campo in MobEffects → identificatore. */
    val EFFECTS = mapOf(
        "MOVEMENT_SPEED" to "speed", "SPEED" to "speed", "MOVEMENT_SLOWDOWN" to "slowness", "SLOWNESS" to "slowness",
        "DIG_SPEED" to "haste", "HASTE" to "haste", "DIG_SLOWDOWN" to "mining_fatigue", "MINING_FATIGUE" to "mining_fatigue",
        "DAMAGE_BOOST" to "strength", "STRENGTH" to "strength", "HEAL" to "instant_health", "INSTANT_HEALTH" to "instant_health",
        "HARM" to "instant_damage", "INSTANT_DAMAGE" to "instant_damage", "JUMP" to "jump_boost", "JUMP_BOOST" to "jump_boost",
        "CONFUSION" to "nausea", "NAUSEA" to "nausea", "REGENERATION" to "regeneration", "DAMAGE_RESISTANCE" to "resistance",
        "RESISTANCE" to "resistance", "FIRE_RESISTANCE" to "fire_resistance", "WATER_BREATHING" to "water_breathing",
        "INVISIBILITY" to "invisibility", "BLINDNESS" to "blindness", "NIGHT_VISION" to "night_vision", "HUNGER" to "hunger",
        "WEAKNESS" to "weakness", "POISON" to "poison", "WITHER" to "wither", "HEALTH_BOOST" to "health_boost",
        "ABSORPTION" to "absorption", "SATURATION" to "saturation", "GLOWING" to "glowing", "LEVITATION" to "levitation",
        "LUCK" to "luck", "UNLUCK" to "unluck", "SLOW_FALLING" to "slow_falling", "CONDUIT_POWER" to "conduit_power",
        "DOLPHINS_GRACE" to "dolphins_grace", "BAD_OMEN" to "bad_omen", "HERO_OF_THE_VILLAGE" to "hero_of_the_village",
        "DARKNESS" to "darkness", "TRIAL_OMEN" to "trial_omen", "RAID_OMEN" to "raid_omen", "WIND_CHARGED" to "wind_charged",
        "WEAVING" to "weaving", "OOZING" to "oozing", "INFESTED" to "infested"
    )

    /** Attributi delle creature: nome del campo → nome breve usato nel rapporto e nella conversione. */
    val ATTRIBUTES = mapOf(
        "MAX_HEALTH" to "max_health", "GENERIC_MAX_HEALTH" to "max_health", "MOVEMENT_SPEED" to "movement_speed",
        "GENERIC_MOVEMENT_SPEED" to "movement_speed", "ATTACK_DAMAGE" to "attack_damage", "GENERIC_ATTACK_DAMAGE" to "attack_damage",
        "FOLLOW_RANGE" to "follow_range", "GENERIC_FOLLOW_RANGE" to "follow_range", "ARMOR" to "armor", "GENERIC_ARMOR" to "armor",
        "ARMOR_TOUGHNESS" to "armor_toughness", "KNOCKBACK_RESISTANCE" to "knockback_resistance", "GENERIC_KNOCKBACK_RESISTANCE" to "knockback_resistance",
        "FLYING_SPEED" to "flying_speed", "GENERIC_FLYING_SPEED" to "flying_speed", "ATTACK_KNOCKBACK" to "attack_knockback",
        "ATTACK_SPEED" to "attack_speed", "JUMP_STRENGTH" to "jump_strength", "HORSE_JUMP_STRENGTH" to "jump_strength",
        "STEP_HEIGHT" to "step_height", "SCALE" to "scale", "GRAVITY" to "gravity", "SPAWN_REINFORCEMENTS_CHANCE" to "spawn_reinforcements",
        "SAFE_FALL_DISTANCE" to "safe_fall_distance", "FALL_DAMAGE_MULTIPLIER" to "fall_damage_multiplier", "WATER_MOVEMENT_EFFICIENCY" to "water_movement_efficiency",
        "BURNING_TIME" to "burning_time", "EXPLOSION_KNOCKBACK_RESISTANCE" to "explosion_knockback_resistance", "MOVEMENT_EFFICIENCY" to "movement_efficiency",
        "OXYGEN_BONUS" to "oxygen_bonus", "TEMPT_RANGE" to "tempt_range"
    )

    /** Valore predefinito di un attributo aggiunto senza valore («add(Attributes.ATTACK_DAMAGE)»). */
    val ATTRIBUTE_DEFAULTS = mapOf(
        "max_health" to 20.0, "movement_speed" to 0.7, "attack_damage" to 2.0, "follow_range" to 32.0, "armor" to 0.0, "armor_toughness" to 0.0,
        "knockback_resistance" to 0.0, "flying_speed" to 0.4, "attack_knockback" to 0.0, "attack_speed" to 4.0, "jump_strength" to 0.42,
        "step_height" to 0.6, "scale" to 1.0, "gravity" to 0.08, "tempt_range" to 10.0
    )

    /** Attributi di partenza dei metodi di Minecraft («Mob.createMobAttributes()» e simili). */
    val ATTRIBUTE_BASES: Map<String, Map<String, Double>> = run {
        val living = mapOf("max_health" to 20.0, "knockback_resistance" to 0.0, "movement_speed" to 0.7, "armor" to 0.0)
        val mob = living + mapOf("follow_range" to 16.0)
        mapOf(
            "createLivingAttributes" to living, "createMobAttributes" to mob,
            "createMonsterAttributes" to mob + mapOf("attack_damage" to 2.0),
            "createAnimalAttributes" to mob
        )
    }

    /** Proprietà dei blocchi di Minecraft più copiati dalle mod (durezza, resistenza, suono Bedrock, luce). */
    class BlockDef(val hardness: Double, val resistance: Double, val sound: String, val light: Int = 0, val requiresTool: Boolean = false)

    val BLOCKS: Map<String, BlockDef> = run {
        val m = HashMap<String, BlockDef>()
        fun d(names: String, h: Double, r: Double, s: String, light: Int = 0, tool: Boolean = false) {
            for (n in names.split(' ')) m[n] = BlockDef(h, r, s, light, tool)
        }
        d("STONE ANDESITE DIORITE GRANITE POLISHED_ANDESITE POLISHED_DIORITE POLISHED_GRANITE STONE_BRICKS MOSSY_STONE_BRICKS CRACKED_STONE_BRICKS CHISELED_STONE_BRICKS SMOOTH_STONE", 1.5, 6.0, "stone", tool = true)
        d("COBBLESTONE MOSSY_COBBLESTONE BRICKS", 2.0, 6.0, "stone", tool = true)
        d("DEEPSLATE COBBLED_DEEPSLATE POLISHED_DEEPSLATE DEEPSLATE_BRICKS DEEPSLATE_TILES", 3.5, 6.0, "deepslate", tool = true)
        d("TUFF POLISHED_TUFF TUFF_BRICKS", 1.5, 6.0, "tuff", tool = true)
        d("CALCITE", 0.75, 0.75, "calcite", tool = true)
        d("OBSIDIAN CRYING_OBSIDIAN", 50.0, 1200.0, "stone", tool = true)
        d("NETHERRACK", 0.4, 0.4, "netherrack", tool = true)
        d("NETHER_BRICKS RED_NETHER_BRICKS", 2.0, 6.0, "nether_brick", tool = true)
        d("BLACKSTONE POLISHED_BLACKSTONE POLISHED_BLACKSTONE_BRICKS", 1.5, 6.0, "stone", tool = true)
        d("BASALT POLISHED_BASALT SMOOTH_BASALT", 1.25, 4.2, "basalt", tool = true)
        d("END_STONE END_STONE_BRICKS", 3.0, 9.0, "stone", tool = true)
        d("SANDSTONE RED_SANDSTONE CUT_SANDSTONE SMOOTH_SANDSTONE QUARTZ_BLOCK", 0.8, 0.8, "stone", tool = true)
        d("PRISMARINE PRISMARINE_BRICKS DARK_PRISMARINE PURPUR_BLOCK", 1.5, 6.0, "stone", tool = true)
        d("TERRACOTTA WHITE_TERRACOTTA", 1.25, 4.2, "stone", tool = true)
        d("IRON_BLOCK GOLD_BLOCK", 5.0, 6.0, "metal", tool = true)
        d("DIAMOND_BLOCK EMERALD_BLOCK", 5.0, 6.0, "metal", tool = true)
        d("NETHERITE_BLOCK ANCIENT_DEBRIS", 50.0, 1200.0, "netherite", tool = true)
        d("COPPER_BLOCK CUT_COPPER", 3.0, 6.0, "copper", tool = true)
        d("LAPIS_BLOCK", 3.0, 3.0, "stone", tool = true)
        d("REDSTONE_BLOCK", 5.0, 6.0, "metal", tool = true)
        d("COAL_BLOCK", 5.0, 6.0, "stone", tool = true)
        d("AMETHYST_BLOCK", 1.5, 1.5, "amethyst_block", tool = true)
        d("IRON_ORE GOLD_ORE COPPER_ORE COAL_ORE LAPIS_ORE DIAMOND_ORE EMERALD_ORE REDSTONE_ORE NETHER_QUARTZ_ORE", 3.0, 3.0, "stone", tool = true)
        d("DEEPSLATE_IRON_ORE DEEPSLATE_GOLD_ORE DEEPSLATE_COPPER_ORE DEEPSLATE_COAL_ORE DEEPSLATE_LAPIS_ORE DEEPSLATE_DIAMOND_ORE DEEPSLATE_EMERALD_ORE DEEPSLATE_REDSTONE_ORE", 4.5, 3.0, "deepslate", tool = true)
        d("NETHER_GOLD_ORE", 3.0, 3.0, "nether_gold_ore", tool = true)
        d("OAK_PLANKS SPRUCE_PLANKS BIRCH_PLANKS JUNGLE_PLANKS ACACIA_PLANKS DARK_OAK_PLANKS MANGROVE_PLANKS CHERRY_PLANKS PALE_OAK_PLANKS", 2.0, 3.0, "wood")
        d("CRIMSON_PLANKS WARPED_PLANKS", 2.0, 3.0, "nether_wood")
        d("BAMBOO_PLANKS BAMBOO_MOSAIC", 2.0, 3.0, "bamboo_wood")
        d("OAK_LOG SPRUCE_LOG BIRCH_LOG JUNGLE_LOG ACACIA_LOG DARK_OAK_LOG MANGROVE_LOG CHERRY_LOG OAK_WOOD STRIPPED_OAK_LOG STRIPPED_OAK_WOOD", 2.0, 2.0, "wood")
        d("CRIMSON_STEM WARPED_STEM", 2.0, 2.0, "stem")
        d("OAK_LEAVES SPRUCE_LEAVES BIRCH_LEAVES JUNGLE_LEAVES ACACIA_LEAVES DARK_OAK_LEAVES MANGROVE_LEAVES AZALEA_LEAVES", 0.2, 0.2, "grass")
        d("CHERRY_LEAVES", 0.2, 0.2, "cherry_leaves")
        d("GLASS WHITE_STAINED_GLASS BLUE_STAINED_GLASS TINTED_GLASS GLASS_PANE", 0.3, 0.3, "glass")
        d("GLOWSTONE SEA_LANTERN", 0.3, 0.3, "glass", light = 15)
        d("WHITE_WOOL ORANGE_WOOL BLACK_WOOL RED_WOOL", 0.8, 0.8, "cloth")
        d("WHITE_CARPET", 0.1, 0.1, "cloth")
        d("DIRT COARSE_DIRT ROOTED_DIRT FARMLAND", 0.5, 0.5, "gravel")
        d("GRASS_BLOCK MYCELIUM PODZOL", 0.6, 0.6, "grass")
        d("SAND RED_SAND", 0.5, 0.5, "sand")
        d("GRAVEL", 0.6, 0.6, "gravel")
        d("CLAY", 0.6, 0.6, "gravel")
        d("MUD", 0.5, 0.5, "mud")
        d("MUD_BRICKS PACKED_MUD", 1.5, 3.0, "mud_bricks", tool = true)
        d("SNOW_BLOCK", 0.2, 0.2, "snow", tool = true)
        d("ICE PACKED_ICE", 0.5, 0.5, "glass")
        d("BLUE_ICE", 2.8, 2.8, "glass")
        d("SOUL_SAND", 0.5, 0.5, "soul_sand")
        d("SOUL_SOIL", 0.5, 0.5, "soul_soil")
        d("BONE_BLOCK", 2.0, 2.0, "bone_block", tool = true)
        d("HAY_BLOCK", 0.5, 0.5, "grass")
        d("MOSS_BLOCK", 0.1, 0.1, "moss_block")
        d("BOOKSHELF", 1.5, 1.5, "wood")
        d("CRAFTING_TABLE", 2.5, 2.5, "wood")
        d("CHEST TRAPPED_CHEST BARREL", 2.5, 2.5, "wood")
        d("FURNACE BLAST_FURNACE SMOKER DISPENSER DROPPER", 3.5, 3.5, "stone", tool = true)
        d("LECTERN", 2.5, 2.5, "wood")
        d("ANVIL", 5.0, 1200.0, "anvil", tool = true)
        d("CONDUIT", 3.0, 3.0, "glass", light = 15)
        d("BEACON", 3.0, 3.0, "glass", light = 15)
        d("TNT", 0.0, 0.0, "grass")
        d("TORCH WALL_TORCH", 0.0, 0.0, "wood", light = 14)
        d("SOUL_TORCH", 0.0, 0.0, "wood", light = 10)
        d("LANTERN", 3.5, 3.5, "lantern", light = 15, tool = true)
        d("SOUL_LANTERN", 3.5, 3.5, "lantern", light = 10, tool = true)
        d("TURTLE_EGG SNIFFER_EGG", 0.5, 0.5, "metal")
        d("OAK_DOOR SPRUCE_DOOR BIRCH_DOOR OAK_TRAPDOOR SPRUCE_TRAPDOOR OAK_FENCE OAK_FENCE_GATE", 3.0, 3.0, "wood")
        d("IRON_DOOR IRON_TRAPDOOR", 5.0, 5.0, "metal", tool = true)
        d("IRON_BARS CHAIN", 5.0, 6.0, "metal", tool = true)
        d("LADDER", 0.4, 0.4, "ladder")
        d("SCAFFOLDING", 0.0, 0.0, "scaffolding")
        d("CACTUS", 0.4, 0.4, "cloth")
        d("SUGAR_CANE WHEAT CARROTS POTATOES BEETROOTS MELON_STEM PUMPKIN_STEM SHORT_GRASS FERN DEAD_BUSH POPPY DANDELION SWEET_BERRY_BUSH OAK_SAPLING", 0.0, 0.0, "grass")
        d("MELON PUMPKIN CARVED_PUMPKIN", 1.0, 1.0, "wood")
        d("JACK_O_LANTERN", 1.0, 1.0, "wood", light = 15)
        d("CAKE", 0.5, 0.5, "cloth")
        d("SPONGE", 0.6, 0.6, "sponge")
        d("SLIME_BLOCK", 0.0, 0.0, "slime")
        d("HONEY_BLOCK", 0.0, 0.0, "honey_block")
        d("COBWEB", 4.0, 4.0, "web")
        d("SHROOMLIGHT", 1.0, 1.0, "shroomlight", light = 15)
        d("OCHRE_FROGLIGHT VERDANT_FROGLIGHT PEARLESCENT_FROGLIGHT", 0.3, 0.3, "froglight", light = 15)
        d("BRAIN_CORAL_BLOCK", 1.5, 6.0, "coral", tool = true)
        d("SCULK", 0.2, 0.2, "sculk")
        d("DRIPSTONE_BLOCK", 1.5, 1.0, "dripstone_block", tool = true)
        d("REDSTONE_LAMP", 0.3, 0.3, "glass")
        d("SPAWNER", 5.0, 5.0, "mob_spawner", tool = true)
        d("BEDROCK", -1.0, 3600000.0, "stone")
        d("AIR", 0.0, 0.0, "stone")
        m
    }

    /** SoundType di Java → suono dei blocchi di Bedrock. */
    val SOUNDS = mapOf(
        "WOOD" to "wood", "GRAVEL" to "gravel", "GRASS" to "grass", "LILY_PAD" to "grass", "STONE" to "stone", "METAL" to "metal",
        "GLASS" to "glass", "WOOL" to "cloth", "SAND" to "sand", "SNOW" to "snow", "POWDER_SNOW" to "powder_snow", "LADDER" to "ladder",
        "ANVIL" to "anvil", "SLIME_BLOCK" to "slime", "HONEY_BLOCK" to "honey_block", "WET_GRASS" to "grass", "CORAL_BLOCK" to "coral",
        "BAMBOO" to "bamboo", "BAMBOO_SAPLING" to "bamboo_sapling", "SCAFFOLDING" to "scaffolding", "SWEET_BERRY_BUSH" to "sweet_berry_bush",
        "CROP" to "grass", "HARD_CROP" to "grass", "VINE" to "vines", "NETHER_WART" to "nether_wart", "LANTERN" to "lantern",
        "STEM" to "stem", "NYLIUM" to "nylium", "FUNGUS" to "fungus", "ROOTS" to "roots", "SHROOMLIGHT" to "shroomlight",
        "WEEPING_VINES" to "weeping_vines", "TWISTING_VINES" to "weeping_vines", "SOUL_SAND" to "soul_sand", "SOUL_SOIL" to "soul_soil",
        "BASALT" to "basalt", "WART_BLOCK" to "nether_wart", "NETHERRACK" to "netherrack", "NETHER_BRICKS" to "nether_brick",
        "NETHER_SPROUTS" to "nether_sprouts", "NETHER_ORE" to "nether_gold_ore", "BONE_BLOCK" to "bone_block", "NETHERITE_BLOCK" to "netherite",
        "ANCIENT_DEBRIS" to "ancient_debris", "LODESTONE" to "lodestone", "CHAIN" to "chain", "NETHER_GOLD_ORE" to "nether_gold_ore",
        "GILDED_BLACKSTONE" to "stone", "CANDLE" to "candle", "AMETHYST" to "amethyst_block", "AMETHYST_CLUSTER" to "amethyst_cluster",
        "SMALL_AMETHYST_BUD" to "small_amethyst_bud", "MEDIUM_AMETHYST_BUD" to "medium_amethyst_bud", "LARGE_AMETHYST_BUD" to "large_amethyst_bud",
        "TUFF" to "tuff", "TUFF_BRICKS" to "tuff_bricks", "POLISHED_TUFF" to "polished_tuff", "CALCITE" to "calcite",
        "DRIPSTONE_BLOCK" to "dripstone_block", "POINTED_DRIPSTONE" to "pointed_dripstone", "COPPER" to "copper", "COPPER_BULB" to "copper_bulb",
        "COPPER_GRATE" to "copper_grate", "CAVE_VINES" to "cave_vines", "SPORE_BLOSSOM" to "spore_blossom", "AZALEA" to "azalea",
        "FLOWERING_AZALEA" to "azalea", "MOSS_CARPET" to "moss_carpet", "PINK_PETALS" to "pink_petals", "MOSS" to "moss_block",
        "BIG_DRIPLEAF" to "big_dripleaf", "SMALL_DRIPLEAF" to "big_dripleaf", "ROOTED_DIRT" to "dirt_with_roots", "HANGING_ROOTS" to "hanging_roots",
        "AZALEA_LEAVES" to "azalea_leaves", "SCULK_SENSOR" to "sculk_sensor", "SCULK_CATALYST" to "sculk_catalyst", "SCULK" to "sculk",
        "SCULK_VEIN" to "sculk_vein", "SCULK_SHRIEKER" to "sculk_shrieker", "GLOW_LICHEN" to "glow_lichen", "DEEPSLATE" to "deepslate",
        "DEEPSLATE_BRICKS" to "deepslate_bricks", "DEEPSLATE_TILES" to "deepslate_bricks", "POLISHED_DEEPSLATE" to "deepslate",
        "FROGLIGHT" to "froglight", "FROGSPAWN" to "frog_spawn", "MANGROVE_ROOTS" to "mangrove_roots", "MUDDY_MANGROVE_ROOTS" to "muddy_mangrove_roots",
        "MUD" to "mud", "MUD_BRICKS" to "mud_bricks", "PACKED_MUD" to "packed_mud", "HANGING_SIGN" to "hanging_sign",
        "NETHER_WOOD_HANGING_SIGN" to "nether_wood_hanging_sign", "BAMBOO_WOOD_HANGING_SIGN" to "bamboo_wood_hanging_sign",
        "BAMBOO_WOOD" to "bamboo_wood", "NETHER_WOOD" to "nether_wood", "CHERRY_WOOD" to "cherry_wood", "CHERRY_SAPLING" to "grass",
        "CHERRY_LEAVES" to "cherry_leaves", "CHERRY_WOOD_HANGING_SIGN" to "cherry_wood_hanging_sign", "CHISELED_BOOKSHELF" to "chiseled_bookshelf",
        "SUSPICIOUS_SAND" to "suspicious_sand", "SUSPICIOUS_GRAVEL" to "suspicious_gravel", "DECORATED_POT" to "decorated_pot",
        "DECORATED_POT_CRACKED" to "decorated_pot", "TRIAL_SPAWNER" to "trial_spawner", "SPONGE" to "sponge", "WET_SPONGE" to "wet_sponge",
        "VAULT" to "vault", "HEAVY_CORE" to "heavy_core", "COBWEB" to "web", "SPAWNER" to "mob_spawner", "CREAKING_HEART" to "creaking_heart",
        "RESIN" to "resin", "RESIN_BRICKS" to "resin_brick", "PALE_HANGING_MOSS" to "pale_hanging_moss", "SPAWNER" to "mob_spawner"
    )

    /** Classi di blocco di Minecraft → ruolo per la ricostruzione in Bedrock. */
    val BLOCK_ROLES = mapOf(
        "CropBlock" to "crop", "BeetrootBlock" to "crop", "CarrotBlock" to "crop", "PotatoBlock" to "crop", "StemBlock" to "crop",
        "SweetBerryBushBlock" to "berry_bush", "NetherWartBlock" to "crop", "DoorBlock" to "door", "TrapDoorBlock" to "trapdoor",
        "FenceGateBlock" to "fence_gate", "FenceBlock" to "fence", "WallBlock" to "wall", "SlabBlock" to "slab", "StairBlock" to "stairs",
        "ButtonBlock" to "button", "PressurePlateBlock" to "pressure_plate", "WeightedPressurePlateBlock" to "pressure_plate",
        "LeverBlock" to "lever", "SaplingBlock" to "sapling", "LeavesBlock" to "leaves", "RotatedPillarBlock" to "pillar",
        "TorchBlock" to "torch", "WallTorchBlock" to "torch", "LanternBlock" to "lantern", "LadderBlock" to "ladder",
        "FlowerBlock" to "plant", "TallFlowerBlock" to "plant", "DoublePlantBlock" to "plant", "BushBlock" to "plant", "VegetationBlock" to "plant",
        "TallGrassBlock" to "plant", "MushroomBlock" to "plant", "FlowerPotBlock" to "flower_pot", "VineBlock" to "vine",
        "CarpetBlock" to "carpet", "WoolCarpetBlock" to "carpet", "DropExperienceBlock" to "ore", "RedStoneOreBlock" to "ore",
        "FallingBlock" to "falling", "ColoredFallingBlock" to "falling", "SandBlock" to "falling", "GravelBlock" to "falling",
        "GlassBlock" to "glass", "TransparentBlock" to "glass", "StainedGlassBlock" to "glass", "TintedGlassBlock" to "glass",
        "HalfTransparentBlock" to "glass", "IronBarsBlock" to "pane", "StainedGlassPaneBlock" to "pane", "ChainBlock" to "chain",
        "ChestBlock" to "chest", "BarrelBlock" to "container", "AbstractFurnaceBlock" to "furnace", "FurnaceBlock" to "furnace",
        "SignBlock" to "sign", "StandingSignBlock" to "sign", "WallSignBlock" to "sign", "CeilingHangingSignBlock" to "sign",
        "BedBlock" to "bed", "CakeBlock" to "cake", "CandleBlock" to "candle", "SnowLayerBlock" to "snow_layer",
        "LiquidBlock" to "liquid", "BaseRailBlock" to "rail", "RailBlock" to "rail", "PoweredRailBlock" to "rail",
        "BaseEntityBlock" to "entity_block", "HorizontalDirectionalBlock" to "directional", "DirectionalBlock" to "directional",
        "CampfireBlock" to "campfire", "AnvilBlock" to "anvil", "SpawnerBlock" to "spawner", "TntBlock" to "tnt",
        "PipeBlock" to "pipe", "MultifaceBlock" to "multiface", "AmethystClusterBlock" to "cluster", "PointedDripstoneBlock" to "dripstone",
        "GrowingPlantHeadBlock" to "vine", "GrowingPlantBodyBlock" to "vine", "ComposterBlock" to "composter", "SkullBlock" to "head",
        "WallSkullBlock" to "head", "BannerBlock" to "banner", "WallBannerBlock" to "banner", "ShulkerBoxBlock" to "container",
        "HopperBlock" to "container", "EnderChestBlock" to "chest", "BrewingStandBlock" to "furnace", "SmokerBlock" to "furnace",
        "BlastFurnaceBlock" to "furnace", "Block" to "plain"
    )

    /** Classi di oggetto di Minecraft → ruolo. */
    val ITEM_ROLES = mapOf(
        "SwordItem" to "sword", "PickaxeItem" to "pickaxe", "AxeItem" to "axe", "ShovelItem" to "shovel", "HoeItem" to "hoe",
        "DiggerItem" to "digger", "TieredItem" to "tiered", "ArmorItem" to "armor", "DyeableArmorItem" to "armor", "AnimalArmorItem" to "horse_armor",
        "ElytraItem" to "elytra", "ShieldItem" to "shield", "BowItem" to "bow", "CrossbowItem" to "crossbow", "TridentItem" to "trident",
        "SpawnEggItem" to "spawn_egg", "ForgeSpawnEggItem" to "spawn_egg", "DeferredSpawnEggItem" to "spawn_egg", "BlockItem" to "block_item",
        "DoubleHighBlockItem" to "block_item", "StandingAndWallBlockItem" to "block_item", "SignItem" to "block_item", "HangingSignItem" to "block_item",
        "BedItem" to "block_item", "ItemNameBlockItem" to "block_item", "ScaffoldingBlockItem" to "block_item", "PlaceOnWaterBlockItem" to "block_item",
        "BucketItem" to "bucket", "MobBucketItem" to "bucket", "SolidBucketItem" to "bucket", "PotionItem" to "potion", "SplashPotionItem" to "potion",
        "ThrowablePotionItem" to "potion", "BowlFoodItem" to "food", "HoneyBottleItem" to "food", "MilkBucketItem" to "food",
        "SuspiciousStewItem" to "food", "ChorusFruitItem" to "food", "ArrowItem" to "arrow", "TippedArrowItem" to "arrow", "SpectralArrowItem" to "arrow",
        "RecordItem" to "music_disc", "FishingRodItem" to "fishing_rod", "ShearsItem" to "shears", "FlintAndSteelItem" to "flint_and_steel",
        "SnowballItem" to "throwable", "EggItem" to "throwable", "EnderpearlItem" to "throwable", "ExperienceBottleItem" to "throwable",
        "FireworkRocketItem" to "firework", "BoatItem" to "boat", "MinecartItem" to "minecart", "SmithingTemplateItem" to "smithing_template",
        "MaceItem" to "mace", "BrushItem" to "brush", "SpyglassItem" to "spyglass", "BundleItem" to "bundle", "Item" to "item"
    )
}
