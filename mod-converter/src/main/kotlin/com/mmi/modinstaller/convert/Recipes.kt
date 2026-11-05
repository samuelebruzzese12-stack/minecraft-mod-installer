package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/** Un ingrediente Bedrock: un oggetto preciso o un gruppo («tag») che Bedrock conosce. */
sealed class Ingredient {
    data class Item(val item: BedrockItem) : Ingredient()
    data class Tag(val tag: String) : Ingredient()

    fun json(count: Int? = null): Map<String, Any?> = when (this) {
        is Item -> linkedMapOf<String, Any?>("item" to item.id).also { m ->
            item.data?.let { m["data"] = it.toLong() }
            if (count != null && count > 1) m["count"] = count.toLong()
        }
        is Tag -> linkedMapOf("tag" to tag)
    }
}

/** Perché una ricetta o un ingrediente non si converte: il testo va nel rapporto (raggruppato). */
class Skip(val reason: String) : Exception(reason)

/**
 * Traduce gli oggetti Java in oggetti Bedrock per le ricette e i bottini: quelli convertiti in questo add-on, quelli di
 * Minecraft (con i nomi che in Bedrock sono diversi) e i gruppi di oggetti (`#c:ingots/iron`, `#minecraft:planks`…).
 */
class ItemResolver(
    /** Oggetto o blocco Java convertito → identificatore Bedrock. */
    private val converted: Map<Rl, String>,
    /** Solo gli oggetti (non i blocchi): sono gli unici a cui si possono dare gruppi Bedrock. */
    private val convertedItems: Set<Rl>,
    /** Gruppi definiti dalle mod convertite (unione di tutte). */
    private val tags: Map<Rl, List<String>>,
    /** Mod presenti (per le condizioni «se la mod X c'è»). */
    val loadedMods: Set<String>
) {
    /** Gruppi Bedrock creati per gli oggetti convertiti: nome del gruppo → oggetti Bedrock che lo hanno. */
    val customTags = LinkedHashMap<String, MutableSet<String>>()

    fun item(rl: Rl): BedrockItem? = converted[rl]?.let { BedrockItem(it) } ?: Vanilla.item(rl)

    fun itemOrSkip(text: String?): BedrockItem {
        val rl = Rl.parse(text) ?: throw Skip("ricetta scritta in un formato che l'app non conosce")
        return item(rl) ?: throw Skip(missing(rl))
    }

    private fun missing(rl: Rl): String = when {
        rl.ns == "minecraft" -> "usa «$rl», che in Bedrock non esiste"
        rl.ns in loadedMods -> "usa un oggetto della mod che non si è potuto convertire (es. «$rl»)"
        else -> "usa oggetti di un'altra mod non convertita (es. «$rl»)"
    }

    /** Tutti gli oggetti di un gruppo definito dalle mod (anche annidato), come identificatori Java. */
    private fun members(tag: Rl, depth: Int = 0): List<Rl> {
        if (depth > 8) return emptyList()
        val out = ArrayList<Rl>()
        for (v in tags[tag].orEmpty()) {
            if (v.startsWith("#")) Rl.parse(v.substring(1))?.let { out.addAll(members(it, depth + 1)) }
            else Rl.parse(v)?.let { out.add(it) }
        }
        return out.distinct()
    }

    fun tag(tagRl: Rl): Ingredient {
        Vanilla.bedrockTag(tagRl)?.let { return Ingredient.Tag(it) }
        val members = members(tagRl)
        val resolved = members.mapNotNull { m -> item(m)?.let { m to it } }
        if (resolved.isNotEmpty()) {
            val custom = resolved.filter { it.first in convertedItems }
            if (resolved.size > 1 && custom.size == resolved.size) {
                // Tutti oggetti convertiti: diventano un gruppo Bedrock (componente minecraft:tags degli oggetti).
                val name = "mmi:" + (tagRl.ns + "_" + tagRl.path).map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
                customTags.getOrPut(name) { LinkedHashSet() }.addAll(custom.map { it.second.id })
                return Ingredient.Tag(name)
            }
            // Altrimenti un oggetto rappresentativo: prima quello di Minecraft, poi quello della mod.
            val pick = resolved.firstOrNull { it.first.ns == "minecraft" } ?: resolved.first()
            return Ingredient.Item(pick.second)
        }
        Vanilla.tagItem(tagRl)?.let { return Ingredient.Item(it) }
        throw Skip(
            when {
                members.isEmpty() -> "usa il gruppo «#$tagRl», che l'app non sa tradurre"
                members.all { it.ns in loadedMods } -> "usa oggetti della mod che non si sono potuti convertire (gruppo «#$tagRl»)"
                else -> "usa oggetti di un'altra mod non convertita (gruppo «#$tagRl»)"
            }
        )
    }

    /** Un ingrediente in uno dei formati di Java: «mod:x», «#c:tag», {"item": …}, {"tag": …}, o un elenco di alternative. */
    fun ingredient(v: Any?): Ingredient {
        when (v) {
            is String -> return if (v.startsWith("#")) tag(Rl.parse(v.substring(1)) ?: throw Skip("ricetta scritta in un formato che l'app non conosce"))
            else Ingredient.Item(itemOrSkip(v))
            is Map<*, *> -> {
                (v["item"] as? String)?.let { return Ingredient.Item(itemOrSkip(it)) }
                (v["tag"] as? String)?.let { return tag(Rl.parse(it) ?: throw Skip("ricetta scritta in un formato che l'app non conosce")) }
                (v["id"] as? String)?.let { return Ingredient.Item(itemOrSkip(it)) }
                // NeoForge/Fabric: ingredienti con condizioni sui componenti, unioni, differenze…
                val type = v["type"] as? String ?: v["fabric:type"] as? String
                if (type != null && (type.endsWith(":compound") || type.endsWith(":any_of"))) {
                    return ingredient(v["children"] ?: v["ingredients"] ?: v["values"])
                }
                throw Skip("usa un ingrediente speciale (${type ?: "sconosciuto"})")
            }
            is List<*> -> {
                var first: Skip? = null
                for (x in v) {
                    try {
                        return ingredient(x)
                    } catch (s: Skip) {
                        if (first == null) first = s
                    }
                }
                throw first ?: Skip("ricetta senza ingredienti")
            }
            else -> throw Skip("ricetta scritta in un formato che l'app non conosce")
        }
    }

    /** Il risultato: {"item"/"id": …, "count": n} oppure, nelle ricette di cottura vecchie, una stringa. */
    fun result(v: Any?): Pair<BedrockItem, Int> = when (v) {
        is String -> itemOrSkip(v) to 1
        is Map<*, *> -> {
            if (v["components"] != null && (v["components"] as? Map<*, *>)?.isNotEmpty() == true) {
                // Il risultato ha dati speciali (nome, incantesimi…): si dà l'oggetto semplice.
            }
            itemOrSkip((v["id"] ?: v["item"]) as? String) to ((v["count"] as? Number)?.toInt() ?: 1)
        }
        else -> throw Skip("ricetta senza risultato")
    }
}

