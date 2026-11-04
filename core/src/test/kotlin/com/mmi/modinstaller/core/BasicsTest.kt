package com.mmi.modinstaller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    @Test
    fun parsesNestedValues() {
        val v = Json.parseObject("""{"a":1,"b":[1,2,{"c":"d"}],"e":true,"f":null,"g":1.5}""")
        assertEquals(1L, (v["a"] as Number).toLong())
        assertEquals("d", ((v["b"] as List<*>)[2] as Map<*, *>)["c"])
        assertEquals(true, v["e"])
        assertNull(v["f"])
        assertEquals(1.5, (v["g"] as Number).toDouble(), 0.0)
    }

    @Test
    fun toleratesBomCommentsAndTrailingCommas() {
        val text = "\uFEFF// commento\n{ /* blocco */ \"a\": [1, 2, ], \"b\": \"x\", }"
        val v = Json.parseObject(text)
        assertEquals(2, (v["a"] as List<*>).size)
        assertEquals("x", v["b"])
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val v = Json.parseObject("""{"s":"a\"b\\c\n\u00e8"}""")
        assertEquals("a\"b\\c\nè", v["s"])
    }

    @Test
    fun reportsErrorsWithPosition() {
        try {
            Json.parse("{\"a\": }")
            fail("doveva fallire")
        } catch (e: JsonParseException) {
            assertTrue(e.position > 0)
        }
        try {
            Json.parse("{\"a\": 1")
            fail("doveva fallire")
        } catch (e: JsonParseException) {
            // ok
        }
    }

    @Test
    fun rejectsExcessiveNesting() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        try {
            Json.parse(deep)
            fail("doveva fallire")
        } catch (e: JsonParseException) {
            // ok
        }
    }

    @Test
    fun stringifyRoundTrip() {
        val src = linkedMapOf<String, Any?>("a" to listOf(1, 2, 3), "b" to "tè\"st", "c" to null, "d" to true, "e" to 2.5)
        val text = Json.stringify(src, true)
        val back = Json.parseObject(text)
        assertEquals("tè\"st", back["b"])
        assertEquals(3, (back["a"] as List<*>).size)
        assertEquals(true, back["d"])
        assertTrue(back.containsKey("c"))
    }
}

class VersionTest {
    @Test
    fun parsesAndCompares() {
        assertEquals(McVersion(listOf(1, 20, 50)), McVersion.parse("1.20.50"))
        assertEquals(McVersion(listOf(1, 21, 0)), McVersion.parse("1.21.0-beta.3"))
        assertNull(McVersion.parse("abc"))
        assertTrue(McVersion(listOf(1, 21, 0)).compareFirst(McVersion(listOf(1, 20, 80)), 3) > 0)
        assertEquals(0, McVersion(listOf(1, 20, 50, 1)).compareFirst(McVersion(listOf(1, 20, 50, 9)), 3))
        assertTrue(McVersion(listOf(1, 20)).compareFirst(McVersion(listOf(1, 20, 1)), 3) < 0)
    }

    @Test
    fun fromJsonAcceptsArrayAndString() {
        assertEquals(McVersion(listOf(1, 2, 3)), McVersion.fromJson(listOf(1, 2, 3)))
        assertEquals(McVersion(listOf(1, 2, 3)), McVersion.fromJson("1.2.3"))
        assertNull(McVersion.fromJson(listOf("a")))
        assertNull(McVersion.fromJson(null))
    }
}

class PathSafetyTest {
    private fun ok(raw: String): String = (PathSafety.normalize(raw) as PathSafety.Normalized.Ok).path

    private fun unsafe(raw: String) = assertTrue(raw, PathSafety.normalize(raw) is PathSafety.Normalized.Unsafe)

    @Test
    fun normalizesSeparatorsAndDots() {
        assertEquals("a/b/c.txt", ok("a\\b\\c.txt"))
        assertEquals("a/b", ok("./a//b/"))
        assertEquals("", ok("/".substring(1)))
    }

