package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Un JAR da convertire, con il nome da mostrare. */
class ConvertSource(val file: File, val label: String)

/** Una cosa convertita, per l'elenco del rapporto. */
class ConvertedEntry(val javaId: String, val bedrockId: String, val name: String, val detail: String)

/** Quanto di una mod passa in Bedrock e cosa no. Tutto in italiano, pronto per lo schermo. */
class ModReport(
    val label: String,
    val title: String,
    val loader: String?,
    val version: String?,
    val items: List<ConvertedEntry>,
    val blocks: List<ConvertedEntry>,
    val recipesConverted: Int,
    val recipesTotal: Int,
    /** Motivo → quante ricette (es. «ricetta di una macchina della mod…» → 12). */
    val recipeSkips: Map<String, Int>,
    val lootTables: Int,
    val ores: Int,
    val armorModels: Int,
    /** Creature convertite (modello e animazioni GeckoLib, comportamento generico). */
    val entities: List<ConvertedEntry>,
    /** Valori stimati (resistenze, danni, cibo…) perché in Java stanno nel codice. */
    val estimates: List<String>,
    /** Cose convertite in modo semplificato (forme, animazioni, bottini…). */
    val simplified: List<String>,
    /** Cose della mod che non diventano Bedrock. */
    val notConverted: List<String>,
    val error: String? = null
) {
    val contentCount: Int get() = items.size + blocks.size + entities.size

    /** Una riga di giudizio onesto. */
    val verdict: String
        get() = when {
            error != null -> "Non letta: $error"
            contentCount == 0 -> "Niente da convertire: è una mod di solo codice (o una libreria)"
            notConverted.isEmpty() && recipeSkips.isEmpty() -> "Contenuto convertito"
            else -> "Convertita in parte: il contenuto sì, il comportamento in codice no"
        }
}

/** Il piano: cosa verrà scritto. Tiene aperti i JAR finché non si chiude ([close]). */
class ConversionPlan internal constructor(
    val reports: List<ModReport>,
    internal val mods: List<JavaModContent>,
    internal val items: List<ItemOut>,
    internal val blocks: List<BlockOut>,
    internal val recipes: List<Pair<String, Map<String, Any?>>>,
    internal val loot: Map<String, Map<String, Any?>>,
    internal val features: List<Pair<String, Map<String, Any?>>>,
    internal val entities: List<EntityOut>,
    internal val featureRules: List<Pair<String, Map<String, Any?>>>,
    internal val customTags: Map<String, Set<String>>,
    internal val languages: Map<String, Map<String, String>>
) : java.io.Closeable {
    val itemCount: Int get() = items.size
    val blockCount: Int get() = blocks.size
    val recipeCount: Int get() = recipes.size
    val oreCount: Int get() = features.size
    val entityCount: Int get() = entities.size
    val hasContent: Boolean get() = items.isNotEmpty() || blocks.isNotEmpty() || entities.isNotEmpty()

    override fun close() = mods.forEach { it.jar.close() }
}

/** L'add-on scritto. */
class WrittenAddon(val file: File, val name: String, val sizeBytes: Long, val fileCount: Int, val behaviorUuid: String, val resourceUuid: String)

internal class ItemOut(
    val mod: JavaModContent,
    val java: Rl,
    val bedrockId: String,
    val nameKey: String,
    val icon: Rl?,
    val kind: ItemKind,
    val material: String?,
    val handheld: Boolean,
    val fuelSeconds: Double?,
    val armorTexture: Rl?
)

internal class BlockOut(
    val mod: JavaModContent,
    val java: Rl,
    val bedrockId: String,
    val nameKey: String,
    val look: BlockLook,
    val hardness: Double,
    val resistance: Double,
    val sound: String,
    val toolTags: List<String>,
    val lightEmission: Int,
    val lootPath: String?,
    val orientation: Orientation = Orientation.NONE
)

/**
 * JAVA TO BEDROCK: legge le mod Java (senza eseguirle) e scrive un add-on Bedrock (.mcaddon) con un pacchetto di comportamento
 * (oggetti, blocchi, ricette, bottini, minerali) e uno di risorse (texture, nomi in tutte le lingue della mod, forme dei
 * blocchi, armature). Quello che in Java è codice non si converte: il rapporto lo dice mod per mod.
 */
class ModConverter(private val limits: ConvertLimits = ConvertLimits()) {

    companion object {
        const val FORMAT = "1.21.80"
        val MIN_ENGINE = listOf(1L, 21L, 80L)

        /** Lingue di Bedrock (codice Java → codice Bedrock). */
        val LANGUAGES = linkedMapOf(
            "en_us" to "en_US", "en_gb" to "en_GB", "it_it" to "it_IT", "de_de" to "de_DE", "es_es" to "es_ES", "es_mx" to "es_MX",
            "fr_fr" to "fr_FR", "fr_ca" to "fr_CA", "ja_jp" to "ja_JP", "ko_kr" to "ko_KR", "pt_br" to "pt_BR", "pt_pt" to "pt_PT",
            "ru_ru" to "ru_RU", "zh_cn" to "zh_CN", "zh_tw" to "zh_TW", "nl_nl" to "nl_NL", "bg_bg" to "bg_BG", "cs_cz" to "cs_CZ",
            "da_dk" to "da_DK", "el_gr" to "el_GR", "fi_fi" to "fi_FI", "hu_hu" to "hu_HU", "id_id" to "id_ID", "nb_no" to "nb_NO",
            "pl_pl" to "pl_PL", "sk_sk" to "sk_SK", "sv_se" to "sv_SE", "tr_tr" to "tr_TR", "uk_ua" to "uk_UA"
        )

        /** Identificatore Bedrock: minuscole, cifre e trattini bassi. */
        fun bedrockId(rl: Rl): String =
            rl.ns.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("") + ":" +
                rl.path.map { if (it.isLetterOrDigit() || it == '_' || it == '.') it else '_' }.joinToString("")

        fun textureKey(rl: Rl): String = (rl.ns + "_" + rl.path).map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

        /** Percorso nel pacchetto di risorse; le texture di Minecraft restano quelle di Bedrock (stesso nome, quasi sempre). */
        fun texturePath(rl: Rl): String = if (rl.ns == "minecraft") {
            val p = rl.path
            when {
                p.startsWith("block/") -> "textures/blocks/" + p.removePrefix("block/")
                p.startsWith("item/") -> "textures/items/" + p.removePrefix("item/")
                else -> "textures/" + p
            }
        } else "textures/mmi/" + rl.ns + "/" + rl.path
    }

    // ------------------------------------------------------------------------------------------------------------ analisi

    fun analyze(sources: List<ConvertSource>, cancel: CancelCheck = {}, progress: ProgressCallback? = null): ConversionPlan {
        val mods = ArrayList<JavaModContent>()
        val failed = ArrayList<ModReport>()
        try {
            for ((i, s) in sources.withIndex()) {
                cancel()
                progress?.invoke(i.toLong(), sources.size.toLong(), "Lettura di «${s.label}»")
                try {
                    val jar = JavaJar.open(s.file, s.label, limits, cancel)
                    mods.add(JavaContentReader.read(jar, limits))
                } catch (e: IOException) {
                    failed.add(failedReport(s.label, e.message ?: "file non leggibile"))
                } catch (e: IllegalArgumentException) {
                    failed.add(failedReport(s.label, "non è un archivio JAR valido"))
                }
            }
            progress?.invoke(sources.size.toLong(), sources.size.toLong(), "Preparazione dell'add-on")
            return plan(mods, failed, cancel)
        } catch (t: Throwable) {
            mods.forEach { it.jar.close() }
            throw t
        }
    }

    /** Per le prove: un piano da JAR già aperti (anche in memoria). */
    fun analyzeJars(jars: List<JavaJar>): ConversionPlan = plan(jars.map { JavaContentReader.read(it, limits) }, emptyList()) {}

