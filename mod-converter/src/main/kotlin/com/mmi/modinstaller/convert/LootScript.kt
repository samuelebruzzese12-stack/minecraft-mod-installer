package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/**
 * Le tabelle dei bottini dei blocchi eseguite come in Java (BLOCKBRIDGE). Le tabelle di Bedrock non sanno fare la fortuna
 * («apply_bonus»), non hanno il «non» tra le condizioni e, provato nel server Bedrock 1.26.62, con il tocco di velluto un
 * blocco delle add-on lascia sempre sé stesso, qualunque cosa dica la tabella. Così, quando un giocatore rompe il blocco,
 * lo script valuta la tabella di Java (attrezzo, incantesimi, stato del blocco, probabilità, meteo, ora) e sostituisce
 * quello che Bedrock ha fatto cadere da sé. Per esplosioni e comandi resta la tabella Bedrock (in Java, senza attrezzo,
 * il risultato è lo stesso).
 *
 * Qui la tabella Java diventa una struttura compatta per lo script, con gli oggetti già tradotti in Bedrock.
 */
internal class LootScript(private val resolver: ItemResolver, private val tables: (Rl) -> Map<String, Any?>?) {

    /** Cose della tabella che lo script non sa fare (per il rapporto: non si nasconde niente). */
    val unsupported = LinkedHashSet<String>()

    /** Quanto sono annidate le tabelle che ne richiamano altre (protezione dai cicli). */
    private var depth = 0

    fun table(o: Map<String, Any?>): Map<String, Any?> {
        val pools = ArrayList<Any?>()
        for (p in o["pools"].asJsonList().orEmpty()) {
            val pool = p.asJsonObject() ?: continue
            val out = linkedMapOf<String, Any?>("r" to number(pool["rolls"] ?: 1L))
            conds(pool["conditions"])?.let { out["c"] = it }
            fns(pool["functions"])?.let { out["f"] = it }
            out["e"] = pool["entries"].asJsonList().orEmpty().mapNotNull { entry(it.asJsonObject()) }
            pools.add(out)
        }
        val t = linkedMapOf<String, Any?>("p" to pools)
        fns(o["functions"])?.let { t["f"] = it }
        return t
    }

    // ---------------------------------------------------------------- voci

    private fun entry(e: Map<String, Any?>?): Map<String, Any?>? {
        e ?: return null
        val type = (e["type"] as? String)?.removePrefix("minecraft:")
        val out = LinkedHashMap<String, Any?>()
        when (type) {
            "item" -> {
                val rl = Rl.parse(e["name"] as? String)
                val item = rl?.let { resolver.item(it) }
                if (item == null) {
                    unsupported.add("oggetti che non esistono in Bedrock (es. «${e["name"]}»): non cadono")
                    return null
                }
                out["t"] = "i"; out["id"] = item.id
            }
            "tag" -> {
                val rl = Rl.parse((e["name"] as? String)?.removePrefix("#"))
                val ids = rl?.let { resolver.tagItems(it) }.orEmpty()
                if (ids.isEmpty()) {
                    unsupported.add("gruppi di oggetti senza oggetti Bedrock (es. «${e["name"]}»)")
                    return null
                }
                out["t"] = "tag"; out["ids"] = ids; out["x"] = e["expand"] == true
            }
            "loot_table" -> {
                val ref = e["value"] ?: e["name"]
                val inner = when (ref) {
                    is String -> Rl.parse(ref)?.let { tables(it) }
                    is Map<*, *> -> ref.asJsonObject()
                    else -> null
                }
                if (inner == null || depth > 6) {
                    unsupported.add("tabelle richiamate che non sono nella mod (es. «$ref»)")
                    return null
                }
                depth++
                try {
                    out["t"] = "l"; out["tab"] = table(inner)
                } finally {
                    depth--
                }
            }
            "empty" -> out["t"] = "e"
            "alternatives", "group", "sequence" -> {
                out["t"] = when (type) { "alternatives" -> "a"; "group" -> "g"; else -> "s" }
                out["ch"] = e["children"].asJsonList().orEmpty().mapNotNull { entry(it.asJsonObject()) }
            }
            "dynamic" -> {
                unsupported.add("contenuto speciale del blocco («dynamic»: es. gli oggetti dentro un contenitore)")
                return null
            }
            else -> {
                unsupported.add("voci di tipo «$type»")
                return null
            }
        }
        (e["weight"] as? Number)?.toLong()?.takeIf { it != 1L }?.let { out["w"] = it }
        conds(e["conditions"])?.let { out["c"] = it }
        fns(e["functions"])?.let { out["f"] = it }
        return out
    }

