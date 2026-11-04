package com.mmi.modinstaller.core

import java.io.File
import java.io.IOException
import java.util.Locale

class BackupInfo(
    val id: String,
    val createdAt: Long,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val reason: String
)

/**
 * Copie di sicurezza dei file gestiti dall'app: prima di eliminare o sostituire un pacchetto ne viene
 * salvata una copia, così l'operazione si può annullare. Ogni backup vive in una cartella propria con un
 * file meta.json.
 */
class BackupManager(
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun create(source: File, title: String, reason: String): BackupInfo {
        if (!source.isFile) throw IOException("File da salvare non trovato: ${source.name}")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Impossibile creare la cartella dei backup")
        val now = clock()
        var stamp = now
        var dir = File(root, idOf(stamp))
        while (dir.exists()) {
            stamp++
            dir = File(root, idOf(stamp))
        }
        if (!dir.mkdirs()) throw IOException("Impossibile creare la cartella del backup")
        val target = File(dir, source.name)
        try {
            source.copyTo(target, overwrite = false)
            if (target.length() != source.length()) throw IOException("Copia di backup incompleta")
            val meta = linkedMapOf<String, Any?>(
                "id" to dir.name,
                "createdAt" to now,
                "title" to title,
                "fileName" to source.name,
                "sizeBytes" to target.length(),
                "reason" to reason
            )
            File(dir, META_NAME).writeText(Json.stringify(meta, true), Charsets.UTF_8)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
        return BackupInfo(dir.name, now, title, source.name, target.length(), reason)
    }

    /** Backup esistenti, dal più recente. I backup con meta.json illeggibile vengono ignorati. */
    fun list(): List<BackupInfo> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val result = ArrayList<BackupInfo>()
        for (dir in dirs) {
            val metaFile = File(dir, META_NAME)
            if (!metaFile.isFile) continue
            val info = try {
                val meta = Json.parseObject(metaFile.readText(Charsets.UTF_8))
                val fileName = meta.str("fileName") ?: continue
                if (!File(dir, fileName).isFile) continue
                BackupInfo(
                    dir.name,
                    meta.long("createdAt") ?: 0L,
                    meta.str("title") ?: fileName,
                    fileName,
                    meta.long("sizeBytes") ?: File(dir, fileName).length(),
                    meta.str("reason") ?: ""
                )
            } catch (e: Exception) {
                continue
            }
            result.add(info)
        }
        result.sortByDescending { it.createdAt }
        return result
    }

    /** Ripristina il backup [id] dentro [targetDir] (con un nome libero se esiste già un file omonimo). */
    fun restore(id: String, targetDir: File): File {
        val dir = backupDir(id)
        val info = list().firstOrNull { it.id == id } ?: throw IOException("Backup non trovato")
        val source = File(dir, info.fileName)
        if (!targetDir.isDirectory && !targetDir.mkdirs()) throw IOException("Impossibile creare la cartella di destinazione")
        val base = info.fileName.substringBeforeLast('.', info.fileName)
        val ext = info.fileName.substringAfterLast('.', "")
        var target = File(targetDir, info.fileName)
        var i = 2
        while (target.exists()) {
            target = File(targetDir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
            i++
        }
        try {
            source.copyTo(target, overwrite = false)
            if (target.length() != source.length()) throw IOException("Ripristino incompleto")
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return target
    }

    fun delete(id: String) {
        backupDir(id).deleteRecursively()
    }

    /**
     * Conserva al massimo [keep] backup e circa [maxBytes] byte complessivi, eliminando i più vecchi.
     * Il backup più recente viene tenuto anche se da solo supera [maxBytes] (a meno che [keep] sia 0).
     */
    fun prune(keep: Int, maxBytes: Long) {
        val all = list()
        var total = all.sumOf { it.sizeBytes }
        var remaining = all.size
        while (remaining > 0 && (remaining > keep || (total > maxBytes && remaining > 1))) {
            val oldest = all[remaining - 1]
            delete(oldest.id)
            total -= oldest.sizeBytes
            remaining--
        }
    }

    private fun backupDir(id: String): File {
        if (id.isEmpty() || !id.all { it in '0'..'9' }) throw IOException("Identificativo di backup non valido")
        return File(root, id)
    }

    private fun idOf(stamp: Long): String = String.format(Locale.ROOT, "%013d", stamp)

    companion object {
        private const val META_NAME = "meta.json"
    }
}