    private fun failedReport(label: String, why: String) = ModReport(
        label, label, null, null, emptyList(), emptyList(), 0, 0, emptyMap(), 0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), why
    )

    private fun plan(mods: List<JavaModContent>, failed: List<ModReport>, cancel: CancelCheck): ConversionPlan {
        val resolverModels = mods.associateWith { ModelResolver(listOf(it.jar), limits) }
        val usedIds = HashSet<String>()
        fun unique(id: String): String {
            var out = id
            var n = 2
            while (!usedIds.add(out)) out = id + "_" + n++
            return out
        }

        fun exists(t: Rl): Boolean = t.ns == "minecraft" || mods.any { it.jar.has("assets/${t.ns}/textures/${t.path}.png") }

        /** Le texture mancanti nel JAR si sostituiscono con una presente dello stesso blocco; null se non ce n'è nessuna. */
        fun withExistingTextures(look: BlockLook): BlockLook? = when (look) {
            is BlockLook.Cube -> {
                val fallback = look.faces.values.firstOrNull { exists(it) }
                if (fallback == null) null else BlockLook.Cube(look.faces.mapValues { (_, t) -> if (exists(t)) t else fallback }, look.tinted)
            }
            is BlockLook.Cross -> if (exists(look.texture)) look else null
            is BlockLook.Shape -> {
                val fallback = look.textures.values.firstOrNull { exists(it) }
                if (fallback == null) null else BlockLook.Shape(look.elements, look.textures.mapValues { (_, t) -> if (exists(t)) t else fallback })
            }
        }

        // ---- blocchi e oggetti di tutte le mod: servono tutti per tradurre le ricette
        val converted = LinkedHashMap<Rl, String>()
        val convertedItems = HashSet<Rl>()
        val drawnByCode = HashMap<JavaModContent, Int>()
        val unknownShapes = HashMap<JavaModContent, MutableList<String>>()
        val itemOnly = HashMap<JavaModContent, MutableList<JavaItemDef>>()

        /** Un blocco che non si può convertire resta almeno come oggetto, se la mod gli dà un'icona piatta (porte, cartelli…). */
        fun asItemOnly(mod: JavaModContent, id: Rl) {
            val def = mod.blockItems[id] ?: return
            val icon = def.info?.icon ?: return
            if (!exists(icon)) return
            itemOnly.getOrPut(mod) { ArrayList() }.add(def)
        }
        class Pending(val mod: JavaModContent, val blocks: List<Pair<JavaBlockDef, LookResult>>, val items: List<JavaItemDef>)
        val pending = ArrayList<Pending>()
        for (mod in mods) {
            cancel()
            val res = resolverModels.getValue(mod)
            val bl = ArrayList<Pair<JavaBlockDef, LookResult>>()
            for (b in mod.blocks) {
                // Cartelli, letti, casse, stendardi, teste: in Java li disegna sempre il codice.
                val byName = DRAWN_BY_CODE.any { b.id.path.endsWith(it) }
                val raw = if (byName) LookResult(null, drawnByCode = true) else b.model?.let { res.blockLook(it) } ?: LookResult(null)
                if (raw.drawnByCode) {
                    drawnByCode[mod] = (drawnByCode[mod] ?: 0) + 1
                    asItemOnly(mod, b.id)
                    continue
                }
                if (raw.unknownShape != null) {
                    unknownShapes.getOrPut(mod) { ArrayList() }.add(b.id.path.substringAfterLast('/') + " (" + raw.unknownShape.substringAfterLast('/') + ")")
                    asItemOnly(mod, b.id)
                    continue
                }
                val look = raw.look?.let { withExistingTextures(it) } ?: continue
                bl.add(b to LookResult(look, raw.simplified))
                converted[b.id] = unique(bedrockId(b.id))
            }
            val il = ArrayList<JavaItemDef>()
            for (it in itemOnly[mod].orEmpty()) {
                il.add(it)
                converted[it.id] = unique(bedrockId(it.id))
                convertedItems.add(it.id)
            }
            for (it in mod.items) {
                val icon = it.info?.icon ?: continue
                if (!exists(icon)) continue
                il.add(it)
                converted[it.id] = unique(bedrockId(it.id))
                convertedItems.add(it.id)
            }
            pending.add(Pending(mod, bl, il))
        }
        val allTags = LinkedHashMap<Rl, MutableList<String>>()
        for (m in mods) for ((k, v) in m.itemTags) allTags.getOrPut(k) { ArrayList() }.addAll(v)
        val loaded = mods.mapNotNull { it.identity.modId }.toSet() + mods.flatMap { it.namespaces }
        val resolver = ItemResolver(converted, convertedItems, allTags, loaded)
        val recipeConv = RecipeConverter(resolver)
        val lootConv = LootConverter(resolver)

        val itemsOut = ArrayList<ItemOut>()
        val blocksOut = ArrayList<BlockOut>()
        val recipesOut = ArrayList<Pair<String, Map<String, Any?>>>()
        val lootOut = LinkedHashMap<String, Map<String, Any?>>()
        val featuresOut = ArrayList<Pair<String, Map<String, Any?>>>()
        val entitiesOut = ArrayList<EntityOut>()
        val rulesOut = ArrayList<Pair<String, Map<String, Any?>>>()
        val languages = LinkedHashMap<String, MutableMap<String, String>>()
        val reports = ArrayList<ModReport>()

        for (p in pending) {
            cancel()
            val mod = p.mod
            val estimates = LinkedHashSet<String>()
            val simplified = LinkedHashSet<String>()
            val notConverted = ArrayList<String>(mod.codeFeatures)
            val blockTagsOf = invert(mod.blockTags)

            fun names(key: String, fallback: String) {
                val en = mod.lang["en_us"]?.get(key) ?: fallback
                languages.getOrPut("en_US") { LinkedHashMap() }[key] = en
                for ((code, map) in mod.lang) {
                    val bedrock = LANGUAGES[code] ?: continue
                    map[key]?.let { languages.getOrPut(bedrock) { LinkedHashMap() }[key] = it }
                }
            }

            // ---- blocchi
            val blockEntries = ArrayList<ConvertedEntry>()
            val shapeNotes = LinkedHashMap<String, MutableList<String>>()
            var lootCount = 0
            for ((b, look) in p.blocks) {
                val id = converted.getValue(b.id)
                val key = "block." + b.id.ns + "." + b.id.path.replace('/', '.')
                val name = mod.lang["en_us"]?.get(key) ?: titleCase(b.id.path)
                names(key, name)
                val tags = blockTagsOf[b.id.toString()].orEmpty()
                val props = blockProperties(b.id, tags, look.look!!)
                if (props.estimated) estimates.add("Durezza dei blocchi stimata (in Java è nel codice)")
                look.simplified?.let { shapeNotes.getOrPut(it) { ArrayList() }.add(name) }
                val orientedStates = when (b.orientation) { Orientation.FACING -> 4; Orientation.AXIS -> 3; Orientation.NONE -> 1 }
                if (b.facing && b.orientation == Orientation.NONE) simplified.add("Alcuni blocchi orientabili si piazzano sempre rivolti nello stesso verso")
                else if (b.stateCount > orientedStates) simplified.add("I blocchi con più stati in Java (es. colture, interruttori) hanno un solo aspetto")
                var lootPath: String? = null
                mod.blockLoot[b.id]?.let { table ->
                    val r = lootConv.convert(table)
                    lootPath = "loot_tables/blocks/" + b.id.ns + "/" + b.id.path + ".json"
                    lootOut[lootPath!!] = r.table
                    lootCount++
                    if (r.simplified) simplified.add("Bottini dei blocchi senza tocco di velluto, fortuna e condizioni speciali")
                }
                blocksOut.add(BlockOut(mod, b.id, id, key, look.look, props.hardness, props.resistance, props.sound, props.toolTags, props.light, lootPath, b.orientation))
                blockEntries.add(ConvertedEntry(b.id.toString(), id, mod.lang["it_it"]?.get(key) ?: name, describe(look.look)))
            }
            for ((why, list) in shapeNotes) {
                simplified.add(
                    "Forma semplificata (" + why + "): " + list.size + (if (list.size == 1) " blocco" else " blocchi") +
                        ", es. " + list.take(3).joinToString(", ") { "«$it»" }
                )
            }
            val code = drawnByCode[mod] ?: 0
            val asItems = itemOnly[mod]?.size ?: 0
            if (code > 0) notConverted.add("Blocchi disegnati dal codice (cartelli, casse, letti…): $code")
            val unknown = unknownShapes[mod].orEmpty()
            if (unknown.isNotEmpty()) {
                notConverted.add(
                    "Blocchi con forme che l'app non sa ancora riprodurre: " + unknown.size + ", es. " + unknown.take(4).joinToString(", ")
                )
            }
            if (asItems > 0) simplified.add("Blocchi che restano solo come oggetti (non si piazzano): $asItems")
            val skippedBlocks = mod.blocks.size - p.blocks.size - code - unknown.size
            if (skippedBlocks > 0) notConverted.add("Blocchi senza un modello o una texture leggibile: $skippedBlocks")

            // ---- oggetti
            val itemEntries = ArrayList<ConvertedEntry>()
            var armorCount = 0
            for (it in p.items) {
                val id = converted.getValue(it.id)
                val blockOnlyItem = it.id in mod.blockItems
                // Gli oggetti-blocco in Java hanno il nome sotto «block.…».
                val itemKey = "item." + it.id.ns + "." + it.id.path.replace('/', '.')
                val blockKey = "block." + it.id.ns + "." + it.id.path.replace('/', '.')
                val key = if (mod.lang["en_us"]?.containsKey(itemKey) != true && mod.lang.values.any { l -> l.containsKey(blockKey) }) blockKey else itemKey
                val name = mod.lang["en_us"]?.get(key) ?: titleCase(it.id.path)
                names(key, name)
                val material = materialOf(it.id)
                val armorTex = if (it.kind in ARMOR) armorTexture(mod, it.id, it.kind) else null
                if (armorTex != null) armorCount++
                if (it.kind != ItemKind.ITEM) {
                    estimates.add(
                        when (it.kind) {
                            ItemKind.FOOD, ItemKind.DRINK -> "Valori del cibo stimati (in Java sono nel codice)"
                            in ARMOR -> "Protezione e durata delle armature stimate dal materiale"
                            else -> "Danni, velocità e durata degli attrezzi stimati dal materiale"
                        }
                    )
                    if (it.kindSource == KindSource.NAME) estimates.add("Alcuni attrezzi e armature riconosciuti dal nome")
                }
                if (it.kind in ARMOR && armorTex == null) simplified.add("Armature indossabili ma senza il disegno sul corpo (texture non trovata)")
                if (it.info?.layered == true) simplified.add("Icone a più strati: si usa il primo")
                if (it.info?.model3d == true) simplified.add("Oggetti con modello 3D: icona piatta")
                val fuel = mod.fuels[it.id]?.let { t -> t / 20.0 }
                itemsOut.add(ItemOut(mod, it.id, id, key, it.info!!.icon, it.kind, material, it.info.handheld, fuel, armorTex))
                itemEntries.add(ConvertedEntry(it.id.toString(), id, mod.lang["it_it"]?.get(key) ?: name, if (blockOnlyItem) "solo oggetto" else it.kind.label))
            }
            if (mod.spawnEggs.isNotEmpty()) notConverted.add("Uova delle creature: ${mod.spawnEggs.size}")
            val noIcon = mod.items.size - (p.items.size - (itemOnly[mod]?.size ?: 0))
            if (noIcon > 0) notConverted.add("Oggetti senza un'icona leggibile: $noIcon")

            // ---- creature con modello GeckoLib
            val entityEntries = ArrayList<ConvertedEntry>()
            for (e in EntityReader.find(mod, limits)) {
                if (entitiesOut.any { it.bedrockId == e.bedrockId }) continue
                entitiesOut.add(e)
                val en = mod.lang["en_us"]?.get(e.javaNameKey) ?: titleCase(e.java.path)
                val enKey = "entity." + e.bedrockId + ".name"
                val eggKey = "item.spawn_egg.entity." + e.bedrockId + ".name"
                languages.getOrPut("en_US") { LinkedHashMap() }[enKey] = en
                languages.getOrPut("en_US") { LinkedHashMap() }[eggKey] = "$en Spawn Egg"
                for ((code, map) in mod.lang) {
                    val bedrock = LANGUAGES[code] ?: continue
                    val v = map[e.javaNameKey] ?: continue
                    languages.getOrPut(bedrock) { LinkedHashMap() }[enKey] = v
                    languages.getOrPut(bedrock) { LinkedHashMap() }[eggKey] = if (code == "it_it") "Uovo di $v" else "$v Spawn Egg"
                }
                val shown = mod.lang["it_it"]?.get(e.javaNameKey) ?: en
                entityEntries.add(
                    ConvertedEntry(
                        e.java.toString(), e.bedrockId, shown,
                        (if (e.walk != null) "modello e animazioni" else "modello") + ", " + if (e.flying) "vola" else if (e.swimming) "nuota" else "cammina"
                    )
                )
            }
            if (entityEntries.isNotEmpty()) {
                notConverted.removeAll { it.startsWith("Creature:") }
                simplified.add("Creature: aspetto e animazioni della mod, comportamento generico (non attaccano, non hanno versi né cibi speciali, si fanno nascere con l'uovo)")
                val javaCount = mod.lang.values.flatMap { it.keys }.filter { it.startsWith("entity.") }.toSet().size
                if (javaCount > entityEntries.size) notConverted.add("Creature senza un modello leggibile (disegnate dal codice): " + (javaCount - entityEntries.size))
            }

            // ---- ricette
            val skips = LinkedHashMap<String, Int>()
            var ok = 0
            for ((rl, o) in mod.recipes) {
                try {
                    val out = recipeConv.convert(rl, o)
                    recipesOut.addAll(out)
                    ok++
                } catch (s: Skip) {
                    skips[s.reason] = (skips[s.reason] ?: 0) + 1
                }
            }

            // ---- minerali
            var ores = 0
            for ((rl, placed) in mod.placedFeatures) {
                val f = oreFeature(rl, placed, mod, converted) ?: continue
                featuresOut.add(f.first); rulesOut.add(f.second); ores++
            }
            if (ores > 0) simplified.add("I minerali compaiono solo nei chunk nuovi del mondo")

            reports.add(
                ModReport(
                    mod.jar.label, mod.identity.name ?: mod.jar.label, mod.identity.loader, mod.identity.version,
                    itemEntries, blockEntries, ok, mod.recipes.size, skips, lootCount, ores, armorCount, entityEntries,
                    estimates.toList(), simplified.toList(), notConverted
                )
            )
        }
        return ConversionPlan(
            failed + reports, mods, itemsOut, blocksOut, recipesOut, lootOut, featuresOut, entitiesOut, rulesOut,
            resolver.customTags.mapValues { it.value.toSet() }, languages
        )
    }

    private val ARMOR = setOf(ItemKind.HELMET, ItemKind.CHESTPLATE, ItemKind.LEGGINGS, ItemKind.BOOTS)

    private val DRAWN_BY_CODE = listOf("_sign", "_bed", "_banner", "_wall_banner", "chest", "_skull", "_head", "_wall_skull", "_wall_head")

    private fun invert(tags: Map<Rl, List<String>>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for ((t, values) in tags) for (v in values) out.getOrPut(v) { ArrayList() }.add(t.toString())
        return out
    }

    private fun titleCase(path: String): String =
        path.substringAfterLast('/').split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    private fun describe(look: BlockLook): String = when (look) {
        is BlockLook.Cube -> "blocco"
        is BlockLook.Cross -> "pianta"
        is BlockLook.Shape -> "blocco con forma propria"
    }

    private class BlockProps(val hardness: Double, val resistance: Double, val sound: String, val toolTags: List<String>, val light: Int, val estimated: Boolean)

    private fun blockProperties(id: Rl, tags: List<String>, look: BlockLook): BlockProps {
        val p = id.path
        val pick = tags.any { it.endsWith("mineable/pickaxe") }
        val axe = tags.any { it.endsWith("mineable/axe") }
        val shovel = tags.any { it.endsWith("mineable/shovel") }
        val hoe = tags.any { it.endsWith("mineable/hoe") }
        val leaves = tags.any { it == "minecraft:leaves" } || p.endsWith("leaves")
        val ore = p.contains("_ore") || p.endsWith("ore") || tags.any { it.contains(":ores") }
        val metal = p.endsWith("_block") && (ore || tags.any { it.contains("storage_blocks") })
        val hardness = when {
            look is BlockLook.Cross -> 0.0
            leaves -> 0.2
            ore && p.contains("deepslate") -> 4.5
            ore -> 3.0
            metal -> 5.0
            tags.any { it.endsWith("needs_diamond_tool") } -> 5.0
            pick -> 1.5
            axe -> 2.0
            shovel -> 0.6
            hoe -> 0.5
            else -> 1.0
        }
        val resistance = when {
            look is BlockLook.Cross || leaves -> 0.2
            metal || ore -> 6.0
            pick -> 6.0
            else -> 3.0
        }
        val glass = p.contains("glass")
        val sound = when {
            look is BlockLook.Cross || leaves -> "grass"
            glass -> "glass"
            metal -> "metal"
            axe -> "wood"
            shovel -> if (p.contains("sand")) "sand" else "gravel"
            p.contains("wool") || p.contains("carpet") -> "cloth"
            else -> "stone"
        }
        val toolTags = ArrayList<String>()
        if (pick || ore || metal) { toolTags.add("stone"); toolTags.add("minecraft:is_pickaxe_item_destructible") }
        if (axe) { toolTags.add("wood"); toolTags.add("minecraft:is_axe_item_destructible") }
        if (shovel) { toolTags.add("dirt"); toolTags.add("minecraft:is_shovel_item_destructible") }
        if (hoe || leaves) toolTags.add("minecraft:is_hoe_item_destructible")
        val light = if (p.contains("lamp") || p.contains("lantern") || p.contains("glowstone") || p.contains("torch") || p.contains("light")) 15 else 0
        return BlockProps(hardness, resistance, sound, toolTags.distinct(), light, true)
    }

    private val MATERIALS = listOf("netherite", "diamond", "iron", "golden", "gold", "stone", "wooden", "wood", "copper", "leather", "chainmail")

    private fun materialOf(id: Rl): String? {
        val p = id.path.substringAfterLast('/')
        return MATERIALS.firstOrNull { p.startsWith(it + "_") || p.contains("_" + it + "_") }
    }

    /** La texture dell'armatura sul corpo: equipment (1.21.2+) o textures/models/armor (prima). */
    private fun armorTexture(mod: JavaModContent, id: Rl, kind: ItemKind): Rl? {
        val p = id.path.substringAfterLast('/')
        val mat = p.substringBeforeLast('_')
        val legs = kind == ItemKind.LEGGINGS
        val names = mod.jar.allNames().toList()
        // 1.21.2+: assets/<ns>/equipment/<mat>.json → textures/entity/equipment/humanoid(_leggings)/<tex>.png
        val eq = mod.jar.json("assets/${id.ns}/equipment/$mat.json", limits.maxJsonBytes)?.asJsonObject()?.get("layers").asJsonObject()
        if (eq != null) {
            val layer = eq[if (legs) "humanoid_leggings" else "humanoid"].asJsonList()?.firstOrNull().asJsonObject()?.get("texture") as? String
            val rl = Rl.parse(layer, id.ns)
            if (rl != null) {
                val path = "assets/${rl.ns}/textures/entity/equipment/${if (legs) "humanoid_leggings" else "humanoid"}/${rl.path}.png"
                if (path in names) return Rl(rl.ns, "entity/equipment/${if (legs) "humanoid_leggings" else "humanoid"}/${rl.path}")
            }
        }
        for (cand in listOf("assets/${id.ns}/textures/entity/equipment/${if (legs) "humanoid_leggings" else "humanoid"}/$mat.png")) {
            if (cand in names) return Rl(id.ns, cand.removePrefix("assets/${id.ns}/textures/").removeSuffix(".png"))
        }
        val layer = if (legs) "2" else "1"
        val old = "assets/${id.ns}/textures/models/armor/${mat}_layer_$layer.png"
        if (old in names) return Rl(id.ns, "models/armor/${mat}_layer_$layer")
        val loose = names.firstOrNull { it.startsWith("assets/${id.ns}/textures/models/armor/") && it.endsWith("_layer_$layer.png") && it.substringAfterLast('/').startsWith(mat) }
        return loose?.let { Rl(id.ns, it.removePrefix("assets/${id.ns}/textures/").removeSuffix(".png")) }
    }

    // ------------------------------------------------------------------------------------------------------------ minerali

    private fun oreFeature(
        rl: Rl,
        placed: Map<String, Any?>,
        mod: JavaModContent,
        converted: Map<Rl, String>
    ): Pair<Pair<String, Map<String, Any?>>, Pair<String, Map<String, Any?>>>? {
        val featureRef = placed["feature"]
        val configured = when (featureRef) {
            is String -> Rl.parse(featureRef)?.let { mod.configuredFeatures[it] }
            is Map<*, *> -> featureRef.asJsonObject()
            else -> null
        } ?: return null
        val type = (configured["type"] as? String)?.removePrefix("minecraft:")
        if (type != "ore" && type != "scattered_ore") return null
        val config = configured["config"].asJsonObject() ?: return null
        val size = (config["size"] as? Number)?.toLong() ?: return null
        val rules = ArrayList<Any?>()
        var dimension = "overworld"
        for (t in config["targets"].asJsonList().orEmpty()) {
            val to = t.asJsonObject() ?: continue
            val block = Rl.parse(to["state"].asJsonObject()?.get("Name") as? String) ?: continue
            val placesBlock = converted[block] ?: Vanilla.item(block)?.id ?: continue
            val target = to["target"].asJsonObject() ?: continue
            val replace: List<String>? = when ((target["predicate_type"] as? String)?.removePrefix("minecraft:")) {
                "tag_match" -> Rl.parse(target["tag"] as? String)?.let { Vanilla.replaceables(it) }
                "block_match" -> Rl.parse(target["block"] as? String)?.let { b -> Vanilla.item(b)?.id?.removePrefix("minecraft:")?.let { listOf(it) } }
                else -> null
            }
            if (replace == null) continue
            if (replace.any { it == "netherrack" || it == "basalt" || it == "blackstone" }) dimension = "nether"
            if (replace.any { it == "end_stone" }) dimension = "the_end"
            rules.add(linkedMapOf("places_block" to placesBlock, "may_replace" to replace.map { "minecraft:$it" }))
        }
        if (rules.isEmpty()) return null
        // Dove: i modificatori dei biomi di NeoForge/Forge, se ci sono, dicono la dimensione.
        for (m in mod.biomeModifiers) {
            val features = m["features"]
            val mentions = (features as? String) == rl.toString() || features.asJsonList()?.contains(rl.toString()) == true
            if (!mentions) continue
            val biomes = m["biomes"] as? String ?: continue
            dimension = when {
                biomes.contains("nether") -> "nether"
                biomes.contains("end") -> "the_end"
                else -> "overworld"
            }
        }
        var iterations: Any = 1L
        var minY = if (dimension == "overworld") -64L else 0L
        var maxY = if (dimension == "overworld") 128L else if (dimension == "nether") 128L else 256L
        var triangle = false
        var chance: Long? = null
        val bottom = if (dimension == "overworld") -64L else 0L
        val top = if (dimension == "overworld") 320L else if (dimension == "nether") 128L else 256L
        for (pl in placed["placement"].asJsonList().orEmpty()) {
            val po = pl.asJsonObject() ?: continue
            when ((po["type"] as? String)?.removePrefix("minecraft:")) {
                "count" -> iterations = countValue(po["count"]) ?: 1L
                "rarity_filter" -> chance = (po["chance"] as? Number)?.toLong()
                "height_range" -> {
                    val h = po["height"].asJsonObject() ?: continue
                    triangle = (h["type"] as? String)?.endsWith("trapezoid") == true
                    anchor(h["min_inclusive"], bottom, top)?.let { minY = it }
                    anchor(h["max_inclusive"], bottom, top)?.let { maxY = it }
                }
            }
        }
        if (maxY < minY) { val t = minY; minY = maxY; maxY = t }
        val featureId = bedrockId(Rl(rl.ns, rl.path.substringAfterLast('/') + "_feature")).substringAfter(':')
        val featureFull = rl.ns + ":" + featureId
        val feature = linkedMapOf<String, Any?>(
            "format_version" to "1.13.0",
            "minecraft:ore_feature" to linkedMapOf(
                "description" to linkedMapOf("identifier" to featureFull), "count" to size, "replace_rules" to rules
            )
        )
        val distribution = linkedMapOf<String, Any?>(
            "iterations" to iterations,
            "coordinate_eval_order" to "zyx",
            "x" to linkedMapOf("distribution" to "uniform", "extent" to listOf(0L, 15L)),
            "y" to linkedMapOf("distribution" to (if (triangle) "triangle" else "uniform"), "extent" to listOf(minY, maxY)),
            "z" to linkedMapOf("distribution" to "uniform", "extent" to listOf(0L, 15L))
        )
        if (chance != null && chance > 1) distribution["scatter_chance"] = linkedMapOf("numerator" to 1L, "denominator" to chance)
        val ruleId = featureId.removeSuffix("_feature") + "_rule"
        val rule = linkedMapOf<String, Any?>(
            "format_version" to "1.13.0",
            "minecraft:feature_rules" to linkedMapOf(
                "description" to linkedMapOf("identifier" to rl.ns + ":" + ruleId, "places_feature" to featureFull),
                "conditions" to linkedMapOf(
                    "placement_pass" to "underground_pass",
                    "minecraft:biome_filter" to listOf(linkedMapOf("test" to "has_biome_tag", "operator" to "==", "value" to dimension))
                ),
                "distribution" to distribution
            )
        )
        return ("features/$featureId.json" to feature) to ("feature_rules/$ruleId.json" to rule)
    }

    private fun countValue(v: Any?): Any? = when (v) {
        is Number -> v.toLong()
        is Map<*, *> -> {
            val inner = (v["value"] as? Map<*, *>) ?: v
            val min = (inner["min_inclusive"] as? Number)?.toLong(); val max = (inner["max_inclusive"] as? Number)?.toLong()
            if (min != null && max != null) "math.random_integer($min, $max)" else null
        }
        else -> null
    }

    private fun anchor(v: Any?, bottom: Long, top: Long): Long? {
        val o = v.asJsonObject() ?: return (v as? Number)?.toLong()
        (o["absolute"] as? Number)?.let { return it.toLong() }
        (o["above_bottom"] as? Number)?.let { return bottom + it.toLong() }
        (o["below_top"] as? Number)?.let { return top - it.toLong() }
        return null
    }

    // ------------------------------------------------------------------------------------------------------------ scrittura

    /**
     * Scrive l'add-on in [out] (un .mcaddon con le cartelle «<nome> BP» e «<nome> RP»). Gli identificatori dei pacchetti
     * dipendono dal nome, la versione dall'ora: convertire di nuovo con lo stesso nome aggiorna l'add-on in Minecraft.
     */
    fun write(plan: ConversionPlan, name: String, out: File, cancel: CancelCheck = {}, progress: ProgressCallback? = null): WrittenAddon {
        if (!plan.hasContent) throw IOException("Non c'è niente da convertire in Bedrock")
        val writer = AddonWriter(plan, cleanName(name), limits, cancel, progress)
        val tmp = File(out.parentFile, out.name + ".part")
        try {
            FileOutputStream(tmp).use { fos -> ZipOutputStream(fos).use { writer.writeTo(it) } }
            if (out.exists() && !out.delete()) throw IOException("Non riesco a sostituire «${out.name}»")
            if (!tmp.renameTo(out)) throw IOException("Non riesco a salvare «${out.name}»")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return WrittenAddon(out, writer.name, out.length(), writer.count, writer.bpUuid, writer.rpUuid)
    }

    fun cleanName(name: String): String {
        val t = name.trim().map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '.') it else ' ' }.joinToString("")
            .replace(Regex(" +"), " ").trim().take(48)
        return t.ifEmpty { "Mod Java" }
    }

    /** Il contenuto JSON dei file più importanti, per le prove (percorso nell'add-on → testo). */
    fun preview(plan: ConversionPlan, name: String): Map<String, ByteArray> {
        val w = AddonWriter(plan, cleanName(name), limits, {}, null)
        val map = LinkedHashMap<String, ByteArray>()
        w.collect { path, bytes -> map[path] = bytes }
        return map
    }

    internal fun json(v: Any?): ByteArray = Json.stringify(v, pretty = true).toByteArray(Charsets.UTF_8)
}

