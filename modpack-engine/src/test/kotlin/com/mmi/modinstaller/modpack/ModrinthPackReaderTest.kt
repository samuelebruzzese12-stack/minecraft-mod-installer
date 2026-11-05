package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.jar.ModLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModrinthPackReaderTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Fx.tempDir()
    }

    @After fun tearDown() {
        TestServer.deleteTree(dir)
    }

    private val modBytes = Fx.fabricJar("demo", "Demo", "1.0.0")
    private val url = "https://cdn.modrinth.com/data/AAAA/versions/BBBB/demo-1.0.0.jar"

    private fun read(index: String, extra: Map<String, ByteArray> = emptyMap(), limits: SafetyLimits = SafetyLimits()): PackReadResult =
        ModrinthPackReader.read(Fx.mrpack(File(dir, "p.mrpack"), index, extra), limits)

    private fun ok(r: PackReadResult): Pack {
        assertTrue(if (r is PackReadResult.Failed) r.message + " / " + r.detail else "", r is PackReadResult.Ok)
        return (r as PackReadResult.Ok).pack
    }

    private fun failed(r: PackReadResult): PackReadResult.Failed {
        assertTrue("doveva fallire", r is PackReadResult.Failed)
        return r as PackReadResult.Failed
    }

    // ------------------------------------------------------------------ un modpack valido

    @Test fun readsNameVersionGameLoaderFilesAndOverrides() {
        val pack = ok(
            read(
                Fx.index(listOf(Fx.fileJson("mods/demo-1.0.0.jar", modBytes, url, env = """{"client":"required","server":"optional"}"""))),
                mapOf(
                    "overrides/config/demo.toml" to "a=1".toByteArray(),
                    "overrides/options.txt" to "x".toByteArray(),
                    "client-overrides/config/client.toml" to "b=2".toByteArray(),
                    "server-overrides/config/server.toml" to "c=3".toByteArray()
                )
            )
        )
        assertEquals("Pacchetto Prova", pack.name)
        assertEquals("1.0.0", pack.versionId)
        assertEquals("1.20.1", pack.minecraft)
        assertEquals(ModLoader.FABRIC, pack.loader)
        assertEquals("0.15.7", pack.loaderVersion)
        assertTrue(pack.problems.isEmpty())
        assertEquals(OverrideCounts(common = 2, client = 1, server = 1), pack.overrides)
        assertEquals(3, pack.overrides.applied)
        val f = pack.files.single()
        assertEquals("mods/demo-1.0.0.jar", f.path)
        assertEquals(Fx.sha1Hex(modBytes), f.sha1)
        assertEquals(Fx.sha512Hex(modBytes), f.sha512)
        assertEquals(modBytes.size.toLong(), f.size)
        assertEquals(listOf(url), f.urls)
        assertEquals(Side.REQUIRED, f.client)
        assertEquals(Side.OPTIONAL, f.server)
        assertEquals(com.mmi.modinstaller.mods.ItemCategory.MOD, f.category)
    }

    @Test fun otherLoadersAreRecognised() {
        for ((key, loader) in listOf("quilt-loader" to ModLoader.QUILT, "forge" to ModLoader.FORGE, "neoforge" to ModLoader.NEOFORGE)) {
            val pack = ok(read(Fx.index(emptyList(), deps = """{"minecraft":"1.20.1","$key":"1.2.3"}""")))
            assertEquals(key, loader, pack.loader)
            assertEquals("1.2.3", pack.loaderVersion)
        }
    }

    @Test fun categoriesDependOnTheFolderOnly() {
        assertEquals(com.mmi.modinstaller.mods.ItemCategory.RESOURCE_PACK, PackFile.categoryOf("resourcepacks/a.zip"))
        assertEquals(com.mmi.modinstaller.mods.ItemCategory.SHADER_PACK, PackFile.categoryOf("shaderpacks/a.zip"))
        assertEquals(com.mmi.modinstaller.mods.ItemCategory.DATA_PACK, PackFile.categoryOf("datapacks/a.zip"))
        assertNull(PackFile.categoryOf("config/a.toml"))
        assertNull("una sottocartella non è un elemento gestito", PackFile.categoryOf("mods/sub/a.jar"))
        assertNull(PackFile.categoryOf("mods/"))
    }

    // ------------------------------------------------------------------ file che non sono un modpack

    @Test fun anArchiveWithoutIndexIsNotAModpack() {
        val f = File(dir, "x.zip")
        f.writeBytes(Fx.zip(linkedMapOf("overrides/a.txt" to "x".toByteArray())))
        val r = failed(ModrinthPackReader.read(f))
        assertTrue(r.message, r.message.contains("Non è un modpack"))
    }

    @Test fun garbageIsReportedAsCorruptNotCrashing() {
        val f = File(dir, "x.mrpack")
        f.writeBytes(ByteArray(300) { (it * 7).toByte() })
        val r = failed(ModrinthPackReader.read(f))
        assertTrue(r.message, r.message.isNotBlank())
    }

    @Test fun aMissingFileIsReported() {
        failed(ModrinthPackReader.read(File(dir, "non-esiste.mrpack")))
    }

    @Test fun invalidJsonAndWrongFormatAreRefused() {
        assertTrue(failed(read("{ non json")).message.contains("JSON"))
        assertTrue(failed(read("[1,2]")).message.contains("non è valido"))
        assertTrue(failed(read("""{"formatVersion":2,"game":"minecraft","files":[]}""")).message.contains("formato"))
        assertTrue(failed(read("""{"game":"minecraft","files":[]}""")).detail!!.contains("formatVersion"))
        assertTrue(failed(read("""{"formatVersion":1,"game":"hytale","files":[]}""")).message.contains("Minecraft Java"))
        assertTrue(failed(read("""{"formatVersion":1,"game":"minecraft"}""")).detail!!.contains("elenco dei file"))
    }

    @Test fun anArchiveWithDangerousEntryNamesIsNeverOpened() {
        val r = failed(read(Fx.index(emptyList()), mapOf("overrides/../../evil.txt" to "x".toByteArray())))
        assertTrue(r.message, r.message.contains("pericolosi"))
    }

    @Test fun anOversizedIndexIsRefused() {
        val big = ByteArray(ModrinthPackReader.MAX_INDEX_BYTES + 10) { ' '.code.toByte() }
        val f = File(dir, "big.mrpack")
        f.writeBytes(Fx.zip(linkedMapOf("modrinth.index.json" to big)))
        val r = failed(ModrinthPackReader.read(f))
        assertTrue(r.message, r.message.contains("limiti"))
    }

    @Test fun tooManyFilesAreRefusedAsAWhole() {
        val files = (0..ModrinthPackReader.MAX_FILES).map { Fx.fileJson("mods/m$it.jar", modBytes, url) }
        assertTrue(failed(read(Fx.index(files))).message.contains("troppi"))
    }

    @Test fun anEnormousDeclaredDownloadIsRefused() {
        // Ognuno entra nel limite per file (2 GiB), ma insieme superano quello dell'intero modpack (8 GiB).
        val entries = (1..5).map {
            """{"path":"mods/m$it.jar","hashes":{"sha1":"${Fx.sha1Hex(modBytes)}"},"downloads":["$url"],"fileSize":1900000000}"""
        }
        val r = failed(read(Fx.index(entries)))
        assertTrue(r.message, r.message.contains("troppo grande"))
    }

    // ------------------------------------------------------------------ voci non valide: scartate e dichiarate

    private fun problemsOf(entry: String): List<String> {
        val good = Fx.fileJson("mods/ok.jar", modBytes, url)
        val pack = ok(read(Fx.index(listOf(good, entry))))
        assertEquals("la voce valida resta", listOf("mods/ok.jar"), pack.files.map { it.path })
        return pack.problems
    }

    private fun entryWithPath(path: String): String =
        """{"path":"$path","hashes":{"sha1":"${Fx.sha1Hex(modBytes)}"},"downloads":["$url"],"fileSize":${modBytes.size}}"""

    @Test fun dangerousPathsNeverEnterThePlan() {
        for (bad in listOf(
            "../evil.jar", "mods/../../evil.jar", "/etc/passwd", "C:/Windows/evil.jar", "C:\\\\evil.jar", "mods\\\\evil.jar", "mods/", ".trash/x.jar",
            "mods/.hidden.jar", "bin/evil.exe", "scripts/run.sh", "x.apk", "mods/a\\u0000b.jar"
        )) {
            val problems = problemsOf(entryWithPath(bad))
            assertEquals("«$bad» doveva essere scartato e dichiarato: $problems", 1, problems.size)
        }
    }

    @Test fun aRepeatedPathIsDeclaredEvenIfOnlyTheCaseDiffers() {
        val pack = ok(
            read(Fx.index(listOf(Fx.fileJson("mods/A.jar", modBytes, url), Fx.fileJson("mods/a.jar", modBytes, url))))
        )
        assertEquals(1, pack.files.size)
        assertEquals(1, pack.problems.size)
        assertTrue(pack.problems.single().contains("ripetuto"))
    }

    @Test fun anEntryWithoutAVerifiableHashIsDiscarded() {
        val noHash = """{"path":"mods/x.jar","hashes":{},"downloads":["$url"],"fileSize":3}"""
        assertTrue(problemsOf(noHash).single().contains("impronta"))
        val badHash = """{"path":"mods/x.jar","hashes":{"sha1":"zzzz"},"downloads":["$url"],"fileSize":3}"""
        assertTrue(problemsOf(badHash).single().contains("impronta non valida"))
        val shortSha512 = """{"path":"mods/x.jar","hashes":{"sha1":"${Fx.sha1Hex(modBytes)}","sha512":"abcd"},"downloads":["$url"]}"""
        assertTrue(problemsOf(shortSha512).single().contains("impronta non valida"))
    }

    @Test fun anEntryWithoutDownloadsOrAbsurdlyBigIsDiscarded() {
        val noUrl = """{"path":"mods/x.jar","hashes":{"sha1":"${Fx.sha1Hex(modBytes)}"},"downloads":[]}"""
        assertTrue(problemsOf(noUrl).single().contains("indirizzo"))
        val huge = """{"path":"mods/x.jar","hashes":{"sha1":"${Fx.sha1Hex(modBytes)}"},"downloads":["$url"],"fileSize":${SafetyLimits().maxEntryBytes + 1}}"""
        assertTrue(problemsOf(huge).single().contains("troppo grande"))
        assertTrue(problemsOf("\"non un oggetto\"").single().contains("non valida"))
    }

    @Test fun sha1OnlyOrSha512OnlyAreAccepted() {
        val a = Fx.fileJson("mods/a.jar", modBytes, url, sha512 = false)
        val b = Fx.fileJson("mods/b.jar", modBytes, url, sha1 = false)
        val pack = ok(read(Fx.index(listOf(a, b))))
        assertTrue(pack.problems.isEmpty())
        assertNull(pack.files[0].sha512)
        assertNull(pack.files[1].sha1)
    }

    @Test fun envValuesAndMissingSizeAreHandled() {
        val a = Fx.fileJson("mods/a.jar", modBytes, url, size = false, env = """{"client":"unsupported","server":"required"}""")
        val pack = ok(read(Fx.index(listOf(a))))
        val f = pack.files.single()
        assertEquals(Side.UNSUPPORTED, f.client)
        assertEquals(Side.REQUIRED, f.server)
        assertEquals(-1L, f.size)
    }

    @Test fun twoLoadersOrUnknownDependenciesAreDeclared() {
        val pack = ok(read(Fx.index(emptyList(), deps = """{"minecraft":"1.20.1","fabric-loader":"0.15.7","forge":"47.0.0","strano":"1"}""")))
        assertEquals(ModLoader.FABRIC, pack.loader)
        assertEquals(2, pack.problems.size)
    }

    @Test fun missingLoaderOrMinecraftAreLeftNullForThePlannerToExplain() {
        val pack = ok(read(Fx.index(emptyList(), deps = "{}")))
        assertNull(pack.minecraft)
        assertNull(pack.loader)
    }

    @Test fun textsFromTheIndexAreCleanedBeforeBeingShown() {
        val pack = ok(read(Fx.index(emptyList(), name = "Nome\\u0007 con   spazi\\n e controllo")))
        assertFalse(pack.name.any { it.isISOControl() })
        assertEquals("Nome con spazi e controllo", pack.name)
    }
}
