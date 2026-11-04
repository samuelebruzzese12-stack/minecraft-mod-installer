package com.mmi.modinstaller.core

import java.io.IOException
import java.io.InputStream
import java.util.Locale

typealias CancelCheck = () -> Unit
typealias ProgressCallback = (done: Long, total: Long, note: String) -> Unit

/** Limiti di sicurezza applicati a ogni archivio. Tutti i controlli usano i byte realmente letti, non le intestazioni. */
data class SafetyLimits(
    val maxArchiveBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxTotalUncompressedBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxEntries: Int = 100_000,
    val maxCompressionRatio: Long = 300,
    val ratioCheckMinBytes: Long = 64L * 1024 * 1024,
    val maxPathLength: Int = 512,
    val maxManifestBytes: Int = 512 * 1024,
    val maxIconBytes: Int = 2 * 1024 * 1024,
    val maxLevelDatBytes: Int = 4 * 1024 * 1024,
    val maxLangBytes: Int = 256 * 1024,
    val maxTextureBytes: Int = 48 * 1024,
    val maxTextureCandidates: Int = 120,
    val maxNestedDepth: Int = 2,
    val maxNestedArchiveBytes: Long = 512L * 1024 * 1024,
    val maxNestedZipBytes: Long = 256L * 1024 * 1024,
    val maxNestedTotalBytes: Long = 1024L * 1024 * 1024
)

open class ArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

class LimitExceededException(val code: FindingCode, message: String) : ArchiveException(message)

class ArchiveCorruptException(message: String, cause: Throwable? = null) : ArchiveException(message, cause)

class ArchivePasswordException(message: String = "Archivio protetto da password") : ArchiveException(message)

class ArchiveUnsupportedException(val code: FindingCode, message: String) : ArchiveException(message)

class ArchiveUnsafeException(val paths: List<String>) :
    ArchiveException("Percorsi non sicuri nell'archivio: " + paths.take(3).joinToString(", "))

/** Interrompe la lettura con [LimitExceededException] se vengono letti più di [limit] byte. */
class BoundedInputStream(
    private val inner: InputStream,
    private val limit: Long,
    private val code: FindingCode,
    private val what: String
) : InputStream() {

    var count: Long = 0
        private set

    override fun read(): Int {
        val b = inner.read()
        if (b >= 0) add(1)
        return b
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        val n = inner.read(buf, off, len)
        if (n > 0) add(n.toLong())
        return n
    }

    private fun add(n: Long) {
        count += n
        if (count > limit) throw LimitExceededException(code, "$what supera il limite consentito (${formatBytes(limit)})")
    }

    override fun available(): Int = inner.available()

    override fun close() {
        inner.close()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var i = 0
    while (value >= 1024.0 && i < units.size - 1) {
        value /= 1024.0
        i++
    }
    return String.format(Locale.ITALY, "%.1f %s", value, units[i])
}
