package com.mmi.modinstaller.worlds

import com.mmi.modinstaller.core.ArchiveException
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.PathSafety
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.core.formatBytes
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/** Come è andata l'importazione di un mondo. */
class WorldImportResult(
    /** La cartella del mondo importato (dentro `saves`), o null se l'importazione non è finita. */
    val worldDir: File?,
    /** File del mondo ora nella cartella (compresi quelli già copiati da un tentativo precedente). */
    val files: Int,
    val bytes: Long,
    /** File che erano già stati copiati da un tentativo interrotto e non si sono ricopiati. */
    val resumedFiles: Int,
    /** Se non null, l'importazione si è fermata: è il motivo. I file già copiati restano per la ripresa. */
    val fatal: String?
) {
    val ok: Boolean get() = fatal == null && worldDir != null
}

/** Un'importazione rimasta a metà (l'app è stata chiusa): si può riprendere o cancellare. */
class PendingWorldImport(
    val token: String,
    val stagingDir: File,
    val archive: File,
    val archiveSize: Long,
    val root: String,
    val folderName: String,
    val title: String,
    val copiedFiles: Int,
    val copiedBytes: Long
)

/**
 * Importa un mondo Java nella cartella `saves` di un profilo, un file alla volta: nessun mondo passa per la memoria. Ogni voce
 * viene controllata dal lettore ZIP (percorso, dimensione e CRC dichiarati); i file finiscono prima in una cartella di lavoro
 * nascosta (`saves/.import-<token>`) con un diario dei file completati, e il mondo compare in `saves` solo alla fine, tutto
 * insieme. Se l'app viene chiusa a metà la ripresa non ricopia i file già completati; se è l'utente ad annullare la cartella di
 * lavoro sparisce. L'archivio di partenza viene solo letto.
 */
