package com.mmi.modinstaller.mods

/** Le cause che l'analisi sa riconoscere nel testo di un crash di Minecraft Java. */
enum class CrashCause(val label: String) {
    MISSING_DEPENDENCY("Manca una mod richiesta"),
    WRONG_VERSION("Versione sbagliata"),
    INCOMPATIBLE("Mod incompatibili"),
    DUPLICATE("Mod duplicate"),
    WRONG_JAVA("Versione di Java sbagliata"),
    OUT_OF_MEMORY("Memoria insufficiente"),
    MIXIN("Una mod non riesce a modificare il gioco"),
    GRAPHICS("Problema grafico o del driver"),
    UNKNOWN("Causa non riconosciuta")
}

/** Una causa trovata: cosa dice il log, in italiano, e cosa si può fare. [mods] sono gli identificativi coinvolti. */
class CrashFinding(val cause: CrashCause, val text: String, val advice: String, val mods: List<String> = emptyList())

/** Quello che si ricava da un crash report o da un log di Minecraft Java. */
class CrashAnalysis(
    /** «crash report» se il testo ha l'intestazione di Minecraft, altrimenti «log». */
    val isCrashReport: Boolean,
    val minecraft: String?,
    /** Fabric, Quilt, Forge, NeoForge, o null se non si capisce. */
    val loader: String?,
    val java: String?,
    /** La riga «Description:» del crash report. */
    val description: String?,
    /** La prima eccezione trovata (tipo e messaggio), accorciata. */
    val exception: String?,
    /** Le mod che il crash report indica come sospette («Suspected Mod(s)»). */
    val suspectedMods: List<String>,
    val findings: List<CrashFinding>
) {
    val recognized: Boolean get() = findings.any { it.cause != CrashCause.UNKNOWN }
}

/**
 * Legge il testo di un crash report (`crash-reports/crash-….txt`) o di un log (`logs/latest.log`) di Minecraft Java e riconosce le
 * cause più comuni dai messaggi che il gioco e i caricatori (Fabric, Quilt, Forge, NeoForge) scrivono davvero: mod mancanti,
 * versioni sbagliate, mod incompatibili o duplicate, Java sbagliato, memoria finita, errori dei mixin, driver grafici.
 * Sono indicazioni ricavate dal testo, non certezze: se il messaggio non è tra quelli noti lo dice. Non esegue nulla.
 */
object CrashAnalyzer {

    /** Oltre questa dimensione si legge solo l'inizio e la fine (i log possono essere enormi). */
    const val MAX_CHARS = 2_000_000

    // I messaggi di Fabric Loader (Messages.properties): «Mod 'Iris' (iris) 1.6.4 requires any version of fabric-api, which is missing!»,
    // «… requires version 1.20.1 of 'Minecraft' (minecraft), but only the wrong version is present: 1.20.4!»,
    // «… is incompatible with any version of mod 'Sodium' (sodium), yet a conflicting version is present: 0.5.3!». Quilt li scrive uguali.
    private const val WHO = """(?:[Mm]od )?'(.+?)' \(([\w.+-]+)\) (\S+)"""
    private const val TARGET = """(?:(?:mod )?'(.+?)' \(([\w.+-]+)\)|([\w.+-]+))"""
    private val FABRIC_REQUIRES = Regex("""$WHO requires (.+?) of $TARGET, which is missing!""")
    private val FABRIC_WRONG = Regex("""$WHO requires (.+?) of $TARGET, but only the wrong versions? (?:is|are) present: (.+?)!""")
    private val FABRIC_INCOMPATIBLE = Regex("""$WHO is incompatible with (.+?) of $TARGET, yet""")
    private val FABRIC_DUPLICATE = Regex("""[Dd]uplicate mod ([\w.+-]+)""")
    private val FORGE_MISSING = Regex("""Mod ID: '([^']+)', Requested by: '([^']+)', Expected range: '([^']*)', Actual version: '([^']*)'""")
    private val CLASS_VERSION = Regex("""class file version (\d+)\.0\), this version of the Java Runtime only recognizes class file versions up to (\d+)\.0""")
    private val DUPLICATE = Regex("""(?:Found duplicate mods?|DuplicateModsFoundException)""", RegexOption.IGNORE_CASE)
    // Forge e NeoForge recenti, nel crash report: «Mod mymod requires geckolib 4.0 or above» e sotto «Currently, geckolib is not installed».
    private val FORGE_REQUIRES = Regex("""Mod ([\w.-]+) (?:requires|only supports) ([\w.-]+) (.+?)\s*\R\s*Currently, \2 is (.+)""")
    private val MIXIN_MOD = Regex("""Mixin \[([\w.$-]+\.json)[^\]]*\] from mod (\S+?) failed""")
    private val MIXIN_ANY = Regex("""(MixinApplyError|InvalidMixinException|Mixin apply failed|MixinTransformerError)""")
    private val MC_VERSION = Regex("""Minecraft Version: (\S+)""")
    private val JAVA_VERSION = Regex("""Java Version: ([^\n,]+)""")
    private val DESCRIPTION = Regex("""^Description: (.+)$""", RegexOption.MULTILINE)
    private val EXCEPTION = Regex("""^((?:[\w$]+\.)+[\w$]*(?:Exception|Error|Throwable)[\w$]*)(?:: (.*))?$""", RegexOption.MULTILINE)
    private val SUSPECTED = Regex("""Suspected Mods?: (.+)""")

