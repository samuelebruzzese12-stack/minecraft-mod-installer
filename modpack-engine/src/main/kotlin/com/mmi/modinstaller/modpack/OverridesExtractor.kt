package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.BoundedInputStream
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.PathSafety
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.core.formatBytes
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Cosa è successo agli «overrides» di un modpack. */
class OverridesResult(
    /** Percorsi (relativi alla cartella del profilo) dei file scritti. */
    val applied: List<String>,
    /** «percorso: motivo» per ogni file del modpack che NON è stato applicato. */
    val skipped: List<String>,
    /** File di sistema, di programmi di versioni o con nome nascosto (.DS_Store, __MACOSX, .gitkeep…): non fanno parte del gioco. */
    val ignored: Int = 0
)

/**
 * Applica gli «overrides» di un modpack Modrinth: i file di `overrides/` e di `client-overrides/` (questi ultimi prevalgono)
 * vanno nella cartella del profilo con lo stesso percorso; `server-overrides/` non serve a un'istanza di gioco e si ignora.
 *
 * Tutto ciò che viene scritto sta DENTRO la cartella del profilo: ogni percorso è normalizzato e controllato (niente `..`,
 * percorsi assoluti, nomi nascosti o riservati, file eseguibili), ogni file passa da un temporaneo ed è limitato in
 * dimensione per quanto realmente letto, non per quanto dichiarato. Nulla viene eseguito.
 */
object OverridesExtractor {

    private const val COMMON = "overrides/"
    private const val CLIENT = "client-overrides/"

    fun extract(
        archive: File,
        target: File,
        limits: SafetyLimits = SafetyLimits(),
        cancel: CancelCheck = {},
        progress: ((done: Int) -> Unit)? = null
    ): OverridesResult {
        if (!target.isDirectory && !target.mkdirs()) throw IOException("Non riesco a preparare la cartella del profilo")
        val root = target.canonicalFile
        val applied = LinkedHashSet<String>()
        val skipped = ArrayList<String>()
        val clientWritten = HashSet<String>()
        var ignored = 0
        var totalBytes = 0L
        ZipArchiveReader.open(archive, limits).use { reader ->
            reader.forEachEntry { entry, content ->
                cancel()
                if (entry.isDirectory || content == null) return@forEachEntry
                val name = (PathSafety.normalize(entry.rawName, limits.maxPathLength) as? PathSafety.Normalized.Ok)?.path ?: return@forEachEntry
                val isClient = name.startsWith(CLIENT)
                if (!isClient && !name.startsWith(COMMON)) return@forEachEntry
                val rel = name.substring(if (isClient) CLIENT.length else COMMON.length)
                if (rel.isEmpty()) return@forEachEntry
                // File di sistema o di programmi di versioni e nomi nascosti (.gitkeep, .cache…): non fanno parte del gioco.
                if (PathSafety.isJunk(rel) || rel.split('/').any { it.startsWith(".") }) {
                    ignored++
                    return@forEachEntry
                }
                val (path, why) = ModrinthPackReader.checkPath(rel, limits.maxPathLength)
                if (path == null) {
                    skipped.add("$rel: $why")
                    return@forEachEntry
                }
                // «overrides/» non deve scavalcare un file già scritto da «client-overrides/» (l'ordine nell'archivio è libero).
                if (!isClient && path in clientWritten) return@forEachEntry
                val dest = File(root, path)
                val canonical = dest.canonicalFile
                if (!canonical.path.startsWith(root.path + File.separator)) {
                    skipped.add("$path: esce dalla cartella del profilo")
                    return@forEachEntry
                }
                if (canonical.isDirectory) {
                    skipped.add("$path: al suo posto c'è una cartella")
                    return@forEachEntry
                }
                val parent = canonical.parentFile
                if (parent == null || (!parent.isDirectory && !parent.mkdirs())) {
                    skipped.add("$path: non riesco a creare la cartella")
                    return@forEachEntry
                }
                val temp = File(parent, "." + canonical.name + ".part")
                try {
                    val bounded = BoundedInputStream(content, limits.maxEntryBytes, FindingCode.ENTRY_TOO_LARGE, "Il file $path")
                    var written = 0L
                    temp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = bounded.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            totalBytes += n
                            if (totalBytes > limits.maxTotalUncompressedBytes) {
                                throw LimitExceededException(
                                    FindingCode.UNCOMPRESSED_TOO_LARGE,
                                    "Le impostazioni del modpack superano ${formatBytes(limits.maxTotalUncompressedBytes)}"
                                )
                            }
                            cancel()
                        }
                    }
                    try {
                        Files.move(temp.toPath(), canonical.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temp.toPath(), canonical.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    if (temp.exists()) temp.delete()
                }
                if (isClient) clientWritten.add(path)
                applied.add(path)
                progress?.invoke(applied.size)
            }
        }
        return OverridesResult(applied.toList(), skipped, ignored)
    }
}
