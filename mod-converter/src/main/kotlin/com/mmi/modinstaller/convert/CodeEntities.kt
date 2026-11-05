package com.mmi.modinstaller.convert

import com.mmi.modinstaller.bytecode.ArrV
import com.mmi.modinstaller.bytecode.ClassV
import com.mmi.modinstaller.bytecode.Obj
import com.mmi.modinstaller.bytecode.StaticV
import com.mmi.modinstaller.bytecode.V
import com.mmi.modinstaller.bytecode.num
import com.mmi.modinstaller.convert.code.AnimFacts
import com.mmi.modinstaller.convert.code.EntityFacts
import com.mmi.modinstaller.convert.code.GoalFacts
import com.mmi.modinstaller.convert.code.ModelFacts
import com.mmi.modinstaller.convert.code.PartFacts

/**
 * Creature ricostruite dal codice Java: geometria dai modelli scritti nel codice (LayerDefinition), animazioni dalle
 * KeyframeAnimations della mod, comportamento Bedrock dagli attributi e dagli obiettivi (IA) di Java.
 */
internal object CodeEntities {

    // ------------------------------------------------------------------------------------------------------------ geometria

    /**
     * Converte il modello Java in geometria Bedrock. Stessa corrispondenza di Blockbench tra i due formati: il perno di ogni
     * parte è [px + x, py − y, pz + z] (il genitore della radice è a [0, 24, 0]), i cubi stanno in [px + x, py − y − h, pz + z]
     * e la rotazione Bedrock è quella Java in gradi.
     */
    fun geometry(identifier: String, model: ModelFacts): Map<String, Any?> {
        val bones = ArrayList<Any?>()
        val used = HashSet<String>()
        fun bone(p: PartFacts, parent: String?, px: Double, py: Double, pz: Double) {
            var name = p.name.ifEmpty { "part" }
            var n = 2
            while (!used.add(name)) name = p.name + "_" + n++
            val x = px + p.x; val y = py - p.y; val z = pz + p.z
            val b = linkedMapOf<String, Any?>("name" to name)
            if (parent != null) b["parent"] = parent
            b["pivot"] = listOf(r(x), r(y), r(z))
            if (p.rx != 0.0 || p.ry != 0.0 || p.rz != 0.0) b["rotation"] = listOf(r(Math.toDegrees(p.rx)), r(Math.toDegrees(p.ry)), r(Math.toDegrees(p.rz)))
            val cubes = p.cubes.map { c ->
                val m = linkedMapOf<String, Any?>(
                    "origin" to listOf(r(x + c.x), r(y - c.y - c.h), r(z + c.z)),
                    "size" to listOf(r(c.w), r(c.h), r(c.d)),
                    "uv" to listOf(c.u.toLong(), c.v.toLong())
                )
                if (c.inflate != 0.0) m["inflate"] = r(c.inflate)
                if (c.mirror) m["mirror"] = true
                m
            }
            if (cubes.isNotEmpty()) b["cubes"] = cubes
            bones.add(b)
            for (ch in p.children) bone(ch, name, x, y, z)
        }
        // La radice della mesh non ha posa: i suoi figli sono le ossa principali.
        for (ch in model.root.children) bone(ch, null, 0.0, 24.0, 0.0)
        if (model.root.cubes.isNotEmpty()) bone(PartFacts("mmi_root", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, model.root.cubes, emptyList()), null, 0.0, 24.0, 0.0)
        val (w, h) = extent(bones)
        return linkedMapOf(
            "format_version" to "1.12.0",
            "minecraft:geometry" to listOf(
                linkedMapOf(
                    "description" to linkedMapOf(
                        "identifier" to identifier, "texture_width" to model.texWidth.toLong(), "texture_height" to model.texHeight.toLong(),
                        "visible_bounds_width" to r(maxOf(2.0, w * 1.5)), "visible_bounds_height" to r(maxOf(2.0, h * 1.5)),
                        "visible_bounds_offset" to listOf(0L, r(h / 2), 0L)
                    ),
                    "bones" to bones
                )
            )
        )
    }

