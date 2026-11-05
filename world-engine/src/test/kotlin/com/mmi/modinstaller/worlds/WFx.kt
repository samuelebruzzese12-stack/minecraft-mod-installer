package com.mmi.modinstaller.worlds

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Attrezzi per le prove: level.dat Java e Bedrock, mondi finti e archivi ZIP. */
object WFx {

    fun tempDir(): File = Files.createTempDirectory("mmi-worlds").toFile()

    fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }

    fun bytes(size: Int, seed: Long): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    // ------------------------------------------------------------------ NBT big-endian (Java)

    private fun DataOutputStream.name(type: Int, name: String) {
        writeByte(type)
        writeUTF(name)
    }

    /** Un level.dat Java: compound radice con «Data» (nome, versione, modalità). [version] null = mondo vecchio senza «Version». */
    fun javaLevelDat(
        name: String = "Mondo di Prova",
        version: String? = "1.20.1",
        dataVersion: Int = 3465,
        gameType: Int = 0,
        hardcore: Boolean = false,
        gzip: Boolean = true
    ): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { o ->
            o.name(10, "")
            o.name(10, "Data")
            o.name(8, "LevelName"); o.writeUTF(name)
            o.name(3, "DataVersion"); o.writeInt(dataVersion)
            if (version != null) {
                o.name(10, "Version")
                o.name(8, "Name"); o.writeUTF(version)
                o.name(3, "Id"); o.writeInt(dataVersion)
                o.name(1, "Snapshot"); o.writeByte(0)
                o.writeByte(0)
            }
            o.name(3, "GameType"); o.writeInt(gameType)
            o.name(1, "hardcore"); o.writeByte(if (hardcore) 1 else 0)
            o.name(4, "LastPlayed"); o.writeLong(1_793_800_000_000L)
            o.name(9, "ServerBrands"); o.writeByte(8); o.writeInt(1); o.writeUTF("fabric")
            o.name(11, "WanderingTraderId"); o.writeInt(4); repeat(4) { o.writeInt(it) }
            o.writeByte(0) // fine di Data
            o.writeByte(0) // fine della radice
        }
        if (!gzip) return raw.toByteArray()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw.toByteArray()) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ NBT little-endian (Bedrock)

    fun bedrockLevelDat(name: String = "Mondo Bedrock", version: List<Int> = listOf(1, 21, 0, 3, 0), gameType: Int = 1): ByteArray {
        val body = ByteArrayOutputStream()
        fun u16(n: Int) { body.write(n and 0xFF); body.write((n shr 8) and 0xFF) }
        fun i32(n: Int) { val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array(); body.write(b) }
        fun str(s: String) { val b = s.toByteArray(); u16(b.size); body.write(b) }
        fun tag(type: Int, n: String) { body.write(type); str(n) }
        tag(10, "")
        tag(8, "LevelName"); str(name)
        tag(3, "GameType"); i32(gameType)
        tag(3, "StorageVersion"); i32(10)
        tag(9, "lastOpenedWithVersion"); body.write(3); i32(version.size); version.forEach { i32(it) }
        body.write(0)
        val payload = body.toByteArray()
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(10).putInt(payload.size).array()
        return head + payload
    }

    // ------------------------------------------------------------------ mondi e archivi

    /** I file di un mondo Java completo: tre dimensioni più una dei data pack, giocatori, data pack, icona e session.lock. */
    fun javaWorld(prefix: String = "", name: String = "Mondo di Prova", levelDat: ByteArray = javaLevelDat(name)): LinkedHashMap<String, ByteArray> {
        val p = if (prefix.isEmpty()) "" else "$prefix/"
        val m = LinkedHashMap<String, ByteArray>()
        m[p + "level.dat"] = levelDat
        m[p + "level.dat_old"] = levelDat
        m[p + "session.lock"] = byteArrayOf(0, 0, 1, 2)
        m[p + "icon.png"] = bytes(300, 1)
        m[p + "region/r.0.0.mca"] = bytes(8192, 2)
        m[p + "region/r.-1.0.mca"] = bytes(8192, 3)
        m[p + "entities/r.0.0.mca"] = bytes(4096, 4)
        m[p + "poi/r.0.0.mca"] = bytes(4096, 5)
        m[p + "DIM-1/region/r.0.0.mca"] = bytes(4096, 6)
        m[p + "DIM1/region/r.0.0.mca"] = bytes(4096, 7)
        m[p + "dimensions/mymod/sky/region/r.0.0.mca"] = bytes(4096, 8)
        m[p + "playerdata/0f8e5b4a-1111-2222-3333-444455556666.dat"] = bytes(700, 9)
        m[p + "playerdata/0f8e5b4a-1111-2222-3333-444455556666.dat_old"] = bytes(700, 9)
        m[p + "datapacks/pacchetto.zip"] = bytes(500, 10)
        m[p + "data/raids.dat"] = bytes(120, 11)
        m[p + "advancements/0f8e5b4a-1111-2222-3333-444455556666.json"] = "{}".toByteArray()
        return m
    }

    fun bedrockWorld(prefix: String = "", name: String = "Mondo Bedrock", withDb: Boolean = true): LinkedHashMap<String, ByteArray> {
        val p = if (prefix.isEmpty()) "" else "$prefix/"
        val m = LinkedHashMap<String, ByteArray>()
        m[p + "level.dat"] = bedrockLevelDat(name)
        m[p + "levelname.txt"] = name.toByteArray()
        if (withDb) {
            m[p + "db/CURRENT"] = "MANIFEST-000001\n".toByteArray()
            m[p + "db/MANIFEST-000001"] = bytes(64, 12)
            m[p + "db/000003.ldb"] = bytes(2048, 13)
        }
        m[p + "world_icon.jpeg"] = bytes(200, 14)
        return m
    }

    /** Scrive uno ZIP; con [stored] le voci non sono compresse (utile per alterarne i byte). */
    fun zip(target: File, entries: Map<String, ByteArray>, stored: Boolean = false): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().also { it.update(data) }.value
                }
                zip.putNextEntry(e)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return target
    }
}
