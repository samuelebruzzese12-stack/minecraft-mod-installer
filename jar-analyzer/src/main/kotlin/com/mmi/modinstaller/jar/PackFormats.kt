package com.mmi.modinstaller.jar

/**
 * Numeri di formato dei resource pack e versioni di Minecraft a cui corrispondono. La tabella si ferma volutamente alla
 * 1.21.1: per i formati più recenti questa app non dichiara nulla invece di indovinare. Minecraft carica comunque i pacchetti
 * con un formato diverso, mostrando solo un avviso: il confronto è informativo.
 */
object PackFormats {

    private class Entry(val format: Int, val from: String, val to: String)

    private val RESOURCE_PACKS = listOf(
        Entry(1, "1.6.1", "1.8.9"),
        Entry(2, "1.9", "1.10.2"),
        Entry(3, "1.11", "1.12.2"),
        Entry(4, "1.13", "1.14.4"),
        Entry(5, "1.15", "1.16.1"),
        Entry(6, "1.16.2", "1.16.5"),
        Entry(7, "1.17", "1.17.1"),
        Entry(8, "1.18", "1.18.2"),
        Entry(9, "1.19", "1.19.2"),
        Entry(12, "1.19.3", "1.19.3"),
        Entry(13, "1.19.4", "1.19.4"),
        Entry(15, "1.20", "1.20.1"),
        Entry(18, "1.20.2", "1.20.2"),
        Entry(22, "1.20.3", "1.20.4"),
        Entry(32, "1.20.5", "1.20.6"),
        Entry(34, "1.21", "1.21.1")
    )

    private fun entry(format: Int?): Entry? = RESOURCE_PACKS.firstOrNull { it.format == format }

    /** «1.20 - 1.20.1», o null se il formato non è nella tabella. */
    fun describe(format: Int?): String? {
        val e = entry(format) ?: return null
        return if (e.from == e.to) e.from else "${e.from} - ${e.to}"
    }

    /** Il formato del pacchetto è giusto per questa versione di Minecraft? UNKNOWN se non si sa. */
    fun test(format: Int?, minecraft: ModVersion?): Compat {
        val e = entry(format) ?: return Compat.UNKNOWN
        if (minecraft == null || !minecraft.comparable) return Compat.UNKNOWN
        val low = ModVersion.parse(e.from) ?: return Compat.UNKNOWN
        val high = ModVersion.parse(e.to) ?: return Compat.UNKNOWN
        return Compat.of(minecraft >= low && minecraft <= high)
    }
}
