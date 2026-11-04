package com.mmi.modinstaller

import com.mmi.modinstaller.core.ArchiveFormat
import com.mmi.modinstaller.core.ContentItem
import com.mmi.modinstaller.core.ContentKind
import com.mmi.modinstaller.core.DependencyState
import com.mmi.modinstaller.core.DependencyStatus
import com.mmi.modinstaller.core.Finding
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.InstallState
import com.mmi.modinstaller.core.ItemStatus
import com.mmi.modinstaller.core.OutputFormat
import com.mmi.modinstaller.core.Severity
import com.mmi.modinstaller.ui.Format
import com.mmi.modinstaller.ui.Tone
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    private fun item(findings: List<Finding> = emptyList(), output: OutputFormat? = OutputFormat.MCPACK) = ContentItem(
        id = "1",
        kind = ContentKind.RESOURCE_PACK,
        title = "Pack",
        description = null,
        author = null,
        version = "1.0.0",
        parts = emptyList(),
        sizeBytes = 10L,
        fileCount = 1,
        dependencies = emptyList(),
        initialFindings = findings,
        output = output,
        iconBytes = null,
        previewImages = emptyList(),
        uuids = emptyList(),
        archiveFile = File("x.zip"),
        archiveFormat = ArchiveFormat.ZIP,
        container = "x.zip"
    )

    @Test
    fun cleanRemovesMinecraftColorCodes() {
        assertEquals("Green Pack", Format.clean("\u00A7aGreen \u00A7lPack\u00A7r"))
        assertEquals("Plain", Format.clean("  Plain "))
        assertEquals("", Format.clean("\u00A7a"))
    }

    @Test
    fun pluralPicksTheRightForm() {
        assertEquals("1 file", Format.plural(1, "file", "file"))
        assertEquals("3 rimasti", Format.plural(3, "rimasto", "rimasti"))
        assertEquals("1 rimasto", Format.plural(1, "rimasto", "rimasti"))
        assertEquals("0 rimasti", Format.plural(0, "rimasto", "rimasti"))
    }

    @Test
    fun readyItemIsOk() {
        val i = item()
        assertEquals(ItemStatus.READY, i.status)
        val view = Format.status(i)
        assertEquals(Tone.OK, view.tone)
    }

    @Test
    fun unverifiedCompatibilityIsInformationNotAClaim() {
        val i = item(listOf(Finding(Severity.INFO, FindingCode.ENGINE_UNKNOWN, "Versione di Minecraft sconosciuta")))
        val view = Format.status(i)
        assertEquals(Tone.INFO, view.tone)
        assertTrue(view.title.contains("non verificata"))
    }

    @Test
    fun warningsKeepTheItemInstallable() {
        val i = item(listOf(Finding(Severity.WARNING, FindingCode.EXTRA_FILES, "File in più")))
        assertEquals(ItemStatus.WARNINGS, i.status)
        assertTrue(i.installable)
        assertEquals(Tone.WARN, Format.status(i).tone)
    }

    @Test
    fun overridableErrorNeedsTheUserToForce() {
        val i = item(listOf(Finding(Severity.ERROR, FindingCode.ENGINE_TOO_OLD, "Minecraft troppo vecchio", null, true)))
        assertEquals(ItemStatus.OVERRIDE, i.status)
        assertTrue(i.installable)
        assertEquals(Tone.WARN, Format.status(i).tone)
    }

    @Test
    fun hardErrorBlocks() {
        val i = item(listOf(Finding(Severity.ERROR, FindingCode.MANIFEST_MISSING, "Manca il manifest")))
        assertEquals(ItemStatus.BLOCKED, i.status)
        assertFalse(i.installable)
        assertEquals(Tone.BAD, Format.status(i).tone)
    }

    @Test
    fun itemWithoutOutputIsBlocked() {
        val i = item(output = null)
        assertEquals(ItemStatus.BLOCKED, i.status)
        assertEquals(Tone.BAD, Format.status(i).tone)
    }

    @Test
    fun severityTones() {
        assertEquals(Tone.BAD, Format.severityTone(Severity.ERROR))
        assertEquals(Tone.WARN, Format.severityTone(Severity.WARNING))
        assertEquals(Tone.INFO, Format.severityTone(Severity.INFO))
    }

    @Test
    fun dependencyLabels() {
        val missing = DependencyStatus("abc", null, "1.0.0", DependencyState.MISSING, null)
        val builtin = DependencyStatus(null, "@minecraft/server", "1.8.0", DependencyState.SCRIPT_MODULE, null)
        val named = DependencyStatus("abc", null, "1.0.0", DependencyState.IN_ITEM, "Mio pack")
        assertEquals(Tone.BAD, Format.dependencyTone(missing))
        assertEquals(Tone.OK, Format.dependencyTone(builtin))
        assertEquals("abc", Format.dependencyName(missing))
        assertEquals("@minecraft/server", Format.dependencyName(builtin))
        assertEquals("Mio pack", Format.dependencyName(named))
        assertEquals("MANCANTE", Format.dependencyState(missing))
    }

    @Test
    fun installStatesAreNeverPresentedAsConfirmedByTheApp() {
        assertTrue(Format.installState(InstallState.SENT).contains("da confermare"))
        assertTrue(Format.installState(InstallState.CONFIRMED).contains("da te"))
        assertEquals(Tone.WARN, Format.installTone(InstallState.SENT))
        assertEquals(Tone.OK, Format.installTone(InstallState.CONFIRMED))
        assertEquals(Tone.BAD, Format.installTone(InstallState.FAILED))
    }

    @Test
    fun everyArchiveFormatHasAName() {
        for (f in ArchiveFormat.values()) assertTrue(Format.archiveFormat(f).isNotBlank())
        assertNotNull(Format.archiveFormat(ArchiveFormat.RAR5))
    }

    @Test
    fun dateTimeHandlesMissingValues() {
        assertEquals("\u2014", Format.dateTime(0L))
        assertTrue(Format.dateTime(1_700_000_000_000L).matches(Regex("\\d\\d/\\d\\d/\\d{4} \\d\\d:\\d\\d")))
    }
}