    // ---------------------------------------------------------------- numeri

    /** Un numero di Java: costante, {uniform min max}, {binomial n p}, {min max} (vecchio formato). */
    private fun number(v: Any?): Any? = when (v) {
        is Number -> v.toDouble()
        is Map<*, *> -> {
            val t = (v["type"] as? String)?.removePrefix("minecraft:")
            when (t) {
                null, "uniform" -> linkedMapOf("u" to listOf(number(v["min"]) ?: 0.0, number(v["max"]) ?: 0.0))
                "constant" -> number(v["value"])
                "binomial" -> linkedMapOf("b" to listOf(number(v["n"]) ?: 0.0, number(v["p"]) ?: 0.0))
                else -> {
                    unsupported.add("numeri di tipo «$t» (si usa 1)")
                    1.0
                }
            }
        }
        else -> null
    }

    // ---------------------------------------------------------------- condizioni

    private fun conds(v: Any?): List<Any?>? = v.asJsonList()?.mapNotNull { cond(it.asJsonObject()) }?.takeIf { it.isNotEmpty() }

    private fun cond(c: Map<String, Any?>?): Map<String, Any?>? {
        c ?: return null
        return when (val t = (c["condition"] as? String)?.removePrefix("minecraft:")) {
            "survives_explosion" -> linkedMapOf("k" to "boom")
            "match_tool" -> tool(c["predicate"].asJsonObject() ?: emptyMap())
            "inverted" -> cond(c["term"].asJsonObject())?.let { linkedMapOf("k" to "not", "c" to it) }
            "any_of", "alternative" -> linkedMapOf("k" to "any", "c" to c["terms"].asJsonList().orEmpty().mapNotNull { cond(it.asJsonObject()) })
            "all_of" -> linkedMapOf("k" to "all", "c" to c["terms"].asJsonList().orEmpty().mapNotNull { cond(it.asJsonObject()) })
            "block_state_property" -> {
                val props = LinkedHashMap<String, Any?>()
                for ((k, v) in c["properties"].asJsonObject().orEmpty()) {
                    props[k] = if (v is Map<*, *>) listOf((v["min"] as? Number)?.toDouble(), (v["max"] as? Number)?.toDouble()) else v.toString()
                }
                linkedMapOf("k" to "state", "p" to props)
            }
            "random_chance" -> linkedMapOf("k" to "chance", "v" to number(c["chance"]))
            "random_chance_with_enchanted_bonus" -> {
                val ench = c["enchanted_chance"]
                val (base, per) = when (ench) {
                    is Number -> ench.toDouble() to 0.0
                    is Map<*, *> -> ((ench["base"] as? Number)?.toDouble() ?: 0.0) to ((ench["per_level_above_first"] as? Number)?.toDouble() ?: 0.0)
                    else -> 0.0 to 0.0
                }
                linkedMapOf(
                    "k" to "echance", "e" to enchant(c["enchantment"] as? String ?: "minecraft:looting"),
                    "un" to ((c["unenchanted_chance"] as? Number)?.toDouble() ?: 0.0), "base" to base, "per" to per
                )
            }
            "table_bonus" -> linkedMapOf(
                "k" to "table", "e" to enchant(c["enchantment"] as? String ?: "minecraft:fortune"),
                "ch" to c["chances"].asJsonList().orEmpty().mapNotNull { (it as? Number)?.toDouble() }
            )
            "weather_check" -> linkedMapOf("k" to "weather", "rain" to c["raining"], "thunder" to c["thundering"])
            "time_check" -> {
                val v = c["value"]
                val (min, max) = when (v) {
                    is Number -> v.toDouble() to v.toDouble()
                    is Map<*, *> -> ((v["min"] as? Number)?.toDouble() ?: 0.0) to ((v["max"] as? Number)?.toDouble() ?: Double.MAX_VALUE)
                    else -> 0.0 to Double.MAX_VALUE
                }
                linkedMapOf("k" to "time", "min" to min, "max" to max, "period" to (c["period"] as? Number)?.toDouble())
            }
            // Per un blocco rotto da un giocatore queste valgono sempre (in Java riguardano chi lo ha rotto o il posto).
            "killed_by_player", "entity_properties", "location_check", "reference", "value_check", "damage_source_properties" -> {
                if (t != "killed_by_player" && t != "entity_properties") unsupported.add("condizioni «$t» (considerate vere)")
                linkedMapOf("k" to "true")
            }
            else -> {
                // Condizioni registrate dalle mod con una probabilità (es. Mythic Metals «random_chance_with_luck»: probabilità
                // più la fortuna del giocatore, che in Bedrock non esiste): vale la probabilità di base, come in Java senza fortuna.
                val chance = c["chance"]
                if (chance is Number || (chance is Map<*, *> && t?.contains("chance") == true)) linkedMapOf("k" to "chance", "v" to number(chance))
                else {
                    unsupported.add("condizioni «$t» (considerate vere)")
                    linkedMapOf("k" to "true")
                }
            }
        }
    }