    @Test
    fun blocksTraversalAndAbsolutePaths() {
        unsafe("../evil.txt")
        unsafe("a/../../evil.txt")
        unsafe("a\\..\\..\\evil.txt")
        unsafe("/etc/passwd")
        unsafe("C:\\Windows\\x")
        unsafe("c:/x")
        unsafe("a/b\u0000.txt")
        unsafe("a/" + "x".repeat(600))
    }

    @Test
    fun detectsJunkAndExecutables() {
        assertTrue(PathSafety.isJunk("__MACOSX/pack/._manifest.json"))
        assertTrue(PathSafety.isJunk("pack/.DS_Store"))
        assertTrue(PathSafety.isJunk("pack/Thumbs.db"))
        assertTrue(PathSafety.isJunk("pack/._icon.png"))
        assertFalse(PathSafety.isJunk("pack/manifest.json"))
        assertTrue(PathSafety.isExecutable("x/Setup.EXE"))
        assertTrue(PathSafety.isExecutable("lib/libfoo.so"))
        assertTrue(PathSafety.isExecutable("a/b.jar"))
        assertFalse(PathSafety.isExecutable("scripts/main.js"))
        assertFalse(PathSafety.isExecutable("textures/a.png"))
    }

    @Test
    fun pathHelpers() {
        assertEquals("a/b", PathSafety.parentOf("a/b/c.txt"))
        assertEquals("", PathSafety.parentOf("c.txt"))
        assertEquals("txt", PathSafety.extension("a/B.TXT"))
        assertTrue(PathSafety.isUnder("a/b/c", "a/b"))
        assertFalse(PathSafety.isUnder("a/bc/d", "a/b"))
        assertFalse(PathSafety.isUnder("a/b", "a/b"))
        assertTrue(PathSafety.isUnderOrEqual("a/b", "a/b"))
        assertTrue(PathSafety.isUnder("x", ""))
        assertEquals("a/b", PathSafety.join("a", "b"))
        assertEquals("b", PathSafety.join("", "b"))
        assertEquals(0, PathSafety.depthOf(""))
        assertEquals(3, PathSafety.depthOf("a/b/c"))
    }

    @Test
    fun sanitizesFileNames() {
        assertEquals("Super_Mod_Pack", PathSafety.sanitizeFileName("Super Mod & Pack!"))
        assertEquals("Citta_Perduta", PathSafety.sanitizeFileName("Città Perduta"))
        assertEquals("contenuto", PathSafety.sanitizeFileName("***"))
        assertEquals(64, PathSafety.sanitizeFileName("a".repeat(200)).length)
        assertEquals("my-pack_2", PathSafety.sanitizeFileName("my-pack 2"))
    }
}

class FormatSnifferTest {
    private fun sniff(vararg b: Int) = FormatSniffer.sniff(ByteArray(b.size) { b[it].toByte() }, b.size)

