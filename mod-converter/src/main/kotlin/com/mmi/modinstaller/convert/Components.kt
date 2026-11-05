package com.mmi.modinstaller.convert

/**
 * BLOCKBRIDGE — registro dei componenti: ogni cosa trovata nella mod (registrazioni nel codice, file del JAR) ha una voce con
 * cosa faceva in Java, cosa è diventata in Bedrock, i test e le differenze. Niente sparisce senza essere registrato.
 */
enum class ComponentStatus(val label: String, val rank: Int) {
    /** Passato con lo stesso significato (dati: texture, nomi, ricette, valori letti dal codice…). */
    CONVERTED("Convertito", 0),
    /** Ricostruito con componenti o Script API di Bedrock: si comporta come l'originale nei limiti dichiarati. */
    RECONSTRUCTED("Ricostruito", 1),
    /** Funziona in Bedrock, ma con le differenze elencate. */
    APPROXIMATED("Approssimato", 2),
    /** Non ricostruito: i dati originali restano nel progetto esportato e il motivo è scritto. */
    DATA_KEPT("Dati conservati", 3),
    /** Bedrock non offre agli add-on nulla che lo possa riprodurre (il motivo è scritto). */
    NOT_RECONSTRUCTABLE("Non ricostruibile", 4)
}

class Component(
    /** Che cos'è: «oggetto», «blocco», «creatura», «ricetta», «suono», «codice», «file»… */
    val kind: String,
    /** Identificatore originale («mod:nome») o percorso del file nel JAR. */
    val originalId: String,
    /** Cosa fa in Java (classe, valori letti). */
    val originalFunction: String,
    /** Da cosa dipende (altre voci, altre mod). */
    val dependencies: List<String>,
    /** Cosa si è fatto per portarlo in Bedrock. */
    val conversion: String,
    /** Identificatore Bedrock, se esiste. */
    val bedrockId: String?,
    /** Cosa fa in Bedrock. */
    val bedrockFunction: String,
    /** Prove che lo riguardano (statiche o nel server Bedrock vero), con il loro esito quando note. */
    val tests: List<String>,
    /** Differenze rispetto all'originale. */
    val differences: List<String>,
    val status: ComponentStatus,
    /** Quanti file o voci rappresenta (per i gruppi di file). */
    val count: Int = 1
)

/** Costruisce il registro dei componenti di una mod a partire da quello che la conversione ha prodotto. */
internal object ComponentRegistry {

    private const val STATIC = "controlli statici dei file (formato e riferimenti)"

    fun item(o: ItemOut, entry: ConvertedEntry, scripts: Boolean = true): Component {
        val diffs = ArrayList<String>()
        val bedrock = ArrayList<String>()
        bedrock.add(entry.detail)
        val tool = o.kind in setOf(ItemKind.SWORD, ItemKind.PICKAXE, ItemKind.AXE, ItemKind.SHOVEL, ItemKind.HOE)
        if (o.effects.isNotEmpty()) {
            if (scripts) bedrock.add("effetti dopo averlo mangiato: " + o.effects.joinToString { it.id })
            else diffs.add("effetti del cibo non inclusi: servono Minecraft 1.21.90 o più nuovo")
        }
        if (tool) {
            if (scripts) bedrock.add("si consuma scavando e colpendo (Script API)")
            else diffs.add("si consuma solo colpendo: l'usura scavando vuole Minecraft 1.21.90 o più nuovo")
        }
        o.places?.let { bedrock.add("piazza il blocco $it") }
        o.convertsTo?.let { bedrock.add("lascia $it") }
        val fromCode = o.code != null
        if (!fromCode && o.kind != ItemKind.ITEM) diffs.add("valori stimati: il codice non si è potuto leggere per questo oggetto")
        val missingFx = o.code?.food?.effects?.count { Behaviors.bedrockEffect(it.effect) == null } ?: 0
        if (missingFx > 0) diffs.add("$missingFx effetti della mod che Bedrock non ha")
        val status = when {
            diffs.isNotEmpty() -> ComponentStatus.APPROXIMATED
            o.effects.isNotEmpty() || o.places != null || tool -> ComponentStatus.RECONSTRUCTED
            else -> ComponentStatus.CONVERTED
        }
        return Component(
            "oggetto", o.java.toString(), o.code?.classes?.firstOrNull()?.substringAfterLast('/')?.let { "classe $it" } ?: o.kind.label,
            emptyList(), if (fromCode) "valori letti dal codice (senza eseguirlo)" else "dal modello e dai gruppi della mod",
            o.bedrockId, bedrock.joinToString("; "), listOf(STATIC), diffs, status
        )
    }

