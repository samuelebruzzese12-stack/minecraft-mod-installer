package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/** Che tipo di oggetto è, per quanto si può capire senza eseguire la mod. */
enum class ItemKind(val label: String) {
    ITEM("oggetto"), FOOD("cibo"), DRINK("bevanda"),
    SWORD("spada"), PICKAXE("piccone"), AXE("ascia"), SHOVEL("pala"), HOE("zappa"),
    HELMET("elmo"), CHESTPLATE("corazza"), LEGGINGS("gambali"), BOOTS("stivali")
}

/** Da dove viene la classificazione: dai gruppi della mod (affidabile) o dal nome (stima). */
enum class KindSource { TAG, NAME, NONE }

class JavaItemDef(val id: Rl, val model: Rl?, val info: ItemModelInfo?, val kind: ItemKind, val kindSource: KindSource)

/** Come si orienta il blocco quando lo piazzi, se la variante scelta permette di riprodurlo in Bedrock. */
enum class Orientation { NONE, FACING, AXIS }

class JavaBlockDef(
    val id: Rl, val model: Rl?, val stateCount: Int, val facing: Boolean, val orientation: Orientation = Orientation.NONE,
    /** Il file «blockstates» per intero (BLOCKBRIDGE: blocchi con più stati). */
    val states: JBlockStates? = null
)

/** Una mod letta: i suoi file utili già divisi per tipo. */
class JavaModContent(
    val jar: JavaJar,
    val identity: ModIdentity,
    val namespaces: Set<String>,
    val blocks: List<JavaBlockDef>,
    val items: List<JavaItemDef>,
    /** lingua Java (es. «it_it») → chiave → testo. */
    val lang: Map<String, Map<String, String>>,
    val itemTags: Map<Rl, List<String>>,
    val blockTags: Map<Rl, List<String>>,
    val recipes: List<Pair<Rl, Map<String, Any?>>>,
    val blockLoot: Map<Rl, Map<String, Any?>>,
    val configuredFeatures: Map<Rl, Map<String, Any?>>,
    val placedFeatures: Map<Rl, Map<String, Any?>>,
    val biomeModifiers: List<Map<String, Any?>>,
    val fuels: Map<Rl, Int>,
    val spawnEggs: List<Rl>,
    /** Icona piatta degli oggetti-blocco (porte, cartelli…): serve se il blocco non si può convertire. */
    val blockItems: Map<Rl, JavaItemDef>,
    val geoModels: List<String>,
    val codeFeatures: List<String>
)

/** Legge da un JAR tutto quello che serve alla conversione. Non esegue nulla: solo file JSON e PNG. */
object JavaContentReader {