/** Scrive i file dell'add-on, uno alla volta. */
internal class AddonWriter(
    private val plan: ConversionPlan,
    val name: String,
    private val limits: ConvertLimits,
    private val cancel: CancelCheck,
    private val progress: ProgressCallback?
) {
    val bpUuid: String = UUID.nameUUIDFromBytes(("mmi-bp:" + name.lowercase()).toByteArray()).toString()
    val rpUuid: String = UUID.nameUUIDFromBytes(("mmi-rp:" + name.lowercase()).toByteArray()).toString()
    private val bpModule = UUID.nameUUIDFromBytes(("mmi-bp-module:" + name.lowercase()).toByteArray()).toString()
    private val rpModule = UUID.nameUUIDFromBytes(("mmi-rp-module:" + name.lowercase()).toByteArray()).toString()
    private val version = listOf(1L, 0L, (System.currentTimeMillis() / 60000L) % 100_000_000L)
    var count = 0
        private set

    private val bp = "$name BP/"
    private val rp = "$name RP/"

    private fun json(v: Any?): ByteArray = Json.stringify(v, pretty = true).toByteArray(Charsets.UTF_8)

    fun writeTo(zip: ZipOutputStream) {
        val seen = HashSet<String>()
        collect { path, bytes ->
            if (!seen.add(path)) return@collect
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
            count++
        }
    }

    /** Produce tutti i file: [sink] riceve percorso e contenuto. */
    fun collect(sink: (String, ByteArray) -> Unit) {
        val total = (plan.items.size + plan.blocks.size).toLong()
        var done = 0L
        val textures = TextureCollector(plan.mods, limits)

        // ---- manifest
        val description = "Convertito da Minecraft Java con Minecraft Mod Installer: " +
            plan.reports.filter { it.contentCount > 0 }.joinToString(", ") { it.title }.take(200)
        sink(bp + "manifest.json", json(manifest("$name (comportamento)", description, bpUuid, bpModule, "data", rpUuid)))
        sink(rp + "manifest.json", json(manifest("$name (risorse)", description, rpUuid, rpModule, "resources", null)))
        val icon = packIcon()
        sink(bp + "pack_icon.png", icon)
        sink(rp + "pack_icon.png", icon)

        // ---- oggetti
        val itemTextureData = LinkedHashMap<String, Any?>()
        val tagsByItem = HashMap<String, MutableList<String>>()
        for ((tag, items) in plan.customTags) for (i in items) tagsByItem.getOrPut(i) { ArrayList() }.add(tag)
        for (it in plan.items) {
            cancel()
            progress?.invoke(done++, total, "Oggetti e blocchi")
            val iconRl = it.icon!!
            val key = ModConverter.textureKey(iconRl)
            textures.require(iconRl, block = false)
            itemTextureData[key] = linkedMapOf("textures" to ModConverter.texturePath(iconRl))
            sink(bp + "items/" + it.bedrockId.replace(':', '/') + ".json", json(itemDoc(it, key, tagsByItem[it.bedrockId].orEmpty())))
            if (it.armorTexture != null) {
                textures.require(it.armorTexture, block = false)
                sink(rp + "attachables/" + it.bedrockId.replace(':', '/') + ".json", json(attachable(it)))
            }
        }

        // ---- blocchi
        val terrain = LinkedHashMap<String, Any?>()
        val blocksJson = linkedMapOf<String, Any?>("format_version" to listOf(1L, 1L, 0L))
        for (b in plan.blocks) {
            cancel()
            progress?.invoke(done++, total, "Oggetti e blocchi")
            val instances = LinkedHashMap<String, Rl>()
            var geometry: Any = "minecraft:geometry.full_block"
            var collision: Any? = null
            when (val look = b.look) {
                is BlockLook.Cube -> {
                    val up = look.faces["up"]!!; val north = look.faces["north"]!!
                    instances["*"] = north
                    for ((d, t) in look.faces) if (t != north) instances[d] = t
                    if (look.faces["down"] != north) instances["down"] = look.faces["down"]!!
                    if (up != north) instances["up"] = up
                }
                is BlockLook.Cross -> {
                    geometry = "minecraft:geometry.cross"
                    instances["*"] = look.texture
                    collision = false
                }
                is BlockLook.Shape -> {
                    val geoId = "geometry." + b.bedrockId.replace(':', '.')
                    sink(rp + "models/blocks/" + b.bedrockId.replace(':', '/') + ".geo.json", json(Geometry.document(geoId, look.elements)))
                    geometry = geoId
                    for ((v, t) in look.textures) instances[Geometry.materialName(v)] = t
                    instances["*"] = look.textures.values.first()
                    val box = Geometry.bounds(look.elements)
                    collision = if (box != null) linkedMapOf("origin" to box.first.map { n(it) }, "size" to box.second.map { n(it) }) else false
                }
            }
            val transparency = instances.values.distinct().map { t -> textures.require(t, block = true) }
            val render = when {
                // Dalla 1.21.70 la forma a X di Minecraft vuole un modo di disegno con le facce posteriori nascoste.
                b.look is BlockLook.Cross -> "alpha_test_single_sided"
                transparency.contains(Transparency.TRANSLUCENT) -> "blend"
                transparency.contains(Transparency.CUTOUT) -> "alpha_test"
                else -> "opaque"
            }
            val tinted = (b.look as? BlockLook.Cube)?.tinted == true || (b.look as? BlockLook.Cross)?.tinted == true
            val mi = LinkedHashMap<String, Any?>()
            for ((k, t) in instances.entries.take(64)) {
                val key = ModConverter.textureKey(t)
                terrain[key] = linkedMapOf("textures" to ModConverter.texturePath(t))
                val inst = linkedMapOf<String, Any?>("texture" to key, "render_method" to render)
                if (tinted && textures.isGray(t) && render != "blend") inst["tint_method"] = if (b.java.path.contains("grass")) "grass" else "default_foliage"
                mi[k] = inst
            }
            sink(bp + "blocks/" + b.bedrockId.replace(':', '/') + ".json", json(blockDoc(b, geometry, mi, collision)))
            blocksJson[b.bedrockId] = linkedMapOf("sound" to b.sound)
        }

        // ---- creature
        for (e in plan.entities) {
            cancel()
            val geo = EntityReader.geometryBytes(e.mod, e, limits) ?: continue
            val path = e.bedrockId.replace(':', '/')
            sink(rp + "models/entity/$path.geo.json", geo)
            e.animEntry?.let { a ->
                e.mod.jar.json(a, limits.maxJsonBytes)?.asJsonObject()?.let { doc -> sink(rp + "animations/$path.animation.json", json(EntityReader.cleanAnimations(doc))) }
            }
            EntityReader.controller(e)?.let { sink(rp + "animation_controllers/$path.controller.json", json(it)) }
            textures.require(e.texture, block = false)
            val egg = EntityReader.eggColors(e.mod.jar.bytes("assets/${e.texture.ns}/textures/${e.texture.path}.png", limits.maxPngBytes))
            sink(rp + "entity/$path.entity.json", json(EntityReader.client(e, egg)))
            sink(bp + "entities/$path.json", json(EntityReader.behavior(e)))
        }

        // ---- ricette, bottini, minerali
        for ((path, doc) in plan.recipes) sink(bp + "recipes/" + path, json(doc))
        for ((path, doc) in plan.loot) sink(bp + path, json(doc))
        for ((path, doc) in plan.features) sink(bp + path, json(doc))
        for ((path, doc) in plan.featureRules) sink(bp + path, json(doc))

        // ---- risorse
        if (itemTextureData.isNotEmpty()) {
            sink(rp + "textures/item_texture.json", json(linkedMapOf("resource_pack_name" to name, "texture_name" to "atlas.items", "texture_data" to itemTextureData)))
        }
        if (terrain.isNotEmpty()) {
            sink(
                rp + "textures/terrain_texture.json",
                json(linkedMapOf("resource_pack_name" to name, "texture_name" to "atlas.terrain", "padding" to 8L, "num_mip_levels" to 4L, "texture_data" to terrain))
            )
            sink(rp + "blocks.json", json(blocksJson))
        }
        val flipbooks = ArrayList<Any?>()
        textures.writeAll { rl, bytes, frames ->
            sink(rp + ModConverter.texturePath(rl) + ".png", bytes)
            if (frames != null) {
                flipbooks.add(linkedMapOf("flipbook_texture" to ModConverter.texturePath(rl), "atlas_tile" to ModConverter.textureKey(rl), "ticks_per_frame" to frames.toLong()))
            }
        }
        if (flipbooks.isNotEmpty()) sink(rp + "textures/flipbook_textures.json", json(flipbooks))
        val langs = ArrayList<String>()
        for ((code, map) in plan.languages) {
            val sb = StringBuilder()
            sb.append("## ").append(name).append(" — testi convertiti dalle mod Java\n")
            for ((k, v) in map) sb.append(k).append('=').append(v.replace('\n', ' ').replace('\r', ' ')).append('\n')
            sink(rp + "texts/$code.lang", sb.toString().toByteArray(Charsets.UTF_8))
            langs.add(code)
        }
        sink(rp + "texts/languages.json", json(langs))
        progress?.invoke(total, total, "Fatto")
    }

    private fun n(v: Double): Any = if (v == Math.floor(v)) v.toLong() else v

    private fun manifest(title: String, description: String, uuid: String, module: String, type: String, dependency: String?): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "format_version" to 2L,
            "header" to linkedMapOf(
                "name" to title, "description" to description, "uuid" to uuid, "version" to version, "min_engine_version" to ModConverter.MIN_ENGINE
            ),
            "modules" to listOf(linkedMapOf("type" to type, "uuid" to module, "version" to version))
        )
        if (dependency != null) m["dependencies"] = listOf(linkedMapOf("uuid" to dependency, "version" to version))
        return m
    }

    private fun packIcon(): ByteArray {
        for (mod in plan.mods) {
            val p = mod.identity.iconPath ?: continue
            val bytes = mod.jar.bytes(p.removePrefix("/"), 1 shl 20) ?: continue
            if (Png.isPng(bytes)) return bytes
        }
        // Un'icona semplice: un cubo verde con il bordo scuro.
        val size = 64
        val px = ByteArray(size * size * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val o = (y * size + x) * 4
            val edge = x < 4 || y < 4 || x >= size - 4 || y >= size - 4
            val top = y < 24
            px[o] = (if (edge) 30 else if (top) 90 else 120).toByte()
            px[o + 1] = (if (edge) 60 else if (top) 170 else 90).toByte()
            px[o + 2] = (if (edge) 30 else if (top) 60 else 50).toByte()
            px[o + 3] = 255.toByte()
        }
        return Png.encode(Image(size, size, px))
    }

    private fun itemDoc(it: ItemOut, textureKey: String, extraTags: List<String>): Map<String, Any?> {
        val c = LinkedHashMap<String, Any?>()
        c["minecraft:icon"] = textureKey
        c["minecraft:display_name"] = linkedMapOf("value" to it.nameKey)
        val stats = Stats.of(it.kind, it.material)
        val tags = ArrayList<String>(extraTags)
        val category: String
        when (it.kind) {
            ItemKind.SWORD, ItemKind.PICKAXE, ItemKind.AXE, ItemKind.SHOVEL, ItemKind.HOE -> {
                category = "equipment"
                c["minecraft:max_stack_size"] = 1L
                c["minecraft:hand_equipped"] = true
                c["minecraft:durability"] = linkedMapOf("max_durability" to stats.durability)
                c["minecraft:damage"] = stats.damage
                c["minecraft:enchantable"] = linkedMapOf("slot" to stats.enchantSlot, "value" to stats.enchantValue)
                val blockTags = when (it.kind) {
                    ItemKind.PICKAXE -> listOf("stone", "metal", "minecraft:is_pickaxe_item_destructible")
                    ItemKind.AXE -> listOf("wood", "minecraft:is_axe_item_destructible")
                    ItemKind.SHOVEL -> listOf("dirt", "sand", "gravel", "grass", "snow", "minecraft:is_shovel_item_destructible")
                    ItemKind.HOE -> listOf("minecraft:is_hoe_item_destructible")
                    else -> emptyList()
                }
                if (blockTags.isNotEmpty()) {
                    c["minecraft:digger"] = linkedMapOf(
                        "use_efficiency" to true,
                        "destroy_speeds" to listOf(linkedMapOf("block" to linkedMapOf("tags" to "q.any_tag(" + blockTags.joinToString(", ") { t -> "'$t'" } + ")"), "speed" to stats.speed))
                    )
                }
                tags.add("minecraft:is_tool")
                tags.add(
                    when (it.kind) {
                        ItemKind.SWORD -> "minecraft:is_sword"; ItemKind.PICKAXE -> "minecraft:is_pickaxe"; ItemKind.AXE -> "minecraft:is_axe"
                        ItemKind.SHOVEL -> "minecraft:is_shovel"; else -> "minecraft:is_hoe"
                    }
                )
                stats.tier?.let { t -> tags.add("minecraft:${t}_tier") }
            }
            ItemKind.HELMET, ItemKind.CHESTPLATE, ItemKind.LEGGINGS, ItemKind.BOOTS -> {
                category = "equipment"
                c["minecraft:max_stack_size"] = 1L
                c["minecraft:durability"] = linkedMapOf("max_durability" to stats.durability)
                c["minecraft:wearable"] = linkedMapOf("slot" to stats.slot, "protection" to stats.protection)
                c["minecraft:enchantable"] = linkedMapOf("slot" to stats.enchantSlot, "value" to stats.enchantValue)
                tags.add("minecraft:is_armor")
                tags.add("minecraft:trimmable_armors")
            }
            ItemKind.FOOD, ItemKind.DRINK -> {
                category = "items"
                c["minecraft:max_stack_size"] = if (it.kind == ItemKind.DRINK) 16L else 64L
                c["minecraft:food"] = linkedMapOf("nutrition" to 4L, "saturation_modifier" to 0.6, "can_always_eat" to (it.kind == ItemKind.DRINK))
                c["minecraft:use_animation"] = if (it.kind == ItemKind.DRINK) "drink" else "eat"
                c["minecraft:use_modifiers"] = linkedMapOf("use_duration" to 1.6, "movement_modifier" to 0.35)
            }
            else -> {
                category = "items"
                c["minecraft:max_stack_size"] = 64L
                if (it.handheld) c["minecraft:hand_equipped"] = true
            }
        }
        it.fuelSeconds?.let { s -> c["minecraft:fuel"] = linkedMapOf("duration" to maxOf(0.05, s)) }
        if (tags.isNotEmpty()) c["minecraft:tags"] = linkedMapOf("tags" to tags.distinct())
        return linkedMapOf(
            "format_version" to ModConverter.FORMAT,
            "minecraft:item" to linkedMapOf(
                "description" to linkedMapOf("identifier" to it.bedrockId, "menu_category" to linkedMapOf("category" to category)),
                "components" to c
            )
        )
    }

    private fun attachable(it: ItemOut): Map<String, Any?> {
        val (geometry, script) = when (it.kind) {
            ItemKind.HELMET -> "geometry.humanoid.armor.helmet" to "variable.helmet_layer_visible = 0.0;"
            ItemKind.CHESTPLATE -> "geometry.humanoid.armor.chestplate" to "variable.chest_layer_visible = 0.0;"
            ItemKind.LEGGINGS -> "geometry.humanoid.armor.leggings" to "variable.leg_layer_visible = 0.0;"
            else -> "geometry.humanoid.armor.boots" to "variable.boot_layer_visible = 0.0;"
        }
        return linkedMapOf(
            "format_version" to "1.8.0",
            "minecraft:attachable" to linkedMapOf(
                "description" to linkedMapOf(
                    "identifier" to it.bedrockId,
                    "materials" to linkedMapOf("default" to "armor", "enchanted" to "armor_enchanted"),
                    "textures" to linkedMapOf("default" to ModConverter.texturePath(it.armorTexture!!), "enchanted" to "textures/misc/enchanted_actor_glint"),
                    "geometry" to linkedMapOf("default" to geometry),
                    "scripts" to linkedMapOf("parent_setup" to script),
                    "render_controllers" to listOf("controller.render.armor")
                )
            )
        )
    }

    private fun blockDoc(b: BlockOut, geometry: Any, materials: Map<String, Any?>, collision: Any?): Map<String, Any?> {
        val c = LinkedHashMap<String, Any?>()
        c["minecraft:display_name"] = b.nameKey
        c["minecraft:geometry"] = geometry
        c["minecraft:material_instances"] = materials
        c["minecraft:destructible_by_mining"] = linkedMapOf("seconds_to_destroy" to b.hardness)
        c["minecraft:destructible_by_explosion"] = linkedMapOf("explosion_resistance" to b.resistance)
        if (b.lightEmission > 0) c["minecraft:light_emission"] = b.lightEmission.toLong()
        if (b.look !is BlockLook.Cube) {
            c["minecraft:light_dampening"] = 0L
            if (collision != null) {
                c["minecraft:collision_box"] = collision
                c["minecraft:selection_box"] = if (collision == false) linkedMapOf("origin" to listOf(-6L, 0L, -6L), "size" to listOf(12L, 13L, 12L)) else collision
            }
        }
        b.lootPath?.let { c["minecraft:loot"] = it }
        for (t in b.toolTags) c["tag:$t"] = linkedMapOf<String, Any?>()
        val category = if (b.look is BlockLook.Cross) "nature" else "construction"
        val description = linkedMapOf<String, Any?>("identifier" to b.bedrockId, "menu_category" to linkedMapOf("category" to category))
        val block = linkedMapOf<String, Any?>("description" to description, "components" to c)
        when (b.orientation) {
            // Come la fornace: il lato «davanti» (nord nel modello Java) guarda il giocatore che piazza il blocco.
            Orientation.FACING -> {
                description["traits"] = linkedMapOf(
                    "minecraft:placement_direction" to linkedMapOf("enabled_states" to listOf("minecraft:cardinal_direction"), "y_rotation_offset" to 180L)
                )
                block["permutations"] = listOf("north" to 0L, "west" to 90L, "south" to 180L, "east" to -90L).map { (dir, rot) ->
                    linkedMapOf(
                        "condition" to "q.block_state('minecraft:cardinal_direction') == '$dir'",
                        "components" to linkedMapOf("minecraft:transformation" to linkedMapOf("rotation" to listOf(0L, rot, 0L)))
                    )
                }
            }
            // Come i tronchi: l'asse segue la faccia su cui lo appoggi.
            Orientation.AXIS -> {
                description["traits"] = linkedMapOf("minecraft:placement_position" to linkedMapOf("enabled_states" to listOf("minecraft:block_face")))
                block["permutations"] = listOf(
                    linkedMapOf(
                        "condition" to "q.block_state('minecraft:block_face') == 'north' || q.block_state('minecraft:block_face') == 'south'",
                        "components" to linkedMapOf("minecraft:transformation" to linkedMapOf("rotation" to listOf(90L, 0L, 0L)))
                    ),
                    linkedMapOf(
                        "condition" to "q.block_state('minecraft:block_face') == 'east' || q.block_state('minecraft:block_face') == 'west'",
                        "components" to linkedMapOf("minecraft:transformation" to linkedMapOf("rotation" to listOf(0L, 0L, 90L)))
                    )
                )
            }
            Orientation.NONE -> {}
        }
        return linkedMapOf("format_version" to ModConverter.FORMAT, "minecraft:block" to block)
    }
}