    fun block(o: BlockOut, entry: ConvertedEntry, simplified: String?): Component {
        val diffs = ArrayList<String>()
        simplified?.let { diffs.add(it) }
        val sf = (o.look as? BlockLook.Stateful)?.block
        sf?.note?.let { diffs.add(it) }
        if (o.code == null) diffs.add("durezza e resistenza stimate")
        val status = when {
            diffs.isNotEmpty() -> ComponentStatus.APPROXIMATED
            sf != null || o.code?.xp != null -> ComponentStatus.RECONSTRUCTED
            else -> ComponentStatus.CONVERTED
        }
        val bedrock = if (sf != null) "${sf.family.label} con i suoi stati (${sf.bones.size} varianti di aspetto)" else entry.detail
        return Component(
            "blocco", o.java.toString(), o.code?.classes?.firstOrNull()?.substringAfterLast('/')?.let { "classe $it" } ?: "blocco",
            emptyList(), if (sf != null) "blockstates di Java ricostruiti con gli stati di Bedrock" else "modello e texture",
            o.bedrockId, bedrock, listOf(STATIC), diffs, status
        )
    }

    fun entity(e: EntityOut, entry: ConvertedEntry, spawns: Boolean = false): Component {
        val diffs = ArrayList<String>()
        if (!spawns) diffs.add("compare solo con l'uovo (la regola di comparsa della mod non si è potuta tradurre)")
        if (e.facts?.goals.isNullOrEmpty()) diffs.add("comportamento generico (l'IA della mod non si è potuta leggere)")
        else diffs.add("IA tradotta negli obiettivi di Bedrock più vicini: le azioni speciali scritte in codice no")
        return Component(
            "creatura", e.java.toString(), e.facts?.vanillaBase?.let { "deriva da $it" } ?: "creatura",
            emptyList(), entry.detail, e.bedrockId, entry.detail + if (spawns) "; compare nel mondo nei biomi della mod" else "",
            listOf(STATIC, "nel server Bedrock ufficiale: nasce viva e con la vita giusta (prove sulle mod di esempio)"), diffs, ComponentStatus.APPROXIMATED
        )
    }