    fun read(jar: JavaJar, limits: ConvertLimits): JavaModContent {
        val identity = ModIdentityReader.read(jar)
        val all = jar.allNames().toList()
        val namespaces = all.filter { it.startsWith("assets/") || it.startsWith("data/") }
            .mapNotNull { it.split('/').getOrNull(1) }.filter { it.isNotEmpty() && it != "minecraft" }.toSortedSet()
        val resolver = ModelResolver(listOf(jar), limits)

        // ---- lingue
        val lang = LinkedHashMap<String, MutableMap<String, String>>()
        for (n in all) {
            val m = LANG.matchEntire(n) ?: continue
            val code = m.groupValues[2].lowercase()
            val map = lang.getOrPut(code) { LinkedHashMap() }
            if (n.endsWith(".json")) {
                jar.json(n, limits.maxJsonBytes)?.asJsonObject()?.forEach { (k, v) -> if (v is String) map[k] = v }
            } else {
                jar.text(n, limits.maxJsonBytes)?.lineSequence()?.forEach { l ->
                    val i = l.indexOf('=')
                    if (i > 0 && !l.startsWith("#")) map[l.substring(0, i).trim()] = l.substring(i + 1).trim()
                }
            }
        }

        // ---- gruppi («tag»)
        fun tags(folderNames: Set<String>): Map<Rl, List<String>> {
            val out = LinkedHashMap<Rl, MutableList<String>>()
            for (n in all) {
                val m = TAG.matchEntire(n) ?: continue
                if (m.groupValues[2] !in folderNames) continue
                val rl = Rl(m.groupValues[1], m.groupValues[3])
                val values = jar.json(n, limits.maxJsonBytes)?.asJsonObject()?.get("values").asJsonList() ?: continue
                val list = out.getOrPut(rl) { ArrayList() }
                for (v in values) {
                    when (v) {
                        is String -> list.add(v)
                        is Map<*, *> -> (v["id"] as? String)?.let { list.add(it) }
                    }
                }
            }
            return out
        }
        val itemTags = tags(setOf("item", "items"))
        val blockTags = tags(setOf("block", "blocks"))

        // ---- blocchi: uno per ogni file in blockstates
        val blocks = ArrayList<JavaBlockDef>()
        for (n in all) {
            val m = BLOCKSTATE.matchEntire(n) ?: continue
            val id = Rl(m.groupValues[1], m.groupValues[2])
            if (id.ns == "minecraft") continue
            val o = jar.json(n, limits.maxJsonBytes)?.asJsonObject()
            val pick = o?.let { pickModel(it) }
            blocks.add(JavaBlockDef(id, pick?.model, pick?.states ?: 1, pick?.facing ?: false, pick?.orientation ?: Orientation.NONE, o?.let { JBlockStates.parse(it) }))
        }
        val blockIds = blocks.map { it.id }.toSet()

        // ---- oggetti: le definizioni 1.21.4+ (assets/<ns>/items) o i modelli in models/item
        val itemModels = LinkedHashMap<Rl, Rl?>()
        for (n in all) {
            ITEM_DEF.matchEntire(n)?.let { m ->
                val id = Rl(m.groupValues[1], m.groupValues[2])
                val def = jar.json(n, limits.maxJsonBytes)
                itemModels[id] = def?.let { firstModel(it) }
            }
        }
        for (n in all) {
            ITEM_MODEL.matchEntire(n)?.let { m ->
                val id = Rl(m.groupValues[1], m.groupValues[2])
                if (id !in itemModels) itemModels[id] = Rl(id.ns, "item/" + id.path)
            }
        }
        val items = ArrayList<JavaItemDef>()
        val spawnEggs = ArrayList<Rl>()
        val blockItems = LinkedHashMap<Rl, JavaItemDef>()
        for ((id, model) in itemModels) {
            if (id.ns == "minecraft") continue
            if (id in blockIds) {
                val info = model?.let { resolver.itemIcon(it) }
                if (info?.icon != null && !info.model3d) blockItems[id] = JavaItemDef(id, model, info, ItemKind.ITEM, KindSource.NONE)
                continue
            }
            val info = model?.let { resolver.itemIcon(it) }
            if (info?.spawnEgg == true || id.path.endsWith("_spawn_egg")) {
                spawnEggs.add(id); continue
            }
            val (kind, source) = classify(id, itemTags)
            items.add(JavaItemDef(id, model, info, kind, source))
        }

        // ---- ricette, bottini, minerali
        val recipes = ArrayList<Pair<Rl, Map<String, Any?>>>()
        val loot = LinkedHashMap<Rl, Map<String, Any?>>()
        val configured = LinkedHashMap<Rl, Map<String, Any?>>()
        val placed = LinkedHashMap<Rl, Map<String, Any?>>()
        val modifiers = ArrayList<Map<String, Any?>>()
        val fuels = LinkedHashMap<Rl, Int>()
        for (n in all) {
            if (!n.startsWith("data/") || !n.endsWith(".json")) continue
            RECIPE.matchEntire(n)?.let { m ->
                jar.json(n, limits.maxJsonBytes)?.asJsonObject()?.let { recipes.add(Rl(m.groupValues[1], m.groupValues[3]) to it) }
                return@let
            }
            LOOT.matchEntire(n)?.let { m ->
                jar.json(n, limits.maxJsonBytes)?.asJsonObject()?.let { loot[Rl(m.groupValues[1], m.groupValues[3])] = it }
            }
            WORLDGEN.matchEntire(n)?.let { m ->
                val o = jar.json(n, limits.maxJsonBytes)?.asJsonObject() ?: return@let
                val rl = Rl(m.groupValues[1], m.groupValues[3])
                if (m.groupValues[2] == "configured_feature") configured[rl] = o else placed[rl] = o
            }
            if (BIOME_MODIFIER.matches(n)) jar.json(n, limits.maxJsonBytes)?.asJsonObject()?.let { modifiers.add(it) }
            if (n == "data/neoforge/data_maps/item/furnace_fuels.json") {
                jar.json(n, limits.maxJsonBytes)?.asJsonObject()?.get("values").asJsonObject()?.forEach { (k, v) ->
                    val t = (v.asJsonObject()?.get("burn_time") as? Number)?.toInt() ?: (v as? Number)?.toInt()
                    val rl = Rl.parse(k)
                    if (t != null && rl != null) fuels[rl] = t
                }
            }
        }

        // ---- cose che vivono nel codice (per il rapporto)
        val code = ArrayList<String>()
        val geo = all.filter { it.startsWith("assets/") && (it.contains("/geo/") || it.contains("/geckolib/models/")) && it.endsWith(".json") }
        val entityLang = lang["en_us"]?.keys?.count { it.startsWith("entity.") && namespaces.any { ns -> it.startsWith("entity.$ns.") } } ?: 0
        if (entityLang > 0) code.add("Creature: $entityLang (il loro comportamento è codice Java)")
        val screens = all.count { it.contains("/textures/gui/") && !it.contains("/gui/sprites/") }
        if (screens > 0) code.add("Schermate e menu della mod ($screens immagini)")
        val sounds = all.count { it.startsWith("assets/") && it.endsWith(".ogg") }
        if (sounds > 0) code.add("Suoni ($sounds file)")
        if (all.any { it.endsWith("mixins.json") && !it.startsWith("assets/") && !it.startsWith("data/") }) {
            code.add("Modifiche al motore del gioco (mixin)")
        }
        val advancements = all.count { ADVANCEMENT.matches(it) }
        if (advancements > 0) code.add("Progressi: $advancements")
        val structures = all.count { it.startsWith("data/") && it.endsWith(".nbt") }
        if (structures > 0) code.add("Strutture: $structures")
        val enchant = all.count { it.startsWith("data/") && it.contains("/enchantment/") && it.endsWith(".json") }
        if (enchant > 0) code.add("Incantesimi: $enchant")
        val dims = all.count { it.startsWith("data/") && !it.contains("/tags/") && (it.contains("/dimension/") || it.contains("/worldgen/biome/")) && it.endsWith(".json") }
        if (dims > 0) code.add("Dimensioni o biomi: $dims")
        return JavaModContent(
            jar, identity, namespaces, blocks, items, lang, itemTags, blockTags, recipes, loot, configured, placed, modifiers,
            fuels, spawnEggs, blockItems, geo, code
        )
    }

