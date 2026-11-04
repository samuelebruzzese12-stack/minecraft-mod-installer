package com.mmi.modinstaller.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Più contenuti dello stesso file (o di file diversi) consegnati a Minecraft in un solo .mcaddon. */
class BundleTest {
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

    private fun report(displayName: String, entries: Map<String, ByteArray>): AnalysisReport {
        val f = T.zip(File(dir, "in_${counter++}.bin"), entries)
        return ContentAnalyzer(AnalyzeContext(File(dir, "work"))).analyze(f, displayName)
    }

    /** Un file ZIP che contiene due .mcpack separati: il behavior pack dipende dal resource pack. */
    private fun twoMcpacks(name: String = "Gun Mod"): Map<String, ByteArray> {
        val rp = T.uuidOf("$name-rp")
        val bp = T.uuidOf("$name-bp")
        val bpPack = T.zipBytes(T.packEntries("", "$name BP", bp, type = "data", deps = listOf(T.uuidDep(rp))))
        val rpPack = T.zipBytes(T.packEntries("", "$name RP", rp))
        return linkedMapOf("$name BP.mcpack" to bpPack, "$name RP.mcpack" to rpPack, "LEGGIMI.txt" to T.bytes("x"))
    }

    @Test
    fun dependencyOnASiblingItemIsNotMissing() {
        val r = report("Gun Mod.zip", twoMcpacks())
        assertEquals(2, r.items.size)
        val bp = r.items.first { it.kind == ContentKind.BEHAVIOR_PACK }
        val rp = r.items.first { it.kind == ContentKind.RESOURCE_PACK }
        assertTrue(bp.findings.none { it.code == FindingCode.DEPENDENCY_MISSING })
        assertEquals(DependencyState.IN_ARCHIVE, bp.dependencies.single().state)
        assertEquals(listOf(rp.id), bp.requires)
        assertTrue(rp.requires.isEmpty())
        assertTrue(bp.findings.any { it.message.contains("importali insieme") })
    }

    @Test
    fun trulyMissingDependencyIsStillReported() {
        val bp = T.uuidOf("lonely-bp")
        val r = report(
            "Lonely.zip",
            linkedMapOf("Lonely BP.mcpack" to T.zipBytes(T.packEntries("", "Lonely BP", bp, type = "data", deps = listOf(T.uuidDep(T.uuidOf("nowhere"))))))
        )
        val item = r.items.single()
        assertTrue(item.findings.any { it.code == FindingCode.DEPENDENCY_MISSING })
        assertEquals(DependencyState.MISSING, item.dependencies.single().state)
        assertTrue(item.requires.isEmpty())
    }

    @Test
    fun separatePacksAreMergedIntoOneAddon() {
        val r = report("Gun Mod.zip", twoMcpacks())
        val p = PackagePreparer().prepareBundle(r.items, "Gun Mod", out)
        assertEquals(OutputFormat.MCADDON, p.format)
        assertEquals("Gun_Mod.mcaddon", p.fileName)
        assertEquals(2, p.itemCount)
        assertFalse(p.passThrough)
        val names = T.entriesOf(p.file).keys
        assertTrue(names.contains("Gun_Mod_BP/manifest.json"))
        assertTrue(names.contains("Gun_Mod_RP/manifest.json"))
        assertTrue(names.contains("Gun_Mod_RP/textures/blocks/stone.png"))
        assertTrue(names.all { it.contains('/') })
        assertEquals(setOf("Gun Mod BP", "Gun Mod RP"), p.packNames.toSet())
        assertTrue(p.notes.any { it.startsWith("Uniti 2 contenuti (2 pacchetti)") })
        assertEquals(names.size, p.entryCount)
        assertEquals(names.size, p.sourceFileCount)
    }

    @Test
    fun packsFromDifferentFilesCanBeMerged() {
        val a = report("A.mcpack", T.packEntries("", "Alfa", T.uuidOf("alfa"))).items.single()
        val b = report("B.zip", T.packEntries("Beta/", "Beta", T.uuidOf("beta"), type = "data")).items.single()
        val p = PackagePreparer().prepareBundle(listOf(a, b), "Alfa e Beta", out)
        val names = T.entriesOf(p.file).keys
        assertTrue(names.contains("Alfa_RP/manifest.json"))
        assertTrue(names.contains("Beta_BP/manifest.json"))
        assertTrue(names.none { it.startsWith("Beta/") })
    }

