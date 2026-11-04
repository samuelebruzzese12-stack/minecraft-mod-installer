package com.mmi.modinstaller.core

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Motore di analisi: legge un file, ne verifica integrità e sicurezza, riconosce il tipo di contenuto
 * (resource/behavior pack, add-on, skin, mondo, template), valuta dipendenze e compatibilità e produce
 * il rapporto mostrato all'utente prima dell'importazione.
 */
class ContentAnalyzer(private val ctx: AnalyzeContext) {

    private val scanner = ArchiveScanner(ctx.limits)
    private val nestedBudget = longArrayOf(ctx.limits.maxNestedTotalBytes)
    private var counter = 0

    private class Env(
        val scan: ScanResult,
        val archive: File,
        val format: ArchiveFormat,
        val container: String,
        val sourceExt: String,
        val prefix: String
    ) {
        val files: List<EntryMeta> = scan.entries.filter { !it.directory }
        val paths: List<String> = files.map { it.path }

        /** ZIP senza file inutili, eseguibili, nomi anomali o duplicati: può essere passato a Minecraft com'è. */
        val archiveClean: Boolean
            get() = format == ArchiveFormat.ZIP && scan.junkCount == 0 && scan.nonCanonicalNames == 0 &&
                scan.duplicateNames == 0 && scan.folderConflicts == 0 && scan.executables.isEmpty()
    }

    private class ParsedPack(
        val root: String,
        val part: PartInfo,
        val findings: List<Finding>,
        val iconBytes: ByteArray?,
        val description: String?
    )

    fun analyze(file: File, displayName: String): AnalysisReport {
        val global = ArrayList<Finding>()
        val items = ArrayList<ContentItem>()
        val format = try {
            FormatSniffer.sniff(file)
        } catch (e: IOException) {
            global.add(Finding(Severity.ERROR, FindingCode.NOT_AN_ARCHIVE, "Impossibile leggere il file", e.message))
            return AnalysisReport(SourceInfo(displayName, 0, ArchiveFormat.UNKNOWN), items, global)
        }
        val source = SourceInfo(displayName, file.length(), format)
        when (format) {
            ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR ->
                analyzeArchive(file, displayName, displayName, format, 0, items, global)
            ArchiveFormat.RAR5 -> global.add(rar5Finding(""))
            ArchiveFormat.JSON -> analyzeJson(file, displayName, items, global)
            ArchiveFormat.HTML -> global.add(
                Finding(
                    Severity.ERROR, FindingCode.HTML_PAGE,
                    "Il file è una pagina web (HTML), non un archivio",
                    "Probabilmente il download non è terminato oppure il sito ha restituito una pagina di errore o di pubblicità. Riscarica il file."
                )
            )
            ArchiveFormat.GZIP -> global.add(
                Finding(
                    Severity.ERROR, FindingCode.UNSUPPORTED_FORMAT,
                    "Archivi .gz/.tar non supportati",
                    "Converti il file in ZIP, .mcpack, .mcaddon o .mcworld."
                )
            )
            ArchiveFormat.UNKNOWN -> global.add(
                if (file.length() == 0L) {
                    Finding(Severity.ERROR, FindingCode.NOT_AN_ARCHIVE, "Il file è vuoto", "Il download potrebbe essere fallito: riscarica il file.")
                } else {
                    Finding(
                        Severity.ERROR, FindingCode.NOT_AN_ARCHIVE, "Formato di file non riconosciuto",
                        "Sono supportati .mcpack, .mcaddon, .mcworld, .mctemplate, .zip, .7z, .rar e .json."
                    )
                }
            )
        }
        resolveAcrossItems(items)
        markDuplicates(items)
        return AnalysisReport(source, items, global)
    }

    // ------------------------------------------------------------------ archivi

    private fun rar5Finding(prefix: String) = Finding(
        Severity.ERROR, FindingCode.RAR5_UNSUPPORTED,
        "${prefix}Archivio RAR5 non supportato",
        "Le librerie RAR per Android leggono solo RAR 2.x–4.x. Ricomprimi il file come ZIP (o RAR4) e riprova."
    )

    private fun mapFailure(e: Throwable, prefix: String): Finding = when (e) {
        is LimitExceededException -> Finding(Severity.ERROR, e.code, prefix + (e.message ?: "Limite superato"))
        is ArchivePasswordException -> Finding(
            Severity.ERROR, FindingCode.PASSWORD_PROTECTED,
            prefix + "L'archivio è protetto da password: l'app non può leggerlo",
            "Estrai o ricomprimi l'archivio senza password e riprova."
        )
        is ArchiveUnsupportedException -> Finding(Severity.ERROR, e.code, prefix + (e.message ?: "Formato non supportato"))
        is ArchiveUnsafeException -> Finding(
            Severity.ERROR, FindingCode.UNSAFE_PATH,
            prefix + "Archivio non sicuro: contiene percorsi che tentano di uscire dalla cartella di destinazione (ZIP Slip / path traversal)",
            e.paths.take(5).joinToString("\n")
        )
        is ArchiveCorruptException -> Finding(
            Severity.ERROR, FindingCode.CORRUPT_ARCHIVE, prefix + "Archivio danneggiato o incompleto", e.message
        )
        else -> Finding(
            Severity.ERROR, FindingCode.CORRUPT_ARCHIVE, prefix + "Errore durante la lettura dell'archivio", e.message
        )
    }

