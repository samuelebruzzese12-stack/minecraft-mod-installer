package com.mmi.modinstaller.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class EntryMeta(val path: String, val size: Long, val directory: Boolean)

class NestedArchive(val path: String, val file: File, val size: Long)

class ScanResult(
    /** Voci sicure e non "spazzatura", con percorso normalizzato. La dimensione è quella realmente letta. */
    val entries: List<EntryMeta>,
    /** File chiave letti in memoria (manifest, level.dat, icone, lingue). */
    val keyFiles: Map<String, ByteArray>,
    /** File chiave troppo grandi per essere letti. */
    val oversized: Set<String>,
    val textures: List<Pair<String, ByteArray>>,
    val nested: List<NestedArchive>,
    val skippedNested: List<String>,
    val executables: List<String>,
    val junkCount: Int,
    val totalBytes: Long,
    /** Voci il cui nome grezzo differisce da quello normalizzato (backslash, "./", ecc.). */
    val nonCanonicalNames: Int = 0,
    /** File con lo stesso percorso (senza distinguere maiuscole/minuscole) comparsi più volte. */
    val duplicateNames: Int = 0,
    /** File il cui percorso è anche una cartella di altri file (es. «textures» vuoto accanto a «textures/a.png»). */
    val folderConflicts: Int = 0
)

/**
 * Un'unica passata sull'archivio: verifica integrità (legge tutto), applica i limiti di sicurezza,
 * raccoglie i file chiave e le miniature, estrae gli archivi annidati.
 */
class ArchiveScanner(private val limits: SafetyLimits) {

    private class State(
        val workDir: File,
        val depth: Int,
        val cancel: CancelCheck,
        val progress: ProgressCallback?,
        val nestedBudget: LongArray,
        val hint: Long
    ) {
        val entries = ArrayList<EntryMeta>()
        val keyFiles = LinkedHashMap<String, ByteArray>()
        val oversized = HashSet<String>()
        val textures = ArrayList<Pair<String, ByteArray>>()
        val nested = ArrayList<NestedArchive>()
        val skippedNested = ArrayList<String>()
        val executables = ArrayList<String>()
        val lowerNames = HashSet<String>()
        var junk = 0
        var nonCanonical = 0
        var duplicates = 0
        var count = 0
        var total = 0L
        var lastProgress = 0L
        val buf = ByteArray(32 * 1024)
    }

    fun scan(
        reader: ArchiveReader,
        archiveBytes: Long,
        workDir: File,
        depth: Int,
        cancel: CancelCheck,
        progress: ProgressCallback?,
        nestedBudget: LongArray
    ): ScanResult {
        val hint = reader.totalSizeHint
        if (hint > limits.maxTotalUncompressedBytes) {
            throw LimitExceededException(
                FindingCode.UNCOMPRESSED_TOO_LARGE,
                "Il contenuto decompresso (${formatBytes(hint)}) supera il limite di ${formatBytes(limits.maxTotalUncompressedBytes)}"
            )
        }
        checkRatio(hint, archiveBytes)

        val st = State(workDir, depth, cancel, progress, nestedBudget, hint)
        reader.forEachEntry { entry, content -> processEntry(st, entry, content) }
        checkRatio(st.total, archiveBytes)
        progress?.invoke(st.total, maxOf(st.total, hint), "Analisi completata")

        return ScanResult(
            st.entries, st.keyFiles, st.oversized, st.textures, st.nested, st.skippedNested,
            st.executables, st.junk, st.total, st.nonCanonical, st.duplicates, countFolderConflicts(st.entries)
        )
    }

    /** Quanti file hanno lo stesso percorso di una cartella che contiene altri file. */
    private fun countFolderConflicts(entries: List<EntryMeta>): Int {
        val folders = HashSet<String>()
        for (e in entries) {
            var p = PathSafety.parentOf(e.path)
            while (p.isNotEmpty() && folders.add(p)) p = PathSafety.parentOf(p)
        }
        return entries.count { !it.directory && it.path in folders }
    }

    private fun checkRatio(uncompressed: Long, archiveBytes: Long) {
        if (uncompressed >= limits.ratioCheckMinBytes && archiveBytes > 0 &&
            uncompressed / archiveBytes > limits.maxCompressionRatio
        ) {
            throw LimitExceededException(
                FindingCode.COMPRESSION_BOMB,
                "Rapporto di compressione sospetto (${uncompressed / archiveBytes}:1): possibile archivio \"bomba\""
            )
        }
    }

