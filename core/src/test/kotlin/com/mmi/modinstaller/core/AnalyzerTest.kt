package com.mmi.modinstaller.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnalyzerTest {
    private lateinit var dir: File
    private var counter = 0

    private val mc1_20_50 = MinecraftInfo("com.mojang.minecraftpe", "1.20.50", McVersion(listOf(1, 20, 50)))

    @Before
    fun setUp() {
        dir = T.tmpDir()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun analyzeFile(
        file: File,
        displayName: String,
        minecraft: MinecraftInfo? = null,
        known: KnownPacks? = null,
        limits: SafetyLimits = SafetyLimits()
    ): AnalysisReport {
        val ctx = AnalyzeContext(File(dir, "work"), ZipOnlyOpener(limits), limits, minecraft, known)
        return ContentAnalyzer(ctx).analyze(file, displayName)
    }

    private fun analyze(
        displayName: String,
        entries: Map<String, ByteArray>,
        minecraft: MinecraftInfo? = null,
        known: KnownPacks? = null,
        limits: SafetyLimits = SafetyLimits()
    ): AnalysisReport {
        val f = T.zip(File(dir, "in_${counter++}.bin"), entries)
        return analyzeFile(f, displayName, minecraft, known, limits)
    }

    private fun codes(item: ContentItem) = item.findings.map { it.code }

    private fun errors(item: ContentItem) = item.findings.filter { it.severity == Severity.ERROR }.map { it.code }

    // ------------------------------------------------------------------ pacchetti

    @Test
    fun resourcePackAtRoot() {
        val u = T.uuidOf("rp")
        val r = analyze("My Pack.mcpack", T.packEntries("", "My Pack", u))
        assertEquals(1, r.items.size)
        val item = r.items[0]
        assertEquals(ContentKind.RESOURCE_PACK, item.kind)
        assertEquals(OutputFormat.MCPACK, item.output)
        assertEquals("My Pack", item.title)
        assertEquals("1.0.0", item.version)
        assertEquals("Tester", item.author)
        assertEquals(listOf(u), item.uuids)
        assertNotNull(item.iconBytes)
        assertTrue(item.previewImages.isNotEmpty())
        assertEquals(ItemStatus.READY, item.status)
        assertTrue(item.compatibilityUnverified)
        assertFalse("un ZIP già corretto non deve essere ricostruito", item.repackRequired)
        assertTrue(r.hasInstallableItems)
    }

    @Test
    fun packInsideFolderIsRestructured() {
        val r = analyze("download.zip", T.packEntries("Cool Pack v2/", "Cool", T.uuidOf("cp")))
        val item = r.items.single()
        assertEquals(ContentKind.RESOURCE_PACK, item.kind)
        assertEquals("Cool Pack v2", item.parts[0].rootPath)
        assertTrue(codes(item).contains(FindingCode.RESTRUCTURED))
        assertTrue(item.repackRequired)
        assertEquals(ItemStatus.READY, item.status)
    }

    @Test
    fun behaviorPackWithScriptModule() {
        val u = T.uuidOf("bp")
        val entries = T.packEntries(
            "", "Scripted", u, type = "data",
            deps = listOf(T.moduleDep("@minecraft/server", "1.8.0"), T.moduleDep("@minecraft/server-ui", "1.2.0-beta"))
        )
        val item = analyze("scripted.mcpack", entries).items.single()
        assertEquals(ContentKind.BEHAVIOR_PACK, item.kind)
        assertEquals(2, item.dependencies.size)
        assertTrue(item.dependencies.all { it.state == DependencyState.SCRIPT_MODULE })
        val beta = item.findings.filter { it.code == FindingCode.EXPERIMENTAL }
        assertEquals(1, beta.count { it.message.contains("Beta") })
        assertEquals(ItemStatus.WARNINGS, item.status)
    }

    @Test
    fun addonWithBehaviorAndResourcePack() {
        val rp = T.uuidOf("addon-rp")
        val bp = T.uuidOf("addon-bp")
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.packEntries("MyAddon_BP/", "MyAddon BP", bp, type = "data", deps = listOf(T.uuidDep(rp))))
        entries.putAll(T.packEntries("MyAddon_RP/", "MyAddon RP", rp))
        val r = analyze("addon.zip", entries)
        val item = r.items.single()
        assertEquals(ContentKind.ADDON, item.kind)
        assertEquals(OutputFormat.MCADDON, item.output)
        assertEquals(2, item.parts.size)
        assertEquals("MyAddon", item.title)
        assertEquals(1, item.dependencies.size)
        assertEquals(DependencyState.IN_ITEM, item.dependencies[0].state)
        assertEquals(setOf(rp, bp), item.uuids.toSet())
        assertTrue(item.repackRequired)
        assertEquals(ItemStatus.READY, item.status)
    }

    @Test
    fun mcaddonExtensionGroupsUnrelatedPacks() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.packEntries("A/", "Alpha", T.uuidOf("a"), type = "data"))
        entries.putAll(T.packEntries("B/", "Beta", T.uuidOf("b")))
        assertEquals(1, analyze("things.mcaddon", entries).items.size)
        val split = analyze("things.zip", entries).items
        assertEquals(2, split.size)
        assertEquals(setOf(ContentKind.BEHAVIOR_PACK, ContentKind.RESOURCE_PACK), split.map { it.kind }.toSet())
    }

    @Test
    fun skinPack() {
        val item = analyze("skins.mcpack", T.packEntries("", "Skins", T.uuidOf("sk"), type = "skin_pack")).items.single()
        assertEquals(ContentKind.SKIN_PACK, item.kind)
        assertEquals(OutputFormat.MCPACK, item.output)
    }

    @Test
    fun nameIsResolvedFromLangFile() {
        val entries = T.packEntries("", "pack.name", T.uuidOf("l"))
        entries["texts/en_US.lang"] = T.bytes("pack.name=Super Texture\npack.description=Bella\n")
        assertEquals("Super Texture", analyze("x.mcpack", entries).items.single().title)
    }

    @Test
    fun invalidManifestIsBlocked() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["manifest.json"] = T.bytes("{ this is not json")
        entries["textures/a.png"] = T.png()
        val item = analyze("broken.mcpack", entries).items.single()
        assertEquals(ItemStatus.BLOCKED, item.status)
        assertTrue(errors(item).contains(FindingCode.MANIFEST_INVALID))
        assertFalse(item.installable)
    }

    @Test
    fun manifestWithBadUuidIsBlocked() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["manifest.json"] = T.bytes(
            """{"format_version":2,"header":{"name":"x","uuid":"not-a-uuid","version":[1,0,0]},"modules":[{"type":"resources","uuid":"also-bad","version":[1,0,0]}]}"""
        )
        val item = analyze("bad.mcpack", entries).items.single()
        assertEquals(ItemStatus.BLOCKED, item.status)
        assertTrue(errors(item).contains(FindingCode.UUID_INVALID))
    }

    @Test
    fun missingDependencyIsWarning() {
        val other = T.uuidOf("missing-dep")
        val item = analyze("x.mcpack", T.packEntries("", "Needs", T.uuidOf("n"), type = "data", deps = listOf(T.uuidDep(other)))).items.single()
        assertEquals(DependencyState.MISSING, item.dependencies.single().state)
        assertTrue(codes(item).contains(FindingCode.DEPENDENCY_MISSING))
        assertEquals(ItemStatus.WARNINGS, item.status)
    }

    @Test
    fun builtinDependencyIsRecognized() {
        val dep = T.uuidDep("0575c61f-a5da-4b7f-9961-ffda2908861e")
        val item = analyze("x.mcpack", T.packEntries("", "Vanilla based", T.uuidOf("v"), deps = listOf(dep))).items.single()
        assertEquals(DependencyState.BUILTIN, item.dependencies.single().state)
        assertFalse(codes(item).contains(FindingCode.DEPENDENCY_MISSING))
    }

    @Test
    fun dependencyFoundInLibrary() {
        val other = T.uuidOf("lib-dep")
        val known = KnownPacks { uuid -> if (uuid == other) KnownPack(uuid, "Libreria", "1.0.0", McVersion(listOf(1, 0, 0))) else null }
        val item = analyze(
            "x.mcpack", T.packEntries("", "Needs lib", T.uuidOf("nl"), type = "data", deps = listOf(T.uuidDep(other))),
            known = known
        ).items.single()
        assertEquals(DependencyState.IN_LIBRARY, item.dependencies.single().state)
        assertEquals("Libreria", item.dependencies.single().resolvedName)
        assertEquals(ItemStatus.READY, item.status)
    }

    // ------------------------------------------------------------------ compatibilità

    @Test
    fun engineTooOldIsOverridable() {
        val entries = T.packEntries("", "Future", T.uuidOf("f"), minEngine = listOf(1, 21, 0))
        val item = analyze("x.mcpack", entries, minecraft = mc1_20_50).items.single()
        val f = item.findings.single { it.code == FindingCode.ENGINE_TOO_OLD }
        assertTrue(f.overridable)
        assertEquals(ItemStatus.OVERRIDE, item.status)
        assertTrue(item.installable)
        assertFalse(item.compatibilityUnverified)
    }

    @Test
    fun engineSatisfied() {
        val entries = T.packEntries("", "Old", T.uuidOf("o"), minEngine = listOf(1, 16, 0))
        val item = analyze("x.mcpack", entries, minecraft = mc1_20_50).items.single()
        assertEquals(ItemStatus.READY, item.status)
        assertFalse(item.compatibilityUnverified)
    }

    @Test
    fun alreadyImportedPackIsFlagged() {
        val u = T.uuidOf("dup")
        val entries = T.packEntries("", "Dup", u, version = listOf(1, 2, 0))
        fun known(v: List<Int>) = KnownPacks { id -> if (id == u) KnownPack(id, "Dup", v.joinToString("."), McVersion(v)) else null }
        val same = analyze("x.mcpack", entries, known = known(listOf(1, 2, 0))).items.single()
        assertTrue(codes(same).contains(FindingCode.SAME_AS_IMPORTED))
        val newer = analyze("x.mcpack", entries, known = known(listOf(1, 1, 0))).items.single()
        assertTrue(codes(newer).contains(FindingCode.NEWER_THAN_IMPORTED))
        assertEquals(ItemStatus.READY, newer.status)
        val older = analyze("x.mcpack", entries, known = known(listOf(1, 3, 0))).items.single()
        assertTrue(codes(older).contains(FindingCode.OLDER_THAN_IMPORTED))
    }

    // ------------------------------------------------------------------ mondi

    @Test
    fun worldInSubfolder() {
        val entries = T.worldEntries("Avventura/", "Avventura", listOf(1, 20, 30, 2, 0), listOf("gametest"))
        val item = analyze("mappa.zip", entries, minecraft = mc1_20_50).items.single()
        assertEquals(ContentKind.WORLD, item.kind)
        assertEquals(OutputFormat.MCWORLD, item.output)
        assertEquals("Avventura", item.title)
        assertEquals("Avventura", item.parts[0].rootPath)
        assertNotNull(item.parts[0].level)
        assertNotNull(item.iconBytes)
        assertTrue(codes(item).contains(FindingCode.WORLD_EXPERIMENTS))
        assertTrue(codes(item).contains(FindingCode.RESTRUCTURED))
        assertEquals(ItemStatus.READY, item.status)
        assertTrue(item.repackRequired)
    }

    @Test
    fun worldAtRootIsPassedThrough() {
        val item = analyze("mappa.mcworld", T.worldEntries("", "Radice", listOf(1, 20, 0, 0, 0))).items.single()
        assertEquals(ContentKind.WORLD, item.kind)
        assertFalse(item.repackRequired)
    }

    @Test
    fun worldTitleFallsBackToLevelNameFromNbt() {
        val entries = T.worldEntries("w/", "Dal NBT", listOf(1, 20, 0))
        entries.remove("w/levelname.txt")
        assertEquals("Dal NBT", analyze("m.zip", entries).items.single().title)
    }

    @Test
    fun worldNewerThanMinecraftIsOverridable() {
        val entries = T.worldEntries("", "Nuova", listOf(1, 21, 2, 0, 0))
        val item = analyze("m.mcworld", entries, minecraft = mc1_20_50).items.single()
        assertTrue(codes(item).contains(FindingCode.WORLD_TOO_NEW))
        assertEquals(ItemStatus.OVERRIDE, item.status)
    }

    @Test
    fun worldWithoutDbIsBlocked() {
        val entries = T.worldEntries("", "Rotta", listOf(1, 20, 0))
        entries.keys.removeAll { it.startsWith("db/") }
        val item = analyze("m.mcworld", entries).items.single()
        assertTrue(errors(item).contains(FindingCode.WORLD_INCOMPLETE))
        assertEquals(ItemStatus.BLOCKED, item.status)
    }

    @Test
    fun javaWorldIsBlocked() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["World/level.dat"] = ByteArray(64) { 1 }
        entries["World/region/r.0.0.mca"] = ByteArray(8192)
        val item = analyze("java.zip", entries).items.single()
        assertTrue(errors(item).contains(FindingCode.WORLD_JAVA))
        assertEquals(ItemStatus.BLOCKED, item.status)
    }

    @Test
    fun worldWithEmbeddedPacksDoesNotCreateExtraItems() {
        val entries = T.worldEntries("", "Con pacchetti", listOf(1, 20, 0))
        entries.putAll(T.packEntries("behavior_packs/bp1/", "Embedded BP", T.uuidOf("ebp"), type = "data"))
        entries.putAll(T.packEntries("resource_packs/rp1/", "Embedded RP", T.uuidOf("erp")))
        val r = analyze("m.mcworld", entries)
        assertEquals(1, r.items.size)
        assertEquals(ContentKind.WORLD, r.items[0].kind)
        assertTrue(r.items[0].findings.any { it.message.contains("1 Behavior Pack e 1 Resource Pack") })
    }

    @Test
    fun worldTemplate() {
        val entries = T.worldEntries("", "Template", listOf(1, 20, 0))
        entries["manifest.json"] = T.manifest(name = "Template", uuid = T.uuidOf("tpl"), type = "world_template")
        val item = analyze("t.mctemplate", entries).items.single()
        assertEquals(ContentKind.WORLD_TEMPLATE, item.kind)
        assertEquals(OutputFormat.MCTEMPLATE, item.output)
        assertEquals(listOf(T.uuidOf("tpl")), item.uuids)
    }

    // ------------------------------------------------------------------ file non validi / pericolosi

    @Test
    fun javaModIsBlocked() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["fabric.mod.json"] = T.bytes("{\"id\":\"x\"}")
        entries["com/example/Mod.class"] = ByteArray(100)
        val item = analyze("mod.zip", entries).items.single()
        assertEquals(ContentKind.JAVA_CONTENT, item.kind)
        assertTrue(errors(item).contains(FindingCode.JAVA_EDITION))
        assertEquals(ItemStatus.BLOCKED, item.status)
    }

    @Test
    fun jarFileIsBlocked() {
        val jar = T.zipBytes(linkedMapOf("META-INF/MANIFEST.MF" to T.bytes("Manifest-Version: 1.0\n"), "A.class" to ByteArray(8)))
        val item = analyze("mods.zip", linkedMapOf("mymod.jar" to jar)).items.single()
        assertEquals(ContentKind.JAVA_CONTENT, item.kind)
    }

    @Test
    fun unrelatedZipHasNoContent() {
        val r = analyze("docs.zip", linkedMapOf("readme.txt" to T.bytes("ciao"), "img/a.png" to T.png()))
        assertTrue(r.items.isEmpty())
        assertTrue(r.findings.any { it.code == FindingCode.NO_CONTENT })
        assertFalse(r.hasInstallableItems)
    }

    @Test
    fun emptyZip() {
        val f = File(dir, "empty.zip")
        java.util.zip.ZipOutputStream(f.outputStream()).use { }
        val r = analyzeFile(f, "empty.zip")
        assertTrue(r.items.isEmpty())
        assertTrue(r.findings.any { it.severity == Severity.ERROR })
    }

    @Test
    fun executablesAreFlaggedAndJunkIgnored() {
        val entries = T.packEntries("", "Sospetto", T.uuidOf("x"))
        entries["installer.exe"] = ByteArray(10)
        entries["native/libhack.so"] = ByteArray(10)
        entries["__MACOSX/._manifest.json"] = ByteArray(4)
        val r = analyze("x.mcpack", entries)
        val item = r.items.single()
        val f = item.findings.single { it.code == FindingCode.EXECUTABLE_FILE }
        assertEquals(Severity.WARNING, f.severity)
        assertTrue(f.message.contains("2"))
        assertTrue(r.findings.any { it.code == FindingCode.JUNK_FILES })
        assertTrue(item.repackRequired)
        assertEquals(ItemStatus.WARNINGS, item.status)
    }

    @Test
    fun zipSlipArchiveIsRejected() {
        val f = File(dir, "slip.zip")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            z.write(T.manifest(uuid = T.uuidOf("s")))
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("../../evil.txt"))
            z.write(1)
            z.closeEntry()
        }
        val r = analyzeFile(f, "slip.mcpack")
        assertTrue(r.items.isEmpty())
        assertTrue(r.findings.any { it.code == FindingCode.UNSAFE_PATH && it.severity == Severity.ERROR })
    }

    @Test
    fun truncatedArchiveIsReportedAsCorrupt() {
        val full = T.zipBytes(T.packEntries("", "Cut", T.uuidOf("c")))
        val f = File(dir, "cut.mcpack")
        f.writeBytes(full.copyOf(full.size - 20))
        val r = analyzeFile(f, "cut.mcpack")
        assertTrue(r.items.isEmpty())
        assertTrue(r.findings.any { it.code == FindingCode.CORRUPT_ARCHIVE })
    }

    @Test
    fun htmlDownloadIsExplained() {
        val f = File(dir, "page.mcpack")
        f.writeText("<html><body>404</body></html>")
        val r = analyzeFile(f, "page.mcpack")
        assertTrue(r.findings.any { it.code == FindingCode.HTML_PAGE })
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun unknownAndEmptyFiles() {
        val a = File(dir, "a.bin")
        a.writeBytes(ByteArray(100) { 3 })
        assertTrue(analyzeFile(a, "a.mcpack").findings.any { it.code == FindingCode.NOT_AN_ARCHIVE })
        val b = File(dir, "b.bin")
        b.writeBytes(ByteArray(0))
        assertTrue(analyzeFile(b, "b.mcpack").findings.any { it.code == FindingCode.NOT_AN_ARCHIVE })
    }

    @Test
    fun rar5AndGzipAreExplained() {
        val rar5 = File(dir, "r.rar")
        rar5.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00, 0, 0, 0, 0))
        assertTrue(analyzeFile(rar5, "r.rar").findings.any { it.code == FindingCode.RAR5_UNSUPPORTED })
        val gz = File(dir, "g.gz")
        gz.writeBytes(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 0, 0, 0, 0))
        assertTrue(analyzeFile(gz, "g.tar.gz").findings.any { it.code == FindingCode.UNSUPPORTED_FORMAT })
    }

    @Test
    fun rarWithoutDecoderIsUnsupportedNotCrash() {
        val rar4 = File(dir, "r4.rar")
        rar4.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0, 0, 0, 0, 0))
        val r = analyzeFile(rar4, "r4.rar")
        assertTrue(r.items.isEmpty())
        assertTrue(r.findings.any { it.severity == Severity.ERROR })
    }

    @Test
    fun jsonManifestAloneIsNotInstallable() {
        val f = File(dir, "manifest.json")
        f.writeBytes(T.manifest(name = "Solo", uuid = T.uuidOf("j")))
        val r = analyzeFile(f, "manifest.json")
        val item = r.items.single()
        assertEquals(ContentKind.MANIFEST_ONLY, item.kind)
        assertEquals(ItemStatus.BLOCKED, item.status)
        assertTrue(errors(item).contains(FindingCode.MANIFEST_ONLY))
    }

    @Test
    fun foreignJsonIsRejected() {
        val f = File(dir, "package.json")
        f.writeText("""{"name":"x","version":"1.0.0"}""")
        val r = analyzeFile(f, "package.json")
        assertTrue(r.items.isEmpty())
        assertTrue(r.findings.any { it.code == FindingCode.JSON_FILE })
    }

    @Test
    fun bombIsRejected() {
        val f = T.zip(File(dir, "bomb.zip"), linkedMapOf("zeros.bin" to ByteArray(20 * 1024 * 1024)))
        val r = analyzeFile(f, "bomb.zip", limits = SafetyLimits(maxCompressionRatio = 50, ratioCheckMinBytes = 1024 * 1024))
        assertTrue(r.findings.any { it.code == FindingCode.COMPRESSION_BOMB })
    }

    @Test
    fun oversizedArchiveIsRejectedWithoutReading() {
        val f = T.zip(File(dir, "x.zip"), T.packEntries("", "Big", T.uuidOf("big")))
        val r = analyzeFile(f, "x.mcpack", limits = SafetyLimits(maxArchiveBytes = 100))
        assertTrue(r.findings.any { it.code == FindingCode.ARCHIVE_TOO_LARGE })
    }

    @Test
    fun duplicateUuidsInsideArchiveAreFlagged() {
        val u = T.uuidOf("same")
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.packEntries("one/", "One", u))
        entries.putAll(T.packEntries("two/", "Two", u))
        val r = analyze("both.zip", entries)
        assertEquals(2, r.items.size)
        assertTrue(r.items.any { codes(it).contains(FindingCode.DUPLICATE_IN_ARCHIVE) })
    }

    // ------------------------------------------------------------------ annidati

    @Test
    fun nestedMcpackIsAnalyzed() {
        val inner = T.zipBytes(T.packEntries("", "Interno", T.uuidOf("in")))
        val r = analyze("bundle.zip", linkedMapOf("Packs/Interno.mcpack" to inner, "LEGGIMI.txt" to T.bytes("ciao")))
        val item = r.items.single()
        assertEquals("Interno", item.title)
        assertTrue(item.container.contains("›"))
        assertTrue(item.archiveFile.exists())
        assertFalse(item.repackRequired)
    }

    @Test
    fun nestedMcaddonAndMcworldAreBothFound() {
        val addon = T.zipBytes(
            LinkedHashMap<String, ByteArray>().apply {
                putAll(T.packEntries("X_BP/", "X BP", T.uuidOf("xb"), type = "data"))
                putAll(T.packEntries("X_RP/", "X RP", T.uuidOf("xr")))
            }
        )
        val world = T.zipBytes(T.worldEntries("", "Mondo", listOf(1, 20, 0)))
        val r = analyze("collection.zip", linkedMapOf("a/X.mcaddon" to addon, "b/Mondo.mcworld" to world))
        assertEquals(2, r.items.size)
        assertEquals(setOf(ContentKind.ADDON, ContentKind.WORLD), r.items.map { it.kind }.toSet())
    }

    @Test
    fun deeplyNestedArchivesStopAtDepthLimit() {
        var data = T.zipBytes(T.packEntries("", "Fondo", T.uuidOf("deep")))
        repeat(4) { data = T.zipBytes(linkedMapOf("inner.mcpack" to data)) }
        val r = analyze("matrioska.zip", linkedMapOf("inner.mcpack" to data))
        assertTrue("non deve trovare il pacchetto oltre il limite di profondità", r.items.isEmpty())
    }

    @Test
    fun cancellationIsPropagated() {
        val f = T.zip(File(dir, "c.zip"), T.packEntries("", "Cancel", T.uuidOf("cc")))
        val ctx = AnalyzeContext(File(dir, "work"), cancel = { throw java.util.concurrent.CancellationException("stop") })
        try {
            ContentAnalyzer(ctx).analyze(f, "c.mcpack")
            org.junit.Assert.fail("la cancellazione doveva interrompere l'analisi")
        } catch (e: java.util.concurrent.CancellationException) {
            // ok
        }
    }

    @Test
    fun manifestWithCapitalizedNameRequiresRepack() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["Manifest.json"] = T.manifest(name = "Maiuscolo", uuid = T.uuidOf("mj"))
        entries["pack_icon.png"] = T.png()
        val item = analyze("x.mcpack", entries).items.single()
        assertEquals(ContentKind.RESOURCE_PACK, item.kind)
        assertTrue(item.repackRequired)
    }

    @Test
    fun windowsBackslashPathsAreHandled() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["Pack\\manifest.json"] = T.manifest(name = "Windows", uuid = T.uuidOf("win"))
        entries["Pack\\textures\\a.png"] = T.png()
        val item = analyze("win.zip", entries).items.single()
        assertEquals("Pack", item.parts[0].rootPath)
        assertTrue(item.repackRequired)
    }

    @Test
    fun noInternalStateLeaksBetweenRuns() {
        val a = analyze("a.mcpack", T.packEntries("", "A", T.uuidOf("1")))
        val b = analyze("b.mcpack", T.packEntries("", "B", T.uuidOf("2")))
        assertNull(a.items.single().parts[0].level)
        assertFalse(a.items.single().id == b.items.single().id && a.items.single().title == b.items.single().title)
    }
}
