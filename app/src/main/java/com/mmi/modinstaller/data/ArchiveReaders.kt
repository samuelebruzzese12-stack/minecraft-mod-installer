package com.mmi.modinstaller.data

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.mmi.modinstaller.core.ArchiveCorruptException
import com.mmi.modinstaller.core.ArchiveEntry
import com.mmi.modinstaller.core.ArchiveException
import com.mmi.modinstaller.core.ArchiveFormat
import com.mmi.modinstaller.core.ArchiveOpener
import com.mmi.modinstaller.core.ArchivePasswordException
import com.mmi.modinstaller.core.ArchiveReader
import com.mmi.modinstaller.core.ArchiveUnsupportedException
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.core.formatBytes
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/** Memoria massima concessa al decompressore LZMA (dizionario) per i 7z: oltre, l'archivio viene rifiutato. */
private const val SEVENZ_MAX_MEMORY_KB = 128 * 1024

/** Tolleranza oltre la dimensione dichiarata di un file RAR prima di considerarlo una bomba di decompressione. */
private const val RAR_SLACK_BYTES = 64L * 1024

/** Spazio libero da lasciare sempre disponibile sul telefono quando si estrae un file temporaneo. */
private const val TEMP_SPACE_MARGIN = 32L * 1024 * 1024

/** Apre ZIP (java.util.zip), 7z (Apache Commons Compress) e RAR4 (junrar). */
class AndroidArchiveOpener(
    private val limits: SafetyLimits,
    /** Cartella per i file temporanei usati dal lettore RAR. */
    private val tempDir: File
) : ArchiveOpener {

    override fun open(file: File, format: ArchiveFormat): ArchiveReader = when (format) {
        ArchiveFormat.ZIP -> ZipArchiveReader.open(file, limits)
        ArchiveFormat.SEVEN_Z -> SevenZipArchiveReader.open(file)
        ArchiveFormat.RAR -> RarArchiveReader.open(file, limits, tempDir)
        else -> throw ArchiveUnsupportedException(FindingCode.UNSUPPORTED_FORMAT, "Formato non supportato: $format")
    }
}

/** Traduce gli errori delle librerie in eccezioni dell'app, con messaggi comprensibili. */
internal object ReaderErrors {

    fun map(e: Throwable, what: String): Throwable {
        if (e is ArchiveException || e is CancellationException || e is OutOfMemoryError) return e
        if (e !is Exception) return e
        val type = e.javaClass.simpleName
        val text = e.message.orEmpty()
        return when {
            type.contains("Password", ignoreCase = true) || type.contains("Encrypt", ignoreCase = true) ->
                ArchivePasswordException()
            type.contains("MemoryLimit", ignoreCase = true) ->
                LimitExceededException(
                    FindingCode.UNCOMPRESSED_TOO_LARGE,
                    "L'archivio richiede troppa memoria per essere letto su questo telefono"
                )
            text.contains("No space left", ignoreCase = true) || text.contains("ENOSPC", ignoreCase = true) ->
                LimitExceededException(FindingCode.DISK_SPACE, "Spazio insufficiente sul telefono")
            text.contains("Unsupported", ignoreCase = true) || type.contains("Unsupported", ignoreCase = true) ->
                ArchiveUnsupportedException(FindingCode.UNSUPPORTED_COMPRESSION, "$what: metodo di compressione non supportato")
            else ->
                ArchiveCorruptException("$what danneggiato o non valido${if (text.isNotEmpty()) " ($text)" else ""}", e)
        }
    }
}

// --------------------------------------------------------------------------------------------- 7z

class SevenZipArchiveReader private constructor(private val sevenZ: SevenZFile) : ArchiveReader {

    override val format: ArchiveFormat = ArchiveFormat.SEVEN_Z

    override val totalSizeHint: Long = computeTotal(sevenZ)

