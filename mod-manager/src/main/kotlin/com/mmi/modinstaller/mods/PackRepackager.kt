package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.PathSafety
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.core.BoundedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minecraft riconosce un resource pack, uno shader o un data pack solo se `pack.mcmeta`, `assets`, `shaders` o `data` sono
 * nella radice dello ZIP. Molti pacchetti scaricati hanno invece tutto dentro una cartella: qui se ne crea una copia con
 * la struttura giusta. L'originale non viene toccato.
 */
object PackRepackager {

    /** Data fissa per le voci: così lo stesso contenuto dà sempre lo stesso file (e la stessa impronta). */
    private const val FIXED_TIME = 315532800000L // 1980-01-01

    /**
     * Scrive in [target] il contenuto di [source] senza la cartella [wrapper]. Salta le voci «spazzatura» (`__MACOSX`,
     * `.DS_Store`…) e quelle fuori dalla cartella. Letto con gli stessi controlli del resto dell'app (percorsi pericolosi,
     * CRC, dimensioni).
     */
    fun repackage(source: File, wrapper: String, target: File, limits: SafetyLimits = SafetyLimits(), cancel: CancelCheck = {}): Int {
        val prefix = "$wrapper/"
        var written = 0
        var total = 0L
        try {
            ZipArchiveReader.open(source, limits).use { reader ->
                FileOutputStream(target).use { fos ->
                    ZipOutputStream(fos.buffered(64 * 1024)).use { zip ->
                        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                        val seen = HashSet<String>()
                        reader.forEachEntry { entry, content ->
                            cancel()
                            if (entry.isDirectory || content == null) return@forEachEntry
                            val normalized = PathSafety.normalize(entry.rawName.replace('\\', '/'))
                            val path = (normalized as? PathSafety.Normalized.Ok)?.path ?: return@forEachEntry
                            if (PathSafety.isJunk(path) || !path.startsWith(prefix)) return@forEachEntry
                            val name = path.substring(prefix.length)
                            if (name.isEmpty() || !seen.add(name)) return@forEachEntry
                            if (written >= limits.maxEntries) {
                                throw LimitExceededException(FindingCode.TOO_MANY_ENTRIES, "Il pacchetto ha troppi file")
                            }
                            val e = ZipEntry(name)
                            e.time = FIXED_TIME
                            zip.putNextEntry(e)
                            val bounded = BoundedInputStream(content, limits.maxEntryBytes, FindingCode.ENTRY_TOO_LARGE, "Un file del pacchetto")
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = bounded.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > limits.maxTotalUncompressedBytes) {
                                    throw LimitExceededException(FindingCode.UNCOMPRESSED_TOO_LARGE, "Il pacchetto è troppo grande da aprire")
                                }
                                zip.write(buf, 0, n)
                                cancel()
                            }
                            zip.closeEntry()
                            written++
                        }
                        zip.finish()
                        zip.flush()
                        fos.fd.sync()
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        return written
    }
}
