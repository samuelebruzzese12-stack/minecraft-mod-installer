package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/**
 * Una creatura con il modello in formato GeckoLib: il modello (`geo/<nome>.geo.json`) e le animazioni (`animations/<nome>.animation.json`)
 * sono già nel formato di Bedrock, quindi l'aspetto e le animazioni passano davvero. Il comportamento (attacchi, versi, cosa
 * mangia, dove nasce) è codice Java: la creatura convertita ha un comportamento generico.
 */
internal class EntityOut(
    val mod: JavaModContent,
    val java: Rl,
    val bedrockId: String,
    val javaNameKey: String,
    val geoEntry: String,
    val geoIdentifier: String,
    val texture: Rl,
    val animEntry: String?,
    val walk: String?,
    val idle: String?,
    val width: Double,
    val height: Double,
    val flying: Boolean,
    val swimming: Boolean
)

internal object EntityReader {

    fun find(mod: JavaModContent, limits: ConvertLimits): List<EntityOut> {
        val names = mod.jar.allNames().toList()
        val nameSet = names.toHashSet()
        val out = ArrayList<EntityOut>()
        val langIds = mod.lang.values.flatMap { it.keys }.mapNotNull { k ->
            val p = k.split('.')
            if (p.size == 3 && p[0] == "entity") Rl.parse(p[1] + ":" + p[2]) else null
        }.toSet()
        for (geo in names) {
            val m = GEO.matchEntire(geo) ?: continue
            val ns = m.groupValues[1]
            val base = m.groupValues[2].substringAfterLast('/')
            val id = Rl.parse("$ns:$base") ?: continue
            // Le varianti («_baby», …) senza un nome proprio sono parti di un'altra creatura.
            if (langIds.isNotEmpty() && id !in langIds) continue
            if (langIds.isEmpty() && (base.endsWith("_baby") || base.contains("armor") || base.contains("layer"))) continue
            val doc = mod.jar.json(geo, limits.maxJsonBytes)?.asJsonObject() ?: continue
            val geometry = doc["minecraft:geometry"].asJsonList()?.firstOrNull().asJsonObject() ?: continue
            val identifier = geometry["description"].asJsonObject()?.get("identifier") as? String ?: continue
            val texture = texture(ns, base, nameSet) ?: continue
            val anim = listOf("assets/$ns/animations/$base.animation.json", "assets/$ns/animations/entity/$base.animation.json",
                "assets/$ns/animations/entities/$base.animation.json").firstOrNull { it in nameSet }
            var walk: String? = null
            var idle: String? = null
            if (anim != null) {
                val keys = mod.jar.json(anim, limits.maxJsonBytes)?.asJsonObject()?.get("animations").asJsonObject()?.keys.orEmpty()
                walk = keys.firstOrNull { it.endsWith(".walk") } ?: keys.firstOrNull { it.contains("walk") }
                    ?: keys.firstOrNull { it.contains("move") || it.contains("run") || it.contains("fly") || it.contains("swim") }
                idle = keys.firstOrNull { it.endsWith(".idle") } ?: keys.firstOrNull { it.contains("idle") }
            }
            val (w, h) = size(geometry)
            val lower = base.lowercase()
            val flying = FLYING.any { lower.contains(it) }
            val swimming = SWIMMING.any { lower.contains(it) }
            out.add(
                EntityOut(
                    mod, id, ModConverter.bedrockId(id), "entity.$ns.$base", geo, identifier, texture, anim, walk, idle, w, h, flying, swimming
                )
            )
        }
        return out
    }

    private val GEO = Regex("""assets/([a-z0-9_.-]+)/geo/(?:entity/|entities/|mob/)?([a-z0-9_./-]+)\.geo\.json""")
    private val FLYING = listOf("bird", "bat", "butterfly", "dragonfly", "firefly", "bee", "fly", "owl", "crow", "vulture", "parrot", "moth", "eagle", "hawk")
    private val SWIMMING = listOf("fish", "bass", "shark", "whale", "dolphin", "squid", "eel", "jelly", "salmon", "cod", "trout", "crab", "seal")

    private fun texture(ns: String, base: String, names: Set<String>): Rl? {
        val direct = listOf("entity/$base", "entity/$base/$base", "entities/$base", "entity/${base}_texture")
        for (d in direct) if ("assets/$ns/textures/$d.png" in names) return Rl(ns, d)
        val folder = "assets/$ns/textures/entity/$base/"
        val candidates = names.filter { it.startsWith(folder) && it.endsWith(".png") && it.count { c -> c == '/' } == folder.count { c -> c == '/' } }
            .sortedWith(compareBy({ it.contains("baby") || it.contains("glow") || it.endsWith("_e.png") }, { it.length }))
        return candidates.firstOrNull()?.let { Rl(ns, it.removePrefix("assets/$ns/textures/").removeSuffix(".png")) }
    }