class WorldImporter(
    private val limits: SafetyLimits = WorldLimits.DEFAULT,
    private val reserveBytes: Long = 64L * 1024 * 1024,
    private val freeSpace: (File) -> Long = { it.usableSpace }
) {

    fun stagingDir(savesDir: File, token: String): File = File(savesDir, STAGING_PREFIX + token)

    fun importJava(
        archive: File,
        world: WorldInfo,
        savesDir: File,
        folderName: String,
        token: String,
        cancel: CancelCheck = {},
        progress: ProgressCallback = { _, _, _ -> }
    ): WorldImportResult {
        if (world.edition != WorldEdition.JAVA) return failed("Questo mondo è Bedrock: si importa in Minecraft dall'INSTALLER BEDROCK.")
        if (!world.importable) return failed(world.notes.firstOrNull { it.level == WorldNoteLevel.ERROR }?.text ?: "Il mondo non si può importare.")
        if (!token.matches(Regex("[A-Za-z0-9_-]{1,40}"))) return failed("Identificativo dell'importazione non valido.")
        val name = safeFolderName(folderName)
        if (!savesDir.isDirectory && !savesDir.mkdirs()) return failed("Non riesco a creare la cartella saves del profilo.")
        val staging = stagingDir(savesDir, token)
        val journalFile = File(staging, JOURNAL)

        // Il diario di un tentativo precedente vale solo per lo stesso archivio e lo stesso mondo; altrimenti si riparte da zero.
        var done = readJournal(journalFile)
        if (done != null && (done.archiveSize != archive.length() || done.root != world.root)) {
            deleteTree(staging)
            done = null
        }
        val copied = done?.files ?: emptyMap()
        val alreadyBytes = copied.values.sumOf { it.size }
        val needed = (world.bytes - alreadyBytes).coerceAtLeast(0L) + reserveBytes
        val free = freeSpace(savesDir)
        if (free in 0 until needed) {
            return failed("Spazio insufficiente: servono circa " + formatBytes(needed) + ", liberi " + formatBytes(free) + ".")
        }
        if (!staging.isDirectory && !staging.mkdirs()) return failed("Non riesco a creare la cartella di lavoro dell'importazione.")
        // Se l'app è stata chiusa mentre scriveva una riga del diario, la riga incompleta va chiusa prima di aggiungerne altre.
        if (done != null && journalFile.length() > 0 && lastByte(journalFile) != '\n'.code) journalFile.appendText("\n")

        var files = 0
        var bytes = 0L
        var resumed = 0
        val staged = staging.canonicalFile
        try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(journalFile, done != null), Charsets.UTF_8)).use { journal ->
                if (done == null) {
                    journal.write(HEADER + "\n")
                    journal.write("archiveSize\t" + archive.length() + "\n")
                    journal.write("archive\t" + archive.absolutePath + "\n")
                    journal.write("root\t" + world.root + "\n")
                    journal.write("name\t" + name + "\n")
                    journal.write("title\t" + world.title.replace('\t', ' ').replace('\n', ' ') + "\n")
                    journal.write(SEPARATOR + "\n")
                    journal.flush()
                }
                // La riga del diario di un file si scrive solo quando il lettore ha finito di controllarne il CRC, cioè all'inizio
                // della voce successiva (o alla fine): un file con i dati danneggiati non risulta mai «completato».
                var pendingLine: String? = null
                ZipArchiveReader.open(archive, limits).use { reader ->
                    reader.forEachEntry { entry, content ->
                        pendingLine?.let {
                            journal.write(it)
                            journal.flush()
                            pendingLine = null
                        }
                        cancel()
                        if (entry.isDirectory || content == null) return@forEachEntry
                        val path = (PathSafety.normalize(entry.rawName, limits.maxPathLength) as? PathSafety.Normalized.Ok)?.path
                            ?: return@forEachEntry
                        if (!PathSafety.isUnder(path, world.root) || PathSafety.isJunk(path)) return@forEachEntry
                        val rel = if (world.root.isEmpty()) path else path.substring(world.root.length + 1)
                        if (rel == "session.lock" || rel == JOURNAL) return@forEachEntry
                        val target = File(staging, rel)
                        // Difesa in più contro i percorsi che escono dalla cartella (il lettore li ha già rifiutati).
                        if (!target.canonicalFile.path.startsWith(staged.path + File.separator)) throw IOException("Percorso non sicuro: $rel")

                        val previous = copied[rel]
                        if (previous != null && previous.size == entry.size && target.isFile && target.length() == previous.size &&
                            crcOf(target, cancel) == previous.crc
                        ) {
                            resumed++
                        } else {
                            target.parentFile?.mkdirs()
                            val crc = CRC32()
                            var written = 0L
                            FileOutputStream(target).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = content.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    crc.update(buf, 0, n)
                                    written += n
                                    cancel()
                                }
                            }
                            if (entry.size >= 0 && written != entry.size) throw IOException("La voce $rel è incompleta")
                            pendingLine = rel.replace('\t', ' ') + "\t" + written + "\t" + crc.value + "\n"
                        }
                        files++
                        bytes += entry.size.coerceAtLeast(0L)
                        progress(bytes, world.bytes, "Copio i file del mondo: $files di ${world.files}")
                    }
                }
                pendingLine?.let {
                    journal.write(it)
                    journal.flush()
                }
            }
        } catch (e: CancellationException) {
            // Annullata dall'utente: non resta nulla.
            deleteTree(staging)
            throw e
        } catch (e: ArchiveException) {
            return WorldImportResult(null, files, bytes, resumed, "L'archivio non si legge fino in fondo: " + (e.message ?: "archivio danneggiato") + ". I file già copiati restano per la ripresa.")
        } catch (e: IOException) {
            return WorldImportResult(null, files, bytes, resumed, "Non sono riuscito a copiare il mondo: " + (e.message ?: e.javaClass.simpleName) + ". I file già copiati restano per la ripresa.")
        }

        if (files != world.files) {
            return WorldImportResult(null, files, bytes, resumed, "Nell'archivio ci sono $files file del mondo invece di ${world.files}: l'archivio è cambiato. Scegli di nuovo il file.")
        }
        journalFile.delete()
        val finalDir = uniqueDir(savesDir, name)
        if (!staging.renameTo(finalDir)) {
            return WorldImportResult(null, files, bytes, resumed, "Non riesco a spostare il mondo nella cartella saves. I file copiati restano per la ripresa.")
        }
        progress(world.bytes, world.bytes, "Mondo importato")
        return WorldImportResult(finalDir, files, bytes, resumed, null)
    }

    /** Le importazioni rimaste a metà in [savesDir] (cartelle di lavoro con il loro diario). */
    fun pending(savesDir: File): List<PendingWorldImport> {
        val dirs = savesDir.listFiles { f -> f.isDirectory && f.name.startsWith(STAGING_PREFIX) } ?: return emptyList()
        val out = ArrayList<PendingWorldImport>()
        for (dir in dirs.sortedBy { it.name }) {
            val j = readJournal(File(dir, JOURNAL)) ?: continue
            out.add(
                PendingWorldImport(
                    dir.name.removePrefix(STAGING_PREFIX), dir, File(j.archive), j.archiveSize, j.root, j.name, j.title,
                    j.files.size, j.files.values.sumOf { it.size }
                )
            )
        }
        return out
    }

    /** Cancella un'importazione rimasta a metà (la cartella di lavoro). Non tocca l'archivio di partenza. */
    fun discard(savesDir: File, token: String) {
        if (token.matches(Regex("[A-Za-z0-9_-]{1,40}"))) deleteTree(stagingDir(savesDir, token))
    }

    // ------------------------------------------------------------------ diario

    private class Copied(val size: Long, val crc: Long)

    private class Journal(val archive: String, val archiveSize: Long, val root: String, val name: String, val title: String, val files: Map<String, Copied>)

    private fun readJournal(file: File): Journal? {
        if (!file.isFile) return null
        return try {
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.firstOrNull() != HEADER) return null
            val head = HashMap<String, String>()
            var i = 1
            while (i < lines.size && lines[i] != SEPARATOR) {
                val k = lines[i].substringBefore('\t')
                head[k] = lines[i].substringAfter('\t', "")
                i++
            }
            if (i >= lines.size) return null
            val files = LinkedHashMap<String, Copied>()
            for (line in lines.drop(i + 1)) {
                val parts = line.split('\t')
                // Una riga incompleta (l'app è stata chiusa mentre la scriveva) non conta: quel file si ricopia.
                if (parts.size != 3) continue
                val size = parts[1].toLongOrNull() ?: continue
                val crc = parts[2].toLongOrNull() ?: continue
                files[parts[0]] = Copied(size, crc)
            }
            Journal(
                head["archive"] ?: return null, head["archiveSize"]?.toLongOrNull() ?: return null, head["root"] ?: "",
                head["name"] ?: "Mondo", head["title"] ?: "Mondo", files
            )
        } catch (e: IOException) {
            null
        }
    }

    private fun crcOf(file: File, cancel: CancelCheck): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
                cancel()
            }
        }
        return crc.value
    }

    private fun failed(message: String) = WorldImportResult(null, 0, 0, 0, message)

    private fun lastByte(file: File): Int = java.io.RandomAccessFile(file, "r").use { raf ->
        raf.seek(raf.length() - 1)
        raf.read()
    }

    companion object {
        const val STAGING_PREFIX = ".import-"
        private const val JOURNAL = ".mmi-import-journal"
        private const val HEADER = "MMI-WORLD-IMPORT 1"
        private const val SEPARATOR = "---"

        /** Un nome di cartella valido su Android e nei launcher: senza separatori né caratteri riservati, al massimo 60 caratteri. */
        fun safeFolderName(name: String): String {
            val cleaned = name.filter { !it.isISOControl() }
                .map { if (it in "/\\:*?\"<>|") '_' else it }
                .joinToString("")
                .trim()
                .trim('.')
                .take(60)
                .trim()
            return cleaned.ifEmpty { "Mondo" }
        }

        /** [name], oppure «name (2)», «name (3)»… se in [dir] c'è già. */
        fun uniqueDir(dir: File, name: String): File {
            var candidate = File(dir, name)
            var n = 2
            while (candidate.exists()) {
                candidate = File(dir, "$name ($n)")
                n++
            }
            return candidate
        }

        internal fun deleteTree(f: File) {
            if (!f.exists()) return
            if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
            f.delete()
        }
    }
}
