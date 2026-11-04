package com.mmi.modinstaller.jar

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Costruisce archivi di prova al volo: nessun file binario nel repository. */
class TestJar {
    private val entries = LinkedHashMap<String, ByteArray>()

    fun text(name: String, content: String) = apply { entries[name] = content.toByteArray(Charsets.UTF_8) }

    fun bytes(name: String, content: ByteArray) = apply { entries[name] = content }

    /** Un file .class minimo: solo intestazione (CAFEBABE, versione). Non è eseguibile, basta per l'analisi. */
    fun classFile(name: String, major: Int) = apply {
        entries[name] = byteArrayOf(
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0,
            ((major shr 8) and 0xFF).toByte(), (major and 0xFF).toByte(), 0, 0
        )
    }

    fun jar(name: String, inner: TestJar) = apply { entries[name] = inner.toBytes() }

    fun manifest(vararg pairs: Pair<String, String>) = apply {
        val sb = StringBuilder("Manifest-Version: 1.0\r\n")
        for ((k, v) in pairs) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        entries["META-INF/MANIFEST.MF"] = sb.toString().toByteArray()
    }

    fun toBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun write(file: File): File {
        file.parentFile?.mkdirs()
        file.writeBytes(toBytes())
        return file
    }

    companion object {
        /** Archivio con una voce «stored» il cui nome è dato così com'è (anche pericoloso). */
        fun withRawName(rawName: String, content: ByteArray = byteArrayOf(1, 2, 3)): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zip ->
                val e = ZipEntry(rawName)
                e.method = ZipEntry.STORED
                e.size = content.size.toLong()
                e.compressedSize = content.size.toLong()
                val crc = CRC32()
                crc.update(content)
                e.crc = crc.value
                zip.putNextEntry(e)
                zip.write(content)
                zip.closeEntry()
            }
            return bos.toByteArray()
        }
    }
}

object Samples {
    const val FABRIC_JSON = """{
      "schemaVersion": 1,
      "id": "examplemod",
      "version": "1.4.2+1.20.1",
      "name": "Example Mod",
      "description": "Una mod di prova.",
      "authors": ["Alice", {"name": "Bob", "contact": {"homepage": "https://example.org"}}],
      "contact": {"homepage": "https://example.org/mod"},
      "license": "MIT",
      "environment": "*",
      "entrypoints": {"main": ["com.example.Main"]},
      "mixins": ["examplemod.mixins.json", {"config": "examplemod.client.mixins.json", "environment": "client"}],
      "depends": {
        "fabricloader": ">=0.14.21",
        "minecraft": "~1.20.1",
        "java": ">=17",
        "fabric-api": "*"
      },
      "recommends": {"modmenu": ">=7.0.0"},
      "suggests": {"sodium": "*"},
      "conflicts": {"badmod": "<2.0"},
      "breaks": {"worse": ["1.0.x", "1.1.x"]},
      "provides": ["example-alias"]
    }"""

    const val FORGE_TOML = """
modLoader="javafml"
loaderVersion="[47,)"
license="MIT"
[[mods]]
modId="forgemod"
version="${'$'}{file.jarVersion}"
displayName="Forge Mod"
authors="Carla, Dino"
description='''Descrizione
su due righe'''
[[dependencies.forgemod]]
    modId="forge"
    mandatory=true
    versionRange="[47.1.0,)"
    ordering="NONE"
    side="BOTH"
[[dependencies.forgemod]]
    modId="minecraft"
    mandatory=true
    versionRange="[1.20.1,1.21)"
    side="BOTH"
[[dependencies.forgemod]]
    modId="curios"
    mandatory=false
    versionRange="[5,)"
    side="BOTH"
"""

    const val NEOFORGE_TOML = """
modLoader="javafml"
loaderVersion="[2,)"
license="MIT"
[[mods]]
modId="neomod"
version="2.1.0"
displayName="Neo Mod"
[[dependencies.neomod]]
    modId="neoforge"
    type="required"
    versionRange="[21.0.0,)"
    side="BOTH"
[[dependencies.neomod]]
    modId="minecraft"
    type="required"
    versionRange="[1.21,1.22)"
    side="BOTH"
[[dependencies.neomod]]
    modId="evilmod"
    type="incompatible"
    versionRange="*"
    reason="Crash noto"
[[dependencies.neomod]]
    modId="meh"
    type="discouraged"
"""