    private fun processEntry(st: State, entry: ArchiveEntry, content: InputStream?) {
        st.cancel()
        st.count++
        if (st.count > limits.maxEntries) {
            throw LimitExceededException(
                FindingCode.TOO_MANY_ENTRIES,
                "L'archivio contiene troppi file (oltre ${limits.maxEntries})"
            )
        }
        val path = when (val norm = PathSafety.normalize(entry.rawName, limits.maxPathLength)) {
            is PathSafety.Normalized.Ok -> norm.path
            is PathSafety.Normalized.Unsafe -> throw ArchiveUnsafeException(listOf(entry.rawName))
        }
        if (path.isEmpty()) return
        if (entry.rawName.removeSuffix("/") != path) st.nonCanonical++
        if (entry.isDirectory || content == null) {
            st.entries.add(EntryMeta(path, 0, true))
            return
        }
        if (PathSafety.isJunk(path)) {
            st.junk++
            return
        }
        if (!st.lowerNames.add(path.lowercase())) st.duplicates++

        val remaining = limits.maxTotalUncompressedBytes - st.total
        val entryLimit = minOf(limits.maxEntryBytes, remaining)
        val code = if (remaining < limits.maxEntryBytes) FindingCode.UNCOMPRESSED_TOO_LARGE else FindingCode.ENTRY_TOO_LARGE
        val bounded = BoundedInputStream(content, entryLimit, code, "Il file '$path'")

        val ext = PathSafety.extension(path)
        if (PathSafety.isExecutable(path)) st.executables.add(path)

        var recordedSize = -1L
        val cap = keyCap(path)
        if (ext in NESTED_EXTENSIONS && st.depth < limits.maxNestedDepth && nestedAllowed(st, ext, entry.size)) {
            recordedSize = copyNested(st, bounded, path)
        } else if (cap > 0) {
            val bytes = readCapped(st, bounded, cap)
            if (bytes != null) st.keyFiles[path] = bytes else st.oversized.add(path)
        } else if (isTextureCandidate(path, entry.size) && st.textures.size < limits.maxTextureCandidates) {
            val bytes = readCapped(st, bounded, limits.maxTextureBytes)
            if (bytes != null) st.textures.add(path to bytes)
        } else {
            drain(st, bounded)
        }

        val actual = bounded.count
        st.total += actual
        if (entry.compressedSize > 0 && actual >= 64L * 1024 * 1024 && actual / entry.compressedSize > 1000) {
            throw LimitExceededException(
                FindingCode.COMPRESSION_BOMB,
                "Il file '$path' ha un rapporto di compressione sospetto (${actual / entry.compressedSize}:1)"
            )
        }
        st.entries.add(EntryMeta(path, if (recordedSize >= 0) recordedSize else actual, false))

        if (st.progress != null && st.total - st.lastProgress >= 4L * 1024 * 1024) {
            st.lastProgress = st.total
            st.progress.invoke(st.total, maxOf(st.hint, st.total), path)
        }
    }

    private fun nestedAllowed(st: State, ext: String, declaredSize: Long): Boolean {
        val cap = minOf(limits.maxNestedArchiveBytes, st.nestedBudget[0])
        if (cap <= 0) return false
        if (ext == "zip" && declaredSize > limits.maxNestedZipBytes) {
            st.skippedNested.add("zip troppo grande")
            return false
        }
        if (declaredSize > cap) return false
        return true
    }

    /** Copia l'archivio annidato in un file temporaneo. Restituisce la dimensione, o -1 se è stato scartato. */
    private fun copyNested(st: State, bounded: BoundedInputStream, path: String): Long {
        st.workDir.mkdirs()
        val nestedLimit = minOf(limits.maxNestedArchiveBytes, st.nestedBudget[0])
        val nestedStream = BoundedInputStream(bounded, nestedLimit, FindingCode.ENTRY_TOO_LARGE, "L'archivio annidato '$path'")
        val file = File.createTempFile("nested_", "." + PathSafety.extension(path), st.workDir)
        try {
            file.outputStream().buffered().use { out ->
                while (true) {
                    val n = nestedStream.read(st.buf)
                    if (n < 0) break
                    out.write(st.buf, 0, n)
                    st.cancel()
                }
            }
        } catch (e: LimitExceededException) {
            file.delete()
            if (nestedStream.count > nestedLimit) {
                st.skippedNested.add(path)
                return -1
            }
            throw e
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        st.nested.add(NestedArchive(path, file, nestedStream.count))
        st.nestedBudget[0] = st.nestedBudget[0] - nestedStream.count
        return nestedStream.count
    }

    private fun readCapped(st: State, input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(cap, 16 * 1024))
        var stored = 0L
        var overflow = false
        while (true) {
            val n = input.read(st.buf)
            if (n < 0) break
            if (!overflow) {
                if (stored + n <= cap) {
                    out.write(st.buf, 0, n)
                    stored += n
                } else {
                    overflow = true
                    out.reset()
                }
            }
            st.cancel()
        }
        return if (overflow) null else out.toByteArray()
    }

    private fun drain(st: State, input: InputStream) {
        while (true) {
            val n = input.read(st.buf)
            if (n < 0) break
            st.cancel()
        }
    }

    private fun keyCap(path: String): Int {
        val name = PathSafety.fileName(path).lowercase()
        return when (name) {
            "manifest.json" -> limits.maxManifestBytes
            "level.dat" -> limits.maxLevelDatBytes
            "levelname.txt" -> 4096
            "pack_icon.png", "world_icon.jpeg", "world_icon.jpg", "world_icon.png" -> limits.maxIconBytes
            "en_us.lang", "en_gb.lang" -> if (path.lowercase().contains("texts/")) limits.maxLangBytes else -1
            else -> -1
        }
    }

    private fun isTextureCandidate(path: String, size: Long): Boolean {
        if (size <= 0 || size > limits.maxTextureBytes) return false
        val lower = path.lowercase()
        return lower.endsWith(".png") && lower.contains("textures/")
    }

    companion object {
        val NESTED_EXTENSIONS: Set<String> = setOf("mcpack", "mcaddon", "mcworld", "mctemplate", "zip")
    }
}
