package com.mmi.modinstaller.core

/** Versione "a punti" (es. 1.21.50.7) usata per `min_engine_version` e per la versione installata di Minecraft. */
data class McVersion(val parts: List<Int>) : Comparable<McVersion> {

    override fun compareTo(other: McVersion): Int = compareFirst(other, Int.MAX_VALUE)

    /** Confronta solo le prime [count] componenti (le mancanti valgono 0). */
    fun compareFirst(other: McVersion, count: Int): Int {
        val n = minOf(count, maxOf(parts.size, other.parts.size))
        for (i in 0 until n) {
            val a = if (i < parts.size) parts[i] else 0
            val b = if (i < other.parts.size) other.parts[i] else 0
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /** "1.21.50.7", "1.21.0-beta.1" → [1,21,50,7] / [1,21,0]. Restituisce null se non è numerica. */
        fun parse(text: String?): McVersion? {
            if (text == null) return null
            val core = text.trim().substringBefore('-').substringBefore('+')
            if (core.isEmpty()) return null
            val nums = ArrayList<Int>()
            for (piece in core.split('.')) {
                val n = piece.trim().toIntOrNull()
                if (n == null || n < 0) return null
                nums.add(n)
            }
            return if (nums.isEmpty()) null else McVersion(nums)
        }

        /** Accetta sia l'array `[1, 20, 0]` sia la stringa semver `"1.20.0"`. */
        fun fromJson(value: Any?): McVersion? {
            if (value is String) return parse(value)
            if (value is List<*>) {
                val nums = ArrayList<Int>()
                for (e in value) {
                    val n = (e as? Number)?.toInt()
                    if (n == null || n < 0) return null
                    nums.add(n)
                }
                return if (nums.isEmpty()) null else McVersion(nums)
            }
            return null
        }
    }
}