/** Converte le ricette Java nei formati di Bedrock (banco da lavoro, fornace, tagliapietre, tavolo da forgia). */
class RecipeConverter(private val resolver: ItemResolver) {

    private val usedIds = HashSet<String>()

    private fun identifier(rl: Rl, suffix: String = ""): String {
        val base = rl.ns + ":" + rl.path.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("") + suffix
        var id = base
        var n = 2
        while (!usedIds.add(id)) id = base + "_" + n++
        return id
    }

    /** Le ricette Bedrock (nome del file → documento) per una ricetta Java. */
    fun convert(rl: Rl, o: Map<String, Any?>): List<Pair<String, Map<String, Any?>>> {
        checkConditions(o)
        val type = (o["type"] as? String)?.let { Rl.parse(it) } ?: throw Skip("ricetta senza tipo")
        if (type.ns != "minecraft") throw Skip("ricetta di una macchina o di un sistema della mod (funziona solo con il suo codice)")
        return when (type.path) {
            "crafting_shaped" -> listOf(shaped(rl, o))
            "crafting_shapeless" -> listOf(shapeless(rl, o))
            "smelting", "blasting", "smoking", "campfire_cooking" -> cooking(rl, o, type.path)
            "stonecutting" -> listOf(stonecutting(rl, o))
            "smithing_transform" -> listOf(smithing(rl, o))
            "smithing_trim" -> throw Skip("finiture delle armature: Bedrock le gestisce da sé")
            else -> if (type.path.startsWith("crafting_special") || type.path.startsWith("crafting_decorated") || type.path == "crafting_transmute")
                throw Skip("ricetta speciale di Minecraft (calcolata dal codice del gioco)")
            else throw Skip("ricetta di tipo «$type», che Bedrock non ha")
        }
    }

