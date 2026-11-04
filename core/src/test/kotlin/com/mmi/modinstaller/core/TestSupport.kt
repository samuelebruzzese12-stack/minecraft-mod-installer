package com.mmi.modinstaller.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Utilità per costruire archivi e file Minecraft di prova. */
object T {

    fun tmpDir(): File = Files.createTempDirectory("mmi-test").toFile()

    fun uuidOf(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** Crea uno ZIP. I nomi che finiscono con "/" sono cartelle. */
    fun zip(file: File, entries: Map<String, ByteArray>, stored: Boolean = false): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (name.endsWith("/")) {
                    z.putNextEntry(e)
                    z.closeEntry()
                    continue
                }
                if (stored) {
                    val crc = CRC32()
                    crc.update(data)
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = crc.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return file
    }

    fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val f = File.createTempFile("mmi-zip", ".zip")
        try {
            zip(f, entries)
            return f.readBytes()
        } finally {
            f.delete()
        }
    }

    fun entriesOf(file: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(file).use { z ->
            val en = z.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                out[e.name] = z.getInputStream(e).readBytes()
            }
        }
        return out
    }

    fun manifest(
        name: String = "Test Pack",
        uuid: String,
        version: List<Int> = listOf(1, 0, 0),
        type: String = "resources",
        deps: List<Map<String, Any?>> = emptyList(),
        minEngine: List<Int>? = listOf(1, 16, 0),
        description: String = "A test pack",
        extraModules: List<Map<String, Any?>> = emptyList(),
        authors: List<String> = listOf("Tester")
    ): ByteArray {
        val header = linkedMapOf<String, Any?>("name" to name, "description" to description, "uuid" to uuid, "version" to version)
        if (minEngine != null) header["min_engine_version"] = minEngine
        val modules = mutableListOf<Any?>(
            linkedMapOf("type" to type, "uuid" to uuidOf("module-$uuid"), "version" to version)
        )
        modules.addAll(extraModules)
        val root = linkedMapOf<String, Any?>("format_version" to 2, "header" to header, "modules" to modules)
        if (deps.isNotEmpty()) root["dependencies"] = deps
        root["metadata"] = mapOf("authors" to authors)
        return bytes(Json.stringify(root, true))
    }

    fun uuidDep(uuid: String, version: List<Int> = listOf(1, 0, 0)): Map<String, Any?> =
        mapOf("uuid" to uuid, "version" to version)

    fun moduleDep(name: String, version: String): Map<String, Any?> =
        mapOf("module_name" to name, "version" to version)

    /** PNG 1x1 valido. */
    fun png(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    private fun ByteArrayOutputStream.le16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(v: Int) {
        le16(v and 0xFFFF)
        le16((v shr 16) and 0xFFFF)
    }

    private fun ByteArrayOutputStream.tag(type: Int, name: String) {
        write(type)
        val nb = name.toByteArray(Charsets.UTF_8)
        le16(nb.size)
        write(nb)
    }

    /** level.dat Bedrock: intestazione di 8 byte + NBT little-endian. */
    fun levelDat(name: String, version: List<Int>, experiments: List<String> = emptyList()): ByteArray {
        val body = ByteArrayOutputStream()
        body.tag(10, "")
        body.tag(8, "LevelName")
        val nb = name.toByteArray(Charsets.UTF_8)
        body.le16(nb.size)
        body.write(nb)
        body.tag(3, "StorageVersion")
        body.le32(10)
        body.tag(3, "GameType")
        body.le32(0)
        body.tag(9, "lastOpenedWithVersion")
        body.write(3)
        body.le32(version.size)
        for (v in version) body.le32(v)
        if (experiments.isNotEmpty()) {
            body.tag(10, "experiments")
            for (e in experiments) {
                body.tag(1, e)
                body.write(1)
            }
            body.tag(1, "experiments_ever_used")
            body.write(1)
            body.write(0)
        }
        body.write(0)
        val payload = body.toByteArray()
        val out = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(10)
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    /** Contenuto tipico di una mappa Bedrock. */
    fun worldEntries(prefix: String, name: String, version: List<Int>, experiments: List<String> = emptyList()): LinkedHashMap<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        m[prefix + "level.dat"] = levelDat(name, version, experiments)
        m[prefix + "levelname.txt"] = bytes(name)
        m[prefix + "db/CURRENT"] = bytes("MANIFEST-000001\n")
        m[prefix + "db/MANIFEST-000001"] = ByteArray(64) { it.toByte() }
        m[prefix + "db/000003.ldb"] = ByteArray(2048) { (it * 7).toByte() }
        m[prefix + "world_icon.jpeg"] = ByteArray(300) { 0x42 }
        return m
    }

    fun packEntries(
        prefix: String,
        name: String,
        uuid: String,
        type: String = "resources",
        deps: List<Map<String, Any?>> = emptyList(),
        minEngine: List<Int>? = listOf(1, 16, 0),
        version: List<Int> = listOf(1, 0, 0)
    ): LinkedHashMap<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        m[prefix + "manifest.json"] = manifest(name = name, uuid = uuid, type = type, deps = deps, minEngine = minEngine, version = version)
        m[prefix + "pack_icon.png"] = png()
        if (type == "resources") {
            m[prefix + "textures/blocks/stone.png"] = png()
            m[prefix + "textures/blocks/dirt.png"] = png()
        } else {
            m[prefix + "entities/thing.json"] = bytes("{\"format_version\":\"1.16.0\"}")
        }
        return m
    }
}