    fun analyze(input: String): CrashAnalysis {
        val text = if (input.length <= MAX_CHARS) input else input.take(MAX_CHARS / 2) + "\n" + input.takeLast(MAX_CHARS / 2)
        val findings = ArrayList<CrashFinding>()
        val seen = HashSet<String>()
        fun add(f: CrashFinding) {
            if (seen.add(f.cause.name + "|" + f.text)) findings.add(f)
        }

        // Fabric e Quilt
        fun target(m: MatchResult, base: Int) = Pair(
            m.groupValues[base].ifEmpty { m.groupValues[base + 2] },
            m.groupValues[base + 1].ifEmpty { m.groupValues[base + 2] }
        )
        for (m in FABRIC_REQUIRES.findAll(text)) {
            val name = m.groupValues[1]; val id = m.groupValues[2]; val range = m.groupValues[4]
            val (tName, tId) = target(m, 5)
            add(
                CrashFinding(
                    CrashCause.MISSING_DEPENDENCY,
                    "«$name» ($id) ha bisogno di «$tName» ($range), che non c'è.",
                    "Aggiungi «$tName» al profilo (da CERCA ONLINE si scarica con le sue dipendenze), oppure togli «$name».",
                    listOf(id, tId)
                )
            )
        }
        for (m in FABRIC_WRONG.findAll(text)) {
            val name = m.groupValues[1]; val id = m.groupValues[2]; val range = m.groupValues[4]
            val (tName, tId) = target(m, 5)
            val present = m.groupValues[8]
            add(
                CrashFinding(
                    CrashCause.WRONG_VERSION,
                    "«$name» ($id) vuole «$tName» $range, ma c'è la versione $present.",
                    if (tId.equals("minecraft", true)) "Usa un profilo con la versione di Minecraft giusta per «$name», o cerca una versione della mod per $present."
                    else if (tId.equals("java", true)) "Nel programma di avvio scegli la versione di Java che chiede «$name»."
                    else "Sostituisci «$tName» con una versione adatta, o cerca un'altra versione di «$name».",
                    listOf(id, tId)
                )
            )
        }
        for (m in FABRIC_INCOMPATIBLE.findAll(text)) {
            val name = m.groupValues[1]; val id = m.groupValues[2]
            val (tName, tId) = target(m, 5)
            add(
                CrashFinding(
                    CrashCause.INCOMPATIBLE,
                    "«$name» ($id) non funziona insieme a «$tName».",
                    "Tieni solo una delle due: disattivane una nel MOD MANAGER.",
                    listOf(id, tId)
                )
            )
        }
        for (m in FABRIC_DUPLICATE.findAll(text)) {
            val id = m.groupValues[1]
            add(
                CrashFinding(
                    CrashCause.DUPLICATE,
                    "La mod «$id» è presente più volte.",
                    "Tieni una sola copia di «$id» (VERIFICA PROFILO nel MOD MANAGER segnala i duplicati).",
                    listOf(id)
                )
            )
        }
        for (m in FORGE_REQUIRES.findAll(text)) {
            val by = m.groupValues[1]; val dep = m.groupValues[2]; val range = m.groupValues[3].trim(); val now = m.groupValues[4].trim()
            if (now.startsWith("not installed", ignoreCase = true)) {
                add(CrashFinding(CrashCause.MISSING_DEPENDENCY, "«$by» ha bisogno di «$dep» ($range), che non c'è.", "Aggiungi «$dep» al profilo, oppure togli «$by».", listOf(by, dep)))
            } else {
                add(CrashFinding(CrashCause.WRONG_VERSION, "«$by» vuole «$dep» $range, ma c'è: $now.", "Sostituisci «$dep» con una versione adatta, o cerca un'altra versione di «$by».", listOf(by, dep)))
            }
        }
        // Forge e NeoForge: «Mod ID: 'geckolib', Requested by: 'mymod', Expected range: '[4.0,)', Actual version: '[MISSING]'»
        for (m in FORGE_MISSING.findAll(text)) {
            val (dep, by, range, actual) = m.destructured
            if (actual.contains("MISSING", ignoreCase = true)) {
                add(
                    CrashFinding(
                        CrashCause.MISSING_DEPENDENCY,
                        "«$by» ha bisogno di «$dep» ($range), che non c'è.",
                        "Aggiungi «$dep» al profilo, oppure togli «$by».",
                        listOf(by, dep)
                    )
                )
            } else {
                add(
                    CrashFinding(
                        CrashCause.WRONG_VERSION,
                        "«$by» vuole «$dep» $range, ma c'è la versione $actual.",
                        if (dep.equals("minecraft", true) || dep.equals("forge", true) || dep.equals("neoforge", true))
                            "Usa un profilo con la versione giusta di $dep, o cerca un'altra versione di «$by»."
                        else "Sostituisci «$dep» con una versione adatta.",
                        listOf(by, dep)
                    )
                )
            }
        }
        // Java troppo vecchio: «class file version 65.0 … only recognizes class file versions up to 61.0» (65 = Java 21, 61 = Java 17)
        CLASS_VERSION.find(text)?.let { m ->
            val need = m.groupValues[1].toInt() - 44
            val have = m.groupValues[2].toInt() - 44
            add(
                CrashFinding(
                    CrashCause.WRONG_JAVA,
                    "Una mod (o il gioco) è fatta per Java $need, ma il programma di avvio usa Java $have.",
                    "Nel programma di avvio scegli Java $need o successivo (JAVA EDITION dice quale Java serve a ogni versione di Minecraft)."
                )
            )
        }
        if (text.contains("java.lang.OutOfMemoryError") || text.contains("GC overhead limit exceeded")) {
            add(
                CrashFinding(
                    CrashCause.OUT_OF_MEMORY,
                    "Il gioco ha finito la memoria (OutOfMemoryError).",
                    "Assegna più memoria al gioco nel programma di avvio (JAVA EDITION consiglia quanta), oppure togli mod pesanti o shader."
                )
            )
        }
        val mixinMods = MIXIN_MOD.findAll(text).map { it.groupValues[2] }.distinct().toList()
        if (mixinMods.isNotEmpty() || MIXIN_ANY.containsMatchIn(text)) {
            add(
                CrashFinding(
                    CrashCause.MIXIN,
                    if (mixinMods.isNotEmpty()) "Le modifiche al gioco (mixin) di " + mixinMods.joinToString(", ") { "«$it»" } + " non si applicano."
                    else "Le modifiche al gioco (mixin) di una mod non si applicano.",
                    "Di solito la mod non è per questa versione di Minecraft o del caricatore, o va in conflitto con un'altra: aggiornala o disattivala.",
                    mixinMods
                )
            )
        }
        if (findings.none { it.cause == CrashCause.DUPLICATE } && DUPLICATE.containsMatchIn(text)) {
            add(
                CrashFinding(
                    CrashCause.DUPLICATE,
                    "La stessa mod è presente più volte.",
                    "Tieni una sola copia di ogni mod (VERIFICA PROFILO nel MOD MANAGER segnala i duplicati)."
                )
            )
        }
        if (Regex("""(GLFW error 6554\d|Pixel format not accelerated|OpenGL \d\.\d is required|Could not create context|No OpenGL context)""").containsMatchIn(text)) {
            add(
                CrashFinding(
                    CrashCause.GRAPHICS,
                    "La scheda grafica o il suo driver non riesce ad aprire la finestra del gioco (OpenGL).",
                    "Su telefono: nel programma di avvio prova un altro motore grafico (renderer); su computer: aggiorna i driver."
                )
            )
        }

        val exception = EXCEPTION.find(text)?.let { m ->
            (m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")).take(300)
        }
        if (findings.isEmpty()) {
            findings.add(
                CrashFinding(
                    CrashCause.UNKNOWN,
                    if (exception != null) "Il gioco si è fermato con: $exception" else "Nel testo non c'è un messaggio di errore che l'app conosca.",
                    "Guarda le mod indicate come sospette e prova a disattivarle una alla volta nel MOD MANAGER."
                )
            )
        }
        val loader = when {
            text.contains("NeoForge", ignoreCase = false) || text.contains("neoforge") -> "NeoForge"
            text.contains("Quilt Loader") || text.contains("quilt_loader") -> "Quilt"
            text.contains("Fabric Mods:") || text.contains("Fabric Loader") || text.contains("fabricloader") -> "Fabric"
            text.contains("Forge Mod Loader") || text.contains("minecraftforge") || text.contains("FML:") -> "Forge"
            else -> null
        }
        return CrashAnalysis(
            isCrashReport = text.contains("---- Minecraft Crash Report ----"),
            minecraft = MC_VERSION.find(text)?.groupValues?.get(1),
            loader = loader,
            java = JAVA_VERSION.find(text)?.groupValues?.get(1)?.trim(),
            description = DESCRIPTION.find(text)?.groupValues?.get(1)?.trim(),
            exception = exception,
            suspectedMods = SUSPECTED.find(text)?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "None" && it != "NONE" }.orEmpty(),
            findings = findings
        )
    }
}