    @Test
    fun folderNamesNeverCollide() {
        val a = report("A.mcpack", T.packEntries("", "Stesso", T.uuidOf("s1"))).items.single()
        val b = report("B.mcpack", T.packEntries("", "Stesso", T.uuidOf("s2"))).items.single()
        val p = PackagePreparer().prepareBundle(listOf(a, b), "Doppi", out)
        val roots = T.entriesOf(p.file).keys.map { it.substringBefore('/') }.toSet()
        assertEquals(setOf("Stesso_RP", "Stesso_RP_2"), roots)
    }

    @Test
    fun aSingleItemIsPreparedAsUsual() {
        val a = report("Solo.mcpack", T.packEntries("", "Solo", T.uuidOf("solo"))).items.single()
        val p = PackagePreparer().prepareBundle(listOf(a), "Ignorato", out)
        assertEquals(OutputFormat.MCPACK, p.format)
        assertEquals(1, p.itemCount)
        assertEquals("Solo.mcpack", p.fileName)
    }

    @Test
    fun worldsCannotBeMergedWithPacks() {
        val pack = report("P.mcpack", T.packEntries("", "Pack", T.uuidOf("pw"))).items.single()
        val world = report("W.zip", T.worldEntries("W/", "Mondo", listOf(1, 20, 0))).items.single()
        try {
            PackagePreparer().prepareBundle(listOf(pack, world), "Misto", out)
            fail()
        } catch (e: PreparationException) {
            assertTrue(e.message!!.contains("mappa"))
        }
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun blockedItemsAreRefusedInABundle() {
        val ok = report("OK.mcpack", T.packEntries("", "Ok", T.uuidOf("ok"))).items.single()
        assertTrue(report("Bad.zip", mapOf("readme.txt" to T.bytes("niente"))).items.isEmpty())
        val java = report("mod.zip", mapOf("fabric.mod.json" to T.bytes("{}"), "a.jar" to ByteArray(4))).items.single()
        try {
            PackagePreparer().prepareBundle(listOf(ok, java), "Java", out)
            fail()
        } catch (e: PreparationException) {
            assertTrue(e.message!!.contains("errori"))
        }
    }

    @Test
    fun cancellationLeavesNothingBehind() {
        val r = report("Gun Mod.zip", twoMcpacks())
        var calls = 0
        try {
            PackagePreparer().prepareBundle(r.items, "Gun Mod", out, cancel = { if (++calls > 3) throw java.util.concurrent.CancellationException() })
            fail()
        } catch (e: java.util.concurrent.CancellationException) {
            // atteso
        }
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun nestedManifestInsideAPackIsReported() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.packEntries("", "Esterno", T.uuidOf("outer")))
        entries.putAll(T.packEntries("Extra/", "Interno", T.uuidOf("inner"), type = "data"))
        val r = report("nidificato.zip", entries)
        val item = r.items.single()
        val f = item.findings.single { it.code == FindingCode.NESTED_ARCHIVE }
        assertEquals(Severity.WARNING, f.severity)
        assertTrue(f.message.contains("manifest.json"))
        assertTrue(f.detail!!.contains("Extra/manifest.json"))
        // Il contenuto resta comunque tra i file del pacchetto: nulla va perso.
        val p = PackagePreparer().prepare(item, out)
        assertTrue(T.entriesOf(p.file).keys.contains("Extra/manifest.json"))
    }

    @Test
    fun manifestsInsideAWorldAreNotReported() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries.putAll(T.worldEntries("W/", "Mondo", listOf(1, 20, 0)))
        entries.putAll(T.packEntries("W/behavior_packs/bp/", "Dentro", T.uuidOf("in-world"), type = "data"))
        val r = report("mondo.zip", entries)
        assertTrue(r.items.single().findings.none { it.code == FindingCode.NESTED_ARCHIVE })
    }

    @Test
    fun secondItemWithSameUuidIsMarked() {
        val uuid = T.uuidOf("clone")
        val entries = linkedMapOf(
            "Pieno.mcpack" to T.zipBytes(T.packEntries("", "Pieno", uuid)),
            "Leggero.mcpack" to T.zipBytes(T.packEntries("", "Leggero", uuid))
        )
        val r = report("varianti.zip", entries)
        assertEquals(2, r.items.size)
        assertNull(r.items[0].sameUuidAs)
        assertNotNull(r.items[1].sameUuidAs)
        assertTrue(r.items[1].findings.any { it.code == FindingCode.DUPLICATE_IN_ARCHIVE })
    }
}
