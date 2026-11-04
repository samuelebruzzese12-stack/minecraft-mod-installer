package com.mmi.modinstaller.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PreparationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Il file pronto da consegnare a Minecraft (già verificato). */
class PreparedPackage(
    val file: File,
    val fileName: String,
    val format: OutputFormat,
    val sizeBytes: Long,
    val entryCount: Int,
    /** True se l'archivio originale era già corretto ed è stato solo copiato. */
    val passThrough: Boolean,
    /** File esclusi perché eseguibili o di codice nativo. */
    val excluded: List<String>,
    val notes: List<String>,
    /** Nomi dei pacchetti (o del mondo) contenuti nel file consegnato a Minecraft. */
    val packNames: List<String> = emptyList(),
    /** File che appartengono ai contenuti scelti, trovati nell'archivio (prima di escludere eseguibili e doppioni). */
    val sourceFileCount: Int = entryCount,
    /** Quanti contenuti del rapporto sono stati uniti in questo file. */
    val itemCount: Int = 1
)

/**
 * Costruisce il file .mcpack / .mcaddon / .mcworld / .mctemplate da consegnare a Minecraft.
 *
 * Non si limita a rinominare l'archivio: porta il contenuto alla radice, separa gli add-on in cartelle
 * `<nome>_BP` / `<nome>_RP`, scarta file inutili ed eseguibili, normalizza i nomi dei file chiave e infine
 * rilegge il risultato per verificarne struttura, checksum e manifest prima di consegnarlo.
 *
 * Nulla viene scartato in silenzio: ogni file letto è contato (incluso, eseguibile escluso, doppione, fuori dai
 * contenuti scelti) e il numero di file scritti deve coincidere con quello trovato rileggendo il risultato.
 */