    /** Larghezza e altezza (in blocchi) dai cubi del modello. */
    private fun size(geometry: Map<String, Any?>): Pair<Double, Double> {
        var minX = 0.0; var maxX = 0.0; var minZ = 0.0; var maxZ = 0.0; var maxY = 0.0
        var any = false
        for (b in geometry["bones"].asJsonList().orEmpty()) {
            for (c in b.asJsonObject()?.get("cubes").asJsonList().orEmpty()) {
                val co = c.asJsonObject() ?: continue
                val o = co["origin"].asJsonList()?.mapNotNull { (it as? Number)?.toDouble() } ?: continue
                val s = co["size"].asJsonList()?.mapNotNull { (it as? Number)?.toDouble() } ?: continue
                if (o.size != 3 || s.size != 3) continue
                if (!any) { minX = o[0]; maxX = o[0] + s[0]; minZ = o[2]; maxZ = o[2] + s[2]; maxY = o[1] + s[1]; any = true }
                minX = minOf(minX, o[0]); maxX = maxOf(maxX, o[0] + s[0]); minZ = minOf(minZ, o[2]); maxZ = maxOf(maxZ, o[2] + s[2])
                maxY = maxOf(maxY, o[1] + s[1])
            }
        }
        if (!any) return 0.6 to 0.9
        val w = (maxOf(maxX - minX, maxZ - minZ) / 16.0).coerceIn(0.3, 4.0)
        val h = (maxY / 16.0).coerceIn(0.3, 5.0)
        return Math.round(w * 100) / 100.0 to Math.round(h * 100) / 100.0
    }

    /** Le animazioni GeckoLib possono scrivere i fotogrammi come {"vector": [...], "easing": "…"}: Bedrock vuole il vettore. */
    fun cleanAnimations(doc: Map<String, Any?>): Map<String, Any?> {
        fun clean(v: Any?): Any? = when (v) {
            is Map<*, *> -> {
                if (v.containsKey("vector") && (v.keys - setOf("vector", "easing", "easingArgs", "lerp_mode")).isEmpty()) clean(v["vector"])
                else LinkedHashMap<String, Any?>().also { m -> for ((k, x) in v) if (k != "easing" && k != "easingArgs") m[k.toString()] = clean(x) }
            }
            is List<*> -> v.map { clean(it) }
            else -> v
        }
        @Suppress("UNCHECKED_CAST")
        val out = clean(doc) as Map<String, Any?>
        val m = LinkedHashMap(out)
        m["format_version"] = "1.10.0"
        return m
    }

    fun behavior(e: EntityOut): Map<String, Any?> {
        val c = LinkedHashMap<String, Any?>()
        c["minecraft:type_family"] = linkedMapOf("family" to listOf(e.bedrockId.replace(':', '_'), "mob"))
        c["minecraft:health"] = linkedMapOf("value" to 20L, "max" to 20L)
        c["minecraft:collision_box"] = linkedMapOf("width" to e.width, "height" to e.height)
        c["minecraft:physics"] = linkedMapOf<String, Any?>()
        c["minecraft:pushable"] = linkedMapOf("is_pushable" to true, "is_pushable_by_piston" to true)
        c["minecraft:nameable"] = linkedMapOf<String, Any?>()
        c["minecraft:leashable"] = linkedMapOf("soft_distance" to 4.0, "hard_distance" to 6.0, "max_distance" to 10.0)
        c["minecraft:behavior.panic"] = linkedMapOf("priority" to 1L, "speed_multiplier" to 1.25)
        when {
            e.swimming -> {
                c["minecraft:breathable"] = linkedMapOf("breathes_water" to true, "breathes_air" to false, "generates_bubbles" to false)
                c["minecraft:movement"] = linkedMapOf("value" to 0.12)
                c["minecraft:underwater_movement"] = linkedMapOf("value" to 0.12)
                c["minecraft:navigation.generic"] = linkedMapOf("is_amphibious" to false, "can_path_over_water" to false, "can_swim" to true, "can_walk" to false, "can_breach" to false, "can_sink" to false)
                c["minecraft:movement.sway"] = linkedMapOf("sway_amplitude" to 0.0)
                c["minecraft:behavior.swim_wander"] = linkedMapOf("priority" to 4L, "speed_multiplier" to 1.0, "look_ahead" to 2.0, "wander_time" to 5.0)
            }
            e.flying -> {
                c["minecraft:breathable"] = linkedMapOf("total_supply" to 15L, "suffocate_time" to 0L)
                c["minecraft:movement"] = linkedMapOf("value" to 0.2)
                c["minecraft:flying_speed"] = linkedMapOf("value" to 0.15)
                c["minecraft:can_fly"] = linkedMapOf<String, Any?>()
                c["minecraft:navigation.fly"] = linkedMapOf("can_path_over_water" to true, "can_path_from_air" to true)
                c["minecraft:movement.fly"] = linkedMapOf<String, Any?>()
                c["minecraft:behavior.random_fly"] = linkedMapOf("priority" to 6L, "xz_dist" to 10L, "y_dist" to 6L)
                c["minecraft:behavior.float"] = linkedMapOf("priority" to 0L)
            }
            else -> {
                c["minecraft:breathable"] = linkedMapOf("total_supply" to 15L, "suffocate_time" to 0L)
                c["minecraft:movement"] = linkedMapOf("value" to 0.25)
                c["minecraft:navigation.walk"] = linkedMapOf("can_walk" to true, "avoid_water" to true, "avoid_damage_blocks" to true)
                c["minecraft:movement.basic"] = linkedMapOf<String, Any?>()
                c["minecraft:jump.static"] = linkedMapOf<String, Any?>()
                c["minecraft:can_climb"] = linkedMapOf<String, Any?>()
                c["minecraft:behavior.float"] = linkedMapOf("priority" to 0L)
                c["minecraft:behavior.random_stroll"] = linkedMapOf("priority" to 6L, "speed_multiplier" to 1.0)
            }
        }
        c["minecraft:behavior.look_at_player"] = linkedMapOf("priority" to 7L, "look_distance" to 6.0)
        c["minecraft:behavior.random_look_around"] = linkedMapOf("priority" to 8L)
        return linkedMapOf(
            "format_version" to ModConverter.FORMAT,
            "minecraft:entity" to linkedMapOf(
                "description" to linkedMapOf("identifier" to e.bedrockId, "is_spawnable" to true, "is_summonable" to true),
                "components" to c
            )
        )
    }

