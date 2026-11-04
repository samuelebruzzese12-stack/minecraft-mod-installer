package com.mmi.modinstaller.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lettore NBT little-endian (formato Bedrock) minimale e con limiti, sufficiente per leggere `level.dat`. */
object Nbt {
    private class NbtException(message: String) : Exception(message)

    /**
     * Legge un `level.dat` Bedrock: 8 byte di intestazione (versione + lunghezza) seguiti dal compound radice.
     * Restituisce null se il contenuto non è leggibile.
     */
    fun parseBedrockLevelDat(bytes: ByteArray): Map<String, Any?>? {
        if (bytes.size < 12) return null
        return try {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(8)
            val rootType = bb.get().toInt()
            if (rootType != 10) return null
            readString(bb)
            @Suppress("UNCHECKED_CAST")
            readPayload(bb, 10, 0, IntArray(1) { 50_000 }) as? Map<String, Any?>
        } catch (e: Exception) {
            null
        }
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
        if (depth > 24) throw NbtException("troppo annidato")
        budget[0] = budget[0] - 1
        if (budget[0] < 0) throw NbtException("troppi nodi")
        return when (type) {
            1 -> bb.get().toInt()
            2 -> bb.short.toInt()
            3 -> bb.int
            4 -> bb.long
            5 -> bb.float
            6 -> bb.double
            7 -> {
                val n = bb.int
                skip(bb, n.toLong())
                n
            }
            8 -> readString(bb)
            9 -> {
                val elementType = bb.get().toInt()
                val n = bb.int
                if (n < 0 || n > bb.remaining()) throw NbtException("lista non valida")
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
            11 -> {
                val n = bb.int
                skip(bb, n.toLong() * 4)
                n
            }
            12 -> {
                val n = bb.int
                skip(bb, n.toLong() * 8)
                n
            }
            else -> throw NbtException("tipo di tag sconosciuto: $type")
        }
    }
}

/** Informazioni utili estratte da `level.dat`. */
class LevelInfo(
    val name: String?,
    val lastOpenedWith: McVersion?,
    val storageVersion: Int?,
    val gameType: Int?,
    val experiments: List<String>
) {
    companion object {
        fun parse(bytes: ByteArray): LevelInfo? {
            val root = Nbt.parseBedrockLevelDat(bytes) ?: return null
            val name = root["LevelName"] as? String
            val rawVersion = root["lastOpenedWithVersion"] as? List<*>
            var last: McVersion? = null
            if (rawVersion != null) {
                val nums = rawVersion.mapNotNull { (it as? Number)?.toInt() }
                if (nums.isNotEmpty()) last = McVersion(nums)
            }
            val experiments = ArrayList<String>()
            val exp = root["experiments"] as? Map<*, *>
            if (exp != null) {
                for ((k, v) in exp) {
                    val key = k as? String ?: continue
                    if (key == "experiments_ever_used" || key == "saved_with_toggled_experiments") continue
                    if ((v as? Number)?.toInt() == 1) experiments.add(key)
                }
            }
            return LevelInfo(
                name,
                last,
                (root["StorageVersion"] as? Number)?.toInt(),
                (root["GameType"] as? Number)?.toInt(),
                experiments
            )
        }
    }
}
