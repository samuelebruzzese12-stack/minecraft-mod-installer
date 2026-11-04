package com.mmi.modinstaller.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mmi.modinstaller.core.BoundedInputStream
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.formatBytes
import java.io.File
import java.io.IOException

/** Un file scelto dall'utente (o ricevuto da un'altra app), ancora da copiare. */
class PickedFile(val uri: Uri, val displayName: String, val sizeBytes: Long)

/**
 * Porta il file scelto con il selettore di sistema dentro lo spazio privato dell'app, con controlli di
 * dimensione e di spazio libero. L'app lavora sempre su una copia: il file originale non viene mai modificato.
 */
class FileInbox(private val context: Context, private val limits: SafetyLimits) {

    fun describe(uri: Uri): PickedFile {
        var name: String? = null
        var size = -1L
        try {
            val cursor = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                } finally {
                    cursor.close()
                }
            }
        } catch (e: Exception) {
            // Alcuni provider non supportano la query: si ripiega sul nome nell'URI.
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')
        val rawName = if (name.isNullOrBlank()) (fallback ?: "file") else name
        return PickedFile(uri, cleanDisplayName(rawName), size)
    }

    /** Copia il contenuto di [uri] in [target]. Lancia [LimitExceededException] se il file supera i limiti. */
    fun stage(uri: Uri, target: File, expectedSize: Long, cancel: CancelCheck, progress: ProgressCallback?) {
        if (expectedSize > limits.maxArchiveBytes) {
            throw LimitExceededException(
                FindingCode.ARCHIVE_TOO_LARGE,
                "File troppo grande (${formatBytes(expectedSize)}): il limite è ${formatBytes(limits.maxArchiveBytes)}"
            )
        }
        val parent = target.parentFile
        if (parent != null) {
            parent.mkdirs()
            if (expectedSize > 0 && parent.usableSpace < expectedSize + SPACE_MARGIN) {
                throw LimitExceededException(
                    FindingCode.DISK_SPACE,
                    "Spazio insufficiente sul telefono: servono ${formatBytes(expectedSize + SPACE_MARGIN)} " +
                        "liberi, ce ne sono ${formatBytes(parent.usableSpace)}"
                )
            }
        }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Impossibile aprire il file: l'app che lo gestisce non lo mette a disposizione")
        try {
            BoundedInputStream(input, limits.maxArchiveBytes, FindingCode.ARCHIVE_TOO_LARGE, "Il file").use { source ->
                target.outputStream().buffered(BUFFER).use { out ->
                    val buffer = ByteArray(BUFFER)
                    var done = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        cancel()
                        progress?.invoke(done, expectedSize, "Copia del file")
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private fun cleanDisplayName(name: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_').trim()
        return if (cleaned.isEmpty()) "file" else cleaned.take(160)
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val SPACE_MARGIN = 32L * 1024 * 1024
    }
}