    private fun checkConditions(o: Map<String, Any?>) {
        val conditions = o["neoforge:conditions"] ?: o["forge:conditions"] ?: o["fabric:load_conditions"] ?: o["conditions"]
        val list = conditions.asJsonList() ?: return
        for (c in list) {
            val m = c.asJsonObject() ?: continue
            val type = (m["type"] ?: m["condition"]) as? String ?: continue
            val mods = when {
                type.endsWith("mod_loaded") -> listOfNotNull(m["modid"] as? String)
                type.endsWith("all_mods_loaded") -> m["values"].asJsonList()?.mapNotNull { it as? String }.orEmpty()
                else -> emptyList()
            }
            for (mod in mods) {
                if (mod !in resolver.loadedMods && mod != "minecraft" && mod != "c" && mod != "neoforge" && mod != "forge" && mod != "fabric") {
                    throw Skip("vale solo se c'è anche la mod «$mod»")
                }
            }
            if (type.endsWith(":not")) {
                val inner = m["value"].asJsonObject()
                if (inner != null && (inner["type"] as? String)?.endsWith("mod_loaded") == true && (inner["modid"] as? String) in resolver.loadedMods) {
                    throw Skip("vale solo se manca la mod «${inner["modid"]}»")
                }
            }
        }
    }

