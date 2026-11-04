package com.mmi.modinstaller.jar

import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.Severity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class JarAnalyzerTest {

    private lateinit var dir: File
    private lateinit var analyzer: JarAnalyzer

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("jartest").toFile()
        analyzer = JarAnalyzer(tempDir = File(dir, "tmp"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun analyze(jar: TestJar, name: String = "file.jar"): JarReport = analyzer.analyze(jar.write(File(dir, name)), name)

    private fun JarReport.has(code: FindingCode, severity: Severity? = null): Boolean =
        findings.any { it.code == code && (severity == null || it.severity == severity) }

    // ---------------------------------------------------------------------------------------------- Fabric

    @Test
    fun fabricMod() {
        val r = analyze(Samples.fabricMod())
        assertEquals(ContentKind.MOD, r.kind)
        assertFalse(r.hasErrors)
        val m = r.primary!!
        assertEquals(ModLoader.FABRIC, m.loader)
        assertEquals(LoaderCertainty.CERTAIN, m.certainty)
        assertEquals("examplemod", m.id)
        assertEquals("1.4.2+1.20.1", m.version)
        assertEquals("Example Mod", m.name)
        assertEquals(listOf("Alice", "Bob"), m.authors)
        assertEquals("MIT", m.license)
        assertEquals("https://example.org/mod", m.homepage)
        assertEquals(ModEnvironment.BOTH, m.environment)
        assertEquals(listOf("example-alias"), m.provides)
        assertEquals(17, r.javaMajor)
        assertEquals(2, r.classCount)
        assertTrue(r.mixinConfigs.contains("examplemod.mixins.json"))
        assertTrue(r.mixinConfigs.contains("examplemod.client.mixins.json"))
        assertEquals("1.4.2", r.manifestAttributes["Implementation-Version"])

        val byId = m.dependencies.associateBy { it.id }
        assertEquals(DependencyKind.REQUIRED, byId["fabricloader"]!!.kind)
        assertEquals(">=0.14.21", byId["fabricloader"]!!.range)
        assertEquals(Compat.YES, byId["minecraft"]!!.test("1.20.4"))
        assertEquals(Compat.NO, byId["minecraft"]!!.test("1.21"))
        assertEquals(Compat.YES, byId["java"]!!.test("17"))
        assertEquals(Compat.NO, byId["java"]!!.test("8"))
        assertEquals(DependencyKind.RECOMMENDED, byId["modmenu"]!!.kind)
        assertEquals(DependencyKind.SUGGESTED, byId["sodium"]!!.kind)
        assertEquals(DependencyKind.CONFLICTS, byId["badmod"]!!.kind)
        assertEquals(DependencyKind.BREAKS, byId["worse"]!!.kind)
        assertEquals("1.0.x || 1.1.x", byId["worse"]!!.range)
        assertEquals(Compat.YES, byId["worse"]!!.test("1.1.5"))
        assertTrue(r.has(FindingCode.JAVA_BYTECODE))
    }

    @Test
    fun hashesAreCorrect() {
        val jar = Samples.fabricMod()
        val f = jar.write(File(dir, "h.jar"))
        val r = analyzer.analyze(f)
        fun hex(alg: String) = MessageDigest.getInstance(alg).digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(hex("SHA-256"), r.sha256)
        assertEquals(hex("SHA-1"), r.sha1)
        assertEquals(f.length(), r.sizeBytes)
    }

    @Test
    fun fabricClientOnlyAndDefaults() {
        val r = analyze(
            TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"clientmod","version":"1.0","environment":"client"}""")
        )
        assertEquals(ModEnvironment.CLIENT, r.primary!!.environment)
        assertNull(r.javaMajor)
        assertEquals(0, r.classCount)
    }

    @Test
    fun fabricInvalidIdIsWarning() {
        val r = analyze(TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"My Mod","version":"1.0"}"""))
        assertEquals(ContentKind.MOD, r.kind)
        assertTrue(r.has(FindingCode.JAVA_ID_INVALID, Severity.WARNING))
        assertFalse(r.hasErrors)
    }

    @Test
    fun fabricWithoutIdIsError() {
        val r = analyze(TestJar().text("fabric.mod.json", """{"schemaVersion":1,"version":"1.0"}"""))
        assertTrue(r.hasErrors)
        assertTrue(r.has(FindingCode.JAVA_METADATA_INVALID, Severity.ERROR))
        assertTrue(r.mods.isEmpty())
    }

    @Test
    fun fabricNotJsonIsError() {
        val r = analyze(TestJar().text("fabric.mod.json", "questo non è json {{"))
        assertTrue(r.has(FindingCode.JAVA_METADATA_INVALID, Severity.ERROR))
    }

    @Test
    fun fabricUnresolvedVersionIsWarning() {
        val r = analyze(TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"devmod","version":"${'$'}{version}"}"""))
        assertTrue(r.has(FindingCode.JAVA_VERSION_UNRESOLVED, Severity.WARNING))
        assertEquals(ContentKind.MOD, r.kind)
    }

    @Test
    fun fabricToleratesCommentsAndTrailingCommas() {
        val r = analyze(
            TestJar().text(
                "fabric.mod.json",
                """{ // commento
                  "schemaVersion": 1, "id": "lenient", "version": "1.0",
                  "depends": { "minecraft": "1.20.x", },
                }"""
            )
        )
        assertEquals("lenient", r.primary!!.id)
    }

    // ---------------------------------------------------------------------------------------------- Forge / NeoForge

    @Test
    fun forgeMod() {
        val r = analyze(
            TestJar().text("META-INF/mods.toml", Samples.FORGE_TOML)
                .manifest("Implementation-Version" to "3.4.5")
                .classFile("a/B.class", 61)
        )
        assertEquals(ContentKind.MOD, r.kind)
        val m = r.primary!!
        assertEquals(ModLoader.FORGE, m.loader)
        assertEquals(LoaderCertainty.CERTAIN, m.certainty)
        assertEquals("forgemod", m.id)
        assertEquals("3.4.5", m.version) // risolto dal manifest
        assertEquals("Forge Mod", m.name)
        assertEquals(listOf("Carla", "Dino"), m.authors)
        assertEquals("javafml", m.languageLoader)
        val byId = m.dependencies.associateBy { it.id }
        assertEquals(RangeSyntax.MAVEN, byId["forge"]!!.syntax)
        assertEquals(Compat.YES, byId["forge"]!!.test("47.2.0"))
        assertEquals(Compat.NO, byId["forge"]!!.test("46.0.1"))
        assertEquals(Compat.YES, byId["minecraft"]!!.test("1.20.1"))
        assertEquals(Compat.NO, byId["minecraft"]!!.test("1.21"))
        assertEquals(DependencyKind.OPTIONAL, byId["curios"]!!.kind)
        assertEquals("BOTH", byId["forge"]!!.side)
    }

    @Test
    fun forgeVersionPlaceholderWithoutManifest() {
        val r = analyze(TestJar().text("META-INF/mods.toml", Samples.FORGE_TOML))
        assertNull(r.primary!!.version)
        assertTrue(r.has(FindingCode.JAVA_VERSION_UNRESOLVED, Severity.WARNING))
    }

    @Test
    fun neoForgeMod() {
        val r = analyze(TestJar().text("META-INF/neoforge.mods.toml", Samples.NEOFORGE_TOML))
        val m = r.primary!!
        assertEquals(ModLoader.NEOFORGE, m.loader)
        assertEquals(LoaderCertainty.CERTAIN, m.certainty)
        assertEquals("2.1.0", m.version)
        val byId = m.dependencies.associateBy { it.id }
        assertEquals(DependencyKind.BREAKS, byId["evilmod"]!!.kind)
        assertEquals("Crash noto", byId["evilmod"]!!.reason)
        assertEquals(DependencyKind.CONFLICTS, byId["meh"]!!.kind)
        assertEquals(Compat.YES, byId["minecraft"]!!.test("1.21.1"))
    }

    @Test
    fun modsTomlWithNeoForgeDependencyIsNeoForge() {
        val toml = Samples.NEOFORGE_TOML
        val r = analyze(TestJar().text("META-INF/mods.toml", toml))
        assertEquals(ModLoader.NEOFORGE, r.primary!!.loader)
    }

    @Test
    fun ambiguousTomlIsForgeGuess() {
        val r = analyze(TestJar().text("META-INF/mods.toml", Samples.AMBIGUOUS_TOML))
        assertEquals(ModLoader.FORGE, r.primary!!.loader)
        assertEquals(LoaderCertainty.GUESSED, r.primary!!.certainty)
        assertTrue(r.has(FindingCode.JAVA_LOADER_GUESSED))
    }

    @Test
    fun brokenTomlIsError() {
        val r = analyze(TestJar().text("META-INF/mods.toml", "modLoader = \"javafml\"\n[[mods]\nmodId=\"x\""))
        assertTrue(r.has(FindingCode.JAVA_METADATA_INVALID, Severity.ERROR))
        assertTrue(r.mods.isEmpty())
    }

    @Test
    fun tomlWithoutModsIsError() {
        val r = analyze(TestJar().text("META-INF/mods.toml", "modLoader=\"javafml\"\nloaderVersion=\"[1,)\""))
        assertTrue(r.has(FindingCode.JAVA_METADATA_INVALID, Severity.ERROR))
    }

    @Test
    fun severalModsInOneJar() {
        val toml = """
            modLoader="javafml"
            loaderVersion="[47,)"
            [[mods]]
            modId="first"
            version="1.0"
            [[mods]]
            modId="second"
            version="2.0"
            [[dependencies.first]]
              modId="forge"
              mandatory=true
              versionRange="[47,)"
            [[dependencies.second]]
              modId="first"
              mandatory=true
        """.trimIndent()
        val r = analyze(TestJar().text("META-INF/mods.toml", toml))
        assertEquals(listOf("first", "second"), r.mods.map { it.id })
        assertEquals(ModLoader.FORGE, r.mods[0].loader)
        assertEquals(ModLoader.FORGE, r.mods[1].loader) // il caricatore è del file, non della singola mod
        assertEquals(listOf("first"), r.mods[1].dependencies.map { it.id })
    }

    // ---------------------------------------------------------------------------------------------- Quilt e vecchio Forge

    @Test
    fun quiltMod() {
        val r = analyze(TestJar().text("quilt.mod.json", Samples.QUILT_JSON))
        val m = r.primary!!
        assertEquals(ModLoader.QUILT, m.loader)
        assertEquals("quiltmod", m.id)
        assertEquals("0.3.1", m.version)
        assertEquals("Quilt Mod", m.name)
        assertEquals(listOf("Eva", "Franco"), m.authors)
        assertEquals("Apache-2.0", m.license)
        assertEquals(ModEnvironment.CLIENT, m.environment)
        assertEquals(listOf("quilt-alias", "quilt-alias2"), m.provides)
        val byId = m.dependencies.associateBy { it.id }
        assertEquals(DependencyKind.REQUIRED, byId["otherlib"]!!.kind)
        assertNull(byId["otherlib"]!!.range)
        assertEquals(Compat.YES, byId["minecraft"]!!.test("1.20.1"))
        assertEquals(Compat.NO, byId["minecraft"]!!.test("1.21"))
        assertEquals(DependencyKind.OPTIONAL, byId["quilted_fabric_api"]!!.kind)
        assertEquals(Compat.YES, byId["multi"]!!.test("2.0.3"))
        assertEquals(Compat.NO, byId["multi"]!!.test("3.0"))
        assertEquals(DependencyKind.BREAKS, byId["oldmod"]!!.kind)
        assertTrue(r.mixinConfigs.contains("quiltmod.mixins.json"))
    }

    @Test
    fun legacyForgeMod() {
        val r = analyze(TestJar().text("mcmod.info", Samples.LEGACY_MCMOD).classFile("a/B.class", 52))
        assertEquals(ContentKind.MOD, r.kind)
        val m = r.primary!!
        assertEquals(ModLoader.FORGE, m.loader)
        assertEquals(LoaderCertainty.GUESSED, m.certainty)
        assertEquals("oldmod", m.id)
        assertEquals("1.12.2", m.minecraftHint)
        assertEquals(8, r.javaMajor)
        assertTrue(r.has(FindingCode.JAVA_LEGACY_FORGE, Severity.WARNING))
        assertEquals(DependencyKind.REQUIRED, m.dependencies.first { it.id == "baselib" }.kind)
    }

    @Test
    fun legacyMcmodV2Format() {
        val r = analyze(TestJar().text("mcmod.info", """{"modListVersion": 2, "modList": [{"modid": "v2mod", "name": "V2", "version": "1"}]}"""))
        assertEquals("v2mod", r.primary!!.id)
    }

    @Test
    fun multiLoaderJar() {
        val r = analyze(
            Samples.fabricMod()
                .text("META-INF/mods.toml", Samples.FORGE_TOML)
                .text("quilt.mod.json", Samples.QUILT_JSON)
        )
        assertEquals(setOf(ModLoader.FABRIC, ModLoader.QUILT, ModLoader.FORGE), r.loaders)
        assertTrue(r.has(FindingCode.JAVA_MULTI_LOADER))
        assertEquals(ModLoader.FABRIC, r.primary!!.loader)
    }

    @Test
    fun brokenDescriptorNextToValidOneIsOnlyAWarning() {
        val r = analyze(Samples.fabricMod().text("META-INF/mods.toml", "non valido [[["))
        assertFalse(r.hasErrors)
        assertTrue(r.has(FindingCode.JAVA_METADATA_INVALID, Severity.WARNING))
        assertEquals(ModLoader.FABRIC, r.primary!!.loader)
    }

    // ---------------------------------------------------------------------------------------------- jar annidati

    @Test
    fun nestedJarsProvideTheirIds() {
        val inner1 = TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"fabric-api-base","version":"0.4.0","provides":["fabric-base-alias"]}""")
        val inner2 = TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"fabric-resource-loader-v0","version":"0.11.0"}""")
        val lib = TestJar().classFile("lib/Util.class", 52)
        val outer = TestJar()
            .text("fabric.mod.json", """{"schemaVersion":1,"id":"fabric-api","version":"0.92.2","jars":[{"file":"META-INF/jars/base.jar"},{"file":"META-INF/jars/res.jar"}]}""")
            .jar("META-INF/jars/base.jar", inner1)
            .jar("META-INF/jars/res.jar", inner2)
            .jar("META-INF/jars/lib.jar", lib)
        val r = analyze(outer)
        assertEquals(3, r.nested.size)
        assertEquals(setOf("fabric-api", "fabric-api-base", "fabric-base-alias", "fabric-resource-loader-v0"), r.providedIds)
        assertEquals(emptyList<ModMetadata>(), r.nested.first { it.path.endsWith("lib.jar") }.mods)
        assertTrue(r.has(FindingCode.JAVA_NESTED_JARS))
        assertFalse(analyzer.javaClass.name.isEmpty())
        // nessun file temporaneo lasciato in giro
        assertTrue(File(dir, "tmp").listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun nestedJarTooBigIsSkippedNotFatal() {
        val small = JarAnalyzer(JarLimits(maxNestedJarBytes = 50), File(dir, "tmp2"))
        val inner = TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"inner","version":"1"}""").text("pad.txt", "x".repeat(2000))
        val outer = TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"outer","version":"1"}""").jar("META-INF/jars/inner.jar", inner)
        val r = small.analyze(outer.write(File(dir, "o.jar")))
        assertEquals(ContentKind.MOD, r.kind)
        assertEquals(1, r.nested.size)
        assertTrue(r.nested[0].mods.isEmpty())
        assertEquals(setOf("outer"), r.providedIds)
    }

    @Test
    fun brokenNestedJarIsIgnored() {
        val outer = TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"outer","version":"1"}""")
            .bytes("META-INF/jars/broken.jar", byteArrayOf(1, 2, 3, 4, 5))
        val r = analyze(outer)
        assertEquals(ContentKind.MOD, r.kind)
        assertTrue(r.nested[0].mods.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------- pack

    @Test
    fun resourcePack() {
        val r = analyze(
            TestJar().text("pack.mcmeta", Samples.PACK_MCMETA).text("assets/minecraft/textures/block/stone.png", "x").bytes("pack.png", byteArrayOf(1)),
            "pack.zip"
        )
        assertEquals(ContentKind.RESOURCE_PACK, r.kind)
        val p = r.pack!!
        assertEquals(15, p.packFormat)
        assertEquals("Il mio pacchetto", p.description)
        assertEquals("1.20 - 1.20.1", p.knownMinecraft)
        assertTrue(p.hasAssets)
        assertFalse(p.hasData)
        assertNull(p.wrapperFolder)
        assertFalse(r.hasErrors)
    }

    @Test
    fun resourcePackNewFormatKeys() {
        val r = analyze(
            TestJar().text("pack.mcmeta", """{"pack":{"description":"x","min_format":[94,1],"max_format":[94,1]}}""").text("assets/a/b.txt", "x"),
            "p.zip"
        )
        assertEquals(94, r.pack!!.minFormat)
        assertNull(r.pack!!.packFormat)
        assertNull(r.pack!!.knownMinecraft)
        assertFalse(r.hasErrors)
    }

    @Test
    fun dataPack() {
        val r = analyze(
            TestJar().text("pack.mcmeta", """{"pack":{"pack_format":15,"description":"dati"}}""").text("data/minecraft/recipes/x.json", "{}"),
            "dp.zip"
        )
        assertEquals(ContentKind.DATA_PACK, r.kind)
        assertTrue(r.pack!!.hasData)
    }

    @Test
    fun shaderPack() {
        val r = analyze(
            TestJar().text("shaders/gbuffers_terrain.fsh", "void main(){}").text("shaders/shaders.properties", "x=1"),
            "Shader.zip"
        )
        assertEquals(ContentKind.SHADER_PACK, r.kind)
        assertTrue(r.pack!!.hasShaders)
        assertFalse(r.hasErrors)
    }

    @Test
    fun wrappedPackIsDetected() {
        val r = analyze(
            TestJar().text("MyPack/pack.mcmeta", Samples.PACK_MCMETA).text("MyPack/assets/minecraft/textures/a.png", "x"),
            "wrapped.zip"
        )
        assertEquals(ContentKind.RESOURCE_PACK, r.kind)
        assertEquals("MyPack", r.pack!!.wrapperFolder)
        assertEquals(15, r.pack!!.packFormat)
        assertTrue(r.has(FindingCode.JAVA_WRAPPED_PACK, Severity.WARNING))
        assertFalse(r.hasErrors)
    }

    @Test
    fun packWithoutMcmetaIsError() {
        val r = analyze(TestJar().text("assets/minecraft/textures/a.png", "x").text("readme.txt", "x"), "p.zip")
        assertEquals(ContentKind.UNKNOWN, r.kind)
    }

    @Test
    fun invalidPackMcmeta() {
        val r = analyze(TestJar().text("pack.mcmeta", "{}").text("assets/a.txt", "x"), "p.zip")
        assertTrue(r.has(FindingCode.JAVA_METADATA_INVALID, Severity.ERROR))
    }

    // ---------------------------------------------------------------------------------------------- modpack e altri tipi

    @Test
    fun modrinthPack() {
        val r = analyze(
            TestJar().text("modrinth.index.json", Samples.MODRINTH_INDEX).text("overrides/config/a.toml", "x").text("client-overrides/options.txt", "x"),
            "pack.mrpack"
        )
        assertEquals(ContentKind.MODPACK_MODRINTH, r.kind)
        val s = r.modpack!!
        assertEquals("modrinth", s.format)
        assertEquals("Super Pack", s.name)
        assertEquals("2.0.1", s.version)
        assertEquals("1.20.1", s.minecraft)
        assertEquals(ModLoader.FABRIC, s.loader)
        assertEquals("0.15.7", s.loaderVersion)
        assertEquals(2, s.fileCount)
        assertEquals(2, s.overrideCount)
        assertTrue(r.has(FindingCode.JAVA_MODPACK))
    }

    @Test
    fun curseForgePack() {
        val r = analyze(
            TestJar().text("manifest.json", Samples.CURSEFORGE_MANIFEST).text("overrides/config/a.toml", "x"),
            "cf.zip"
        )
        assertEquals(ContentKind.MODPACK_CURSEFORGE, r.kind)
        val s = r.modpack!!
        assertEquals("curseforge", s.format)
        assertEquals("CF Pack", s.name)
        assertEquals("Hana", s.author)
        assertEquals("1.20.1", s.minecraft)
        assertEquals(ModLoader.FORGE, s.loader)
        assertEquals("47.2.0", s.loaderVersion)
        assertEquals(2, s.fileCount)
        assertEquals(1, s.overrideCount)
    }

    @Test
    fun bedrockPackIsRedirected() {
        val r = analyze(TestJar().text("manifest.json", Samples.BEDROCK_MANIFEST).text("texts/en_US.lang", "x"), "bp.mcpack")
        assertEquals(ContentKind.BEDROCK_PACK, r.kind)
        assertTrue(r.has(FindingCode.JAVA_BEDROCK_FILE, Severity.ERROR))
    }

    @Test
    fun bedrockAddonWithSubfolders() {
        val r = analyze(TestJar().text("BP/manifest.json", Samples.BEDROCK_MANIFEST).text("BP/items/a.json", "{}"), "x.mcaddon")
        assertEquals(ContentKind.BEDROCK_PACK, r.kind)
    }

    @Test
    fun serverPlugin() {
        val r = analyze(TestJar().text("plugin.yml", "name: X\nmain: a.B\nversion: 1").classFile("a/B.class", 61))
        assertEquals(ContentKind.SERVER_PLUGIN, r.kind)
        assertTrue(r.has(FindingCode.JAVA_SERVER_PLUGIN, Severity.ERROR))
    }

    @Test
    fun unsupportedLoaders() {
        val lite = analyze(TestJar().text("litemod.json", "{}").classFile("a/B.class", 52))
        assertEquals(ContentKind.UNSUPPORTED_LOADER, lite.kind)
        assertEquals("LiteLoader", lite.otherLoader)
        assertTrue(lite.hasErrors)
        val rift = analyze(TestJar().text("riftmod.json", "{}").classFile("a/B.class", 52))
        assertEquals("Rift", rift.otherLoader)
    }

    @Test
    fun optifine() {
        val r = analyze(TestJar().classFile("optifine/Installer.class", 52).classFile("net/optifine/Config.class", 52))
        assertEquals(ContentKind.OPTIFINE, r.kind)
        assertTrue(r.has(FindingCode.JAVA_OPTIFINE, Severity.WARNING))
        assertFalse(r.hasErrors)
    }

    @Test
    fun libraryWithoutMetadata() {
        val r = analyze(TestJar().classFile("org/lib/Util.class", 55).classFile("org/lib/Other.class", 52))
        assertEquals(ContentKind.LIBRARY, r.kind)
        assertEquals(11, r.javaMajor)
        assertTrue(r.has(FindingCode.JAVA_NO_METADATA, Severity.WARNING))
    }

    @Test
    fun multiReleaseClassesDoNotRaiseTheRequirement() {
        val r = analyze(
            TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"mr","version":"1"}""")
                .classFile("a/B.class", 52)
                .classFile("META-INF/versions/21/a/B.class", 65)
        )
        assertEquals(8, r.javaMajor)
        assertEquals(1, r.classCount)
    }

    @Test
    fun javaWorld() {
        val r = analyze(TestJar().bytes("World/level.dat", byteArrayOf(1)).bytes("World/region/r.0.0.mca", byteArrayOf(1)), "w.zip")
        assertEquals(ContentKind.JAVA_WORLD, r.kind)
        assertTrue(r.has(FindingCode.JAVA_WORLD_FILE, Severity.ERROR))
    }

    @Test
    fun bundleOfJars() {
        val a = TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"a","version":"1"}""")
        val b = TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"b","version":"1"}""")
        val bundle = TestJar().jar("mods/a.jar", a).jar("b.jar", b).text("LEGGIMI.txt", "ciao")
        val file = bundle.write(File(dir, "bundle.zip"))
        val r = analyzer.analyze(file)
        assertEquals(ContentKind.MOD_BUNDLE, r.kind)
        assertEquals(listOf("mods/a.jar", "b.jar"), r.bundleJars)
        val out = File(dir, "out.jar")
        assertTrue(JarAnalyzer.extractEntry(file, "mods/a.jar", out, 10_000_000))
        assertEquals("a", analyzer.analyze(out).primary!!.id)
        assertFalse(JarAnalyzer.extractEntry(file, "mods/zzz.jar", File(dir, "none.jar"), 10_000_000))
        assertFalse(File(dir, "none.jar").exists())
    }

    @Test
    fun extractEntryEnforcesLimit() {
        val a = TestJar().text("big.bin", "x".repeat(5000))
        val bundle = TestJar().jar("a.jar", a)
        val file = bundle.write(File(dir, "bundle.zip"))
        try {
            JarAnalyzer.extractEntry(file, "a.jar", File(dir, "o.jar"), 100)
            fail("doveva superare il limite")
        } catch (e: com.mmi.modinstaller.core.LimitExceededException) {
            assertFalse(File(dir, "o.jar").exists())
        }
    }

    @Test
    fun nativeLibrariesAndSignatures() {
        val r = analyze(
            TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"nat","version":"1"}""")
                .bytes("natives/libfoo.so", byteArrayOf(1)).bytes("natives/foo.dll", byteArrayOf(1))
                .text("META-INF/CERT.SF", "x").bytes("META-INF/CERT.RSA", byteArrayOf(1))
        )
        assertEquals(2, r.nativeLibraries.size)
        assertTrue(r.has(FindingCode.JAVA_NATIVE_LIBS, Severity.WARNING))
        assertTrue(r.signed)
    }

    // ---------------------------------------------------------------------------------------------- file non validi

    @Test
    fun emptyFile() {
        val f = File(dir, "empty.jar").also { it.writeBytes(ByteArray(0)) }
        val r = analyzer.analyze(f)
        assertEquals(ContentKind.UNKNOWN, r.kind)
        assertTrue(r.has(FindingCode.EMPTY_ARCHIVE, Severity.ERROR))
    }

    @Test
    fun htmlPageInsteadOfJar() {
        val f = File(dir, "page.jar").also { it.writeText("<!DOCTYPE html><html><body>Accesso negato</body></html>") }
        val r = analyzer.analyze(f)
        assertTrue(r.has(FindingCode.HTML_PAGE, Severity.ERROR))
        assertTrue(r.sha256.isNotEmpty())
    }

    @Test
    fun randomBytes() {
        val f = File(dir, "x.jar").also { it.writeBytes(ByteArray(2000) { (it * 7).toByte() }) }
        assertTrue(analyzer.analyze(f).has(FindingCode.NOT_AN_ARCHIVE, Severity.ERROR))
    }

    @Test
    fun sevenZipIsNotReadHere() {
        val f = File(dir, "x.7z").also { it.writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4, 0, 0, 0, 0)) }
        assertTrue(analyzer.analyze(f).has(FindingCode.UNSUPPORTED_FORMAT, Severity.ERROR))
    }

    @Test
    fun truncatedJar() {
        val full = Samples.fabricMod().toBytes()
        val f = File(dir, "cut.jar").also { it.writeBytes(full.copyOf(full.size / 2)) }
        val r = analyzer.analyze(f)
        assertTrue(r.has(FindingCode.CORRUPT_ARCHIVE, Severity.ERROR))
        assertEquals(ContentKind.UNKNOWN, r.kind)
    }

    @Test
    fun corruptedContentIsDetectedByCrc() {
        val bytes = TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"crc","version":"1"}""").toBytes()
        // la voce è compressa: alteriamo un byte dei dati (dopo l'intestazione locale)
        val idx = bytes.indexOfFirstSequence("fabric.mod.json".toByteArray()) + "fabric.mod.json".length + 3
        bytes[idx] = (bytes[idx].toInt() xor 0x5A).toByte()
        val f = File(dir, "crc.jar").also { it.writeBytes(bytes) }
        val r = analyzer.analyze(f)
        assertTrue(r.hasErrors)
        assertTrue(r.has(FindingCode.CORRUPT_ARCHIVE, Severity.ERROR))
    }

    @Test
    fun pathTraversalIsRefused() {
        val f = File(dir, "evil.jar").also { it.writeBytes(TestJar.withRawName("../../evil.txt")) }
        val r = analyzer.analyze(f)
        assertTrue(r.has(FindingCode.UNSAFE_PATH, Severity.ERROR))
        assertEquals(ContentKind.UNKNOWN, r.kind)
    }

    @Test
    fun compressionBombIsRefused() {
        val bomb = TestJar().bytes("zeros.bin", ByteArray(70 * 1024 * 1024))
        val r = analyze(bomb, "bomb.jar")
        assertTrue(r.has(FindingCode.COMPRESSION_BOMB, Severity.ERROR))
    }

    @Test
    fun tooLargeFile() {
        val tiny = JarAnalyzer(JarLimits(maxFileBytes = 100), File(dir, "t"))
        val f = Samples.fabricMod().write(File(dir, "big.jar"))
        assertTrue(tiny.analyze(f).has(FindingCode.ARCHIVE_TOO_LARGE, Severity.ERROR))
    }

    @Test
    fun oversizedMetadataIsReportedNotRead() {
        val small = JarAnalyzer(JarLimits(maxMetadataBytes = 100), File(dir, "t"))
        val f = Samples.fabricMod().write(File(dir, "m.jar"))
        val r = small.analyze(f)
        assertTrue(r.has(FindingCode.JAVA_METADATA_INVALID, Severity.WARNING))
        assertTrue(r.mods.isEmpty())
    }

    @Test
    fun cancellationPropagates() {
        val f = Samples.fabricMod().write(File(dir, "c.jar"))
        try {
            analyzer.analyze(f, cancel = { throw java.util.concurrent.CancellationException("stop") })
            fail("doveva essere annullata")
        } catch (e: java.util.concurrent.CancellationException) {
            // atteso
        }
        // anche durante la lettura delle voci
        var calls = 0
        try {
            analyzer.analyze(f, cancel = { if (++calls > 3) throw java.util.concurrent.CancellationException("stop") })
            fail("doveva essere annullata")
        } catch (e: java.util.concurrent.CancellationException) {
            // atteso
        }
    }

    @Test
    fun junkInsideJarDoesNotConfuseWrapperDetection() {
        val r = analyze(
            TestJar().text("MyPack/pack.mcmeta", Samples.PACK_MCMETA).text("MyPack/assets/a.txt", "x").text("__MACOSX/MyPack/._pack.mcmeta", "x"),
            "mac.zip"
        )
        assertEquals("MyPack", r.pack!!.wrapperFolder)
    }

    @Test
    fun reportIsConsistentForUnicodeNames() {
        val r = analyze(TestJar().text("fabric.mod.json", """{"schemaVersion":1,"id":"uni","version":"1","name":"Caffè §6Dorato","description":"È tutto §rok"}"""))
        assertEquals("Caffè Dorato", r.primary!!.name)
        assertEquals("È tutto ok", r.primary!!.description)
        assertNotNull(r.primary!!.displayName)
    }

    private fun ByteArray.indexOfFirstSequence(seq: ByteArray): Int {
        outer@ for (i in 0..size - seq.size) {
            for (j in seq.indices) if (this[i + j] != seq[j]) continue@outer
            return i
        }
        return -1
    }
}
