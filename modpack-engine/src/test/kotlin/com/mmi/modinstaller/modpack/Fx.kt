package com.mmi.modinstaller.modpack

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Attrezzi per le prove: archivi, mod finte e indici di modpack. */
object Fx {

    fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha512Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Uno ZIP in memoria con le voci date, nell'ordine dato. */
    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Una mod Fabric minima per Minecraft 1.20.1 (solo l'intestazione di una classe, non eseguibile). */
    fun fabricJar(id: String, name: String, version: String, deps: Map<String, String> = emptyMap()): ByteArray {
        val depends = LinkedHashMap<String, String>()
        depends["fabricloader"] = ">=0.14.0"
        depends["minecraft"] = "~1.20.1"
        depends["java"] = ">=17"
        depends.putAll(deps)
        val dependsJson = depends.entries.joinToString(",") { "\"" + it.key + "\":\"" + it.value + "\"" }
        val descriptor = """{"schemaVersion":1,"id":"$id","version":"$version","name":"$name","environment":"*","depends":{$dependsJson}}"""
        return zip(
            linkedMapOf(
                "fabric.mod.json" to descriptor.toByteArray(),
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\n\r\n".toByteArray(),
                "prova/$id/Main.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 61, 0, 0)
            )
        )
    }

    /** Un resource pack minimo (formato 15 = Minecraft 1.20.1). */
    fun resourcePack(label: String): ByteArray = zip(
        linkedMapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":15,"description":"$label"}}""".toByteArray(),
            "assets/minecraft/lang/it_it.json" to "{}".toByteArray()
        )
    )

    /** Una voce dell'elenco «files» dell'indice. */
    fun fileJson(
        path: String,
        bytes: ByteArray,
        url: String,
        sha1: Boolean = true,
        sha512: Boolean = true,
        size: Boolean = true,
        env: String? = null
    ): String {
        val hashes = ArrayList<String>()
        if (sha1) hashes.add("\"sha1\":\"" + sha1Hex(bytes) + "\"")
        if (sha512) hashes.add("\"sha512\":\"" + sha512Hex(bytes) + "\"")
        return "{\"path\":\"$path\",\"hashes\":{${hashes.joinToString(",")}},\"downloads\":[\"$url\"]" +
            (if (size) ",\"fileSize\":${bytes.size}" else "") + (if (env != null) ",\"env\":$env" else "") + "}"
    }

    fun index(
        files: List<String>,
        name: String = "Pacchetto Prova",
        version: String = "1.0.0",
        deps: String = """{"minecraft":"1.20.1","fabric-loader":"0.15.7"}"""
    ): String = """{"formatVersion":1,"game":"minecraft","versionId":"$version","name":"$name","summary":"Un modpack di prova","files":[${files.joinToString(",")}],"dependencies":$deps}"""

    /** Scrive un .mrpack: l'indice più gli «overrides» dati (percorso completo nell'archivio → contenuto). */
    fun mrpack(target: File, indexJson: String, extra: Map<String, ByteArray> = emptyMap()): File {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["modrinth.index.json"] = indexJson.toByteArray()
        entries.putAll(extra)
        target.parentFile?.mkdirs()
        target.writeBytes(zip(entries))
        return target
    }

    fun tempDir(): File = TestServer.tmpDir()
}