/** Un file di un profilo che fornisce una mod nominata nel crash. */
class CrashModMatch(val profile: Profile, val modId: String, val item: ProfileItem)

/** Cerca nei profili del MOD MANAGER i file che forniscono le mod nominate dal crash (cause e «Suspected Mods»). */
object CrashMatcher {

    /** Identificatori che non sono mod da cercare: il gioco, Java e i caricatori. */
    private val NOT_MODS = setOf("minecraft", "java", "fabricloader", "fabric-loader", "quilt_loader", "forge", "neoforge", "fml", "mixinextras")
    private val ID_IN_PARENS = Regex("""\(([\w.+-]+)\)\s*$""")

    fun ids(analysis: CrashAnalysis): List<String> {
        val ids = LinkedHashSet<String>()
        for (f in analysis.findings) for (m in f.mods) ids.add(m.lowercase())
        for (s in analysis.suspectedMods) {
            // Il crash report scrive «Nome (id)»: si prende solo l'identificatore tra parentesi, senza indovinare dal nome.
            ID_IN_PARENS.find(s)?.groupValues?.get(1)?.let { ids.add(it.lowercase()) }
        }
        return ids.filter { it.isNotEmpty() && it !in NOT_MODS }
    }

    fun find(analysis: CrashAnalysis, profiles: List<Pair<Profile, List<ProfileItem>>>): List<CrashModMatch> {
        val wanted = ids(analysis)
        if (wanted.isEmpty()) return emptyList()
        val out = ArrayList<CrashModMatch>()
        for ((profile, items) in profiles) {
            for (id in wanted) {
                for (item in items) {
                    if (item.mods.any { m -> m.allIds.any { it.equals(id, ignoreCase = true) } }) out.add(CrashModMatch(profile, id, item))
                }
            }
        }
        return out
    }
}