    private val LANG = Regex("""assets/([^/]+)/lang/([a-zA-Z]{2,3}_[a-zA-Z]{2,4})\.(json|lang)""")
    private val TAG = Regex("""data/([^/]+)/tags/(items?|blocks?)/(.+)\.json""")
    private val BLOCKSTATE = Regex("""assets/([a-z0-9_.-]+)/blockstates/([a-z0-9_./-]+)\.json""")
    private val ITEM_DEF = Regex("""assets/([a-z0-9_.-]+)/items/([a-z0-9_./-]+)\.json""")
    private val ITEM_MODEL = Regex("""assets/([a-z0-9_.-]+)/models/item/([a-z0-9_./-]+)\.json""")
    private val RECIPE = Regex("""data/([a-z0-9_.-]+)/(recipes?)/([a-z0-9_./-]+)\.json""")
    private val LOOT = Regex("""data/([a-z0-9_.-]+)/(loot_tables?)/blocks/([a-z0-9_./-]+)\.json""")
    private val WORLDGEN = Regex("""data/([a-z0-9_.-]+)/worldgen/(configured_feature|placed_feature)/([a-z0-9_./-]+)\.json""")
    private val BIOME_MODIFIER = Regex("""data/[^/]+/(neoforge|forge)/biome_modifiers?/.+\.json""")
    private val ADVANCEMENT = Regex("""data/[^/]+/advancements?/.+\.json""")

    private val PREFERRED = setOf(
        "facing=north", "axis=y", "half=bottom", "type=bottom", "lit=false", "powered=false", "open=false", "waterlogged=false",
        "occupied=false", "shape=straight", "attached=false", "hanging=false", "part=foot", "triggered=false", "enabled=true"
    )

    private class Pick(val model: Rl?, val states: Int, val facing: Boolean, val orientation: Orientation)

    /**
     * Il modello da usare e quanti stati ha il blocco in Java. Se la variante scelta è quella rivolta a nord (o con l'asse
     * verticale) e il modello non è già ruotato, Bedrock può orientare il blocco come Java quando lo piazzi.
     */
    private fun pickModel(o: Map<String, Any?>): Pick? {
        val variants = o["variants"].asJsonObject()
        if (variants != null && variants.isNotEmpty()) {
            val best = variants.entries.maxByOrNull { (key, _) -> key.split(',').count { it in PREFERRED } * 10 - key.length / 100 }!!
            val v = when (val x = best.value) {
                is Map<*, *> -> x
                is List<*> -> x.firstOrNull() as? Map<*, *>
                else -> null
            }
            val m = v?.get("model") as? String
            val rotated = ((v?.get("x") as? Number)?.toInt() ?: 0) != 0 || ((v?.get("y") as? Number)?.toInt() ?: 0) != 0
            val props = best.key.split(',').toSet()
            val keys = variants.keys
            val facing = keys.any { it.contains("facing=") || it.contains("horizontal_facing=") }
            val horizontal = keys.none { it.contains("facing=up") || it.contains("facing=down") }
            val orientation = when {
                rotated -> Orientation.NONE
                facing && horizontal && ("facing=north" in props || "horizontal_facing=north" in props) &&
                    keys.count { it.contains("facing=") } >= 4 -> Orientation.FACING
                "axis=y" in props && keys.any { it.contains("axis=x") } && keys.any { it.contains("axis=z") } -> Orientation.AXIS
                else -> Orientation.NONE
            }
            return Pick(Rl.parse(m), variants.size, facing, orientation)
        }
        val parts = o["multipart"].asJsonList() ?: return null
        val always = parts.firstOrNull { (it as? Map<*, *>)?.get("when") == null } ?: parts.firstOrNull()
        val apply = (always as? Map<*, *>)?.get("apply")
        val m = when (apply) {
            is Map<*, *> -> apply["model"] as? String
            is List<*> -> (apply.firstOrNull() as? Map<*, *>)?.get("model") as? String
            else -> null
        }
        return Pick(Rl.parse(m), parts.size, false, Orientation.NONE)
    }