    /** «match_tool»: oggetti, gruppi e incantesimi dell'attrezzo (formato fino alla 1.20.4 e dalla 1.20.5). */
    private fun tool(pred: Map<String, Any?>): Map<String, Any?> {
        val ids = LinkedHashSet<String>()
        val tags = LinkedHashSet<String>()
        fun addItems(v: Any?) {
            when (v) {
                is String -> if (v.startsWith("#")) addTag(v.removePrefix("#"), ids, tags) else Rl.parse(v)?.let { rl -> resolver.item(rl)?.let { ids.add(it.id) } }
                is List<*> -> v.forEach { addItems(it) }
            }
        }
        addItems(pred["items"])
        addItems(pred["item"])
        (pred["tag"] as? String)?.let { addTag(it, ids, tags) }
        val ench = ArrayList<Any?>()
        val list = pred["enchantments"].asJsonList()
            ?: pred["predicates"].asJsonObject()?.get("minecraft:enchantments").asJsonList()
            ?: pred["predicates"].asJsonObject()?.get("enchantments").asJsonList()
        for (x in list.orEmpty()) {
            val m = x.asJsonObject() ?: continue
            val names = when (val e = m["enchantment"] ?: m["enchantments"]) {
                is String -> listOf(e.removePrefix("#"))
                is List<*> -> e.mapNotNull { it as? String }
                else -> listOf("*")
            }
            val lv = m["levels"]
            val (min, max) = when (lv) {
                is Number -> lv.toDouble() to lv.toDouble()
                is Map<*, *> -> ((lv["min"] as? Number)?.toDouble() ?: 1.0) to ((lv["max"] as? Number)?.toDouble() ?: 255.0)
                else -> 1.0 to 255.0
            }
            ench.add(linkedMapOf("e" to names.map { enchant(it) }, "min" to min, "max" to max))
        }
        val out = linkedMapOf<String, Any?>("k" to "tool")
        val hadItems = pred["items"] != null || pred["item"] != null || pred["tag"] != null
        if (hadItems) {
            out["ids"] = ids.toList(); out["tags"] = tags.toList()
            if (ids.isEmpty() && tags.isEmpty()) unsupported.add("attrezzi richiesti che non esistono in Bedrock")
        }
        if (ench.isNotEmpty()) out["ench"] = ench
        return out
    }

    /** Un gruppo di attrezzi di Java: i gruppi di Bedrock equivalenti (es. picconi) e gli oggetti che ne fanno parte. */
    private fun addTag(tag: String, ids: MutableSet<String>, tags: MutableSet<String>) {
        val rl = Rl.parse(tag) ?: return
        TOOL_TAGS[rl.path]?.let { tags.add(it) }
        if (rl.path.endsWith("shears")) ids.add("minecraft:shears")
        ids.addAll(resolver.tagItems(rl))
    }

    private fun enchant(id: String): String = id.removePrefix("minecraft:")

    // ---------------------------------------------------------------- funzioni

    private fun fns(v: Any?): List<Any?>? = v.asJsonList()?.mapNotNull { fn(it.asJsonObject()) }?.takeIf { it.isNotEmpty() }