/** Lettura prudente del file di testo da analizzare: anche compresso (.log.gz), senza tenerlo tutto in memoria. */
object CrashText {

    /** Oltre questa quantità letta si smette (un log normale è molto più piccolo). */
    const val MAX_READ_BYTES = 512L * 1024 * 1024
    private const val HEAD = CrashAnalyzer.MAX_CHARS / 2

    class Result(val text: String, val bytesRead: Long, val truncated: Boolean, val compressed: Boolean)

    /** Null se non sembra un file di testo (troppi byte nulli all'inizio). */
    fun read(input: java.io.InputStream): Result? {
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val compressed = b0 == 0x1f && b1 == 0x8b
        val source: java.io.InputStream = if (compressed) java.util.zip.GZIPInputStream(buffered, 64 * 1024) else buffered
        val head = java.io.ByteArrayOutputStream()
        val tail = ByteArray(HEAD)
        var tailLen = 0
        var tailPos = 0
        var total = 0L
        val buf = ByteArray(64 * 1024)
        while (total < MAX_READ_BYTES) {
            val n = source.read(buf)
            if (n < 0) break
            var off = 0
            if (head.size() < HEAD) {
                val take = minOf(n, HEAD - head.size())
                head.write(buf, 0, take)
                off = take
            }
            for (i in off until n) {
                tail[tailPos] = buf[i]
                tailPos = (tailPos + 1) % HEAD
                if (tailLen < HEAD) tailLen++
            }
            total += n
        }
        val truncated = total >= MAX_READ_BYTES
        val headBytes = head.toByteArray()
        val probe = minOf(headBytes.size, 64 * 1024)
        val zeros = (0 until probe).count { headBytes[it].toInt() == 0 }
        if (probe > 0 && zeros * 100 > probe) return null
        val tailBytes = if (tailLen < HEAD) tail.copyOfRange(0, tailLen) else tail.copyOfRange(tailPos, HEAD) + tail.copyOfRange(0, tailPos)
        val text = String(headBytes, Charsets.UTF_8) + (if (tailLen > 0) "\n" + String(tailBytes, Charsets.UTF_8) else "")
        return Result(text, total, truncated, compressed)
    }
}