    /** Il primo modello citato in una definizione di oggetto 1.21.4+ (`{"model": {"type": "minecraft:model", "model": "…"}}`). */
    private fun firstModel(v: Any?, depth: Int = 0): Rl? {
        if (depth > 12) return null
        when (v) {
            is Map<*, *> -> {
                val type = v["type"] as? String
                val model = v["model"]
                if (model is String && (type == null || type.endsWith("model"))) return Rl.parse(model)
                for (x in v.values) firstModel(x, depth + 1)?.let { return it }
            }
            is List<*> -> for (x in v) firstModel(x, depth + 1)?.let { return it }
        }
        return null
    }

    private val TAG_KINDS = listOf(
        ItemKind.SWORD to listOf("minecraft:swords", "c:tools/melee_weapon", "c:tools/sword", "forge:tools/swords", "c:swords", "fabric:swords"),
        ItemKind.PICKAXE to listOf("minecraft:pickaxes", "c:tools/mining_tool", "c:tools/pickaxe", "forge:tools/pickaxes", "c:pickaxes", "fabric:pickaxes"),
        ItemKind.AXE to listOf("minecraft:axes", "c:tools/axe", "forge:tools/axes", "c:axes", "fabric:axes"),
        ItemKind.SHOVEL to listOf("minecraft:shovels", "c:tools/shovel", "forge:tools/shovels", "c:shovels", "fabric:shovels"),
        ItemKind.HOE to listOf("minecraft:hoes", "c:tools/hoe", "forge:tools/hoes", "c:hoes", "fabric:hoes"),
        ItemKind.HELMET to listOf("minecraft:head_armor", "c:armors/helmets", "c:armors/helmet", "forge:armors/helmets"),
        ItemKind.CHESTPLATE to listOf("minecraft:chest_armor", "c:armors/chestplates", "c:armors/chestplate", "forge:armors/chestplates"),
        ItemKind.LEGGINGS to listOf("minecraft:leg_armor", "c:armors/leggings", "forge:armors/leggings"),
        ItemKind.BOOTS to listOf("minecraft:foot_armor", "c:armors/boots", "forge:armors/boots"),
        ItemKind.DRINK to listOf("c:drinks"),
        ItemKind.FOOD to listOf("c:foods", "forge:foods")
    )

    private fun classify(id: Rl, tags: Map<Rl, List<String>>): Pair<ItemKind, KindSource> {
        val key = id.toString()
        for ((kind, names) in TAG_KINDS) {
            for (name in names) {
                val rl = Rl.parse(name) ?: continue
                // Il gruppo stesso o i suoi sottogruppi (c:foods/bread, c:tools/…)
                val hit = tags.entries.any { (t, v) -> (t == rl || (t.ns == rl.ns && t.path.startsWith(rl.path + "/"))) && key in v }
                if (hit) return kind to KindSource.TAG
            }
        }
        val p = id.path.substringAfterLast('/')
        val byName = when {
            p.endsWith("_sword") -> ItemKind.SWORD
            p.endsWith("_pickaxe") -> ItemKind.PICKAXE
            p.endsWith("_axe") && !p.endsWith("_pickaxe") -> ItemKind.AXE
            p.endsWith("_shovel") -> ItemKind.SHOVEL
            p.endsWith("_hoe") -> ItemKind.HOE
            p.endsWith("_helmet") -> ItemKind.HELMET
            p.endsWith("_chestplate") -> ItemKind.CHESTPLATE
            p.endsWith("_leggings") -> ItemKind.LEGGINGS
            p.endsWith("_boots") -> ItemKind.BOOTS
            else -> null
        }
        return if (byName != null) byName to KindSource.NAME else ItemKind.ITEM to KindSource.NONE
    }
}
