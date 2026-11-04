package com.mmi.modinstaller.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipException
import java.util.zip.ZipFile

enum class ArchiveFormat { ZIP, SEVEN_Z, RAR, RAR5, JSON, HTML, GZIP, UNKNOWN }

class ArchiveEntry(
    val rawName: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long
)

/**
 * Lettore sequenziale di archivi. Il visitor riceve lo stream del contenuto (null per le cartelle) valido
 * solo durante la chiamata; può leggerlo in parte o ignorarlo: il lettore passa comunque alla voce successiva.
 */
interface ArchiveReader : Closeable {
    val format: ArchiveFormat

    /** Somma delle dimensioni decompresse dichiarate dalle intestazioni, o -1 se ignota. */
    val totalSizeHint: Long

    fun forEachEntry(visitor: (entry: ArchiveEntry, content: InputStream?) -> Unit)
}

fun interface ArchiveOpener {
    fun open(file: File, format: ArchiveFormat): ArchiveReader
}

/** Opener di base: solo ZIP (java.util.zip). L'app aggiunge 7z e RAR con librerie dedicate. */
class ZipOnlyOpener(private val limits: SafetyLimits = SafetyLimits()) : ArchiveOpener {
    override fun open(file: File, format: ArchiveFormat): ArchiveReader {
        if (format == ArchiveFormat.ZIP) return ZipArchiveReader.open(file, limits)
        throw ArchiveUnsupportedException(
            FindingCode.UNSUPPORTED_FORMAT,
            "Formato non supportato in questa configurazione: $format"
        )
    }
}

/** Riconoscimento del formato dai primi byte (non dall'estensione). */
object FormatSniffer {
    fun sniff(file: File): ArchiveFormat {
        val head = ByteArray(512)
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) return ArchiveFormat.UNKNOWN
        return sniff(head, n)
    }

    fun sniff(h: ByteArray, n: Int): ArchiveFormat {
        fun b(i: Int): Int = if (i < n) (h[i].toInt() and 0xFF) else -1
        if (b(0) == 0x50 && b(1) == 0x4B) {
            val sig = (b(2) == 3 && b(3) == 4) || (b(2) == 5 && b(3) == 6) || (b(2) == 7 && b(3) == 8)
            if (sig) return ArchiveFormat.ZIP
        }
        if (b(0) == 0x37 && b(1) == 0x7A && b(2) == 0xBC && b(3) == 0xAF && b(4) == 0x27 && b(5) == 0x1C) {
            return ArchiveFormat.SEVEN_Z
        }
        if (b(0) == 0x52 && b(1) == 0x61 && b(2) == 0x72 && b(3) == 0x21 && b(4) == 0x1A && b(5) == 0x07) {
            return if (b(6) == 0x01) ArchiveFormat.RAR5 else ArchiveFormat.RAR
        }
        if (b(0) == 0x1F && b(1) == 0x8B) return ArchiveFormat.GZIP
        var i = 0
        if (b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) i = 3
        while (i < n && (b(i) == 0x20 || b(i) == 0x09 || b(i) == 0x0A || b(i) == 0x0D)) i++
        if (i < n) {
            val c = b(i)
            if (c == '<'.code) return ArchiveFormat.HTML
            if (c == '{'.code || c == '['.code) return ArchiveFormat.JSON
        }
        return ArchiveFormat.UNKNOWN
    }
}

/**
 * Lettura minimale della directory centrale ZIP: serve a distinguere archivi cifrati, metodi di compressione
 * non supportati, voci con percorsi pericolosi e file troncati PRIMA di aprirli con java.util.zip.
 */
internal object ZipDirectory {
    class Info(
        val entryCount: Int,
        val encryptedEntries: Int,
        val unsupportedMethods: Set<Int>,
        val unsafeNames: List<String>,
        val totalUncompressed: Long
    )

    private const val MAX_CD_BYTES = 128L * 1024 * 1024