    /** Larghezza e altezza (in blocchi) occupate dai cubi. */
    fun extent(bones: List<Any?>): Pair<Double, Double> {
        var minX = 0.0; var maxX = 0.0; var minZ = 0.0; var maxZ = 0.0; var maxY = 0.0; var any = false
        for (b in bones) {
            @Suppress("UNCHECKED_CAST") val cubes = (b as Map<String, Any?>)["cubes"] as? List<Map<String, Any?>> ?: continue
            for (c in cubes) {
                @Suppress("UNCHECKED_CAST") val o = (c["origin"] as List<Any?>).map { (it as Number).toDouble() }
                @Suppress("UNCHECKED_CAST") val s = (c["size"] as List<Any?>).map { (it as Number).toDouble() }
                if (!any) { minX = o[0]; maxX = o[0] + s[0]; minZ = o[2]; maxZ = o[2] + s[2]; maxY = o[1] + s[1]; any = true }
                minX = minOf(minX, o[0]); maxX = maxOf(maxX, o[0] + s[0]); minZ = minOf(minZ, o[2]); maxZ = maxOf(maxZ, o[2] + s[2]); maxY = maxOf(maxY, o[1] + s[1])
            }
        }
        if (!any) return 1.0 to 1.0
        return maxOf(maxX - minX, maxZ - minZ) / 16.0 to maxY / 16.0
    }

    fun boneNames(model: ModelFacts): List<String> {
        val out = ArrayList<String>()
        fun walk(p: PartFacts) { out.add(p.name); p.children.forEach { walk(it) } }
        model.root.children.forEach { walk(it) }
        return out
    }

    // ------------------------------------------------------------------------------------------------------------ animazioni

    class Animations(val doc: Map<String, Any?>, val byRole: Map<String, String>, val generic: Boolean)

    /**
     * Le KeyframeAnimations della mod (valori come in Bedrock: Blockbench scrive gli stessi numeri nei due formati). Se la mod
     * non ne ha, animazioni generiche costruite dai nomi delle ossa (gambe che camminano, testa che guarda).
     */
    fun animations(prefix: String, model: ModelFacts): Animations {
        val anims = LinkedHashMap<String, Any?>()
        val byRole = LinkedHashMap<String, String>()
        val bones = boneNames(model).toSet()
        for (a in model.animations) {
            val id = "animation.$prefix.${a.role}" + if (byRole.containsKey(a.role)) "_" + byRole.size else ""
            val doc = keyframes(a, bones) ?: continue
            anims[id] = doc
            if (!byRole.containsKey(a.role)) byRole[a.role] = id
        }
        var generic = false
        if (!byRole.containsKey("walk")) {
            walkCycle(bones)?.let { anims["animation.$prefix.walk"] = it; byRole["walk"] = "animation.$prefix.walk"; generic = true }
        }
        head(bones)?.let { h ->
            anims["animation.$prefix.look"] = linkedMapOf(
                "loop" to true,
                "bones" to linkedMapOf(h to linkedMapOf("rotation" to listOf("query.target_x_rotation", "query.target_y_rotation", 0L)))
            )
            byRole["look"] = "animation.$prefix.look"
        }
        return Animations(linkedMapOf("format_version" to "1.8.0", "animations" to anims), byRole, generic)
    }