    const val AMBIGUOUS_TOML = """
modLoader="javafml"
loaderVersion="[1,)"
[[mods]]
modId="ambiguous"
version="3.0"
"""

    const val QUILT_JSON = """{
      "schema_version": 1,
      "quilt_loader": {
        "group": "com.example",
        "id": "quiltmod",
        "version": "0.3.1",
        "metadata": {
          "name": "Quilt Mod",
          "description": "Per Quilt",
          "contributors": {"Eva": "Owner", "Franco": "Artist"},
          "license": "Apache-2.0",
          "contact": {"homepage": "https://quilt.example"}
        },
        "intermediate_mappings": "net.fabricmc:intermediary",
        "depends": [
          "otherlib",
          {"id": "minecraft", "versions": ">=1.19.2 <1.21"},
          {"id": "quilted_fabric_api", "versions": "*", "optional": true},
          {"id": "multi", "versions": {"any": ["1.0.x", "2.0.x"]}}
        ],
        "breaks": [{"id": "oldmod", "versions": "<1.0"}],
        "provides": ["quilt-alias", {"id": "quilt-alias2", "version": "1.0"}]
      },
      "minecraft": {"environment": "client"},
      "mixin": "quiltmod.mixins.json"
    }"""

    const val LEGACY_MCMOD = """[{
      "modid": "oldmod",
      "name": "Old Mod",
      "description": "Mod 1.12",
      "version": "2.3",
      "mcversion": "1.12.2",
      "url": "https://old.example",
      "authorList": ["Gino"],
      "requiredMods": ["baselib"],
      "dependencies": ["optionallib"]
    }]"""

    const val PACK_MCMETA = """{"pack": {"pack_format": 15, "description": {"text": "Il mio ", "extra": [{"text": "pacchetto"}]}}}"""

    const val MODRINTH_INDEX = """{
      "formatVersion": 1, "game": "minecraft", "versionId": "2.0.1", "name": "Super Pack", "summary": "x",
      "files": [
        {"path": "mods/a.jar", "hashes": {"sha1": "aa", "sha512": "bb"}, "downloads": ["https://cdn.modrinth.com/data/x/versions/y/a.jar"], "fileSize": 10},
        {"path": "mods/b.jar", "hashes": {"sha1": "cc", "sha512": "dd"}, "downloads": ["https://cdn.modrinth.com/data/x/versions/z/b.jar"], "fileSize": 20}
      ],
      "dependencies": {"minecraft": "1.20.1", "fabric-loader": "0.15.7"}
    }"""

    const val CURSEFORGE_MANIFEST = """{
      "minecraft": {"version": "1.20.1", "modLoaders": [{"id": "forge-47.2.0", "primary": true}]},
      "manifestType": "minecraftModpack", "manifestVersion": 1,
      "name": "CF Pack", "version": "1.2", "author": "Hana",
      "files": [{"projectID": 1, "fileID": 2, "required": true}, {"projectID": 3, "fileID": 4, "required": true}],
      "overrides": "overrides"
    }"""

    const val BEDROCK_MANIFEST = """{
      "format_version": 2,
      "header": {"name": "BP", "description": "x", "uuid": "11111111-2222-3333-4444-555555555555", "version": [1,0,0], "min_engine_version": [1,20,0]},
      "modules": [{"type": "data", "uuid": "66666666-7777-8888-9999-000000000000", "version": [1,0,0]}]
    }"""

    fun fabricMod(): TestJar = TestJar()
        .text("fabric.mod.json", FABRIC_JSON)
        .manifest("Implementation-Version" to "1.4.2")
        .classFile("com/example/Main.class", 61)
        .classFile("com/example/Other.class", 61)
        .text("examplemod.mixins.json", "{}")
        .text("assets/examplemod/icon.png", "x")
}