    private fun analyzeArchive(
        file: File,
        entryName: String,
        container: String,
        format: ArchiveFormat,
        depth: Int,
        items: MutableList<ContentItem>,
        global: MutableList<Finding>
    ) {
        val prefix = if (depth == 0) "" else "$container: "
        ctx.cancel()
        if (file.length() > ctx.limits.maxArchiveBytes) {
            global.add(
                Finding(
                    Severity.ERROR, FindingCode.ARCHIVE_TOO_LARGE,
                    "${prefix}File troppo grande (${formatBytes(file.length())}): il limite è ${formatBytes(ctx.limits.maxArchiveBytes)}"
                )
            )
            return
        }
        if (format == ArchiveFormat.RAR5) {
            global.add(rar5Finding(prefix))
            return
        }
        val scan: ScanResult = try {
            ctx.opener.open(file, format).use { reader ->
                scanner.scan(reader, file.length(), ctx.workDir, depth, ctx.cancel, ctx.progress, nestedBudget)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            global.add(
                Finding(Severity.ERROR, FindingCode.COMPRESSION_BOMB, prefix + "Memoria insufficiente per analizzare questo archivio")
            )
            return
        } catch (e: Exception) {
            global.add(mapFailure(e, prefix))
            return
        }

        val ext = entryName.substringAfterLast('.', "").lowercase()
        val env = Env(scan, file, format, container, ext, prefix)
        val before = items.size
        detect(env, items, global)
        val outerCount = items.size - before

        for (n in scan.nested) {
            ctx.cancel()
            val nestedExt = PathSafety.extension(n.path)
            val label = "$container › ${n.path}"
            if (nestedExt == "zip" && outerCount > 0) {
                global.add(Finding(Severity.INFO, FindingCode.NESTED_ARCHIVE, "Archivio annidato ignorato: $label"))
                n.file.delete()
                continue
            }
            val nestedFormat = FormatSniffer.sniff(n.file)
            if (nestedFormat == ArchiveFormat.ZIP || nestedFormat == ArchiveFormat.SEVEN_Z ||
                nestedFormat == ArchiveFormat.RAR || nestedFormat == ArchiveFormat.RAR5
            ) {
                val beforeNested = items.size
                analyzeArchive(n.file, n.path, label, nestedFormat, depth + 1, items, global)
                if (items.size == beforeNested) n.file.delete()
            } else {
                global.add(Finding(Severity.WARNING, FindingCode.NESTED_ARCHIVE, "$label non è un archivio leggibile"))
                n.file.delete()
            }
        }
        if (items.size == before) noContent(env, items, global)
        if (scan.skippedNested.isNotEmpty()) {
            global.add(
                Finding(
                    Severity.WARNING, FindingCode.NESTED_ARCHIVE,
                    "${prefix}Archivi annidati troppo grandi non analizzati: ${scan.skippedNested.take(3).joinToString(", ")}"
                )
            )
        }
    }

    private fun analyzeJson(file: File, displayName: String, items: MutableList<ContentItem>, global: MutableList<Finding>) {
        if (file.length() > ctx.limits.maxManifestBytes) {
            global.add(
                Finding(
                    Severity.ERROR, FindingCode.JSON_FILE,
                    "File JSON troppo grande per essere un manifest (${formatBytes(file.length())})"
                )
            )
            return
        }
        val text = try {
            String(file.readBytes(), Charsets.UTF_8)
        } catch (e: IOException) {
            global.add(Finding(Severity.ERROR, FindingCode.JSON_FILE, "Impossibile leggere il file JSON", e.message))
            return
        }
        val result = ManifestParser.parse(text, displayName)
        if (!result.looksLikeBedrock) {
            global.add(
                Finding(
                    Severity.ERROR, FindingCode.JSON_FILE,
                    "Questo file JSON non è un manifest di Minecraft Bedrock",
                    "Per installare contenuti seleziona un archivio (.mcpack, .mcaddon, .mcworld, .zip, .7z, .rar)."
                )
            )
            return
        }
        val findings = ArrayList<Finding>(result.findings)
        findings.add(
            Finding(
                Severity.ERROR, FindingCode.MANIFEST_ONLY,
                "Un manifest.json da solo non è installabile",
                "Servono tutti i file del pacchetto: seleziona l'archivio completo (.zip, .mcpack, .mcaddon) oppure estrai l'intera cartella."
            )
        )
        val m = result.manifest
        items.add(
            ContentItem(
                id = nextId(), kind = ContentKind.MANIFEST_ONLY,
                title = m?.name?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.'),
                description = m?.description, author = m?.authors?.joinToString(", ")?.takeIf { it.isNotBlank() },
                version = m?.versionText?.takeIf { it.isNotBlank() }, parts = emptyList(),
                sizeBytes = file.length(), fileCount = 1, dependencies = emptyList(), initialFindings = findings,
                output = null, iconBytes = null, previewImages = emptyList(),
                uuids = listOfNotNull(m?.uuid?.lowercase()), archiveFile = file, archiveFormat = ArchiveFormat.JSON,
                container = displayName
            )
        )
    }

    // ------------------------------------------------------------------ riconoscimento

    private fun detect(env: Env, items: MutableList<ContentItem>, global: MutableList<Finding>) {
        val scan = env.scan
        val prefix = env.prefix
        if (scan.junkCount > 0) {
            global.add(
                Finding(
                    Severity.INFO, FindingCode.JUNK_FILES,
                    "${prefix}Ignorati ${scan.junkCount} file di sistema inutili (__MACOSX, .DS_Store, …)"
                )
            )
        }
        if (env.paths.isEmpty()) {
            global.add(Finding(Severity.ERROR, FindingCode.EMPTY_ARCHIVE, "${prefix}L'archivio non contiene file"))
            return
        }
        val startCount = items.size

        // Mondi: cartelle con level.dat (le più esterne).
        val levelByRoot = LinkedHashMap<String, String>()
        for (p in env.paths) {
            if (PathSafety.fileName(p).equals("level.dat", ignoreCase = true)) levelByRoot[PathSafety.parentOf(p)] = p
        }
        val worldRoots = ArrayList<String>()
        for (r in levelByRoot.keys.sortedBy { PathSafety.depthOf(it) }) {
            if (worldRoots.none { PathSafety.isUnder(r, it) }) worldRoots.add(r)
        }

        // Pacchetti: cartelle con manifest.json non contenute in un mondo né in un altro pacchetto.
        val manifestByRoot = LinkedHashMap<String, String>()
        for (p in env.paths) {
            if (PathSafety.fileName(p).equals("manifest.json", ignoreCase = true)) manifestByRoot[PathSafety.parentOf(p)] = p
        }
        val packRoots = ArrayList<String>()
        // Altri manifest.json dentro un pacchetto già riconosciuto: restano nei suoi file ma non diventano pacchetti.
        val nestedManifests = LinkedHashMap<String, MutableList<String>>()
        for (r in manifestByRoot.keys.sortedBy { PathSafety.depthOf(it) }) {
            if (worldRoots.any { PathSafety.isUnderOrEqual(r, it) }) continue
            val outer = packRoots.firstOrNull { PathSafety.isUnderOrEqual(r, it) }
            if (outer != null) {
                if (r != outer) nestedManifests.getOrPut(outer) { ArrayList() }.add(manifestByRoot.getValue(r))
                continue
            }
            packRoots.add(r)
        }

        for (root in worldRoots) {
            val levelPath = levelByRoot[root] ?: continue
            items.add(buildWorldItem(env, root, levelPath, manifestByRoot[root]))
        }

        val parsed = ArrayList<ParsedPack>()
        for (root in packRoots) {
            val manifestPath = manifestByRoot[root] ?: continue
            val pp = parsePack(env, root, manifestPath, global)
            if (pp != null) parsed.add(pp)
        }
        for (group in groupPacks(env, parsed)) {
            val item = buildPackItem(env, group, parsed)
            items.add(item)
            warnNestedManifests(item, group, nestedManifests)
        }

        val produced = items.size - startCount
        if (produced > 0 && javaMarkers(env).isNotEmpty()) {
            global.add(
                Finding(
                    Severity.INFO, FindingCode.JAVA_EDITION,
                    "${prefix}L'archivio contiene anche file per Java Edition (ignorati)"
                )
            )
        }

        // File fuori da tutti i contenuti riconosciuti.
        val partRoots = ArrayList<String>(worldRoots)
        for (p in parsed) partRoots.add(p.root)
        if (produced > 0) {
            val extra = env.paths.count { p -> partRoots.none { PathSafety.isUnderOrEqual(p, it) } }
            if (extra > 0) {
                global.add(
                    Finding(
                        Severity.INFO, FindingCode.EXTRA_FILES,
                        "${prefix}$extra file fuori dai contenuti riconosciuti (es. README) non verranno inclusi"
                    )
                )
            }
        }

        // File eseguibili: segnalati e poi esclusi in fase di preparazione.
        if (produced > 0 && scan.executables.isNotEmpty()) {
            val newItems = items.subList(startCount, items.size)
            val perItem = LinkedHashMap<ContentItem, MutableList<String>>()
            val orphan = ArrayList<String>()
            for (path in scan.executables) {
                val owner = newItems.firstOrNull { it.parts.any { part -> PathSafety.isUnder(path, part.rootPath) } }
                if (owner == null) orphan.add(path) else perItem.getOrPut(owner) { ArrayList() }.add(path)
            }
            for ((item, list) in perItem) {
                item.addFinding(
                    Finding(
                        Severity.WARNING, FindingCode.EXECUTABLE_FILE,
                        "Contiene ${list.size} file eseguibili o di codice nativo che NON verranno inclusi nel pacchetto",
                        list.take(5).joinToString("\n")
                    )
                )
            }
            if (orphan.isNotEmpty()) {
                global.add(
                    Finding(
                        Severity.WARNING, FindingCode.EXECUTABLE_FILE,
                        "${prefix}${orphan.size} file eseguibili fuori dai contenuti: ignorati",
                        orphan.take(5).joinToString("\n")
                    )
                )
            }
        }
    }

    private fun nextId(): String {
        counter++
        return "item$counter"
    }

    private fun javaMarkers(env: Env): List<String> = env.paths.filter {
        val n = PathSafety.fileName(it).lowercase()
        n == "pack.mcmeta" || n == "fabric.mod.json" || n == "mcmod.info" || n == "mods.toml" ||
            PathSafety.extension(it) == "jar"
    }

    /**
     * Chiamata quando né l'archivio né gli archivi annidati hanno prodotto contenuti: distingue i contenuti
     * Java Edition dai file che non c'entrano con Minecraft.
     */
    private fun noContent(env: Env, items: MutableList<ContentItem>, global: MutableList<Finding>) {
        if (env.paths.isEmpty()) return
        val markers = javaMarkers(env)
        if (markers.isNotEmpty()) {
            items.add(buildJavaItem(env, markers))
            return
        }
        // Gli archivi annidati hanno già segnalato da soli il proprio problema.
        if (env.scan.nested.isNotEmpty()) return
        val top = env.paths.map { it.substringBefore('/') }.distinct().take(5).joinToString(", ")
        global.add(
            Finding(
                Severity.ERROR, FindingCode.NO_CONTENT,
                "${env.prefix}Nessun contenuto Minecraft Bedrock riconosciuto",
                "Non ho trovato né un manifest.json (pacchetti) né un level.dat (mondi). Contenuto: $top"
            )
        )
    }

    private fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    private fun stats(env: Env, root: String): Pair<Int, Long> {
        var count = 0
        var size = 0L
        for (f in env.files) {
            if (PathSafety.isUnder(f.path, root)) {
                count++
                size += f.size
            }
        }
        return count to size
    }

    private fun findFile(env: Env, root: String, namesLower: Set<String>): String? =
        env.paths.firstOrNull { PathSafety.parentOf(it) == root && PathSafety.fileName(it).lowercase() in namesLower }

    private fun findLang(env: Env, root: String): Map<String, String>? {
        val dir = PathSafety.join(root, "texts")
        val path = env.paths.firstOrNull {
            PathSafety.parentOf(it) == dir && PathSafety.fileName(it).lowercase() in setOf("en_us.lang", "en_gb.lang")
        } ?: return null
        val bytes = env.scan.keyFiles[path] ?: return null
        return LangFile.parse(bytes)
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (v in values) if (!v.isNullOrBlank()) return v.trim()
        return null
    }

    // ------------------------------------------------------------------ mondi

    private fun buildWorldItem(env: Env, root: String, levelPath: String, manifestPath: String?): ContentItem {
        val scan = env.scan
        val findings = ArrayList<Finding>()
        val prefixSlash = if (root.isEmpty()) "" else "$root/"

        val hasDb = env.paths.any { it.startsWith(prefixSlash + "db/") }
        val hasJavaWorld = env.paths.any {
            it.startsWith(prefixSlash + "region/") || it.startsWith(prefixSlash + "DIM-1/") || it.startsWith(prefixSlash + "DIM1/")
        }
        if (hasJavaWorld && !hasDb) {
            findings.add(
                Finding(
                    Severity.ERROR, FindingCode.WORLD_JAVA, "È un mondo di Minecraft Java Edition: non è compatibile con Bedrock",
                    "Bedrock usa una cartella 'db' (LevelDB), Java usa 'region'. Servono strumenti di conversione esterni."
                )
            )
        } else if (!hasDb) {
            findings.add(
                Finding(
                    Severity.ERROR, FindingCode.WORLD_INCOMPLETE, "Mondo incompleto: manca la cartella 'db'",
                    "Un mondo Bedrock contiene level.dat e la cartella db con i dati dei chunk."
                )
            )
        }

        val levelBytes = scan.keyFiles[levelPath]
        val level = levelBytes?.let { LevelInfo.parse(it) }
        if (level == null) {
            findings.add(
                Finding(
                    Severity.WARNING, FindingCode.WORLD_UNREADABLE,
                    "level.dat non leggibile: nome e versione del mondo sconosciuti"
                )
            )
        }

        var templateManifest: PackManifest? = null
        var templateDescription: String? = null
        val lang = findLang(env, root)
        if (manifestPath != null) {
            val mb = scan.keyFiles[manifestPath]
            if (mb != null) {
                val r = ManifestParser.parse(decode(mb), manifestPath)
                if (r.looksLikeBedrock && r.manifest?.kind == PackKind.WORLD_TEMPLATE) {
                    templateManifest = r.manifest
                    findings.addAll(r.findings)
                    templateDescription = LangFile.resolve(r.manifest.description, lang)
                }
            }
        }
        val isTemplate = templateManifest != null

        val levelNameTxt = scan.keyFiles[PathSafety.join(root, "levelname.txt")]?.let { decode(it).trim() }
        val folder = if (root.isEmpty()) null else root.substringAfterLast('/')
        val title = firstNonBlank(
            LangFile.resolve(templateManifest?.name, lang), levelNameTxt, level?.name, folder,
            env.container.substringAfterLast('›').trim().substringBeforeLast('.')
        ) ?: "Mondo"

        if (root.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.RESTRUCTURED,
                    "Il mondo si trova nella cartella «$root»: verrà portato alla radice del file .${if (isTemplate) "mctemplate" else "mcworld"}"
                )
            )
        }
        if (level != null && level.experiments.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.WORLD_EXPERIMENTS,
                    "Il mondo usa funzioni sperimentali: ${level.experiments.joinToString(", ")}",
                    "Minecraft le attiva da solo all'apertura del mondo."
                )
            )
        }
        val bpCount = env.paths.filter { it.startsWith(prefixSlash + "behavior_packs/") }
            .map { it.removePrefix(prefixSlash + "behavior_packs/").substringBefore('/') }.distinct().size
        val rpCount = env.paths.filter { it.startsWith(prefixSlash + "resource_packs/") }
            .map { it.removePrefix(prefixSlash + "resource_packs/").substringBefore('/') }.distinct().size
        if (bpCount + rpCount > 0) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.GENERIC,
                    "Il mondo include $bpCount Behavior Pack e $rpCount Resource Pack"
                )
            )
        }

        // Compatibilità con la versione di Minecraft installata.
        val mc = ctx.minecraft?.version
        val worldVersion = level?.lastOpenedWith
        if (worldVersion != null) {
            if (mc == null) {
                findings.add(unknownEngineFinding())
            } else if (worldVersion.compareFirst(mc, 3) > 0) {
                findings.add(
                    Finding(
                        Severity.ERROR, FindingCode.WORLD_TOO_NEW,
                        "Mondo salvato con Minecraft ${shortVersion(worldVersion)}, più recente della tua versione (${shortVersion(mc)})",
                        "Minecraft può rifiutarsi di aprirlo: aggiorna il gioco.",
                        overridable = true
                    )
                )
            }
        }

        val (count, size) = stats(env, root)
        val part = PartInfo(
            if (isTemplate) PartRole.TEMPLATE else PartRole.WORLD, root, title,
            if (isTemplate) PackKind.WORLD_TEMPLATE else null, templateManifest, level, count, size
        )
        val iconPath = findFile(env, root, setOf("world_icon.jpeg", "world_icon.jpg", "world_icon.png"))
            ?: findFile(env, root, setOf("pack_icon.png"))
        val icon = iconPath?.let { scan.keyFiles[it] }
        val uuid = templateManifest?.uuid?.lowercase()

        return ContentItem(
            id = nextId(),
            kind = if (isTemplate) ContentKind.WORLD_TEMPLATE else ContentKind.WORLD,
            title = title,
            description = templateDescription?.takeIf { it.isNotBlank() },
            author = templateManifest?.authors?.joinToString(", ")?.takeIf { it.isNotBlank() },
            version = templateManifest?.versionText?.takeIf { it.isNotBlank() }
                ?: level?.lastOpenedWith?.let { shortVersion(it) },
            parts = listOf(part), sizeBytes = size, fileCount = count, dependencies = emptyList(),
            initialFindings = findings,
            output = if (isTemplate) OutputFormat.MCTEMPLATE else OutputFormat.MCWORLD,
            iconBytes = icon, previewImages = emptyList(), uuids = listOfNotNull(uuid),
            archiveFile = env.archive, archiveFormat = env.format, container = env.container,
            repackRequired = !(env.archiveClean && root.isEmpty() && "level.dat" in env.paths &&
                (!isTemplate || "manifest.json" in env.paths))
        )
    }

    // ------------------------------------------------------------------ pacchetti

    private fun parsePack(env: Env, root: String, manifestPath: String, global: MutableList<Finding>): ParsedPack? {
        val bytes = env.scan.keyFiles[manifestPath]
        val folder = if (root.isEmpty()) null else root.substringAfterLast('/')
        val (count, size) = stats(env, root)
        if (bytes == null) {
            val f = Finding(
                Severity.ERROR, FindingCode.MANIFEST_INVALID, "manifest.json troppo grande o illeggibile", manifestPath
            )
            val part = PartInfo(PartRole.PACK, root, folder ?: "Pacchetto", PackKind.UNKNOWN, null, null, count, size)
            return ParsedPack(root, part, listOf(f), null, null)
        }
        val result = ManifestParser.parse(decode(bytes), manifestPath)
        if (!result.looksLikeBedrock) {
            global.add(
                Finding(
                    Severity.INFO, FindingCode.MANIFEST_INVALID,
                    "${env.prefix}Ignorato un manifest.json che non appartiene a Minecraft Bedrock: $manifestPath"
                )
            )
            return null
        }
        val m = result.manifest
        val lang = findLang(env, root)
        val name = firstNonBlank(LangFile.resolve(m?.name, lang), folder, env.container.substringAfterLast('›').trim().substringBeforeLast('.'))
            ?: "Pacchetto"
        val description = LangFile.resolve(m?.description, lang)?.takeIf { it.isNotBlank() }
        val part = PartInfo(PartRole.PACK, root, name, m?.kind ?: PackKind.UNKNOWN, m, null, count, size)
        val icon = findFile(env, root, setOf("pack_icon.png"))?.let { env.scan.keyFiles[it] }
        return ParsedPack(root, part, result.findings, icon, description)
    }

    private fun groupPacks(env: Env, parsed: List<ParsedPack>): List<List<ParsedPack>> {
        val n = parsed.size
        if (n == 0) return emptyList()
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        val byUuid = HashMap<String, Int>()
        for ((i, p) in parsed.withIndex()) {
            val u = p.part.manifest?.uuid?.lowercase() ?: continue
            if (!byUuid.containsKey(u)) byUuid[u] = i
        }
        for ((i, p) in parsed.withIndex()) {
            val deps = p.part.manifest?.dependencies ?: continue
            for (d in deps) {
                val u = d.uuid?.lowercase() ?: continue
                val j = byUuid[u] ?: continue
                union(i, j)
            }
        }
        if (env.sourceExt == "mcaddon" && n > 1) {
            for (i in 1 until n) union(0, i)
        }
        val groups = LinkedHashMap<Int, MutableList<ParsedPack>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(parsed[i])
        return groups.values.toList()
    }

    private fun commonTitle(group: List<ParsedPack>): String {
        val suffix = Regex(
            "(?i)[\\s_\\-\\[(]*(bp|rp|behou?r\\s*pack|behavior\\s*pack|behaviour\\s*pack|resource\\s*pack|resources|data)[\\])]*\\s*$"
        )
        val preferred = group.firstOrNull { it.part.packKind == PackKind.BEHAVIOR } ?: group[0]
        val stripped = preferred.part.displayName.replace(suffix, "").trim()
        return if (stripped.isNotEmpty()) stripped else preferred.part.displayName
    }

    private fun buildPackItem(env: Env, group: List<ParsedPack>, all: List<ParsedPack>): ContentItem {
        val findings = ArrayList<Finding>()
        val multi = group.size > 1
        fun tag(p: ParsedPack, f: Finding): Finding =
            if (multi) f.copy(message = "[${p.part.displayName}] ${f.message}") else f
        for (p in group) for (f in p.findings) findings.add(tag(p, f))

        val kind = if (multi) {
            ContentKind.ADDON
        } else {
            when (group[0].part.packKind) {
                PackKind.RESOURCE -> ContentKind.RESOURCE_PACK
                PackKind.BEHAVIOR -> ContentKind.BEHAVIOR_PACK
                PackKind.SKIN -> ContentKind.SKIN_PACK
                else -> ContentKind.UNKNOWN
            }
        }
        for (p in group) {
            val pk = p.part.packKind
            if (p.part.manifest != null && (pk == PackKind.UNKNOWN || pk == PackKind.WORLD_TEMPLATE || pk == null)) {
                findings.add(
                    tag(
                        p,
                        Finding(
                            Severity.ERROR, FindingCode.MODULE_UNKNOWN,
                            if (pk == PackKind.WORLD_TEMPLATE) "Template di mondo senza i file del mondo (level.dat, db)"
                            else "Tipo di pacchetto non riconosciuto: i moduli non sono resources, data, script o skin_pack"
                        )
                    )
                )
            }
        }

        // Struttura: cartella interna
        if (!multi && group[0].root.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.RESTRUCTURED,
                    "Il pacchetto si trova nella cartella «${group[0].root}»: verrà portato alla radice del .mcpack"
                )
            )
        }
        if (multi) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.GENERIC,
                    "Add-on composto da ${group.size} pacchetti: ${group.joinToString(", ") { it.part.displayName }}"
                )
            )
        }

        // UUID duplicati nello stesso contenuto
        val seenUuids = HashMap<String, String>()
        for (p in group) {
            val u = p.part.manifest?.uuid?.lowercase() ?: continue
            val prev = seenUuids[u]
            if (prev != null) {
                findings.add(
                    Finding(
                        Severity.WARNING, FindingCode.DUPLICATE_IN_ARCHIVE,
                        "«${p.part.displayName}» ha lo stesso UUID di «$prev»: Minecraft ne importerà uno solo", u
                    )
                )
            } else {
                seenUuids[u] = p.part.displayName
            }
        }

        val deps = resolveDependencies(group, all, findings)
        checkEngineVersion(group, multi, findings)
        checkLibrary(group, findings)

        // Capacità richieste
        val caps = group.flatMap { it.part.manifest?.capabilities ?: emptyList() }.distinct()
        if (caps.isNotEmpty()) {
            findings.add(
                Finding(Severity.INFO, FindingCode.EXPERIMENTAL, "Capacità richieste dal pacchetto: ${caps.joinToString(", ")}")
            )
        }

        val count = group.sumOf { it.part.fileCount }
        val size = group.sumOf { it.part.sizeBytes }
        val title = if (multi) commonTitle(group) else group[0].part.displayName
        val description = group.firstNotNullOfOrNull { it.description }
        val authors = group.flatMap { it.part.manifest?.authors ?: emptyList() }.distinct()
        val leader = group.firstOrNull { it.part.packKind == PackKind.BEHAVIOR } ?: group[0]
        val version = leader.part.manifest?.versionText?.takeIf { it.isNotBlank() }
        val rpFirst = group.sortedBy { if (it.part.packKind == PackKind.RESOURCE) 0 else 1 }
        val icon = rpFirst.firstNotNullOfOrNull { it.iconBytes }
        val previews = if (group.any { it.part.packKind == PackKind.RESOURCE }) {
            pickPreviews(env, group.filter { it.part.packKind == PackKind.RESOURCE }.map { it.root })
        } else {
            emptyList()
        }

        return ContentItem(
            id = nextId(), kind = kind, title = title, description = description,
            author = authors.joinToString(", ").takeIf { it.isNotBlank() }, version = version,
            parts = group.map { it.part }, sizeBytes = size, fileCount = count, dependencies = deps,
            initialFindings = findings,
            output = when (kind) {
                ContentKind.ADDON -> OutputFormat.MCADDON
                ContentKind.UNKNOWN -> null
                else -> OutputFormat.MCPACK
            },
            iconBytes = icon, previewImages = previews,
            uuids = group.mapNotNull { it.part.manifest?.uuid?.lowercase() }.distinct(),
            archiveFile = env.archive, archiveFormat = env.format, container = env.container,
            repackRequired = !(env.archiveClean && !multi && group[0].root.isEmpty() && "manifest.json" in env.paths)
        )
    }

    private fun resolveDependencies(
        group: List<ParsedPack>,
        all: List<ParsedPack>,
        findings: MutableList<Finding>
    ): List<DependencyStatus> {
        val groupNames = HashMap<String, String>()
        for (p in group) p.part.manifest?.uuid?.lowercase()?.let { groupNames[it] = p.part.displayName }
        val archiveNames = HashMap<String, String>()
        for (p in all) p.part.manifest?.uuid?.lowercase()?.let { archiveNames[it] = p.part.displayName }
        val result = ArrayList<DependencyStatus>()
        val seen = HashSet<String>()
        var betaWarned = false
        for (p in group) {
            val m = p.part.manifest ?: continue
            for (d in m.dependencies) {
                val key = (d.uuid ?: d.moduleName ?: "") + "@" + d.versionText
                if (!seen.add(key)) continue
                if (d.uuid != null) {
                    val u = d.uuid.lowercase()
                    val inGroup = groupNames[u]
                    val inArchive = archiveNames[u]
                    val known = ctx.known?.lookup(u)
                    when {
                        inGroup != null -> result.add(DependencyStatus(d.uuid, null, d.versionText, DependencyState.IN_ITEM, inGroup))
                        inArchive != null -> result.add(DependencyStatus(d.uuid, null, d.versionText, DependencyState.IN_ARCHIVE, inArchive))
                        u in BUILTIN_UUIDS -> result.add(
                            DependencyStatus(d.uuid, null, d.versionText, DependencyState.BUILTIN, BUILTIN_UUIDS[u])
                        )
                        known != null -> result.add(DependencyStatus(d.uuid, null, d.versionText, DependencyState.IN_LIBRARY, known.name))
                        else -> {
                            result.add(DependencyStatus(d.uuid, null, d.versionText, DependencyState.MISSING, null))
                            findings.add(
                                Finding(
                                    Severity.WARNING, FindingCode.DEPENDENCY_MISSING,
                                    "Dipendenza non trovata: $u (v${d.versionText})",
                                    "Non è nell'archivio né tra i contenuti importati con l'app. Se non è già installata in Minecraft, il contenuto potrebbe non funzionare."
                                )
                            )
                        }
                    }
                } else if (d.moduleName != null) {
                    result.add(DependencyStatus(null, d.moduleName, d.versionText, DependencyState.SCRIPT_MODULE, null))
                    if (!betaWarned && d.versionText.contains("beta", ignoreCase = true)) {
                        betaWarned = true
                        findings.add(
                            Finding(
                                Severity.WARNING, FindingCode.EXPERIMENTAL,
                                "Richiede le Beta APIs di scripting (${d.moduleName} ${d.versionText})",
                                "Attiva «Beta APIs» tra gli esperimenti del mondo e verifica che la versione di Minecraft sia quella prevista dall'autore."
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun checkEngineVersion(group: List<ParsedPack>, multi: Boolean, findings: MutableList<Finding>) {
        val mc = ctx.minecraft?.version
        var unknownReported = false
        for (p in group) {
            val min = p.part.manifest?.minEngineVersion ?: continue
            val label = if (multi) "[${p.part.displayName}] " else ""
            if (mc == null) {
                if (!unknownReported) {
                    unknownReported = true
                    findings.add(unknownEngineFinding())
                }
            } else if (min.compareFirst(mc, 3) > 0) {
                findings.add(
                    Finding(
                        Severity.ERROR, FindingCode.ENGINE_TOO_OLD,
                        "${label}Richiede Minecraft ${shortVersion(min)} o successivo: tu hai la ${shortVersion(mc)}",
                        "Aggiorna Minecraft dal Play Store, oppure forza l'installazione a tuo rischio.",
                        overridable = true
                    )
                )
            }
        }
    }

    private fun checkLibrary(group: List<ParsedPack>, findings: MutableList<Finding>) {
        val known = ctx.known ?: return
        for (p in group) {
            val m = p.part.manifest ?: continue
            val u = m.uuid?.lowercase() ?: continue
            val k = known.lookup(u) ?: continue
            val a = m.version
            val b = k.version
            val cmp = if (a != null && b != null) a.compareFirst(b, 3) else 0
            val name = p.part.displayName
            when {
                cmp == 0 -> findings.add(
                    Finding(
                        Severity.WARNING, FindingCode.SAME_AS_IMPORTED,
                        "«$name» è già stato importato con questa app (v${k.versionText})",
                        "Minecraft ignora un pacchetto con lo stesso UUID e la stessa versione."
                    )
                )
                cmp > 0 -> findings.add(
                    Finding(
                        Severity.INFO, FindingCode.NEWER_THAN_IMPORTED,
                        "«$name» è un aggiornamento: v${m.versionText} (importata: v${k.versionText})"
                    )
                )
                else -> findings.add(
                    Finding(
                        Severity.WARNING, FindingCode.OLDER_THAN_IMPORTED,
                        "«$name» è più vecchio della versione già importata (v${m.versionText} contro v${k.versionText})"
                    )
                )
            }
        }
    }

    private fun unknownEngineFinding() = Finding(
        Severity.INFO, FindingCode.ENGINE_UNKNOWN,
        "Compatibilità con la versione di Minecraft non verificabile: Minecraft non risulta installato o la versione è ignota"
    )

    private fun shortVersion(v: McVersion): String = v.parts.take(3).joinToString(".")

    private val previewNames = listOf(
        "grass_side", "grass_carried", "grass_top", "dirt", "stone", "cobblestone", "planks_oak", "log_oak", "sand",
        "diamond_ore", "gold_ore", "iron_ore", "diamond", "apple", "bread", "iron_sword", "diamond_sword", "stick"
    )

    private fun pickPreviews(env: Env, roots: List<String>): List<ByteArray> {
        val candidates = env.scan.textures.filter { (p, _) -> roots.any { PathSafety.isUnder(p, it) } }
        val scored = candidates.mapIndexed { idx, (p, bytes) ->
            val base = PathSafety.fileName(p).substringBeforeLast('.').lowercase()
            val pri = previewNames.indexOfFirst { base == it || base.startsWith(it) }
            Triple(if (pri >= 0) pri else 1000 + idx, p, bytes)
        }.sortedBy { it.first }
        return scored.take(12).map { it.third }
    }

    private fun buildJavaItem(env: Env, markers: List<String>): ContentItem {
        val jar = markers.any { PathSafety.extension(it) == "jar" }
        val findings = listOf(
            Finding(
                Severity.ERROR, FindingCode.JAVA_EDITION,
                if (jar) "È una mod per Minecraft Java Edition (.jar): Bedrock non supporta i mod Java"
                else "È un pacchetto per Minecraft Java Edition: non è compatibile con Bedrock",
                "Cerca la versione Bedrock del contenuto (.mcpack, .mcaddon o .mcworld)."
            )
        )
        val title = env.container.substringAfterLast('›').trim().substringBeforeLast('.')
        return ContentItem(
            id = nextId(), kind = ContentKind.JAVA_CONTENT, title = title.ifBlank { "Contenuto Java" },
            description = null, author = null, version = null, parts = emptyList(),
            sizeBytes = env.files.sumOf { it.size }, fileCount = env.files.size, dependencies = emptyList(),
            initialFindings = findings, output = null, iconBytes = null, previewImages = emptyList(),
            uuids = emptyList(), archiveFile = env.archive, archiveFormat = env.format, container = env.container
        )
    }

    // ------------------------------------------------------------------ tra contenuti dello stesso file

    /** Segnala i manifest.json trovati dentro un pacchetto: Minecraft non li importa come pacchetti a sé. */
    private fun warnNestedManifests(
        item: ContentItem,
        group: List<ParsedPack>,
        nestedManifests: Map<String, List<String>>
    ) {
        val found = group.flatMap { nestedManifests[it.root] ?: emptyList() }
        if (found.isEmpty()) return
        item.addFinding(
            Finding(
                Severity.WARNING, FindingCode.NESTED_ARCHIVE,
                "Dentro il pacchetto ci sono altri ${found.size} manifest.json: restano tra i suoi file ma NON vengono importati come pacchetti separati",
                "Se sono add-on distinti, estraili dalla cartella e riprova.\n" + found.take(5).joinToString("\n")
            )
        )
    }

    /**
     * Le dipendenze di un pacchetto possono stare in un altro contenuto dello stesso file (per esempio due .mcpack
     * annidati, uno di behavior e uno di resource pack): non sono «mancanti» e vanno importate insieme.
     */
    private fun resolveAcrossItems(items: List<ContentItem>) {
        val providers = HashMap<String, ContentItem>()
        for (item in items) for (u in item.uuids) providers.putIfAbsent(u, item)
        for (item in items) {
            if (item.dependencies.none { it.state == DependencyState.MISSING && it.uuid != null }) continue
            val resolved = HashSet<String>()
            val needs = LinkedHashMap<String, ContentItem>()
            val updated = item.dependencies.map { d ->
                val u = d.uuid?.lowercase()
                val provider = if (d.state == DependencyState.MISSING && u != null) providers[u] else null
                if (provider != null && provider !== item) {
                    resolved.add(u!!)
                    needs[provider.id] = provider
                    DependencyStatus(d.uuid, null, d.versionText, DependencyState.IN_ARCHIVE, provider.title)
                } else {
                    d
                }
            }
            if (resolved.isEmpty()) continue
            item.dependencies = updated
            item.removeFindings { f -> f.code == FindingCode.DEPENDENCY_MISSING && resolved.any { u -> f.message.contains(u) } }
            item.requires = needs.keys.toList()
            item.addFinding(
                Finding(
                    Severity.INFO, FindingCode.GENERIC,
                    "Ha bisogno di ${needs.values.joinToString(", ") { "«${it.title}»" }}, che si trova nello stesso file: importali insieme"
                )
            )
        }
    }

    // ------------------------------------------------------------------ duplicati tra contenuti

    private fun markDuplicates(items: List<ContentItem>) {
        val seen = HashMap<String, ContentItem>()
        for (item in items) {
            for (u in item.uuids) {
                val prev = seen[u]
                if (prev != null && prev !== item) {
                    item.addFinding(
                        Finding(
                            Severity.WARNING, FindingCode.DUPLICATE_IN_ARCHIVE,
                            "Ha lo stesso UUID di «${prev.title}»: Minecraft ne importerà uno solo", u
                        )
                    )
                    if (item.sameUuidAs == null) item.sameUuidAs = prev.title
                } else if (prev == null) {
                    seen[u] = item
                }
            }
        }
    }

    companion object {
        /** Pacchetti inclusi in Minecraft che alcuni add-on dichiarano come dipendenza. */
        val BUILTIN_UUIDS: Map<String, String> = mapOf(
            "0575c61f-a5da-4b7f-9961-ffda2908861e" to "Vanilla Resource Pack",
            "fe9f8597-5454-481a-8730-8d070a8e2e58" to "Vanilla Behavior Pack",
            "0fba4063-dba1-4281-9b89-ff9390653530" to "Chemistry Resource Pack"
        )
    }
}