    override fun forEachEntry(visitor: (entry: ArchiveEntry, content: InputStream?) -> Unit) {
        try {
            while (true) {
                val e = sevenZ.nextEntry ?: break
                if (e.isAntiItem) continue
                val name = e.name ?: ""
                val entry = ArchiveEntry(name, e.isDirectory, e.size, -1L)
                if (e.isDirectory) {
                    visitor(entry, null)
                    continue
                }
                val stream = EntryStream(sevenZ, e.size, if (e.hasCrc) e.crcValue else -1L)
                visitor(entry, stream)
                stream.verifyIfComplete(name)
            }
        } catch (t: Throwable) {
            throw ReaderErrors.map(t, "Archivio 7z")
        }
    }

    override fun close() {
        try {
            sevenZ.close()
        } catch (e: IOException) {
            // niente da fare
        }
    }

    /** Legge i dati dell'elemento corrente di [SevenZFile]; controlla il CRC se l'elemento è stato letto per intero. */
    private class EntryStream(
        private val file: SevenZFile,
        private val declaredSize: Long,
        private val expectedCrc: Long
    ) : InputStream() {
        private val crc = CRC32()
        private var count = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = file.read(b, off, len)
            if (n > 0) {
                crc.update(b, off, n)
                count += n
            }
            return n
        }

        fun verifyIfComplete(name: String) {
            if (declaredSize >= 0 && count == declaredSize && expectedCrc >= 0 && crc.value != expectedCrc) {
                throw ArchiveCorruptException("Archivio 7z danneggiato: il file «$name» non supera il controllo CRC")
            }
        }
    }

    companion object {
        fun open(file: File): SevenZipArchiveReader {
            var opened: SevenZFile? = null
            try {
                val created: SevenZFile = SevenZFile.builder().setFile(file).setMaxMemoryLimitKb(SEVENZ_MAX_MEMORY_KB).get()
                opened = created
                return SevenZipArchiveReader(created)
            } catch (t: Throwable) {
                try {
                    opened?.close()
                } catch (ignored: Exception) {
                    // niente da fare
                }
                throw ReaderErrors.map(t, "Archivio 7z")
            }
        }

        private fun computeTotal(sz: SevenZFile): Long {
            var total = 0L
            for (e in sz.entries) {
                if (!e.isDirectory && e.size > 0) total += e.size
            }
            return total
        }
    }
}

// --------------------------------------------------------------------------------------------- RAR

/**
 * Lettore RAR4 basato su junrar. Ogni file viene estratto in un file temporaneo SOLO quando il chiamante
 * inizia a leggerlo (e il temporaneo è cancellato subito dopo). Negli archivi "solidi" ogni voce va decompressa
 * in ordine, quindi viene estratta anche se il chiamante la ignora.
 */