    private fun doc(kind: String, body: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("format_version" to "1.20.10", kind to body)

    private fun shaped(rl: Rl, o: Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val pattern = o["pattern"].asJsonList()?.mapNotNull { it as? String } ?: throw Skip("ricetta senza schema")
        if (pattern.isEmpty() || pattern.size > 3 || pattern.any { it.length > 3 }) throw Skip("schema più grande di 3×3")
        val width = pattern.maxOf { it.length }
        val rows = pattern.map { it.padEnd(width, ' ') }
        val keyIn = o["key"].asJsonObject() ?: throw Skip("ricetta senza ingredienti")
        val key = LinkedHashMap<String, Any?>()
        for (ch in rows.joinToString("").toSet()) {
            if (ch == ' ') continue
            val v = keyIn[ch.toString()] ?: throw Skip("ricetta incompleta")
            key[ch.toString()] = resolver.ingredient(v).json()
        }
        val (item, count) = resolver.result(o["result"])
        val id = identifier(rl)
        val body = linkedMapOf<String, Any?>(
            "description" to linkedMapOf("identifier" to id), "tags" to listOf("crafting_table"), "pattern" to rows, "key" to key,
            "unlock" to linkedMapOf("context" to "AlwaysUnlocked"), "result" to Ingredient.Item(item).json(count)
        )
        return fileName(id) to doc("minecraft:recipe_shaped", body)
    }

    private fun shapeless(rl: Rl, o: Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val list = o["ingredients"].asJsonList() ?: throw Skip("ricetta senza ingredienti")
        if (list.isEmpty() || list.size > 9) throw Skip("numero di ingredienti non valido")
        val ingredients = list.map { resolver.ingredient(it).json() }
        val (item, count) = resolver.result(o["result"])
        val id = identifier(rl)
        val body = linkedMapOf<String, Any?>(
            "description" to linkedMapOf("identifier" to id), "tags" to listOf("crafting_table"), "ingredients" to ingredients,
            "unlock" to linkedMapOf("context" to "AlwaysUnlocked"), "result" to Ingredient.Item(item).json(count)
        )
        return fileName(id) to doc("minecraft:recipe_shapeless", body)
    }

    private fun cooking(rl: Rl, o: Map<String, Any?>, type: String): List<Pair<String, Map<String, Any?>>> {
        val tags = when (type) {
            "smelting" -> listOf("furnace")
            "blasting" -> listOf("blast_furnace")
            "smoking" -> listOf("smoker")
            else -> listOf("campfire", "soul_campfire")
        }
        val (out, _) = resolver.result(o["result"])
        // La fornace di Bedrock vuole un oggetto preciso: un gruppo o un elenco diventa una ricetta per ogni oggetto.
        val inputs = cookingInputs(o["ingredient"])
        if (inputs.isEmpty()) throw Skip("ricetta senza ingredienti")
        return inputs.take(16).mapIndexed { i, input ->
            val id = identifier(rl, if (inputs.size > 1) "_" + (i + 1) else "")
            val body = linkedMapOf<String, Any?>(
                "description" to linkedMapOf("identifier" to id), "tags" to tags,
                "input" to (if (input.data != null) linkedMapOf("item" to input.id, "data" to input.data.toLong()) else input.id),
                "output" to (if (out.data != null) linkedMapOf("item" to out.id, "data" to out.data.toLong()) else out.id),
                "unlock" to linkedMapOf("context" to "AlwaysUnlocked")
            )
            fileName(id) to doc("minecraft:recipe_furnace", body)
        }
    }

    private fun cookingInputs(v: Any?): List<BedrockItem> {
        val out = ArrayList<BedrockItem>()
        var first: Skip? = null
        val list = if (v is List<*>) v else listOf(v)
        for (x in list) {
            try {
                when (val ing = resolver.ingredient(x)) {
                    is Ingredient.Item -> out.add(ing.item)
                    is Ingredient.Tag -> {
                        val members = resolver.customTags[ing.tag]
                        if (members != null) out.addAll(members.map { BedrockItem(it) })
                        else throw Skip("la fornace di Bedrock non accetta il gruppo «${ing.tag}»")
                    }
                }
            } catch (s: Skip) {
                if (first == null) first = s
            }
        }
        if (out.isEmpty()) throw first ?: Skip("ricetta senza ingredienti")
        return out.distinct()
    }

    private fun stonecutting(rl: Rl, o: Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val input = resolver.ingredient(o["ingredient"])
        val (item, count0) = resolver.result(o["result"])
        val count = (o["count"] as? Number)?.toInt() ?: count0
        val id = identifier(rl)
        val body = linkedMapOf<String, Any?>(
            "description" to linkedMapOf("identifier" to id), "tags" to listOf("stonecutter"), "ingredients" to listOf(input.json()),
            "unlock" to linkedMapOf("context" to "AlwaysUnlocked"), "result" to Ingredient.Item(item).json(count)
        )
        return fileName(id) to doc("minecraft:recipe_shapeless", body)
    }

    private fun single(v: Any?): String = when (val i = resolver.ingredient(v)) {
        is Ingredient.Item -> i.item.id
        is Ingredient.Tag -> resolver.customTags[i.tag]?.firstOrNull() ?: throw Skip("il tavolo da forgia di Bedrock non accetta il gruppo «${i.tag}»")
    }

    private fun smithing(rl: Rl, o: Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val template = single(o["template"])
        val base = single(o["base"])
        val addition = single(o["addition"])
        val (result, _) = resolver.result(o["result"])
        val id = identifier(rl)
        // Bedrock vuole che modello, oggetto base e materiale abbiano i gruppi del tavolo da forgia (verificato nel server
        // Bedrock: senza, la ricetta si scarta). Gli oggetti di Minecraft li hanno già; quelli della mod li ricevono qui.
        for ((item, tag) in listOf(template to "minecraft:transform_templates", base to "minecraft:transformable_items", addition to "minecraft:transform_materials")) {
            if (!item.startsWith("minecraft:")) resolver.customTags.getOrPut(tag) { LinkedHashSet() }.add(item)
            else if (item !in VANILLA_SMITHING.getValue(tag)) throw Skip("il tavolo da forgia di Bedrock accetta solo oggetti del gruppo «$tag» (es. il lingotto di netherite)")
        }
        val body = linkedMapOf<String, Any?>(
            "description" to linkedMapOf("identifier" to id), "tags" to listOf("smithing_table"),
            "template" to template, "base" to base, "addition" to addition, "result" to result.id
        )
        return fileName(id) to doc("minecraft:recipe_smithing_transform", body)
    }

    private fun fileName(id: String) = id.replace(':', '/') + ".json"

    /** Oggetti di Minecraft Bedrock che hanno già i gruppi del tavolo da forgia (aggiornamento al netherite). */
    private val VANILLA_SMITHING = mapOf(
        "minecraft:transform_templates" to setOf("minecraft:netherite_upgrade_smithing_template"),
        "minecraft:transform_materials" to setOf("minecraft:netherite_ingot"),
        "minecraft:transformable_items" to setOf(
            "minecraft:diamond_sword", "minecraft:diamond_pickaxe", "minecraft:diamond_axe", "minecraft:diamond_shovel", "minecraft:diamond_hoe",
            "minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:diamond_leggings", "minecraft:diamond_boots"
        )
    )
}

/** Converte la tabella dei bottini di un blocco (cosa lascia quando lo rompi). */
class LootConverter(private val resolver: ItemResolver) {