    private fun fn(f: Map<String, Any?>?): Map<String, Any?>? {
        f ?: return null
        val out: LinkedHashMap<String, Any?> = when (val t = (f["function"] as? String)?.removePrefix("minecraft:")) {
            "set_count" -> linkedMapOf("k" to "count", "n" to number(f["count"]), "add" to (f["add"] == true))
            "apply_bonus" -> {
                val p = f["parameters"].asJsonObject().orEmpty()
                linkedMapOf(
                    "k" to "bonus", "e" to enchant(f["enchantment"] as? String ?: "minecraft:fortune"),
                    "fo" to (f["formula"] as? String)?.removePrefix("minecraft:"),
                    "m" to (p["bonusMultiplier"] as? Number)?.toDouble(), "x" to (p["extra"] as? Number)?.toDouble(),
                    "pr" to (p["probability"] as? Number)?.toDouble()
                )
            }
            "limit_count" -> {
                val l = f["limit"]
                if (l is Number) linkedMapOf("k" to "limit", "min" to l.toDouble(), "max" to l.toDouble())
                else linkedMapOf("k" to "limit", "min" to number(l.asJsonObject()?.get("min")), "max" to number(l.asJsonObject()?.get("max")))
            }
            "explosion_decay" -> linkedMapOf("k" to "decay")
            "set_damage" -> linkedMapOf("k" to "damage", "n" to number(f["damage"]), "add" to (f["add"] == true))
            "set_name" -> linkedMapOf("k" to "name", "s" to text(f["name"]))
            "set_lore" -> linkedMapOf("k" to "lore", "l" to f["lore"].asJsonList().orEmpty().map { text(it) })
            // Copiano dati del blocco sull'oggetto (nome dato con l'incudine, contenuto): l'oggetto Bedrock resta quello base.
            "copy_name", "copy_nbt", "copy_components", "copy_state", "copy_custom_data", "set_contents", "set_nbt", "set_components" -> return null
            "furnace_smelt" -> {
                unsupported.add("«cotto alla fornace» nei bottini (l'oggetto cade crudo)")
                return null
            }
            else -> {
                unsupported.add("funzioni dei bottini «$t»")
                return null
            }
        }
        conds(f["conditions"])?.let { out["c"] = it }
        return out
    }

    private fun text(v: Any?): String = when (v) {
        is String -> v
        is Map<*, *> -> (v["text"] as? String) ?: (v["translate"] as? String) ?: ""
        is List<*> -> v.joinToString("") { text(it) }
        else -> ""
    }

    companion object {
        /** Gruppi di attrezzi di Java → gruppi di Bedrock. */
        private val TOOL_TAGS = mapOf(
            "pickaxes" to "minecraft:is_pickaxe", "axes" to "minecraft:is_axe", "shovels" to "minecraft:is_shovel",
            "hoes" to "minecraft:is_hoe", "swords" to "minecraft:is_sword", "tools/pickaxe" to "minecraft:is_pickaxe",
            "tools/axe" to "minecraft:is_axe", "tools/shovel" to "minecraft:is_shovel", "tools/hoe" to "minecraft:is_hoe",
            "tools/sword" to "minecraft:is_sword", "tools/pickaxes" to "minecraft:is_pickaxe", "tools/axes" to "minecraft:is_axe",
            "tools/shovels" to "minecraft:is_shovel", "tools/hoes" to "minecraft:is_hoe", "tools/swords" to "minecraft:is_sword",
            "cluster_max_harvestables" to "minecraft:is_pickaxe"
        )

        /** La tabella fa cadere sempre e solo il blocco stesso (come Bedrock da solo): lo script non serve. */
        fun dropsOnlySelf(o: Map<String, Any?>, javaId: Rl): Boolean {
            val pools = o["pools"].asJsonList().orEmpty()
            if (pools.size != 1) return false
            val pool = pools[0].asJsonObject() ?: return false
            if ((pool["rolls"] as? Number)?.toDouble() != 1.0) return false
            if (pool["conditions"].asJsonList().orEmpty().any { (it.asJsonObject()?.get("condition") as? String)?.endsWith("survives_explosion") != true }) return false
            val entries = pool["entries"].asJsonList().orEmpty()
            if (entries.size != 1) return false
            val e = entries[0].asJsonObject() ?: return false
            if ((e["type"] as? String)?.removePrefix("minecraft:") != "item" || Rl.parse(e["name"] as? String) != javaId) return false
            if (e["conditions"].asJsonList().orEmpty().isNotEmpty()) return false
            val okFns = setOf("explosion_decay", "copy_name", "copy_nbt", "copy_components", "copy_state")
            return e["functions"].asJsonList().orEmpty().all { (it.asJsonObject()?.get("function") as? String)?.removePrefix("minecraft:") in okFns } &&
                o["functions"].asJsonList().orEmpty().isEmpty()
        }
    }
}