/** Valori indicativi presi dagli attrezzi e dalle armature di Minecraft dello stesso materiale (in Java sono nel codice). */
internal class Stats(
    val durability: Long, val damage: Long, val speed: Long, val protection: Long, val enchantSlot: String, val enchantValue: Long,
    val slot: String?, val tier: String?
) {
    companion object {
        fun of(kind: ItemKind, material: String?): Stats {
            val mat = when (material) { "golden" -> "gold"; "wooden" -> "wood"; null -> "iron"; else -> material }
            val toolDurability = mapOf("wood" to 59L, "stone" to 131L, "iron" to 250L, "gold" to 32L, "diamond" to 1561L, "netherite" to 2031L, "copper" to 190L)
            val speed = mapOf("wood" to 2L, "stone" to 4L, "iron" to 6L, "gold" to 12L, "diamond" to 8L, "netherite" to 9L, "copper" to 5L)
            val bonus = mapOf("wood" to 0L, "stone" to 1L, "iron" to 2L, "gold" to 0L, "diamond" to 3L, "netherite" to 4L, "copper" to 1L)
            val enchant = mapOf("wood" to 15L, "stone" to 5L, "iron" to 14L, "gold" to 22L, "diamond" to 10L, "netherite" to 15L, "copper" to 13L, "leather" to 15L, "chainmail" to 12L)
            val tier = when (mat) { "wood" -> "wooden"; "stone" -> "stone"; "iron" -> "iron"; "gold" -> "golden"; "diamond" -> "diamond"; "netherite" -> "netherite"; else -> null }
            val b = bonus[mat] ?: 2L
            val armorBase = mapOf(
                "leather" to listOf(1L, 3L, 2L, 1L), "chainmail" to listOf(2L, 5L, 4L, 1L), "iron" to listOf(2L, 6L, 5L, 2L),
                "gold" to listOf(2L, 5L, 3L, 1L), "diamond" to listOf(3L, 8L, 6L, 3L), "netherite" to listOf(3L, 8L, 6L, 3L),
                "copper" to listOf(2L, 4L, 3L, 1L)
            )
            val armorDur = mapOf("leather" to 5L, "chainmail" to 15L, "iron" to 15L, "gold" to 7L, "diamond" to 33L, "netherite" to 37L, "copper" to 11L)
            val ev = enchant[mat] ?: 14L
            return when (kind) {
                ItemKind.SWORD -> Stats(toolDurability[mat] ?: 250L, 5 + b, speed[mat] ?: 6L, 0, "sword", ev, null, tier)
                ItemKind.AXE -> Stats(toolDurability[mat] ?: 250L, 4 + b, speed[mat] ?: 6L, 0, "axe", ev, null, tier)
                ItemKind.PICKAXE -> Stats(toolDurability[mat] ?: 250L, 2 + b, speed[mat] ?: 6L, 0, "pickaxe", ev, null, tier)
                ItemKind.SHOVEL -> Stats(toolDurability[mat] ?: 250L, 1 + b, speed[mat] ?: 6L, 0, "shovel", ev, null, tier)
                ItemKind.HOE -> Stats(toolDurability[mat] ?: 250L, 1L, speed[mat] ?: 6L, 0, "hoe", ev, null, tier)
                ItemKind.HELMET, ItemKind.CHESTPLATE, ItemKind.LEGGINGS, ItemKind.BOOTS -> {
                    val i = listOf(ItemKind.HELMET, ItemKind.CHESTPLATE, ItemKind.LEGGINGS, ItemKind.BOOTS).indexOf(kind)
                    val mult = listOf(11L, 16L, 15L, 13L)[i]
                    val slots = listOf("slot.armor.head", "slot.armor.chest", "slot.armor.legs", "slot.armor.feet")
                    val enchSlots = listOf("armor_head", "armor_torso", "armor_legs", "armor_feet")
                    Stats(mult * (armorDur[mat] ?: 15L), 0, 0, (armorBase[mat] ?: armorBase.getValue("iron"))[i], enchSlots[i], ev, slots[i], null)
                }
                else -> Stats(0, 0, 0, 0, "none", 0, null, null)
            }
        }
    }
}

