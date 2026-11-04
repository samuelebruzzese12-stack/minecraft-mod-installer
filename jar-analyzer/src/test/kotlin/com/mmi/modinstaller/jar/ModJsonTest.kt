package com.mmi.modinstaller.jar

import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.asJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModJsonTest {

    @Test
    fun roundTripThroughJsonText() {
        val dir = Files.createTempDirectory("mj").toFile()
        try {
            val report = JarAnalyzer(tempDir = File(dir, "t")).analyze(Samples.fabricMod().write(File(dir, "a.jar")))
            val original = report.primary!!
            val text = Json.stringify(ModJson.toMap(original), pretty = true)
            val back = ModJson.fromMap(Json.parse(text).asJsonObject()!!)!!
            assertEquals(original, back)
            // le dipendenze mantengono sintassi e verifica delle versioni
            val mc = back.dependencies.first { it.id == "minecraft" }
            assertEquals(Compat.YES, mc.test("1.20.4"))
            assertEquals(Compat.NO, mc.test("1.21"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun withoutDependenciesIsSmaller() {
        val m = ModMetadata(
            ModLoader.QUILT, LoaderCertainty.GUESSED, "x", provides = listOf("y"), descriptor = "quilt.mod.json",
            dependencies = listOf(ModDependency("z", DependencyKind.REQUIRED, ">=1", RangeSyntax.SEMVER))
        )
        val slim = ModJson.toMap(m, withDependencies = false)
        assertTrue(!slim.containsKey("dependencies"))
        assertEquals(m.copy(dependencies = emptyList()), ModJson.fromMap(slim))
    }

    @Test
    fun invalidRecordsAreRejected() {
        assertNull(ModJson.fromMap(mapOf("id" to "x")))
        assertNull(ModJson.fromMap(mapOf("loader" to "fabric")))
        assertNull(ModJson.fromMap(mapOf("loader" to "boh", "id" to "x")))
        assertNull(ModJson.depFromMap(null))
        assertNull(ModJson.depFromMap(mapOf("kind" to "REQUIRED")))
    }

    @Test
    fun packFormats() {
        assertEquals("1.20 - 1.20.1", PackFormats.describe(15))
        assertEquals("1.19.4", PackFormats.describe(13))
        assertNull(PackFormats.describe(99))
        assertNull(PackFormats.describe(null))
        val mc = ModVersion.parse("1.20.1")
        assertEquals(Compat.YES, PackFormats.test(15, mc))
        assertEquals(Compat.NO, PackFormats.test(15, ModVersion.parse("1.20.2")))
        assertEquals(Compat.NO, PackFormats.test(13, mc))
        assertEquals(Compat.YES, PackFormats.test(4, ModVersion.parse("1.14.1")))
        assertEquals(Compat.UNKNOWN, PackFormats.test(99, mc))
        assertEquals(Compat.UNKNOWN, PackFormats.test(15, ModVersion.parse("24w14a")))
        assertEquals(Compat.UNKNOWN, PackFormats.test(15, null))
    }

    @Test
    fun lowestVersionOfConstraints() {
        assertEquals("1.20.1", VersionRanges.parseSemver("~1.20.1").lowestVersion().toString())
        assertEquals("1.20", VersionRanges.parseSemver("1.20.x").lowestVersion().toString())
        assertEquals("1.20", VersionRanges.parseSemver(">=1.20 <1.21").lowestVersion().toString())
        assertEquals("1.20", VersionRanges.parseMaven("[1.20,1.21)").lowestVersion().toString())
        assertEquals("1.19", VersionRanges.parseSemver("1.19.x || 1.20.x").lowestVersion().toString())
        assertNull(VersionRanges.parseSemver("*").lowestVersion())
        assertNull(VersionRanges.parseMaven("(,1.0]").lowestVersion())
        assertNull(VersionRanges.parseSemver("@@").lowestVersion())
    }
}