    /** Le voci del JAR raggruppate per tipo: ogni file finisce in un gruppo, con quello che se n'è fatto. */
    fun files(mod: JavaModContent, soundsConverted: Boolean = false, entitiesConverted: Boolean = false): List<Component> {
        class G(val label: String, val status: ComponentStatus, val what: String)
        val groups = LinkedHashMap<String, Pair<G, Int>>()
        fun add(key: String, g: G) { groups[key] = g to ((groups[key]?.second ?: 0) + 1) }
        val code = G("Codice Java (classi)", ComponentStatus.APPROXIMATED, "letto senza eseguirlo: registrazioni, valori, IA, modelli e animazioni; la logica delle macchine e degli eventi resta nel JAR")
        for (n in mod.jar.allNames()) {
            val parts = n.split('/')
            val area = parts.getOrNull(2)
            when {
                n.endsWith(".class") -> add("code", code)
                n.startsWith("META-INF/") || n.endsWith(".mixins.json") || n.endsWith("refmap.json") || n.endsWith(".accesswidener") ->
                    add("meta", G("Descrittori, mixin e modifiche al motore", ComponentStatus.NOT_RECONSTRUCTABLE, "Bedrock non permette agli add-on di cambiare il motore del gioco"))
                n.startsWith("assets/") && area == "lang" -> add("lang", G("Testi e traduzioni", ComponentStatus.CONVERTED, "tutte le lingue della mod"))
                n.startsWith("assets/") && area == "models" && parts.getOrNull(3) == "tabula" -> add("tabula", G("Modelli Tabula", ComponentStatus.CONVERTED, "modelli e pose delle creature (camminata, volo, nuoto)"))
                n.startsWith("assets/") && (area == "blockstates" || area == "models" || area == "items") -> add("models", G("Modelli e stati dei blocchi", ComponentStatus.CONVERTED, "forme, varianti e icone"))
                n.startsWith("assets/") && area == "textures" -> add("textures", G("Texture", ComponentStatus.CONVERTED, "quelle usate da oggetti, blocchi, armature e creature"))
                n.startsWith("assets/") && (area == "sounds" || n.endsWith("sounds.json")) -> add(
                    "sounds", if (soundsConverted) G("Suoni", ComponentStatus.CONVERTED, "eventi e file .ogg nel pacchetto di risorse; i versi delle creature dal codice")
                    else G("Suoni", ComponentStatus.DATA_KEPT, "non convertiti: restano nel JAR originale")
                )
                n.startsWith("assets/") && (area == "geo" || area == "animations") -> add("geo", G("Modelli e animazioni GeckoLib", ComponentStatus.CONVERTED, "convertiti per le creature"))
                n.startsWith("assets/") && (area == "shaders" || area == "post_effect") -> add("shaders", G("Shader", ComponentStatus.NOT_RECONSTRUCTABLE, "gli add-on Bedrock non possono usare shader propri"))
                n.startsWith("assets/") && area == "particles" -> add("particles", G("Particelle", ComponentStatus.DATA_KEPT, "non ancora convertite"))
                n.startsWith("data/") && (area == "recipe" || area == "recipes") -> add("recipes", G("Ricette", ComponentStatus.CONVERTED, "quelle che Bedrock sa fare (vedi le ricette saltate)"))
                n.startsWith("data/") && (area == "loot_table" || area == "loot_tables") && parts.getOrNull(3) == "blocks" -> add("loot", G("Bottini dei blocchi", ComponentStatus.CONVERTED, "anche per stato (lastre doppie, colture mature)"))
                n.startsWith("data/") && (area == "loot_table" || area == "loot_tables") && parts.getOrNull(3) == "entities" && entitiesConverted ->
                    add("loot3", G("Bottini delle creature", ComponentStatus.CONVERTED, "quelli delle creature convertite"))
                n.startsWith("data/") && (area == "loot_table" || area == "loot_tables") -> add("loot2", G("Bottini di casse, pesca e altro", ComponentStatus.DATA_KEPT, "non ancora convertiti"))
                n.startsWith("data/") && (area == "tags") -> add("tags", G("Gruppi (tag)", ComponentStatus.CONVERTED, "usati per ricette e attrezzi"))
                n.startsWith("data/") && (area == "structure" || area == "structures") -> add("structures", G("Strutture", ComponentStatus.DATA_KEPT, "non ancora convertite"))
                n.startsWith("data/") && (area == "advancement" || area == "advancements") -> add("adv", G("Progressi", ComponentStatus.DATA_KEPT, "Bedrock non ha progressi per gli add-on"))
                n.startsWith("data/") && area == "worldgen" -> add(
                    "worldgen", G("Generazione del mondo (biomi, minerali, strutture)", ComponentStatus.APPROXIMATED, "i minerali sì; biomi, dimensioni e strutture non ancora")
                )
                n.startsWith("data/") && (area == "dimension" || area == "dimension_type") -> add("dim", G("Dimensioni", ComponentStatus.DATA_KEPT, "non ancora convertite"))
                n.startsWith("data/") && (area == "enchantment" || area == "damage_type" || area == "trim_material" || area == "trim_pattern" || area == "painting_variant" || area == "jukebox_song") ->
                    add("registries", G("Incantesimi, danni e altri registri", ComponentStatus.DATA_KEPT, "non ancora convertiti"))
                n.endsWith("/") -> {}
                else -> add("other", G("Altri file", ComponentStatus.DATA_KEPT, "restano nel JAR originale"))
            }
        }
        return groups.map { (_, p) ->
            val (g, n) = p
            Component("file", g.label, "$n file", emptyList(), g.what, null, g.what, emptyList(), emptyList(), g.status, n)
        }
    }

    fun recipes(ok: Int, skips: Map<String, Int>): List<Component> {
        val out = ArrayList<Component>()
        if (ok > 0) out.add(Component("ricette", "Ricette convertite", "$ok ricette", emptyList(), "tradotte nel formato di Bedrock", null, "si fanno nel banco o nel tavolo giusto", listOf(STATIC), emptyList(), ComponentStatus.CONVERTED, ok))
        for ((why, n) in skips) {
            val status = if (why.contains("macchina") || why.contains("codice")) ComponentStatus.NOT_RECONSTRUCTABLE else ComponentStatus.DATA_KEPT
            out.add(Component("ricette", "Ricette non convertite", "$n ricette", emptyList(), why, null, "—", emptyList(), listOf(why), status, n))
        }
        return out
    }

    /** Il registro in JSON (per l'esportazione del progetto). */
    fun json(components: List<Component>): List<Map<String, Any?>> = components.map { c ->
        linkedMapOf(
            "tipo" to c.kind, "originale" to c.originalId, "funzione_java" to c.originalFunction, "dipendenze" to c.dependencies,
            "conversione" to c.conversion, "bedrock" to c.bedrockId, "funzione_bedrock" to c.bedrockFunction, "prove" to c.tests,
            "differenze" to c.differences, "stato" to c.status.label, "quanti" to c.count.toLong()
        )
    }
}