    fun controllerId(e: EntityOut) = "controller.animation.mmi." + e.bedrockId.replace(':', '.') + ".move"

    fun client(e: EntityOut, egg: Pair<String, String>): Map<String, Any?> {
        val animations = LinkedHashMap<String, Any?>()
        e.walk?.let { animations["walk"] = it }
        e.idle?.let { animations["idle"] = it }
        val description = linkedMapOf<String, Any?>(
            "identifier" to e.bedrockId,
            "materials" to linkedMapOf("default" to "entity_alphatest"),
            "textures" to linkedMapOf("default" to ModConverter.texturePath(e.texture)),
            "geometry" to linkedMapOf("default" to e.geoIdentifier)
        )
        if (animations.isNotEmpty()) {
            animations["move"] = controllerId(e)
            description["animations"] = animations
            description["scripts"] = linkedMapOf("animate" to listOf("move"))
        }
        description["render_controllers"] = listOf("controller.render.default")
        description["spawn_egg"] = linkedMapOf("base_color" to egg.first, "overlay_color" to egg.second)
        return linkedMapOf("format_version" to "1.10.0", "minecraft:client_entity" to linkedMapOf("description" to description))
    }

    fun controller(e: EntityOut): Map<String, Any?>? {
        if (e.walk == null && e.idle == null) return null
        val states = LinkedHashMap<String, Any?>()
        val idleAnims = listOfNotNull(e.idle?.let { "idle" })
        val walkAnims = listOfNotNull(e.walk?.let { "walk" })
        states["idle"] = linkedMapOf("animations" to idleAnims, "transitions" to listOf(linkedMapOf("walk" to "q.modified_move_speed > 0.1")), "blend_transition" to 0.2)
        states["walk"] = linkedMapOf("animations" to walkAnims, "transitions" to listOf(linkedMapOf("idle" to "q.modified_move_speed <= 0.1")), "blend_transition" to 0.2)
        return linkedMapOf(
            "format_version" to "1.10.0",
            "animation_controllers" to linkedMapOf(controllerId(e) to linkedMapOf("initial_state" to "idle", "states" to states))
        )
    }

    /** I colori dell'uovo: il colore medio della texture e una versione più scura. */
    fun eggColors(png: ByteArray?): Pair<String, String> {
        val img = png?.let { Png.decode(it) } ?: return "#7a6a52" to "#3d3529"
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        var i = 0
        while (i < img.rgba.size) {
            if ((img.rgba[i + 3].toInt() and 0xFF) > 128) {
                r += img.rgba[i].toInt() and 0xFF; g += img.rgba[i + 1].toInt() and 0xFF; b += img.rgba[i + 2].toInt() and 0xFF; n++
            }
            i += 4
        }
        if (n == 0L) return "#7a6a52" to "#3d3529"
        fun hex(v: Long) = String.format("%02x", v.coerceIn(0, 255))
        return "#" + hex(r / n) + hex(g / n) + hex(b / n) to "#" + hex(r / n / 2) + hex(g / n / 2) + hex(b / n / 2)
    }

    fun geometryBytes(mod: JavaModContent, e: EntityOut, limits: ConvertLimits): ByteArray? {
        val doc = mod.jar.json(e.geoEntry, limits.maxJsonBytes)?.asJsonObject() ?: return null
        return Json.stringify(doc, pretty = false).toByteArray(Charsets.UTF_8)
    }
}