/** Raccoglie le texture usate e le scrive una volta sola; tiene conto delle animazioni (.mcmeta) e della trasparenza. */
internal class TextureCollector(private val mods: List<JavaModContent>, private val limits: ConvertLimits) {

    private class Tex(val bytes: ByteArray?, val transparency: Transparency, val gray: Boolean, val frames: Int?, var block: Boolean, var item: Boolean)

    private val textures = LinkedHashMap<Rl, Tex>()

    private fun load(rl: Rl): Tex {
        val name = "assets/${rl.ns}/textures/${rl.path}.png"
        val bytes = mods.firstNotNullOfOrNull { it.jar.bytes(name, limits.maxPngBytes) }
        if (bytes == null || !Png.isPng(bytes)) return Tex(null, Transparency.OPAQUE, false, null, false, false)
        val img = Png.decode(bytes)
        val meta = mods.firstNotNullOfOrNull { it.jar.json("$name.mcmeta", 1 shl 16) }.asJsonObject()?.get("animation").asJsonObject()
        var frames: Int? = null
        if (meta != null && img != null && img.height > img.width && img.width > 0) {
            frames = ((meta["frametime"] as? Number)?.toInt() ?: 1).coerceAtLeast(1)
        }
        val transparency = img?.let { Png.transparency(it) } ?: Transparency.OPAQUE
        val gray = img != null && isGray(img)
        return Tex(bytes, transparency, gray, frames, false, false)
    }