class RarArchiveReader private constructor(
    private val archive: Archive,
    private val limits: SafetyLimits,
    private val tempDir: File
) : ArchiveReader {

    override val format: ArchiveFormat = ArchiveFormat.RAR

    override val totalSizeHint: Long = computeTotal(archive)

    override fun forEachEntry(visitor: (entry: ArchiveEntry, content: InputStream?) -> Unit) {
        val solid = isSolid(archive)
        try {
            tempDir.mkdirs()
            while (true) {
                val header = archive.nextFileHeader() ?: break
                if (header.isEncrypted) throw ArchivePasswordException()
                val name = nameOf(header)
                if (header.isDirectory) {
                    visitor(ArchiveEntry(name, true, 0L, 0L), null)
                    continue
                }
                val size = header.fullUnpackSize
                val stream = LazyRarStream(archive, header, size, limits, tempDir)
                try {
                    visitor(ArchiveEntry(name, false, size, header.fullPackSize), stream)
                    if (solid) stream.ensureExtracted()
                } finally {
                    stream.close()
                }
            }
        } catch (t: Throwable) {
            throw ReaderErrors.map(t, "Archivio RAR")
        }
    }

    override fun close() {
        try {
            archive.close()
        } catch (e: Exception) {
            // niente da fare
        }
    }

    private class LazyRarStream(
        private val archive: Archive,
        private val header: FileHeader,
        private val declaredSize: Long,
        private val limits: SafetyLimits,
        private val tempDir: File
    ) : InputStream() {
        private var temp: File? = null
        private var input: InputStream? = null
        private var extracted = false
        private var closed = false

        fun ensureExtracted() {
            if (!extracted) extract()
        }

        private fun extract() {
            if (declaredSize > limits.maxEntryBytes) {
                throw LimitExceededException(
                    FindingCode.ENTRY_TOO_LARGE,
                    "Un file nell'archivio è troppo grande (${formatBytes(declaredSize)})"
                )
            }
            if (declaredSize > 0 && tempDir.usableSpace < declaredSize + TEMP_SPACE_MARGIN) {
                throw LimitExceededException(
                    FindingCode.DISK_SPACE,
                    "Spazio insufficiente per leggere l'archivio RAR: servono ${formatBytes(declaredSize + TEMP_SPACE_MARGIN)} liberi"
                )
            }
            val file = File.createTempFile("rar_", ".tmp", tempDir)
            temp = file
            val cap = if (declaredSize >= 0) minOf(limits.maxEntryBytes, declaredSize + RAR_SLACK_BYTES) else limits.maxEntryBytes
            file.outputStream().buffered(64 * 1024).use { out ->
                archive.extractFile(header, BoundedOutput(out, cap))
            }
            extracted = true
        }

        private fun source(): InputStream {
            val existing = input
            if (existing != null) return existing
            if (closed) throw IOException("Flusso già chiuso")
            ensureExtracted()
            val file = temp ?: throw IOException("File temporaneo mancante")
            val stream = FileInputStream(file)
            input = stream
            return stream
        }

        override fun read(): Int = source().read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = source().read(b, off, len)

        override fun available(): Int = input?.available() ?: 0

        override fun close() {
            closed = true
            try {
                input?.close()
            } catch (e: IOException) {
                // niente da fare
            }
            input = null
            temp?.delete()
            temp = null
        }
    }

    /** Scrittura con tetto massimo: una voce che decomprime più del dichiarato è un archivio danneggiato o una bomba. */
    private class BoundedOutput(private val inner: OutputStream, private val cap: Long) : OutputStream() {
        private var written = 0L

        override fun write(b: Int) {
            add(1)
            inner.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            add(len.toLong())
            inner.write(b, off, len)
        }

        override fun flush() {
            inner.flush()
        }

        private fun add(n: Long) {
            written += n
            if (written > cap) {
                throw LimitExceededException(
                    FindingCode.COMPRESSION_BOMB,
                    "Un file dell'archivio RAR contiene più dati di quelli dichiarati: archivio danneggiato o pericoloso"
                )
            }
        }
    }

    companion object {
        fun open(file: File, limits: SafetyLimits, tempDir: File): RarArchiveReader {
            var opened: Archive? = null
            try {
                val created = Archive(file)
                opened = created
                return RarArchiveReader(created, limits, tempDir)
            } catch (t: Throwable) {
                try {
                    opened?.close()
                } catch (ignored: Exception) {
                    // niente da fare
                }
                throw ReaderErrors.map(t, "Archivio RAR")
            }
        }

        /**
         * True se l'archivio è "solido" (le voci dipendono l'una dall'altra). Se non si riesce a capirlo si assume di sì,
         * che è la scelta prudente. Letto per riflessione perché l'API di junrar cambia tra le versioni.
         */
        private fun isSolid(archive: Archive): Boolean {
            return try {
                val main = archive.javaClass.getMethod("getMainHeader").invoke(archive) ?: return true
                (main.javaClass.getMethod("isSolid").invoke(main) as? Boolean) ?: true
            } catch (e: Throwable) {
                true
            }
        }

        private fun computeTotal(archive: Archive): Long {
            var total = 0L
            try {
                for (h in archive.fileHeaders) {
                    if (!h.isDirectory && h.fullUnpackSize > 0) total += h.fullUnpackSize
                }
            } catch (e: Exception) {
                return -1L
            }
            return total
        }

        /** Il nome del file nelle versioni di junrar cambia (getFileName / getFileNameW / getFileNameString). */
        private fun nameOf(header: FileHeader): String {
            for (method in arrayOf("getFileName", "getFileNameW", "getFileNameString")) {
                try {
                    val value = header.javaClass.getMethod(method).invoke(header) as? String
                    if (!value.isNullOrEmpty()) return value
                } catch (e: Exception) {
                    // prova il nome successivo
                }
            }
            return ""
        }
    }
}
