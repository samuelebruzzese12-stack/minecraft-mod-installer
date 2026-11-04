package com.mmi.modinstaller.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PreparerTest {
    private lateinit var dir: File
    private lateinit var out: File
    private var counter = 0

    @Before
    fun setUp() {
        dir = T.tmpDir()
        out = File(dir, "out")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun item(
        displayName: String,
        entries: Map<String, ByteArray>,
        minecraft: MinecraftInfo? = null,
        index: Int = 0
    ): ContentItem {
        val f = T.zip(File(dir, "in_${counter++}.bin"), entries)
        val ctx = AnalyzeContext(File(dir, "work"), minecraft = minecraft)
        return ContentAnalyzer(ctx).analyze(f, displayName).items[index]
    }

    private fun prepare(item: ContentItem, preparer: PackagePreparer = PackagePreparer()): PreparedPackage =
        preparer.prepare(item, out)

    @Test
    fun cleanPackIsCopiedUnchanged() {
        val it = item("My Pack.mcpack", T.packEntries("", "My Pack", T.uuidOf("p1")))
        val p = prepare(it)
        assertTrue(p.passThrough)
        assertEquals("My_Pack.mcpack", p.fileName)
        assertEquals(OutputFormat.MCPACK, p.format)
        assertArrayEquals(it.archiveFile.readBytes(), p.file.readBytes())
        assertTrue(out.listFiles()!!.none { f -> f.name.endsWith(".part") })
    }

    @Test
    fun packInFolderIsMovedToRoot() {
        val it = item("dl.zip", T.packEntries("Cool Pack v2/", "Cool", T.uuidOf("p2")))
        val p = prepare(it)
        assertFalse(p.passThrough)
        val names = T.entriesOf(p.file).keys
        assertTrue(names.contains("manifest.json"))
        assertTrue(names.contains("textures/blocks/stone.png"))
        assertTrue(names.none { n -> n.startsWith("Cool Pack v2") })
        assertEquals(".mcpack", p.fileName.substring(p.fileName.lastIndexOf('.')))
        assertTrue(p.notes.any { n -> n.contains("radice") })
    }

    @Test
    fun addonIsSplitIntoPackFolders() {
        val rp = T.uuidOf("ad-rp")
        val bp = T.uuidOf("ad-bp")
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.packEntries("My Addon BP/", "My Addon BP", bp, type = "data", deps = listOf(T.uuidDep(rp))))
        entries.putAll(T.packEntries("My Addon RP/", "My Addon RP", rp))
        entries["LEGGIMI.txt"] = T.bytes("non incluso")
        val it = item("addon.zip", entries)
        val p = prepare(it)
        assertEquals(OutputFormat.MCADDON, p.format)
        assertTrue(p.fileName.endsWith(".mcaddon"))
        val names = T.entriesOf(p.file).keys
        assertTrue(names.contains("My_Addon_BP/manifest.json"))
        assertTrue(names.contains("My_Addon_RP/manifest.json"))
        assertTrue(names.contains("My_Addon_RP/textures/blocks/stone.png"))
        assertFalse(names.contains("LEGGIMI.txt"))
        assertTrue(names.all { n -> n.contains('/') })
    }

    @Test
    fun worldFromSubfolderGoesToRoot() {
        val it = item("mappa.zip", T.worldEntries("Avventura Epica/", "Avventura Epica", listOf(1, 20, 0)))
        val p = prepare(it)
        assertEquals(OutputFormat.MCWORLD, p.format)
        assertEquals("Avventura_Epica.mcworld", p.fileName)
        val names = T.entriesOf(p.file).keys
        assertTrue(names.contains("level.dat"))
        assertTrue(names.contains("db/CURRENT"))
        assertTrue(names.contains("levelname.txt"))
        assertTrue(names.none { n -> n.startsWith("Avventura") })
    }

    @Test
    fun worldTemplateKeepsManifest() {
        val entries = T.worldEntries("tpl/", "Template", listOf(1, 20, 0))
        entries["tpl/manifest.json"] = T.manifest(name = "Template", uuid = T.uuidOf("tp"), type = "world_template")
        val p = prepare(item("t.zip", entries))
        assertEquals(OutputFormat.MCTEMPLATE, p.format)
        assertTrue(T.entriesOf(p.file).keys.containsAll(listOf("manifest.json", "level.dat", "db/CURRENT")))
    }

    @Test
    fun executablesAndJunkAreDropped() {
        val entries = T.packEntries("", "Sospetto", T.uuidOf("p3"))
        entries["setup.exe"] = ByteArray(10)
        entries["lib/hack.dll"] = ByteArray(10)
        entries["__MACOSX/._manifest.json"] = ByteArray(10)
        entries[".DS_Store"] = ByteArray(10)
        val p = prepare(item("s.mcpack", entries))
        val names = T.entriesOf(p.file).keys
        assertTrue(names.none { n -> n.endsWith(".exe") || n.endsWith(".dll") || n.contains("__MACOSX") || n.endsWith(".DS_Store") })
        assertEquals(setOf("setup.exe", "lib/hack.dll"), p.excluded.toSet())
        assertTrue(p.notes.any { n -> n.contains("eseguibili") })
        assertTrue(names.contains("manifest.json"))
    }

    @Test
    fun capitalizedKeyFilesAreCanonicalized() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["Manifest.JSON"] = T.manifest(name = "Maiuscole", uuid = T.uuidOf("p4"))
        entries["Pack_Icon.PNG"] = T.png()
        entries["textures/a.png"] = T.png()
        val p = prepare(item("c.mcpack", entries))
        val names = T.entriesOf(p.file).keys
        assertTrue(names.contains("manifest.json"))
        assertTrue(names.contains("textures/a.png"))
        assertFalse(names.contains("Manifest.JSON"))
    }

    @Test
    fun windowsPathsAreNormalized() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["Pack\\manifest.json"] = T.manifest(name = "Win", uuid = T.uuidOf("p5"))
        entries["Pack\\textures\\blocks\\x.png"] = T.png()
        val p = prepare(item("w.zip", entries))
        val names = T.entriesOf(p.file).keys
        assertEquals(setOf("manifest.json", "textures/blocks/x.png"), names)
    }

    @Test
    fun filesDifferingOnlyByCaseAreAllKept() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.packEntries("", "Dup", T.uuidOf("p6")))
        entries["textures/blocks/Stone.png"] = T.png() // stesso nome a meno delle maiuscole
        val p = prepare(item("d.mcpack", entries))
        val stones = T.entriesOf(p.file).keys.filter { n -> n.lowercase() == "textures/blocks/stone.png" }
        // Prima uno dei due veniva scartato in silenzio e la texture poteva mancare: ora restano entrambi.
        assertEquals(2, stones.size)
        assertTrue(p.notes.any { n -> n.contains("maiuscole") })
    }

    @Test
    fun identicalPathsAreKeptOnceAndReported() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.packEntries("", "Same", T.uuidOf("p6b")))
        entries["textures/blocks/dupA.png"] = T.png()
        entries["textures/blocks/dupB.png"] = T.png()
        val zip = T.zip(File(dir, "dup_${counter++}.bin"), entries)
        // Stessa lunghezza: sostituendo il nome nei byte dello ZIP si ottengono due voci con lo stesso percorso.
        val raw = String(zip.readBytes(), Charsets.ISO_8859_1).replace("dupB.png", "dupA.png")
        zip.writeBytes(raw.toByteArray(Charsets.ISO_8859_1))
        val report = ContentAnalyzer(AnalyzeContext(File(dir, "work"))).analyze(zip, "dup.mcpack")
        val p = prepare(report.items.single())
        val names = T.entriesOf(p.file).keys
        assertEquals(1, names.count { n -> n == "textures/blocks/dupA.png" })
        assertTrue(p.notes.any { n -> n.contains("stesso identico percorso") })
    }

    @Test
    fun everyFileReadIsAccountedFor() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.packEntries("Pack/", "Conti", T.uuidOf("p6c")))
        entries["Pack/tool.exe"] = ByteArray(10)
        entries["LEGGIMI.txt"] = T.bytes("fuori dal pacchetto")
        val it = item("conti.zip", entries)
        val p = prepare(it)
        val inScope = entries.keys.count { n -> n.startsWith("Pack/") }
        assertEquals(inScope, p.sourceFileCount)
        assertEquals(inScope - 1, p.entryCount)
        assertTrue(p.notes.any { n -> n == "Inclusi ${inScope - 1} file su $inScope dei contenuti scelti" })
        assertTrue(p.notes.any { n -> n.contains("1 file dell'archivio non fanno parte") })
        assertEquals(listOf("Conti"), p.packNames)
    }

    @Test
    fun unsafeCharactersInTitleAreSanitized() {
        val p = prepare(item("x.mcpack", T.packEntries("", "A&B: Mega \"Pack\"! è", T.uuidOf("p7"))))
        assertFalse(p.fileName.contains('&'))
        assertTrue(Regex("^[A-Za-z0-9_\\-]+\\.mcpack$").matches(p.fileName))
    }

    @Test
    fun preparingTwiceNeverOverwrites() {
        val it = item("x.mcpack", T.packEntries("", "Same", T.uuidOf("p8")))
        val a = prepare(it)
        val b = prepare(it)
        assertNotEquals(a.file, b.file)
        assertTrue(a.file.exists() && b.file.exists())
        assertEquals("Same.mcpack", a.fileName)
        assertEquals("Same_2.mcpack", b.fileName)
    }

    @Test
    fun blockedItemsAreRefused() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["manifest.json"] = T.bytes("{ broken")
        val it = item("b.mcpack", entries)
        try {
            prepare(it)
            fail()
        } catch (e: PreparationException) {
            // ok
        }
        assertFalse(out.exists() && out.listFiles()!!.isNotEmpty())
    }

    @Test
    fun overrideItemsCanBePrepared() {
        val mc = MinecraftInfo("com.mojang.minecraftpe", "1.20.50", McVersion(listOf(1, 20, 50)))
        val it = item("f.mcpack", T.packEntries("", "Future", T.uuidOf("p9"), minEngine = listOf(1, 21, 0)), minecraft = mc)
        assertEquals(ItemStatus.OVERRIDE, it.status)
        assertTrue(prepare(it).file.exists())
    }

    @Test
    fun diskSpaceIsChecked() {
        val it = item("x.mcpack", T.packEntries("", "Space", T.uuidOf("p10")))
        try {
            prepare(it, PackagePreparer(freeSpace = { 1000L }))
            fail()
        } catch (e: LimitExceededException) {
            assertEquals(FindingCode.DISK_SPACE, e.code)
        }
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationLeavesNoTemporaryFiles() {
        val it = item("f.zip", T.packEntries("Folder/", "Cancel", T.uuidOf("p11")))
        try {
            PackagePreparer().prepare(it, out, cancel = { throw java.util.concurrent.CancellationException("stop") })
            fail()
        } catch (e: java.util.concurrent.CancellationException) {
            // ok
        }
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun entryLimitIsEnforced() {
        val it = item("f.zip", T.packEntries("Folder/", "Limit", T.uuidOf("p12")))
        try {
            PackagePreparer(SafetyLimits(maxEntries = 2)).prepare(it, out)
            fail()
        } catch (e: LimitExceededException) {
            assertEquals(FindingCode.TOO_MANY_ENTRIES, e.code)
        }
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun verificationCatchesWrongUuid() {
        val real = item("x.mcpack", T.packEntries("", "Real", T.uuidOf("real")))
        val forged = ContentItem(
            id = "forged", kind = real.kind, title = real.title, description = null, author = null, version = null,
            parts = real.parts, sizeBytes = real.sizeBytes, fileCount = real.fileCount, dependencies = emptyList(),
            initialFindings = emptyList(), output = OutputFormat.MCPACK, iconBytes = null, previewImages = emptyList(),
            uuids = listOf(T.uuidOf("other")), archiveFile = real.archiveFile, archiveFormat = real.archiveFormat,
            container = real.container, repackRequired = true
        )
        try {
            prepare(forged)
            fail()
        } catch (e: PreparationException) {
            assertTrue(e.message!!.contains("UUID"))
        }
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun verificationCatchesMissingManifestInOutput() {
        // contenuto dichiarato come pacchetto ma senza manifest alla radice dopo la ricostruzione
        val real = item("x.zip", T.packEntries("P/", "NoManifest", T.uuidOf("nm")))
        val brokenPart = PartInfo(PartRole.PACK, "Q", "x", PackKind.RESOURCE, null, null, 1, 1)
        val forged = ContentItem(
            id = "forged", kind = real.kind, title = real.title, description = null, author = null, version = null,
            parts = listOf(brokenPart), sizeBytes = 100, fileCount = 1, dependencies = emptyList(),
            initialFindings = emptyList(), output = OutputFormat.MCPACK, iconBytes = null, previewImages = emptyList(),
            uuids = emptyList(), archiveFile = real.archiveFile, archiveFormat = real.archiveFormat,
            container = real.container, repackRequired = true
        )
        try {
            prepare(forged)
            fail()
        } catch (e: PreparationException) {
            // nessun file nella cartella "Q": nulla da includere
        }
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun outputIsAValidZipAndEntriesMatchOriginalBytes() {
        val entries = T.packEntries("Folder/", "Bytes", T.uuidOf("p13"))
        entries["Folder/scripts/main.js"] = ByteArray(5000) { (it % 251).toByte() }
        val p = prepare(item("f.zip", entries))
        val result = T.entriesOf(p.file)
        assertArrayEquals(entries["Folder/scripts/main.js"], result["scripts/main.js"])
        assertArrayEquals(entries["Folder/manifest.json"], result["manifest.json"])
        assertEquals(entries.size, result.size)
    }
}

class BackupTest {
    private lateinit var dir: File
    private var now = 1_000L

    @Before
    fun setUp() {
        dir = T.tmpDir()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun manager() = BackupManager(File(dir, "backups")) { now++ }

    private fun file(name: String, size: Int): File {
        val f = File(dir, name)
        f.writeBytes(ByteArray(size) { (it % 100).toByte() })
        return f
    }

    @Test
    fun createListRestoreDelete() {
        val m = manager()
        val src = file("Mappa.mcworld", 5000)
        val info = m.create(src, "Mappa bella", "prima dell'eliminazione")
        assertEquals("Mappa.mcworld", info.fileName)
        assertEquals(5000L, info.sizeBytes)
        val list = m.list()
        assertEquals(1, list.size)
        assertEquals("Mappa bella", list[0].title)
        assertEquals("prima dell'eliminazione", list[0].reason)

        val restoreDir = File(dir, "restored")
        val r1 = m.restore(info.id, restoreDir)
        assertArrayEquals(src.readBytes(), r1.readBytes())
        val r2 = m.restore(info.id, restoreDir)
        assertNotEquals(r1, r2)
        assertEquals("Mappa_2.mcworld", r2.name)

        m.delete(info.id)
        assertTrue(m.list().isEmpty())
    }

    @Test
    fun listIsNewestFirstAndPruneKeepsRecent() {
        val m = manager()
        val ids = (1..5).map { i -> m.create(file("f$i.mcpack", 100 * i), "t$i", "r").id }
        assertEquals(ids.reversed(), m.list().map { it.id })
        m.prune(keep = 3, maxBytes = Long.MAX_VALUE)
        assertEquals(ids.reversed().take(3), m.list().map { it.id })
        m.prune(keep = 10, maxBytes = 650)
        // 500 + 400 + 300 = 1200 -> elimina i più vecchi finché il totale è <= 650 (resta il più recente)
        assertEquals(1, m.list().size)
        assertEquals(ids.last(), m.list()[0].id)
    }

    @Test
    fun invalidIdsAreRejected() {
        val m = manager()
        for (bad in listOf("", "../x", "12/34", "abc")) {
            try {
                m.delete(bad)
                fail("id non valido accettato: $bad")
            } catch (e: java.io.IOException) {
                // ok
            }
        }
    }

    @Test
    fun missingSourceFails() {
        try {
            manager().create(File(dir, "nope.mcpack"), "x", "y")
            fail()
        } catch (e: java.io.IOException) {
            // ok
        }
    }

    @Test
    fun corruptedMetaIsIgnored() {
        val m = manager()
        val info = m.create(file("a.mcpack", 10), "a", "r")
        File(dir, "backups/${info.id}/meta.json").writeText("{ not json")
        assertTrue(m.list().isEmpty())
    }
}
