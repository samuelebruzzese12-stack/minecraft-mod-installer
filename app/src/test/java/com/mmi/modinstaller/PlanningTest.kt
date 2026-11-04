package com.mmi.modinstaller

import com.mmi.modinstaller.core.AnalysisReport
import com.mmi.modinstaller.core.AnalyzeContext
import com.mmi.modinstaller.core.ContentAnalyzer
import com.mmi.modinstaller.core.ItemStatus
import com.mmi.modinstaller.core.MinecraftInfo
import com.mmi.modinstaller.core.McVersion
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Quali contenuti partono con «INSTALLA» e quali avvisi compaiono nel rapporto. */
class PlanningTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mmi-plan").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun uuid(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private fun manifest(name: String, uuid: String, type: String, dep: String? = null, minEngine: String = "[1, 16, 0]"): ByteArray {
        val deps = if (dep == null) "" else ""","dependencies":[{"uuid":"$dep","version":[1,0,0]}]"""
        return (
            """{"format_version":2,"header":{"name":"$name","description":"d","uuid":"$uuid","version":[1,0,0],""" +
                """"min_engine_version":$minEngine},"modules":[{"type":"$type","uuid":"${uuid("m-$uuid")}","version":[1,0,0]}]$deps}"""
            ).toByteArray()
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, d) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(d)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun analyze(name: String, entries: Map<String, ByteArray>, minecraft: MinecraftInfo? = null): AnalysisReport {
        val f = File(dir, "in.bin")
        f.writeBytes(zip(entries))
        return ContentAnalyzer(AnalyzeContext(File(dir, "work"), minecraft = minecraft)).analyze(f, name)
    }

    private fun state(report: AnalysisReport, selected: Set<String>, forced: Set<String> = emptySet()): UiState {
        val file = FileReport(0, "file.zip", 1L, report, null, dir, null)
        return UiState(files = listOf(file), selected = selected, forced = forced)
    }

    private fun gunMod(): AnalysisReport {
        val rp = uuid("rp")
        val bpPack = zip(mapOf("manifest.json" to manifest("Gun Mod BP", uuid("bp"), "data", dep = rp)))
        val rpPack = zip(mapOf("manifest.json" to manifest("Gun Mod RP", rp, "resources")))
        return analyze("Gun Mod.zip", linkedMapOf("Gun Mod BP.mcpack" to bpPack, "Gun Mod RP.mcpack" to rpPack))
    }

    @Test
    fun selectedItemsAreExactlyThePlannedOnes() {
        val r = gunMod()
        assertEquals(2, r.items.size)
        val keys = r.items.map { itemKey(0, it) }.toSet()
        val planned = plannedItems(state(r, keys))
        assertEquals(2, planned.size)
        assertEquals(1, plannedItems(state(r, setOf(keys.first()))).size)
        assertTrue(plannedItems(state(r, emptySet())).isEmpty())
    }

    @Test
    fun noWarningWhenBothPacksAreSelected() {
        val r = gunMod()
        val keys = r.items.map { itemKey(0, it) }.toSet()
        assertTrue(missingCompanions(state(r, keys)).isEmpty())
    }

    @Test
    fun warningWhenTheCompanionPackIsLeftOut() {
        val r = gunMod()
        val bp = r.items.first { it.requires.isNotEmpty() }
        val lines = missingCompanions(state(r, setOf(itemKey(0, bp))))
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("Gun Mod BP"))
        assertTrue(lines[0].contains("Gun Mod RP"))
    }

    @Test
    fun anItemNeedingForceIsPlannedOnlyWhenForced() {
        val old = MinecraftInfo("com.mojang.minecraftpe", "1.16.0", McVersion(listOf(1, 16, 0)))
        val r = analyze("Nuovo.mcpack", mapOf("manifest.json" to manifest("Nuovo", uuid("n"), "resources", minEngine = "[1, 99, 0]")), old)
        val item = r.items.single()
        assertEquals(ItemStatus.OVERRIDE, item.status)
        val key = itemKey(0, item)
        assertTrue(plannedItems(state(r, setOf(key))).isEmpty())
        assertEquals(1, plannedItems(state(r, setOf(key), setOf(key))).size)
        assertNotNull(notSelectedReason(item, false))
        assertNull(notSelectedReason(item, true))
    }

    @Test
    fun duplicateUuidIsExplainedAsReasonNotToSelect() {
        val u = uuid("clone")
        val r = analyze(
            "varianti.zip",
            linkedMapOf(
                "Pieno.mcpack" to zip(mapOf("manifest.json" to manifest("Pieno", u, "resources"))),
                "Leggero.mcpack" to zip(mapOf("manifest.json" to manifest("Leggero", u, "resources")))
            )
        )
        assertEquals(2, r.items.size)
        assertNull(notSelectedReason(r.items[0], false))
        val reason = notSelectedReason(r.items[1], false)
        assertNotNull(reason)
        assertTrue(reason!!.contains(r.items[0].title))
    }
}