    private fun isGray(img: Image): Boolean {
        var i = 0
        var colored = 0
        while (i < img.rgba.size) {
            if (img.rgba[i + 3].toInt() != 0) {
                val r = img.rgba[i].toInt() and 0xFF; val g = img.rgba[i + 1].toInt() and 0xFF; val b = img.rgba[i + 2].toInt() and 0xFF
                if (Math.abs(r - g) > 12 || Math.abs(g - b) > 12) colored++
            }
            i += 4
        }
        return colored * 50 < img.width * img.height
    }

    /** Segna la texture come usata e ne restituisce la trasparenza. */
    fun require(rl: Rl, block: Boolean): Transparency {
        val t = textures.getOrPut(rl) { load(rl) }
        if (block) t.block = true else t.item = true
        return t.transparency
    }

    fun isGray(rl: Rl): Boolean = textures[rl]?.gray == true

    /** Scrive le texture trovate: per gli oggetti animati si tiene il primo fotogramma, per i blocchi l'animazione. */
    fun writeAll(sink: (Rl, ByteArray, Int?) -> Unit) {
        for ((rl, t) in textures) {
            if (rl.ns == "minecraft") continue
            val bytes = t.bytes ?: continue
            if (t.frames != null && !t.block) {
                val img = Png.decode(bytes)
                if (img != null) {
                    sink(rl, Png.encode(img.crop(0, img.width)), null)
                    continue
                }
            }
            sink(rl, bytes, if (t.block) t.frames else null)
        }
    }