    @Test
    fun detectsFormatsByMagicBytes() {
        assertEquals(ArchiveFormat.ZIP, sniff(0x50, 0x4B, 3, 4, 0, 0))
        assertEquals(ArchiveFormat.ZIP, sniff(0x50, 0x4B, 5, 6, 0, 0))
        assertEquals(ArchiveFormat.SEVEN_Z, sniff(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0))
        assertEquals(ArchiveFormat.RAR, sniff(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        assertEquals(ArchiveFormat.RAR5, sniff(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        assertEquals(ArchiveFormat.GZIP, sniff(0x1F, 0x8B, 8))
        assertEquals(ArchiveFormat.HTML, sniff('<'.code, '!'.code, 'D'.code))
        assertEquals(ArchiveFormat.HTML, sniff(0xEF, 0xBB, 0xBF, ' '.code, '<'.code))
        assertEquals(ArchiveFormat.JSON, sniff('{'.code, '}'.code))
        assertEquals(ArchiveFormat.JSON, sniff('\n'.code, '['.code))
        assertEquals(ArchiveFormat.UNKNOWN, sniff(1, 2, 3, 4))
    }
}

class NbtAndLangTest {
    @Test
    fun readsBedrockLevelDat() {
        val bytes = T.levelDat("Il mio mondo", listOf(1, 20, 50, 1, 0), listOf("gametest", "data_driven_items"))
        val info = LevelInfo.parse(bytes)
        assertNotNull(info)
        assertEquals("Il mio mondo", info!!.name)
        assertEquals(McVersion(listOf(1, 20, 50, 1, 0)), info.lastOpenedWith)
        assertEquals(10, info.storageVersion)
        assertEquals(0, info.gameType)
        assertEquals(listOf("gametest", "data_driven_items"), info.experiments)
    }

    @Test
    fun rejectsGarbageLevelDat() {
        assertNull(LevelInfo.parse(ByteArray(0)))
        assertNull(LevelInfo.parse(ByteArray(100) { 7 }))
        val ok = T.levelDat("x", listOf(1, 2, 3))
        assertNull(LevelInfo.parse(ok.copyOf(ok.size - 10)))
    }

    @Test
    fun langFileResolvesKeys() {
        val lang = LangFile.parse(T.bytes("# commento\npack.name=Cool Pack\npack.description = Una descrizione\t#nota\n"))
        assertEquals("Cool Pack", LangFile.resolve("pack.name", lang))
        assertEquals("Una descrizione", LangFile.resolve("pack.description", lang))
        assertEquals("Plain", LangFile.resolve("Plain", lang))
        assertEquals("pack.name", LangFile.resolve("pack.name", null))
        assertNull(LangFile.resolve(null, lang))
    }
}

class ManifestParserTest {
    @Test
    fun parsesValidManifest() {
        val u = T.uuidOf("a")
        val r = ManifestParser.parse(String(T.manifest(name = "N", uuid = u, type = "data")), "manifest.json")
        assertTrue(r.looksLikeBedrock)
        assertTrue(r.findings.none { it.severity == Severity.ERROR })
        assertEquals(PackKind.BEHAVIOR, r.manifest!!.kind)
        assertEquals("1.0.0", r.manifest!!.versionText)
        assertEquals(listOf("Tester"), r.manifest!!.authors)
    }

    @Test
    fun flagsBrokenFields() {
        val r = ManifestParser.parse(
            """{"format_version":2,"header":{"name":"x","uuid":"nope","version":[1,0]},"modules":[]}""", "m"
        )
        assertTrue(r.findings.any { it.code == FindingCode.UUID_INVALID })
        assertTrue(r.findings.any { it.code == FindingCode.VERSION_INVALID })
        assertTrue(r.findings.any { it.code == FindingCode.MANIFEST_FIELD })
    }

    @Test
    fun acceptsSemverVersionsAndDependencies() {
        val u = T.uuidOf("sv")
        val text = """{"format_version":3,"header":{"name":"x","uuid":"$u","version":"1.2.3","min_engine_version":[1,21,0]},
            "modules":[{"type":"script","uuid":"${T.uuidOf("m")}","version":"1.0.0","language":"javascript","entry":"scripts/main.js"}],
            "dependencies":[{"module_name":"@minecraft/server","version":"1.9.0-beta"},{"uuid":"${T.uuidOf("d")}","version":[1,0,0]}]}"""
        val r = ManifestParser.parse(text, "m")
        assertTrue(r.findings.none { it.severity == Severity.ERROR })
        assertEquals(2, r.manifest!!.dependencies.size)
        assertEquals("@minecraft/server", r.manifest!!.dependencies[0].moduleName)
        assertEquals(PackKind.BEHAVIOR, r.manifest!!.kind)
    }

    @Test
    fun nonBedrockJsonIsNotAManifest() {
        val r = ManifestParser.parse("""{"name":"my-npm-package","version":"1.0.0"}""", "m")
        assertFalse(r.looksLikeBedrock)
    }

    @Test
    fun brokenJsonIsAnError() {
        val r = ManifestParser.parse("{ nope", "m")
        assertTrue(r.findings.any { it.code == FindingCode.MANIFEST_INVALID && it.severity == Severity.ERROR })
        assertNull(r.manifest)
    }
}
