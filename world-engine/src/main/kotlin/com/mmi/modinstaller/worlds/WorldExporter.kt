package com.mmi.modinstaller.worlds

import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.ProgressCallback
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Quanto è stato messo nello ZIP. */
class WorldExportSummary(val files: Int, val bytes: Long)

/**
 * Esporta un mondo estratto in uno ZIP con la cartella del mondo dentro («Nome del mondo/level.dat…»): è la forma che si estrae
 * direttamente nella cartella `saves` di un programma di avvio. Un file alla volta, senza tenere il mondo in memoria; salta
 * `session.lock` e i file nascosti dell'app.
 */
object WorldExporter {

    fun zip(
        dir: File,
        out: OutputStream,
        folderName: String = dir.name,
        cancel: CancelCheck = {},
        progress: ProgressCallback = { _, _, _ -> }
    ): WorldExportSummary {
        val base = dir.canonicalFile
        val files = base.walkTopDown().onEnter { !it.name.startsWith(".") || it == base }
            .filter { it.isFile && it.name != "session.lock" && !it.name.startsWith(".") }
            .toList()
        val total = files.sumOf { it.length() }
        val prefix = WorldImporter.safeFolderName(folderName) + "/"
        var done = 0L
        var count = 0
        ZipOutputStream(out).use { zip ->
            val buf = ByteArray(64 * 1024)
            for (f in files) {
                cancel()
                val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                val entry = ZipEntry(prefix + rel)
                entry.time = f.lastModified()
                zip.putNextEntry(entry)
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                        done += n
                        cancel()
                    }
                }
                zip.closeEntry()
                count++
                progress(done, total, "Esporto il mondo: $count di ${files.size} file")
            }
        }
        return WorldExportSummary(count, done)
    }
}