    val missing: List<Rl> get() = textures.filter { it.value.bytes == null }.keys.toList()
}

/** Il rapporto della conversione in testo semplice (da condividere o da leggere senza l'app). */
object ConversionText {
    fun of(reports: List<ModReport>, addonName: String?): String {
        val b = StringBuilder()
        b.append("JAVA TO BEDROCK — ").append(addonName ?: "rapporto").append('\n')
        b.append("Bedrock non esegue codice Java: passano il contenuto (oggetti, blocchi, texture, nomi, ricette, bottini, minerali, ")
            .append("armature, creature con modello GeckoLib), non il comportamento scritto in codice.\n")
        for (r in reports) {
            b.append("\n== ").append(r.title)
            if (r.version != null) b.append(' ').append(r.version)
            if (r.loader != null) b.append(" (").append(r.loader).append(')')
            b.append('\n').append(r.verdict).append('\n')
            if (r.error != null) continue
            b.append("Oggetti: ").append(r.items.size).append(" · Blocchi: ").append(r.blocks.size).append(" · Creature: ").append(r.entities.size)
                .append(" · Ricette: ").append(r.recipesConverted).append(" su ").append(r.recipesTotal)
                .append(" · Bottini: ").append(r.lootTables).append(" · Minerali: ").append(r.ores).append('\n')
            for ((why, n) in r.recipeSkips.entries.sortedByDescending { it.value }) b.append("- ricette non convertite (").append(n).append("): ").append(why).append('\n')
            for (s in r.simplified) b.append("- semplificato: ").append(s).append('\n')
            for (s in r.estimates) b.append("- stimato: ").append(s).append('\n')
            for (s in r.notConverted) b.append("- non convertito: ").append(s).append('\n')
        }
        return b.toString()
    }
}
