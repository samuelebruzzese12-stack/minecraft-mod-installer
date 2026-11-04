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
    val notes: List<String>
)

/**
 * Costruisce il file .mcpack / .mcaddon / .mcworld / .mctemplate da consegnare a Minecraft.
 *
 * Non si limita a rinominare l'archivio: porta il contenuto alla radice, separa gli add-on in cartelle
 * `<nome>_BP` / `<nome>_RP`, scarta file inutili ed eseguibili, normalizza i nomi dei file chiave e infine
 * rilegge il risultato per verificarne struttura, checksum e manifest prima di consegnarlo.
 */
class PackagePreparer(
    private val limits: SafetyLimits = SafetyLimits(),
    private val freeSpace: (File) -> Long = { it.usableSpace }
) {

    private class Target(val root: String, val prefix: String, val role: PartRole)

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
        try {
            if (passThrough) {
                copyFile(item.archiveFile, tmp, cancel, progress)
                notes.add("Il file era già nel formato corretto: copiato senza modifiche")
            } else {
                repack(item, format, tmp, opener, cancel, progress, excluded, notes)
            }
            val verified = PackageVerifier.verify(tmp, item, format, limits, cancel)
            finalize(tmp, finalFile)
            return PreparedPackage(
                finalFile, fileName, format, finalFile.length(), verified.entryCount, passThrough, excluded, notes
            )
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    // ------------------------------------------------------------------ ricostruzione

    private fun repack(
        item: ContentItem,
        format: OutputFormat,
        tmp: File,
        opener: ArchiveOpener,
        cancel: CancelCheck,
        progress: ProgressCallback?,
        excluded: MutableList<String>,
        notes: MutableList<String>
    ) {
        val targets = planTargets(item, format)
        val seen = HashSet<String>()
        val buf = ByteArray(64 * 1024)
        val now = System.currentTimeMillis()
        var total = 0L
        var written = 0
        var lastProgress = 0L
        var excludedCount = 0
        var duplicates = 0
        val estimate = maxOf(item.sizeBytes, 1L)

        ZipOutputStream(BufferedOutputStream(tmp.outputStream(), 128 * 1024)).use { zos ->
            opener.open(item.archiveFile, item.archiveFormat).use { reader ->
                reader.forEachEntry { entry, content ->
                    cancel()
                    val path = when (val n = PathSafety.normalize(entry.rawName, limits.maxPathLength)) {
                        is PathSafety.Normalized.Ok -> n.path
                        is PathSafety.Normalized.Unsafe -> throw ArchiveUnsafeException(listOf(entry.rawName))
                    }
                    if (path.isEmpty() || entry.isDirectory || content == null) return@forEachEntry
                    if (PathSafety.isJunk(path)) return@forEachEntry
                    val target = locate(path, targets) ?: return@forEachEntry
                    if (PathSafety.isExecutable(path)) {
                        excludedCount++
                        if (excluded.size < MAX_EXCLUDED_LISTED) excluded.add(path)
                        return@forEachEntry
                    }
                    val outPath = PathSafety.join(target.first.prefix, target.second)
                    if (!seen.add(outPath.lowercase())) {
                        duplicates++
                        return@forEachEntry
                    }
                    if (written >= limits.maxEntries) {
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
                    written++
                    if (progress != null && total - lastProgress >= 4L * 1024 * 1024) {
                        lastProgress = total
                        progress(total, maxOf(estimate, total), path)
                    }
                }
            }
            if (written == 0) throw PreparationException("Nessun file da includere nel pacchetto")
        }
        progress?.invoke(total, maxOf(estimate, total), "Pacchetto creato")
        if (excludedCount > 0) {
            notes.add("Esclusi $excludedCount file eseguibili o di codice nativo")
        }
        if (duplicates > 0) {
            notes.add("Ignorati $duplicates file duplicati (stesso percorso)")
        }
        val moved = item.parts.filter { it.rootPath.isNotEmpty() }
        if (moved.isNotEmpty()) {
            notes.add("Contenuto portato dalla cartella interna alla radice del pacchetto")
        }
        if (format == OutputFormat.MCADDON) {
            notes.add("Add-on suddiviso in ${item.parts.size} cartelle (una per pacchetto)")
        }
    }

    private fun planTargets(item: ContentItem, format: OutputFormat): List<Target> {
        if (format != OutputFormat.MCADDON) {
            val p = item.parts.first()
            return listOf(Target(p.rootPath, "", p.role))
        }
        val used = HashSet<String>()
        val base = PathSafety.sanitizeFileName(item.title, "addon", 40)
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

        private val ALREADY_COMPRESSED: Set<String> = setOf(
            "png", "jpg", "jpeg", "webp", "ogg", "mp3", "mp4", "fsb", "zip", "mcpack", "mcaddon", "mcworld",
            "mctemplate", "ldb", "gz", "7z", "rar"
        )
    }
}

/** Rilegge il file preparato e controlla che Minecraft possa importarlo. */
internal object PackageVerifier {

    class Result(val entryCount: Int, val totalBytes: Long)

    private fun fail(message: String): Nothing =
        throw PreparationException("Verifica del pacchetto preparato fallita: $message")

    fun verify(file: File, item: ContentItem, format: OutputFormat, limits: SafetyLimits, cancel: CancelCheck): Result {
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

        when (format) {
            OutputFormat.MCPACK -> {
                val m = manifests["manifest.json"] ?: fail("manca manifest.json alla radice")
                checkManifest(m, item.uuids)
            }
            OutputFormat.MCADDON -> {
                if (names.any { !it.contains('/') }) fail("ci sono file fuori dalle cartelle dei pacchetti")
                val packManifests = manifests.filterKeys {
                    val parent = PathSafety.parentOf(it)
                    parent.isNotEmpty() && !parent.contains('/')
                }
                if (packManifests.size != item.parts.size) {
                    fail("trovati ${packManifests.size} pacchetti invece di ${item.parts.size}")
                }
                for (bytes in packManifests.values) checkManifest(bytes, item.uuids)
            }
            OutputFormat.MCWORLD, OutputFormat.MCTEMPLATE -> {
                if ("level.dat" !in names) fail("manca level.dat alla radice")
                if (names.none { it.startsWith("db/") }) fail("manca la cartella db del mondo")
                if (format == OutputFormat.MCTEMPLATE) {
                    val m = manifests["manifest.json"] ?: fail("manca manifest.json del template")
                    checkManifest(m, item.uuids)
                }
            }
        }
        return Result(names.size, total)
    }

    private fun checkManifest(bytes: ByteArray, expectedUuids: List<String>) {
        val result = ManifestParser.parse(String(bytes, Charsets.UTF_8), "manifest.json")
        val manifest = result.manifest
        if (!result.looksLikeBedrock || manifest == null) fail("manifest.json non leggibile")
        val uuid = manifest.uuid?.lowercase() ?: fail("manifest.json senza UUID")
        if (expectedUuids.isNotEmpty() && uuid !in expectedUuids) fail("l'UUID $uuid non corrisponde a quello analizzato")
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