    class Result(val table: Map<String, Any?>, val simplified: Boolean)

    /**
     * [state]: lo stato Java del blocco (es. «type=double», «age=7»), se si conosce: le condizioni sullo stato del blocco
     * («block_state_property») allora si valutano come in Java invece di essere ignorate.
     */
    fun convert(o: Map<String, Any?>, state: Map<String, String>? = null): Result {
        lossy = false
        var simplified = false
        val pools = ArrayList<Any?>()
        for (p in o["pools"].asJsonList().orEmpty()) {
            val pool = p.asJsonObject() ?: continue
            if (state != null && conditions(pool["conditions"], state) == false) continue
            val entries = ArrayList<Any?>()
            for (e in pool["entries"].asJsonList().orEmpty()) {
                val r = entry(e.asJsonObject() ?: continue, state)
                if (r.second) simplified = true
                r.first?.let { entries.add(it) }
            }
            if (entries.isEmpty()) continue
            val out = linkedMapOf<String, Any?>("rolls" to rolls(pool["rolls"]), "entries" to entries)
            pool["conditions"].asJsonList()?.forEach { c ->
                val type = (c.asJsonObject()?.get("condition") as? String)?.removePrefix("minecraft:")
                if (type == "random_chance") {
                    val ch = (c.asJsonObject()?.get("chance") as? Number)?.toDouble()
                    if (ch != null) out["conditions"] = listOf(linkedMapOf("condition" to "random_chance", "chance" to ch))
                } else if (type != "survives_explosion" && !(state != null && condition(c.asJsonObject(), state) != null)) simplified = true
            }
            pools.add(out)
        }
        return Result(linkedMapOf("pools" to pools), simplified || lossy)
    }

    /** Si è scartato un ramo per attrezzi speciali (tocco di velluto, cesoie): il bottino resta quello con l'attrezzo normale. */
    private var lossy = false

    private fun rolls(v: Any?): Any = when (v) {
        is Number -> v.toLong().coerceAtLeast(1)
        is Map<*, *> -> {
            val min = (v["min"] as? Number)?.toLong(); val max = (v["max"] as? Number)?.toLong()
            if (min != null && max != null) linkedMapOf("min" to min, "max" to max) else 1L
        }
        else -> 1L
    }

    /** Vero/falso se tutte le condizioni si sanno valutare con lo stato del blocco, null se qualcuna no. */
    private fun conditions(v: Any?, state: Map<String, String>): Boolean? {
        var all = true
        for (c in v.asJsonList().orEmpty()) {
            when (condition(c.asJsonObject(), state)) { false -> return false; null -> all = false; true -> {} }
        }
        return if (all) true else null
    }

    /** Una condizione di Java: quelle sullo stato del blocco si valutano; «sopravvive all'esplosione» è vera; gli attrezzi
     *  speciali (tocco di velluto, cesoie) sono falsi (si scava con un attrezzo normale); le altre non si sanno (null). */
    private fun condition(c: Map<String, Any?>?, state: Map<String, String>): Boolean? {
        c ?: return null
        return when ((c["condition"] as? String)?.removePrefix("minecraft:")) {
            "block_state_property" -> {
                val props = c["properties"].asJsonObject() ?: return null
                props.all { (k, v) ->
                    val actual = state[k] ?: return null
                    when (v) {
                        is Map<*, *> -> {
                            val n = actual.toIntOrNull() ?: return null
                            val min = (v["min"] as? Number)?.toInt() ?: Int.MIN_VALUE; val max = (v["max"] as? Number)?.toInt() ?: Int.MAX_VALUE
                            n in min..max
                        }
                        else -> v.toString() == actual
                    }
                }
            }
            "survives_explosion" -> true
            "match_tool" -> { lossy = true; false }
            "inverted" -> condition(c["term"].asJsonObject(), state)?.not()
            "any_of", "alternative" -> {
                val r = c["terms"].asJsonList().orEmpty().map { condition(it.asJsonObject(), state) }
                if (r.any { it == true }) true else if (r.all { it == false }) false else null
            }
            "all_of" -> conditions(c["terms"], state)
            else -> null
        }
    }