    private fun u16(b: ByteArray, o: Int): Long =
        ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toLong()

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)

    private fun sig(b: ByteArray, o: Int, s2: Int, s3: Int): Boolean =
        b[o] == 0x50.toByte() && b[o + 1] == 0x4B.toByte() && b[o + 2] == s2.toByte() && b[o + 3] == s3.toByte()

    fun inspect(file: File, limits: SafetyLimits): Info {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 22) throw ArchiveCorruptException("Il file è troppo piccolo per essere un archivio ZIP valido")
            val tailSize = minOf(len, 22L + 65535L).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(len - tailSize)
            raf.readFully(tail)
            var eocd = -1
            var i = tailSize - 22
            while (i >= 0) {
                if (sig(tail, i, 0x05, 0x06)) {
                    eocd = i
                    break
                }
                i--
            }
            if (eocd < 0) {
                throw ArchiveCorruptException(
                    "Directory centrale ZIP non trovata: il file è incompleto o danneggiato (download interrotto?)"
                )
            }
            var total = u16(tail, eocd + 10)
            var cdSize = u32(tail, eocd + 12)
            var cdOffset = u32(tail, eocd + 16)
            if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                val locPos = len - tailSize + eocd - 20
                if (locPos >= 0) {
                    val loc = ByteArray(20)
                    raf.seek(locPos)
                    raf.readFully(loc)
                    if (sig(loc, 0, 0x06, 0x07)) {
                        val z64 = u64(loc, 8)
                        if (z64 < 0 || z64 + 56 > len) throw ArchiveCorruptException("Record ZIP64 fuori dal file")
                        val rec = ByteArray(56)
                        raf.seek(z64)
                        raf.readFully(rec)
                        if (sig(rec, 0, 0x06, 0x06)) {
                            total = u64(rec, 32)
                            cdSize = u64(rec, 40)
                            cdOffset = u64(rec, 48)
                        }
                    }
                }
            }
            if (cdSize < 0 || cdOffset < 0 || cdOffset + cdSize > len) {
                throw ArchiveCorruptException("La directory centrale supera la fine del file: archivio troncato")
            }
            if (cdSize > MAX_CD_BYTES || total > limits.maxEntries) {
                throw LimitExceededException(
                    FindingCode.TOO_MANY_ENTRIES,
                    "L'archivio contiene troppi file (limite ${limits.maxEntries})"
                )
            }
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)

            var p = 0
            var count = 0
            var encrypted = 0
            var totalU = 0L
            val badMethods = HashSet<Int>()
            val unsafe = ArrayList<String>()
            while (p + 46 <= cd.size) {
                if (u32(cd, p) != 0x02014B50L) throw ArchiveCorruptException("Directory centrale ZIP danneggiata")
                val flags = u16(cd, p + 8).toInt()
                val method = u16(cd, p + 10).toInt()
                var usize = u32(cd, p + 24)
                val nameLen = u16(cd, p + 28).toInt()
                val extraLen = u16(cd, p + 30).toInt()
                val commentLen = u16(cd, p + 32).toInt()
                val next = p + 46 + nameLen + extraLen + commentLen
                if (next > cd.size) throw ArchiveCorruptException("Directory centrale ZIP danneggiata")
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                if (usize == 0xFFFFFFFFL) usize = zip64Size(cd, p + 46 + nameLen, extraLen, usize)
                totalU += usize
                if ((flags and 1) != 0) encrypted++
                if (method != 0 && method != 8) badMethods.add(method)
                if (PathSafety.normalize(name, limits.maxPathLength) is PathSafety.Normalized.Unsafe) unsafe.add(name)
                count++
                p = next
            }
            if (count.toLong() != total) {
                throw ArchiveCorruptException("Numero di voci incoerente nella directory ZIP ($count invece di $total)")
            }
            return Info(count, encrypted, badMethods, unsafe, totalU)
        }
    }

    /** Legge la dimensione decompressa dal campo extra ZIP64 (id 0x0001), se presente. */
    private fun zip64Size(cd: ByteArray, extraStart: Int, extraLen: Int, fallback: Long): Long {
        var q = extraStart
        val end = extraStart + extraLen
        while (q + 4 <= end) {
            val id = u16(cd, q).toInt()
            val size = u16(cd, q + 2).toInt()
            if (id == 0x0001 && size >= 8 && q + 4 + 8 <= end) return u64(cd, q + 4)
            q += 4 + size
        }
        return fallback
    }
}