class PackagePreparer(
    private val limits: SafetyLimits = SafetyLimits(),
    private val freeSpace: (File) -> Long = { it.usableSpace }
) {

    private class Target(val root: String, val prefix: String, val role: PartRole)

    /** Un archivio sorgente e i contenuti da prelevarne. */
    private class ArchivePlan(val file: File, val format: ArchiveFormat, val targets: MutableList<Target>)

    /** Contabilità di ogni file letto: la somma delle voci deve sempre tornare. */
    private class Tally {
        /** File che appartengono ai contenuti scelti. */
        var inScope = 0
        var written = 0
        var excluded = 0
        var duplicates = 0
        var caseVariants = 0

        /** File dell'archivio che non fanno parte dei contenuti scelti (LEGGIMI, altri pacchetti non scelti). */
        var outside = 0
    }

    fun prepare(
        item: ContentItem,
        outDir: File,
        opener: ArchiveOpener = ZipOnlyOpener(limits),
        cancel: CancelCheck = {},
        progress: ProgressCallback? = null
    ): PreparedPackage {
        val format = item.output ?: throw PreparationException("Questo contenuto non può essere installato")
        if (!item.installable) throw PreparationException("Questo contenuto ha errori che ne impediscono l'installazione")
        if (item.parts.isEmpty()) throw PreparationException("Nessun contenuto da preparare")
        if (format != OutputFormat.MCADDON && item.parts.size != 1) {
            throw PreparationException("Struttura non supportata: più contenuti per un file ${format.extension}")
        }
        if (!outDir.isDirectory && !outDir.mkdirs()) throw PreparationException("Impossibile creare la cartella di lavoro")

        val passThrough = !item.repackRequired && item.archiveFormat == ArchiveFormat.ZIP
        val needed = (if (passThrough) item.archiveFile.length() else item.sizeBytes) + SPACE_MARGIN
        checkSpace(outDir, needed)

        val fileName = uniqueName(outDir, PathSafety.sanitizeFileName(item.title), format.extension)
        val finalFile = File(outDir, fileName)
        val tmp = File(outDir, ".$fileName.part")
        tmp.delete()

        val excluded = ArrayList<String>()
        val notes = ArrayList<String>()
        val tally = Tally()
        try {
            if (passThrough) {
                copyFile(item.archiveFile, tmp, cancel, progress)
                notes.add("Il file era già nel formato corretto: copiato senza modifiche")
            } else {
                val plan = ArchivePlan(item.archiveFile, item.archiveFormat, planTargets(item, format, HashSet()).toMutableList())
                repack(listOf(plan), tmp, opener, cancel, progress, item.sizeBytes, excluded, tally)
                addRepackNotes(tally, notes)
                if (item.parts.any { it.rootPath.isNotEmpty() }) {
                    notes.add("Contenuto portato dalla cartella interna alla radice del pacchetto")
                }
                if (format == OutputFormat.MCADDON) {
                    notes.add("Add-on suddiviso in ${item.parts.size} cartelle (una per pacchetto)")
                }
            }
            val verified = PackageVerifier.verify(tmp, item, format, limits, cancel)
            if (!passThrough) checkWritten(verified, tally)
            finalize(tmp, finalFile)
            return PreparedPackage(
                finalFile, fileName, format, finalFile.length(), verified.entryCount, passThrough, excluded, notes,
                packNames = item.parts.map { it.displayName },
                sourceFileCount = if (passThrough) verified.entryCount else tally.inScope,
                itemCount = 1
            )
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Unisce più contenuti (pacchetti) in un solo file `.mcaddon`, così Minecraft li importa tutti insieme:
     * un add-on è spesso composto da behavior pack e resource pack che da soli non funzionano.
     * Mappe e template non si possono unire: vanno consegnati ciascuno col proprio file.
     */
    fun prepareBundle(
        items: List<ContentItem>,
        title: String,
        outDir: File,
        opener: ArchiveOpener = ZipOnlyOpener(limits),
        cancel: CancelCheck = {},
        progress: ProgressCallback? = null
    ): PreparedPackage {
        if (items.isEmpty()) throw PreparationException("Nessun contenuto da preparare")
        if (items.size == 1) return prepare(items[0], outDir, opener, cancel, progress)
        for (item in items) {
            if (!item.installable) throw PreparationException("«${item.title}» ha errori che ne impediscono l'installazione")
            if (item.parts.isEmpty()) throw PreparationException("«${item.title}» non contiene pacchetti da preparare")
            if (item.output != OutputFormat.MCPACK && item.output != OutputFormat.MCADDON) {
                throw PreparationException("«${item.title}» è una mappa o un template: va importato da solo")
            }
        }
        if (!outDir.isDirectory && !outDir.mkdirs()) throw PreparationException("Impossibile creare la cartella di lavoro")
        val estimate = items.sumOf { it.sizeBytes }
        checkSpace(outDir, estimate + SPACE_MARGIN)

        val fileName = uniqueName(outDir, PathSafety.sanitizeFileName(title, "contenuti"), OutputFormat.MCADDON.extension)
        val finalFile = File(outDir, fileName)
        val tmp = File(outDir, ".$fileName.part")
        tmp.delete()

        val used = HashSet<String>()
        val plans = LinkedHashMap<String, ArchivePlan>()
        for (item in items) {
            val plan = plans.getOrPut(item.archiveFile.absolutePath) {
                ArchivePlan(item.archiveFile, item.archiveFormat, ArrayList())
            }
            plan.targets.addAll(planTargets(item, OutputFormat.MCADDON, used, stripPackSuffix = item.parts.size == 1))
        }
        val packCount = items.sumOf { it.parts.size }
        val uuids = items.flatMap { it.uuids }.distinct()

        val excluded = ArrayList<String>()
        val notes = ArrayList<String>()
        val tally = Tally()
        try {
            repack(plans.values.toList(), tmp, opener, cancel, progress, estimate, excluded, tally)
            notes.add("Uniti ${items.size} contenuti ($packCount pacchetti) in un solo file .mcaddon")
            addRepackNotes(tally, notes)
            val verified = PackageVerifier.verifyAddon(tmp, packCount, uuids, limits, cancel)
            checkWritten(verified, tally)
            finalize(tmp, finalFile)
            return PreparedPackage(
                finalFile, fileName, OutputFormat.MCADDON, finalFile.length(), verified.entryCount, false, excluded, notes,
                packNames = items.flatMap { item -> item.parts.map { it.displayName } },
                sourceFileCount = tally.inScope,
                itemCount = items.size
            )
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    // ------------------------------------------------------------------ ricostruzione

    private fun repack(
        plans: List<ArchivePlan>,
        tmp: File,
        opener: ArchiveOpener,
        cancel: CancelCheck,
        progress: ProgressCallback?,
        estimateBytes: Long,
        excluded: MutableList<String>,
        tally: Tally
    ) {
        val seen = HashSet<String>()
        val seenLower = HashSet<String>()
        val buf = ByteArray(64 * 1024)
        val now = System.currentTimeMillis()
        var total = 0L
        var lastProgress = 0L
        val estimate = maxOf(estimateBytes, 1L)

        ZipOutputStream(BufferedOutputStream(tmp.outputStream(), 128 * 1024)).use { zos ->
            for (plan in plans) {
                opener.open(plan.file, plan.format).use { reader ->
                    reader.forEachEntry { entry, content ->
                        cancel()
                        val path = when (val n = PathSafety.normalize(entry.rawName, limits.maxPathLength)) {
                            is PathSafety.Normalized.Ok -> n.path
                            is PathSafety.Normalized.Unsafe -> throw ArchiveUnsafeException(listOf(entry.rawName))
                        }
                        if (path.isEmpty() || entry.isDirectory || content == null) return@forEachEntry
                        if (PathSafety.isJunk(path)) return@forEachEntry
                        val target = locate(path, plan.targets)
                        if (target == null) {
                            tally.outside++
                            return@forEachEntry
                        }
                        tally.inScope++
                        if (PathSafety.isExecutable(path)) {
                            tally.excluded++
                            if (excluded.size < MAX_EXCLUDED_LISTED) excluded.add(path)
                            return@forEachEntry
                        }
                        val outPath = PathSafety.join(target.first.prefix, target.second)
                        // Lo stesso percorso identico non può comparire due volte in uno ZIP: si tiene il primo.
                        if (!seen.add(outPath)) {
                            tally.duplicates++
                            return@forEachEntry
                        }
                        // Nomi che differiscono solo per le maiuscole si tengono entrambi: non si perde nulla e,
                        // dove il disco non distingue le maiuscole, vince l'ultimo come in un'estrazione normale.
                        if (!seenLower.add(outPath.lowercase())) tally.caseVariants++
                        if (tally.written >= limits.maxEntries) {
                            throw LimitExceededException(
                                FindingCode.TOO_MANY_ENTRIES, "Troppi file nel pacchetto (oltre ${limits.maxEntries})"
                            )
                        }

                        val ze = ZipEntry(outPath)
                        ze.time = now
                        zos.setLevel(levelFor(outPath))
                        zos.putNextEntry(ze)
                        val remaining = limits.maxTotalUncompressedBytes - total
                        val bounded = BoundedInputStream(
                            content, minOf(limits.maxEntryBytes, remaining), FindingCode.ENTRY_TOO_LARGE, "Il file '$path'"
                        )
                        while (true) {
                            val n = bounded.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                            cancel()
                        }
                        zos.closeEntry()
                        total += bounded.count
                        tally.written++
                        if (progress != null && total - lastProgress >= 4L * 1024 * 1024) {
                            lastProgress = total
                            progress(total, maxOf(estimate, total), path)
                        }
                    }
                }
            }
            if (tally.written == 0) throw PreparationException("Nessun file da includere nel pacchetto")
        }
        progress?.invoke(total, maxOf(estimate, total), "Pacchetto creato")
    }

    private fun addRepackNotes(tally: Tally, notes: MutableList<String>) {
        notes.add("Inclusi ${tally.written} file su ${tally.inScope} dei contenuti scelti")
        if (tally.excluded > 0) {
            notes.add("Esclusi ${tally.excluded} file eseguibili o di codice nativo")
        }
        if (tally.duplicates > 0) {
            notes.add("Ignorati ${tally.duplicates} file con lo stesso identico percorso (tenuto il primo)")
        }
        if (tally.caseVariants > 0) {
            notes.add(
                (if (tally.caseVariants == 1) "1 file ha lo stesso nome di un altro" else "${tally.caseVariants} file hanno lo stesso nome di altri") +
                    " a meno delle maiuscole: inclusi tutti"
            )
        }
        if (tally.outside > 0) {
            notes.add(
                (if (tally.outside == 1) "1 file dell'archivio non fa parte" else "${tally.outside} file dell'archivio non fanno parte") +
                    " dei contenuti scelti (es. LEGGIMI, altri pacchetti)"
            )
        }
    }

    /** Il numero di file scritti deve coincidere con quello che si trova rileggendo il risultato. */
    private fun checkWritten(verified: PackageVerifier.Result, tally: Tally) {
        if (verified.entryCount != tally.written) {
            throw PreparationException(
                "Verifica del pacchetto preparato fallita: contiene ${verified.entryCount} file invece di ${tally.written}"
            )
        }
    }

    private fun planTargets(
        item: ContentItem,
        format: OutputFormat,
        used: MutableSet<String>,
        stripPackSuffix: Boolean = false
    ): List<Target> {
        if (format != OutputFormat.MCADDON) {
            val p = item.parts.first()
            return listOf(Target(p.rootPath, "", p.role))
        }
        // Un pacchetto singolo chiamato «Nome BP» diventerebbe «Nome_BP_BP»: si toglie il suffisso già presente.
        val stripped = if (stripPackSuffix) item.title.replace(PACK_SUFFIX, "").trim() else item.title
        val base = PathSafety.sanitizeFileName(stripped.ifEmpty { item.title }, "addon", 40)
        return item.parts.map { p ->
            val suffix = when (p.packKind) {
                PackKind.BEHAVIOR -> "BP"
                PackKind.RESOURCE -> "RP"
                PackKind.SKIN -> "SP"
                else -> "PK"
            }
            var name = "${base}_$suffix"
            var i = 2
            while (!used.add(name.lowercase())) {
                name = "${base}_${suffix}_$i"
                i++
            }
            Target(p.rootPath, name, p.role)
        }
    }

    /** Trova il contenuto a cui appartiene [path] e restituisce (contenuto, percorso relativo canonico). */
    private fun locate(path: String, targets: List<Target>): Pair<Target, String>? {
        var best: Target? = null
        for (t in targets) {
            if (PathSafety.isUnder(path, t.root) && (best == null || t.root.length > best.root.length)) best = t
        }
        val t = best ?: return null
        val rel = if (t.root.isEmpty()) path else path.substring(t.root.length + 1)
        return t to canonicalRel(rel, t.role)
    }

    /** Minecraft distingue maiuscole e minuscole: i file chiave alla radice vengono scritti col nome esatto. */
    private fun canonicalRel(rel: String, role: PartRole): String {
        if (rel.indexOf('/') >= 0) return rel
        return when (rel.lowercase()) {
            "manifest.json" -> "manifest.json"
            "pack_icon.png" -> "pack_icon.png"
            "level.dat" -> if (role == PartRole.PACK) rel else "level.dat"
            "levelname.txt" -> if (role == PartRole.PACK) rel else "levelname.txt"
            "world_icon.jpeg" -> if (role == PartRole.PACK) rel else "world_icon.jpeg"
            else -> rel
        }
    }

    private fun levelFor(path: String): Int {
        val ext = PathSafety.extension(path)
        return if (ext in ALREADY_COMPRESSED) java.util.zip.Deflater.BEST_SPEED else java.util.zip.Deflater.DEFAULT_COMPRESSION
    }

    // ------------------------------------------------------------------ utilità

    private fun checkSpace(dir: File, needed: Long) {
        val free = freeSpace(dir)
        if (free > 0 && free < needed) {
            throw LimitExceededException(
                FindingCode.DISK_SPACE,
                "Spazio insufficiente sul telefono: servono circa ${formatBytes(needed)}, ne restano ${formatBytes(free)}"
            )
        }
    }

    private fun uniqueName(dir: File, base: String, ext: String): String {
        var name = "$base.$ext"
        var i = 2
        while (File(dir, name).exists()) {
            name = "${base}_$i.$ext"
            i++
        }
        return name
    }

    private fun copyFile(src: File, dst: File, cancel: CancelCheck, progress: ProgressCallback?) {
        val total = src.length()
        var done = 0L
        var last = 0L
        val buf = ByteArray(128 * 1024)
        src.inputStream().use { input ->
            dst.outputStream().buffered(128 * 1024).use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    cancel()
                    if (progress != null && done - last >= 4L * 1024 * 1024) {
                        last = done
                        progress(done, total, "Copia del file")
                    }
                }
            }
        }
        if (dst.length() != total) throw PreparationException("Copia incompleta del file (${dst.length()} di $total byte)")
    }

    private fun finalize(tmp: File, dst: File) {
        if (tmp.renameTo(dst)) return
        try {
            tmp.copyTo(dst, overwrite = true)
        } catch (e: IOException) {
            dst.delete()
            throw PreparationException("Impossibile salvare il pacchetto preparato: ${e.message}", e)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val SPACE_MARGIN = 16L * 1024 * 1024
        private const val MAX_EXCLUDED_LISTED = 200

        private val PACK_SUFFIX = Regex(
            "(?i)[\\s_\\-\\[(]*(bp|rp|behou?r\\s*pack|behavior\\s*pack|behaviour\\s*pack|resource\\s*pack|resources|data)[\\])]*\\s*$"
        )

        private val ALREADY_COMPRESSED: Set<String> = setOf(
            "png", "jpg", "jpeg", "webp", "ogg", "mp3", "mp4", "fsb", "zip", "mcpack", "mcaddon", "mcworld",
            "mctemplate", "ldb", "gz", "7z", "rar"
        )
    }
}

/** Rilegge il file preparato e controlla che Minecraft possa importarlo. */
internal object PackageVerifier {

    class Result(val entryCount: Int, val totalBytes: Long)

    private class Listing(val names: List<String>, val manifests: Map<String, ByteArray>, val total: Long)

    private fun fail(message: String): Nothing =
        throw PreparationException("Verifica del pacchetto preparato fallita: $message")

    fun verify(file: File, item: ContentItem, format: OutputFormat, limits: SafetyLimits, cancel: CancelCheck): Result {
        val listing = list(file, limits, cancel)
        val names = listing.names
        val manifests = listing.manifests
        when (format) {
            OutputFormat.MCPACK -> {
                val m = manifests["manifest.json"] ?: fail("manca manifest.json alla radice")
                checkManifest(m, item.uuids)
            }
            OutputFormat.MCADDON -> checkAddon(listing, item.parts.size, item.uuids)
            OutputFormat.MCWORLD, OutputFormat.MCTEMPLATE -> {
                if ("level.dat" !in names) fail("manca level.dat alla radice")
                if (names.none { it.startsWith("db/") }) fail("manca la cartella db del mondo")
                if (format == OutputFormat.MCTEMPLATE) {
                    val m = manifests["manifest.json"] ?: fail("manca manifest.json del template")
                    checkManifest(m, item.uuids)
                }
            }
        }
        return Result(names.size, listing.total)
    }

    /** Verifica un `.mcaddon` che unisce più contenuti: devono esserci tutti i pacchetti attesi. */
    fun verifyAddon(file: File, packCount: Int, uuids: List<String>, limits: SafetyLimits, cancel: CancelCheck): Result {
        val listing = list(file, limits, cancel)
        checkAddon(listing, packCount, uuids)
        return Result(listing.names.size, listing.total)
    }

    private fun list(file: File, limits: SafetyLimits, cancel: CancelCheck): Listing {
        val names = ArrayList<String>()
        val manifests = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipArchiveReader.open(file, limits).use { reader ->
                reader.forEachEntry { entry, content ->
                    cancel()
                    val path = when (val n = PathSafety.normalize(entry.rawName, limits.maxPathLength)) {
                        is PathSafety.Normalized.Ok -> n.path
                        is PathSafety.Normalized.Unsafe -> fail("percorso non sicuro «${entry.rawName}»")
                    }
                    if (entry.isDirectory || content == null || path.isEmpty()) return@forEachEntry
                    names.add(path)
                    total += maxOf(entry.size, 0L)
                    // Il lettore finisce comunque di leggere ogni voce e ne controlla il CRC.
                    if (PathSafety.fileName(path) == "manifest.json" && PathSafety.depthOf(PathSafety.parentOf(path)) <= 1) {
                        val bytes = readCapped(content, limits.maxManifestBytes)
                        if (bytes != null) manifests[path] = bytes
                    }
                }
            }
        } catch (e: PreparationException) {
            throw e
        } catch (e: ArchiveException) {
            fail(e.message ?: "archivio non leggibile")
        }
        if (names.isEmpty()) fail("il pacchetto è vuoto")
        return Listing(names, manifests, total)
    }

    private fun checkAddon(listing: Listing, packCount: Int, expectedUuids: List<String>) {
        if (listing.names.any { !it.contains('/') }) fail("ci sono file fuori dalle cartelle dei pacchetti")
        val packManifests = listing.manifests.filterKeys {
            val parent = PathSafety.parentOf(it)
            parent.isNotEmpty() && !parent.contains('/')
        }
        if (packManifests.size != packCount) {
            fail("trovati ${packManifests.size} pacchetti invece di $packCount")
        }
        val found = HashSet<String>()
        for (bytes in packManifests.values) found.add(checkManifest(bytes, expectedUuids))
        val missing = expectedUuids.filter { it !in found }
        if (missing.isNotEmpty()) fail("mancano i pacchetti con UUID ${missing.joinToString(", ")}")
    }

    /** Controlla un manifest e restituisce il suo UUID (minuscolo). */
    private fun checkManifest(bytes: ByteArray, expectedUuids: List<String>): String {
        val result = ManifestParser.parse(String(bytes, Charsets.UTF_8), "manifest.json")
        val manifest = result.manifest
        if (!result.looksLikeBedrock || manifest == null) fail("manifest.json non leggibile")
        val uuid = manifest.uuid?.lowercase() ?: fail("manifest.json senza UUID")
        if (expectedUuids.isNotEmpty() && uuid !in expectedUuids) fail("l'UUID $uuid non corrisponde a quello analizzato")
        return uuid
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