    /** L'elemento convertito (o null se non si converte) e se è stato semplificato. */
    private fun entry(e: Map<String, Any?>, state: Map<String, String>? = null): Pair<Map<String, Any?>?, Boolean> {
        if (state != null && conditions(e["conditions"], state) == false) return null to false
        val type = (e["type"] as? String)?.removePrefix("minecraft:")
        return when (type) {
            "item" -> {
                val rl = Rl.parse(e["name"] as? String) ?: return null to true
                val item = resolver.item(rl) ?: return null to true
                var simplified = false
                val functions = ArrayList<Any?>()
                for (f in e["functions"].asJsonList().orEmpty()) {
                    val fo = f.asJsonObject() ?: continue
                    // Una funzione con condizioni sullo stato (es. «2 se è una lastra doppia»): vale solo se lo stato le rispetta.
                    if (fo["conditions"] != null) {
                        val ok = if (state != null) conditions(fo["conditions"], state) else null
                        if (ok == false) continue
                        if (ok == null && state == null) { simplified = true; continue }
                    }
                    when ((fo["function"] as? String)?.removePrefix("minecraft:")) {
                        "set_count" -> countJson(fo["count"])?.let { functions.add(linkedMapOf("function" to "set_count", "count" to it)) } ?: run { simplified = true }
                        "explosion_decay", "copy_name", "copy_components", "copy_nbt", "copy_state" -> {}
                        else -> simplified = true
                    }
                }
                if (e["conditions"].asJsonList()?.any { (it.asJsonObject()?.get("condition") as? String)?.endsWith("survives_explosion") != true } == true &&
                    (state == null || conditions(e["conditions"], state) == null)) simplified = true
                val out = linkedMapOf<String, Any?>("type" to "item", "name" to item.id, "weight" to ((e["weight"] as? Number)?.toLong() ?: 1L))
                if (item.data != null) functions.add(0, linkedMapOf("function" to "set_data", "data" to item.data.toLong()))
                if (functions.isNotEmpty()) out["functions"] = functions
                out to simplified
            }
            "alternatives" -> {
                val children = e["children"].asJsonList()?.mapNotNull { it.asJsonObject() }.orEmpty()
                if (state != null) {
                    // Come in Java: il primo ramo le cui condizioni valgono (es. coltura matura → raccolto, altrimenti → semi).
                    for (c in children) {
                        when (conditions(c["conditions"], state)) {
                            true -> return entry(c, state).first to false
                            false -> continue
                            null -> break
                        }
                    }
                }
                // Di solito: [con il tocco di velluto → il blocco, altrimenti → il minerale]. Si tiene l'ultimo ramo.
                val last = children.lastOrNull() ?: return null to true
                entry(last, state).first to true
            }
            "group", "sequence" -> {
                val first = e["children"].asJsonList()?.firstNotNullOfOrNull { c -> c.asJsonObject()?.let { entry(it, state).first } }
                first to true
            }
            "empty" -> linkedMapOf<String, Any?>("type" to "empty", "weight" to ((e["weight"] as? Number)?.toLong() ?: 1L)) to false
            else -> null to true
        }
    }

    private fun countJson(v: Any?): Any? = when (v) {
        is Number -> v.toLong()
        is Map<*, *> -> {
            val t = (v["type"] as? String)?.removePrefix("minecraft:")
            when {
                t == null || t == "uniform" -> {
                    val min = (v["min"] as? Number)?.toLong(); val max = (v["max"] as? Number)?.toLong()
                    if (min != null && max != null) linkedMapOf("min" to min, "max" to max) else null
                }
                t == "constant" -> (v["value"] as? Number)?.toLong()
                t == "binomial" -> {
                    val n = (v["n"] as? Number)?.toLong()
                    if (n != null) linkedMapOf("min" to 0L, "max" to n) else null
                }
                else -> null
            }
        }
        else -> null
    }
}