    private fun keyframes(a: AnimFacts, bones: Set<String>): Map<String, Any?>? {
        val out = LinkedHashMap<String, Any?>()
        for (ch in a.channels) {
            if (ch.bone !in bones) continue
            val channel = when (ch.target) { "rotation" -> "rotation"; "position" -> "position"; "scale" -> "scale"; else -> continue }
            val frames = LinkedHashMap<String, Any?>()
            for (k in ch.keys) {
                val v = listOf(r(k.x), r(k.y), r(k.z))
                frames[time(k.time)] = if (k.interpolation.contains("catmull")) linkedMapOf("post" to v, "lerp_mode" to "catmullrom") else v
            }
            @Suppress("UNCHECKED_CAST")
            val bone = out.getOrPut(ch.bone) { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>
            bone[channel] = frames
        }
        if (out.isEmpty()) return null
        val m = linkedMapOf<String, Any?>()
        if (a.looping) m["loop"] = true
        m["animation_length"] = r(a.length)
        m["bones"] = out
        return m
    }

    private fun time(t: Double): String {
        val s = String.format(java.util.Locale.ROOT, "%.4f", t).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-0") "0.0" else if (s.contains('.')) s else "$s.0"
    }

    private fun head(bones: Set<String>): String? =
        bones.firstOrNull { it.equals("head", true) } ?: bones.firstOrNull { it.lowercase().endsWith("head") && !it.lowercase().contains("pivot") }
            ?: bones.firstOrNull { it.lowercase().contains("head") }

    /** Gambe riconosciute dal nome (sinistra/destra, davanti/dietro) che oscillano come nei modelli di Minecraft. */
    private fun walkCycle(bones: Set<String>): Map<String, Any?>? {
        val legs = bones.filter { b -> val l = b.lowercase(); ("leg" in l || "foot" in l || "paw" in l) && !("lower" in l || "toe" in l || "claw" in l) }
        if (legs.isEmpty()) return null
        val out = LinkedHashMap<String, Any?>()
        for (b in legs) {
            val l = b.lowercase()
            val n = Regex("""leg(\d)$""").find(l)?.groupValues?.get(1)?.toIntOrNull()
            val left = "left" in l || l.endsWith("_l") || l.startsWith("l_")
            val back = "back" in l || "hind" in l || "rear" in l
            val phase = if (n != null) (if (n == 0 || n == 3) 0 else 180) else (if (left) 180 else 0) + (if (back) 180 else 0)
            out[b] = linkedMapOf(
                "rotation" to listOf("math.cos(query.modified_distance_moved * 38.17 + $phase) * 80.0 * query.modified_move_speed", 0L, 0L)
            )
        }
        val arms = bones.filter { b -> val l = b.lowercase(); "arm" in l && "armor" !in l && !("lower" in l) }
        for (b in arms) {
            val l = b.lowercase()
            val left = "left" in l || l.endsWith("_l")
            out[b] = linkedMapOf("rotation" to listOf("math.cos(query.modified_distance_moved * 38.17 + ${if (left) 0 else 180}) * 50.0 * query.modified_move_speed", 0L, 0L))
        }
        return linkedMapOf("loop" to true, "bones" to out)
    }

    fun controller(id: String, anims: Animations): Map<String, Any?>? {
        val walk = anims.byRole["walk"] ?: anims.byRole["fly"] ?: anims.byRole["swim"]
        val idle = anims.byRole["idle"]
        if (walk == null && idle == null) return null
        val idleList = listOfNotNull(idle?.let { "idle" })
        val walkList = listOfNotNull(walk?.let { "walk" })
        return linkedMapOf(
            "format_version" to "1.10.0",
            "animation_controllers" to linkedMapOf(
                id to linkedMapOf(
                    "initial_state" to "idle",
                    "states" to linkedMapOf(
                        "idle" to linkedMapOf("animations" to idleList, "transitions" to listOf(linkedMapOf("walk" to "q.modified_move_speed > 0.1")), "blend_transition" to 0.2),
                        "walk" to linkedMapOf("animations" to walkList, "transitions" to listOf(linkedMapOf("idle" to "q.modified_move_speed <= 0.1")), "blend_transition" to 0.2)
                    )
                )
            )
        )
    }

    // ------------------------------------------------------------------------------------------------------------ comportamento

    /** Che tipo di movimento ha la creatura, dalla classe di Minecraft da cui deriva e dagli obiettivi. */
    enum class Motion { WALK, FLY, SWIM }

    fun motion(f: EntityFacts): Motion {
        val base = f.classes.map { it.substringAfterLast('/') }
        val goals = f.goals.map { it.goal }
        return when {
            base.any { it in setOf("WaterAnimal", "AbstractFish", "AbstractSchoolingFish", "Squid", "Dolphin", "AbstractGroupFish", "Guardian") } ||
                goals.any { it == "RandomSwimmingGoal" } && f.category?.startsWith("WATER") == true -> Motion.SWIM
            base.any { it in setOf("FlyingMob", "Bat", "Ghast", "Phantom", "Blaze", "Vex", "Allay", "Bee", "Parrot", "AmbientCreature") } ||
                goals.any { it in setOf("WaterAvoidingRandomFlyingGoal", "RandomFlyingGoal") } -> Motion.FLY
            else -> Motion.WALK
        }
    }

    private fun speed(g: GoalFacts, index: Int = 0): Double? = g.args.mapNotNull { it.num() }.getOrNull(index)

    /** Le classi bersaglio (Player.class…) come famiglie Bedrock. */
    private fun families(g: GoalFacts): List<String> {
        val classes = g.args.mapNotNull { (it as? ClassV)?.name }
        return classes.map { c ->
            when (val s = c.substringAfterLast('/').substringAfterLast('$')) {
                "Player", "ServerPlayer" -> "player"
                "AbstractVillager", "Villager" -> "villager"
                "IronGolem" -> "irongolem"
                "AbstractSkeleton", "Skeleton" -> "skeleton"
                "Zombie" -> "zombie"
                "Animal" -> "animal"
                "Monster" -> "monster"
                else -> s.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
            }
        }.distinct()
    }

    private fun items(v: List<V>): List<String> {
        val out = ArrayList<String>()
        fun add(x: V?) {
            when (x) {
                is StaticV -> if (x.owner.endsWith("/Items")) Vanilla.item(Rl("minecraft", x.name.lowercase()))?.let { out.add(it.id) }
                is Obj -> {
                    if (x.origin?.name == "of") x.origin!!.args.forEach { add(it) }
                    else if (x.type.endsWith("/Ingredient")) x.args.forEach { add(it) }
                }
                is ArrV -> x.items.forEach { add(it) }
                else -> {}
            }
        }
        v.forEach { add(it) }
        return out.distinct()
    }

    /** Un obiettivo Java → un comportamento Bedrock (null se non ha un equivalente). */
    fun behavior(g: GoalFacts): Pair<String, Map<String, Any?>>? {
        val p = g.priority.toLong()
        fun m(vararg kv: Pair<String, Any?>) = linkedMapOf<String, Any?>("priority" to p).also { it.putAll(kv) }
        val sp = speed(g)
        return when (g.goal) {
            "FloatGoal" -> "minecraft:behavior.float" to m()
            "PanicGoal" -> "minecraft:behavior.panic" to m("speed_multiplier" to (sp ?: 1.25))
            "BreedGoal" -> "minecraft:behavior.breed" to m("speed_multiplier" to (sp ?: 1.0))
            "TemptGoal" -> {
                val list = items(g.args)
                if (list.isEmpty()) null else "minecraft:behavior.tempt" to m("speed_multiplier" to (sp ?: 1.2), "items" to list)
            }
            "FollowParentGoal" -> "minecraft:behavior.follow_parent" to m("speed_multiplier" to (sp ?: 1.1))
            "RandomStrollGoal", "WaterAvoidingRandomStrollGoal" -> "minecraft:behavior.random_stroll" to m("speed_multiplier" to (sp ?: 1.0))
            "LookAtPlayerGoal" -> "minecraft:behavior.look_at_player" to m("look_distance" to (g.args.mapNotNull { (it as? com.mmi.modinstaller.bytecode.FloatV)?.v?.toDouble() }.firstOrNull() ?: 6.0))
            "RandomLookAroundGoal" -> "minecraft:behavior.random_look_around" to m()
            "MeleeAttackGoal", "ZombieAttackGoal", "OcelotAttackGoal" -> "minecraft:behavior.melee_attack" to m("speed_multiplier" to (sp ?: 1.0), "track_target" to true)
            "LeapAtTargetGoal" -> "minecraft:behavior.leap_at_target" to m("yd" to (g.args.mapNotNull { it.num() }.firstOrNull() ?: 0.4), "must_be_on_ground" to true)
            "NearestAttackableTargetGoal", "NonTameRandomTargetGoal" -> {
                val fam = families(g).ifEmpty { listOf("player") }
                "minecraft:behavior.nearest_attackable_target" to m(
                    "must_see" to true,
                    "entity_types" to fam.map { f -> linkedMapOf("filters" to linkedMapOf("test" to "is_family", "subject" to "other", "value" to f), "max_dist" to 16L) }
                )
            }
            "HurtByTargetGoal" -> "minecraft:behavior.hurt_by_target" to m()
            "AvoidEntityGoal" -> {
                val fam = families(g).ifEmpty { listOf("player") }
                val n = g.args.mapNotNull { it.num() }
                "minecraft:behavior.avoid_mob_type" to m(
                    "entity_types" to fam.map { f ->
                        linkedMapOf(
                            "filters" to linkedMapOf("test" to "is_family", "subject" to "other", "value" to f),
                            "max_dist" to (n.getOrNull(0) ?: 6.0), "walk_speed_multiplier" to (n.getOrNull(1) ?: 1.0), "sprint_speed_multiplier" to (n.getOrNull(2) ?: 1.2)
                        )
                    }
                )
            }
            "RandomSwimmingGoal" -> "minecraft:behavior.random_swim" to m("speed_multiplier" to (sp ?: 1.0))
            "WaterAvoidingRandomFlyingGoal", "RandomFlyingGoal" -> "minecraft:behavior.random_fly" to m("xz_dist" to 10L, "y_dist" to 7L, "speed_multiplier" to (sp ?: 1.0))
            "SitWhenOrderedToGoal" -> "minecraft:behavior.stay_while_sitting" to m()
            "FollowOwnerGoal" -> "minecraft:behavior.follow_owner" to m("speed_multiplier" to (sp ?: 1.0), "start_distance" to 10L, "stop_distance" to 2L)
            "OwnerHurtByTargetGoal" -> "minecraft:behavior.owner_hurt_by_target" to m()
            "OwnerHurtTargetGoal" -> "minecraft:behavior.owner_hurt_target" to m()
            "EatBlockGoal" -> "minecraft:behavior.eat_block" to m(
                "eat_and_replace_block_pairs" to listOf(linkedMapOf("eat_block" to "minecraft:short_grass", "replace_block" to "minecraft:air"), linkedMapOf("eat_block" to "minecraft:grass_block", "replace_block" to "minecraft:dirt"))
            )
            "RestrictSunGoal" -> "minecraft:behavior.restrict_sun" to m()
            "FleeSunGoal" -> "minecraft:behavior.flee_sun" to m("speed_multiplier" to (sp ?: 1.0))
            "OpenDoorGoal" -> "minecraft:behavior.open_door" to m("close_door_after" to true)
            "BreakDoorGoal" -> "minecraft:behavior.break_door" to m()
            "MoveThroughVillageGoal" -> "minecraft:behavior.move_through_village" to m("speed_multiplier" to (sp ?: 0.6), "only_at_night" to false)
            "TryFindWaterGoal" -> "minecraft:behavior.move_to_water" to m("search_range" to 16L, "search_height" to 5L)
            "MoveTowardsRestrictionGoal" -> "minecraft:behavior.move_towards_home_restriction" to m("speed_multiplier" to (sp ?: 1.0))
            "MoveTowardsTargetGoal" -> "minecraft:behavior.move_towards_target" to m("within_radius" to 32L, "speed_multiplier" to (sp ?: 0.9))
            "RangedAttackGoal", "RangedBowAttackGoal", "RangedCrossbowAttackGoal" -> null
            else -> null
        }
    }

    /** I componenti Bedrock della creatura. [loot] è il percorso della tabella dei bottini, se la mod ne ha una. */
    fun behaviorDoc(bedrockId: String, f: EntityFacts, width: Double, height: Double, loot: String?): Pair<Map<String, Any?>, List<String>> {
        val notes = ArrayList<String>()
        val c = LinkedHashMap<String, Any?>()
        val a = f.attributes
        val motion = motion(f)
        val base = f.classes.map { it.substringAfterLast('/') }
        val hostile = f.category == "MONSTER" || base.any { it in setOf("Monster", "AbstractSkeleton", "Zombie", "Spider", "Raider") }
        val fam = ArrayList<String>()
        fam.add(bedrockId.substringBefore(':'))
        fam.add(if (hostile) "monster" else if (f.category == "CREATURE") "animal" else "mob")
        fam.add("mob")
        c["minecraft:type_family"] = linkedMapOf("family" to fam.distinct())
        val hp = a["max_health"] ?: 20.0
        c["minecraft:health"] = linkedMapOf("value" to r(hp), "max" to r(hp))
        c["minecraft:collision_box"] = linkedMapOf("width" to r(f.width ?: width), "height" to r(f.height ?: height))
        c["minecraft:physics"] = linkedMapOf<String, Any?>()
        c["minecraft:pushable"] = linkedMapOf("is_pushable" to true, "is_pushable_by_piston" to true)
        c["minecraft:nameable"] = linkedMapOf<String, Any?>()
        c["minecraft:jump.static"] = linkedMapOf<String, Any?>()
        a["follow_range"]?.let { c["minecraft:follow_range"] = linkedMapOf("value" to r(it), "max" to r(it)) }
        a["knockback_resistance"]?.takeIf { it > 0 }?.let { c["minecraft:knockback_resistance"] = linkedMapOf("value" to r(it)) }
        if (f.fireImmune) c["minecraft:fire_immune"] = linkedMapOf<String, Any?>()
        val speed = a["movement_speed"] ?: 0.25
        when (motion) {
            Motion.SWIM -> {
                c["minecraft:breathable"] = linkedMapOf("breathes_water" to true, "breathes_air" to false, "generates_bubbles" to false)
                c["minecraft:movement"] = linkedMapOf("value" to r(speed))
                c["minecraft:underwater_movement"] = linkedMapOf("value" to r(speed))
                c["minecraft:navigation.generic"] = linkedMapOf("is_amphibious" to false, "can_path_over_water" to false, "can_swim" to true, "can_walk" to false, "can_breach" to false, "can_sink" to false)
                c["minecraft:movement.sway"] = linkedMapOf("sway_amplitude" to 0.0)
            }
            Motion.FLY -> {
                c["minecraft:breathable"] = linkedMapOf("total_supply" to 15L, "suffocate_time" to 0L)
                c["minecraft:movement"] = linkedMapOf("value" to r(speed))
                c["minecraft:flying_speed"] = linkedMapOf("value" to r(a["flying_speed"] ?: 0.15))
                c["minecraft:can_fly"] = linkedMapOf<String, Any?>()
                c["minecraft:navigation.fly"] = linkedMapOf("can_path_over_water" to true, "can_path_from_air" to true)
                c["minecraft:movement.fly"] = linkedMapOf<String, Any?>()
            }
            Motion.WALK -> {
                c["minecraft:breathable"] = linkedMapOf("total_supply" to 15L, "suffocate_time" to 0L)
                c["minecraft:movement"] = linkedMapOf("value" to r(speed))
                c["minecraft:navigation.walk"] = linkedMapOf("can_walk" to true, "avoid_water" to true, "avoid_damage_blocks" to true, "can_open_doors" to f.goals.any { it.goal == "OpenDoorGoal" })
                c["minecraft:movement.basic"] = linkedMapOf<String, Any?>()
                if (base.any { it == "Spider" }) c["minecraft:can_climb"] = linkedMapOf<String, Any?>()
            }
        }
        val melee = f.goals.any { it.goal in setOf("MeleeAttackGoal", "ZombieAttackGoal", "OcelotAttackGoal", "LeapAtTargetGoal") }
        if (melee || a.containsKey("attack_damage")) c["minecraft:attack"] = linkedMapOf("damage" to r(a["attack_damage"] ?: 2.0))
        var mapped = 0
        val skipped = ArrayList<String>()
        for (g in f.goals) {
            val b = behavior(g)
            if (b == null) { skipped.add(g.custom ?: g.goal); continue }
            if (c.containsKey(b.first)) continue
            c[b.first] = b.second
            mapped++
        }
        if (f.goals.isEmpty()) {
            c["minecraft:behavior.float"] = linkedMapOf("priority" to 0L)
            c["minecraft:behavior.random_stroll"] = linkedMapOf("priority" to 6L, "speed_multiplier" to 1.0)
            c["minecraft:behavior.look_at_player"] = linkedMapOf("priority" to 7L, "look_distance" to 6.0)
            c["minecraft:behavior.random_look_around"] = linkedMapOf("priority" to 8L)
            notes.add("IA non letta dal codice: comportamento generico")
        } else if (skipped.isNotEmpty()) {
            notes.add("Obiettivi IA senza equivalente Bedrock: " + skipped.distinct().joinToString(", "))
        }
        if (c.containsKey("minecraft:behavior.breed") && !c.containsKey("minecraft:breedable")) {
            val tempt = (c["minecraft:behavior.tempt"] as? Map<*, *>)?.get("items") as? List<*>
            if (tempt != null) {
                c["minecraft:breedable"] = linkedMapOf("require_tame" to false, "breeds_with" to listOf(linkedMapOf("mate_type" to bedrockId, "baby_type" to bedrockId)), "breed_items" to tempt)
            } else {
                c.remove("minecraft:behavior.breed")
                notes.add("Riproduzione non ricostruita (il cibo che la fa riprodurre è nel codice)")
            }
        }
        if (hostile) c["minecraft:despawn"] = linkedMapOf("despawn_from_distance" to linkedMapOf<String, Any?>())
        loot?.let { c["minecraft:loot"] = linkedMapOf("table" to it) }
        if ((a["armor"] ?: 0.0) > 0) notes.add("Armatura naturale (${r(a["armor"]!!)}) non riproducibile in Bedrock")
        val doc = linkedMapOf(
            "format_version" to ModConverter.FORMAT,
            "minecraft:entity" to linkedMapOf(
                "description" to linkedMapOf("identifier" to bedrockId, "is_spawnable" to true, "is_summonable" to true),
                "components" to c
            )
        )
        return doc to notes
    }

    private fun r(v: Double): Any {
        val x = Math.round(v * 10000.0) / 10000.0
        return if (x == Math.floor(x) && Math.abs(x) < 1e9) x.toLong() else x
    }
}