/** Lettore ZIP basato su java.util.zip, con pre-controlli sulla directory centrale. */
class ZipArchiveReader private constructor(
    private val zip: ZipFile,
    override val totalSizeHint: Long
) : ArchiveReader {

    override val format: ArchiveFormat get() = ArchiveFormat.ZIP

    override fun forEachEntry(visitor: (entry: ArchiveEntry, content: InputStream?) -> Unit) {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            val meta = ArchiveEntry(e.name, e.isDirectory, e.size, e.compressedSize)
            if (e.isDirectory) {
                visitor(meta, null)
                continue
            }
            val input = try {
                zip.getInputStream(e)
            } catch (ex: ZipException) {
                throw ArchiveCorruptException("Voce non leggibile: ${e.name}", ex)
            }
            try {
                input.use { raw ->
                    val checked = CrcCountingStream(raw)
                    visitor(meta, checked)
                    verifyEntry(e, checked)
                }
            } catch (ex: ArchiveException) {
                throw ex
            } catch (ex: ZipException) {
                throw ArchiveCorruptException("Dati danneggiati nella voce '${e.name}': ${ex.message}", ex)
            } catch (ex: java.io.EOFException) {
                throw ArchiveCorruptException("Archivio troncato nella voce '${e.name}'", ex)
            }
        }
    }

    /**
     * Finisce di leggere la voce (se il visitor non l'ha fatto) e controlla dimensione e CRC32 dichiarati:
     * java.util.zip.ZipFile non verifica il checksum, quindi i dati alterati passerebbero inosservati.
     */
    private fun verifyEntry(e: java.util.zip.ZipEntry, stream: CrcCountingStream) {
        val declared = e.size
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = stream.read(buf, 0, buf.size)
            if (n < 0) break
            if (declared >= 0 && stream.count > declared) {
                throw ArchiveCorruptException("La voce '${e.name}' contiene più dati di quanti dichiarati: archivio danneggiato")
            }
        }
        if (declared >= 0 && stream.count != declared) {
            throw ArchiveCorruptException(
                "La voce '${e.name}' è incompleta (${stream.count} byte letti su $declared): archivio danneggiato"
            )
        }
        if (e.crc != -1L && stream.crc.value != e.crc) {
            throw ArchiveCorruptException("Checksum (CRC) non valido per '${e.name}': i dati sono danneggiati")
        }
    }

    private class CrcCountingStream(private val inner: InputStream) : InputStream() {
        val crc = java.util.zip.CRC32()
        var count: Long = 0
            private set

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) {
                crc.update(b)
                count++
            }
            return b
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(buf, off, len)
            if (n > 0) {
                crc.update(buf, off, n)
                count += n
            }
            return n
        }

        override fun available(): Int = inner.available()

        override fun close() {
            inner.close()
        }
    }

    override fun close() {
        zip.close()
    }

    companion object {
        fun open(file: File, limits: SafetyLimits = SafetyLimits()): ZipArchiveReader {
            val info = try {
                ZipDirectory.inspect(file, limits)
            } catch (e: ArchiveException) {
                throw e
            } catch (e: IOException) {
                throw ArchiveCorruptException("Archivio ZIP non leggibile: ${e.message}", e)
            }
            if (info.unsafeNames.isNotEmpty()) throw ArchiveUnsafeException(info.unsafeNames)
            if (info.encryptedEntries > 0) throw ArchivePasswordException()
            if (info.unsupportedMethods.isNotEmpty()) {
                throw ArchiveUnsupportedException(
                    FindingCode.UNSUPPORTED_COMPRESSION,
                    "Metodo di compressione ZIP non supportato (${info.unsupportedMethods.joinToString()}): " +
                        "ricomprimi l'archivio come ZIP standard (Deflate)"
                )
            }
            val zip = try {
                ZipFile(file)
            } catch (e: IOException) {
                throw ArchiveCorruptException("Archivio ZIP non leggibile: ${e.message}", e)
            }
            return ZipArchiveReader(zip, info.totalUncompressed)
        }
    }
}
