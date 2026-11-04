package com.mmi.modinstaller.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * ZIP creati da programmi di Windows (PowerShell 5 «Compress-Archive», .NET Framework): le cartelle compaiono come
 * voci vuote con la barra rovesciata finale, o come voci vuote senza barra. Se venissero copiate come file,
 * accanto a «textures» file ci sarebbe «textures/…»: la cartella non potrebbe esistere e il contenuto andrebbe perso.
 */
class WindowsZipTest {
    private lateinit var dir: File
    private lateinit var out: File

    @Before
    fun setUp() {
        dir = T.tmpDir()
        out = File(dir, "out")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun analyze(entries: Map<String, ByteArray>): ContentItem {
        val f = T.zip(File(dir, "in.zip"), entries)
        return ContentAnalyzer(AnalyzeContext(File(dir, "work"))).analyze(f, "in.zip").items[0]
    }

    @Test
    fun foldersWrittenWithBackslashAreNotCopiedAsFiles() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["Mod RP\\"] = ByteArray(0)
        entries["Mod RP\\textures\\"] = ByteArray(0)
        entries["Mod RP\\textures\\items\\"] = ByteArray(0)
        entries["Mod RP\\manifest.json"] = T.manifest(name = "Mod RP", uuid = T.uuidOf("win-rp"))
        entries["Mod RP\\textures\\items\\gun.png"] = T.png()
        entries["Mod RP\\textures\\items\\flag.png"] = T.png()
        val item = analyze(entries)
        assertTrue(item.installable)

        val p = PackagePreparer().prepare(item, out)

        assertEquals(setOf("manifest.json", "textures/items/gun.png", "textures/items/flag.png"), T.entriesOf(p.file).keys)
        assertEquals(3, p.entryCount)
        assertEquals(3, p.sourceFileCount)
        assertTrue(p.notes.any { it.contains("Inclusi 3 file su 3") })
        assertTrue("le cartelle non devono comparire tra i file fuori dai contenuti", p.notes.none { it.contains("non fa parte") })
    }

    @Test
    fun emptyEntryWithTheNameOfAFolderIsNotWrittenAsFile() {
        // «textures» vuoto, senza barra finale, prima dei file che contiene.
        val entries = LinkedHashMap<String, ByteArray>()
        entries["textures"] = ByteArray(0)
        entries.putAll(T.packEntries("", "Pack", T.uuidOf("win-empty")))
        val item = analyze(entries)
        assertTrue(item.installable)
        assertTrue("l'archivio non è pulito: va ricostruito", item.repackRequired)

        val p = PackagePreparer().prepare(item, out)

        assertFalse(p.passThrough)
        val names = T.entriesOf(p.file).keys
        assertFalse(names.contains("textures"))
        assertTrue(names.contains("textures/blocks/stone.png"))
        assertTrue(names.contains("textures/blocks/dirt.png"))
        assertTrue(names.contains("manifest.json"))
        assertTrue(p.notes.any { it.contains("era in realtà una cartella") })
        assertTrue(p.notes.any { it.contains("Inclusi 4 file su 4") })
    }

    @Test
    fun genuinelyEmptyFilesAreKept() {
        val entries = T.packEntries("Pack/", "Pack", T.uuidOf("win-keep"))
        entries["Pack/texts/vuoto.lang"] = ByteArray(0)
        entries["Pack/texts/en_US.lang"] = T.bytes("a=b")
        val item = analyze(entries)

        val p = PackagePreparer().prepare(item, out)

        val files = T.entriesOf(p.file)
        assertTrue(files.containsKey("texts/vuoto.lang"))
        assertEquals(0, files["texts/vuoto.lang"]!!.size)
        assertEquals("a=b", String(files["texts/en_US.lang"]!!))
        assertEquals(p.entryCount, p.sourceFileCount)
        assertTrue(p.notes.none { it.contains("in realtà") })
    }

    @Test
    fun backslashSeparatorsAtTheRootAreFixed() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["manifest.json"] = T.manifest(name = "Root", uuid = T.uuidOf("win-root"))
        entries["textures\\"] = ByteArray(0)
        entries["textures\\blocks\\stone.png"] = T.png()
        val item = analyze(entries)
        assertTrue(item.repackRequired)

        val p = PackagePreparer().prepare(item, out)

        assertEquals(setOf("manifest.json", "textures/blocks/stone.png"), T.entriesOf(p.file).keys)
    }

    @Test
    fun aRealFileCannotShareItsNameWithAFolder() {
        // «data» con contenuto vero e «data/x.json»: non si può risolvere in silenzio, la preparazione deve fallire.
        val entries = LinkedHashMap<String, ByteArray>()
        entries["manifest.json"] = T.manifest(name = "Conflitto", uuid = T.uuidOf("win-conflict"))
        entries["data"] = T.bytes("non sono una cartella")
        entries["data/x.json"] = T.bytes("{}")
        val item = analyze(entries)

        try {
            PackagePreparer().prepare(item, out)
            fail("avrebbe dovuto rifiutare il pacchetto")
        } catch (e: PreparationException) {
            assertTrue(e.message!!, e.message!!.contains("sia un file sia una cartella"))
        }
        assertTrue("nessun file parziale deve restare", out.listFiles().orEmpty().none { it.isFile })
    }
}
