package com.mmi.modinstaller.worlds

import com.mmi.modinstaller.core.LevelInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Quello che serve sapere di un `level.dat`. */
internal class LevelSummary(
    val edition: WorldEdition,
    val levelName: String?,
    val version: String?,
    val dataVersion: Int?,
    val gameType: Int?,
    val hardcore: Boolean
)

/**
 * Lettura di `level.dat` con limiti (dimensione, profondità, numero di voci). Java: NBT big-endian compresso con gzip (a volte
 * non compresso), con tutto dentro il compound «Data». Bedrock: 8 byte di intestazione e NBT little-endian (lo legge già il
 * motore dell'INSTALLER BEDROCK, [LevelInfo]).
 */
internal object LevelDat {

    private class NbtException(message: String) : Exception(message)

    fun isGzip(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** Un `level.dat` Bedrock ha la versione del formato (piccola) e la lunghezza del resto nei primi 8 byte. */
    fun looksBedrock(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || isGzip(bytes)) return false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val storage = bb.int
        val length = bb.int
        return storage in 1..100 && length.toLong() == bytes.size - 8L && bytes[8].toInt() == 10
    }

    fun readJava(bytes: ByteArray, maxUncompressed: Int): LevelSummary? {
        val raw = if (isGzip(bytes)) gunzip(bytes, maxUncompressed) ?: return null else bytes
        val root = try {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            if (bb.get().toInt() != 10) return null
            readString(bb)
            @Suppress("UNCHECKED_CAST")
            readPayload(bb, 10, 0, IntArray(1) { 200_000 }) as? Map<String, Any?>
        } catch (e: NbtException) {
            null
        } catch (e: BufferUnderflowException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        val data = root["Data"] as? Map<String, Any?> ?: return null
        @Suppress("UNCHECKED_CAST")
        val version = data["Version"] as? Map<String, Any?>
        return LevelSummary(
            edition = WorldEdition.JAVA,
            levelName = data["LevelName"] as? String,
            version = version?.get("Name") as? String,
            dataVersion = (data["DataVersion"] as? Number)?.toInt() ?: (version?.get("Id") as? Number)?.toInt(),
            gameType = (data["GameType"] as? Number)?.toInt(),
            hardcore = (data["hardcore"] as? Number)?.toInt() == 1
        )
    }

    fun readBedrock(bytes: ByteArray): LevelSummary? {
        val info = LevelInfo.parse(bytes) ?: return null
        return LevelSummary(
            edition = WorldEdition.BEDROCK,
            levelName = info.name,
            version = info.lastOpenedWith?.parts?.take(3)?.joinToString("."),
            dataVersion = null,
            gameType = info.gameType,
            hardcore = false
        )
    }

    private fun gunzip(bytes: ByteArray, max: Int): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
            out.toByteArray()
        }
    } catch (e: java.io.IOException) {
        null
    }

    private fun readString(bb: ByteBuffer): String {
        val n = bb.short.toInt() and 0xFFFF
        if (n > bb.remaining()) throw NbtException("stringa oltre la fine")
        val arr = ByteArray(n)
        bb.get(arr)
        return String(arr, Charsets.UTF_8)
    }

    private fun skip(bb: ByteBuffer, bytes: Long) {
        if (bytes < 0 || bytes > bb.remaining()) throw NbtException("array oltre la fine")
        bb.position(bb.position() + bytes.toInt())
    }

    private fun readPayload(bb: ByteBuffer, type: Int, depth: Int, budget: IntArray): Any? {
        if (depth > 32) throw NbtException("troppo annidato")
        budget[0] = budget[0] - 1
        if (budget[0] < 0) throw NbtException("troppi nodi")
        return when (type) {
            1 -> bb.get().toInt()
            2 -> bb.short.toInt()
            3 -> bb.int
            4 -> bb.long
            5 -> bb.float
            6 -> bb.double
            7 -> bb.int.also { skip(bb, it.toLong()) }
            8 -> readString(bb)
            9 -> {
                val elementType = bb.get().toInt()
                val n = bb.int
                if (n < 0 || n > bb.remaining()) throw NbtException("lista non valida")
                if (n > 0 && elementType == 0) throw NbtException("lista senza tipo")
                val list = ArrayList<Any?>(minOf(n, 256))
                for (i in 0 until n) list.add(readPayload(bb, elementType, depth + 1, budget))
                list
            }
            10 -> {
                val map = LinkedHashMap<String, Any?>()
                while (true) {
                    val t = bb.get().toInt()
                    if (t == 0) break
                    val key = readString(bb)
                    map[key] = readPayload(bb, t, depth + 1, budget)
                }
                map
            }
            11 -> bb.int.also { skip(bb, it.toLong() * 4) }
            12 -> bb.int.also { skip(bb, it.toLong() * 8) }
            else -> throw NbtException("tipo di tag sconosciuto: $type")
        }
    }
}
