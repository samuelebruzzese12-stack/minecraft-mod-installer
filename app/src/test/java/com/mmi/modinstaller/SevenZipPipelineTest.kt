package com.mmi.modinstaller

import com.mmi.modinstaller.core.AnalyzeContext
import com.mmi.modinstaller.core.ArchiveFormat
import com.mmi.modinstaller.core.ContentAnalyzer
import com.mmi.modinstaller.core.ContentKind
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.ItemStatus
import com.mmi.modinstaller.core.OutputFormat
import com.mmi.modinstaller.core.PackagePreparer
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.data.AndroidArchiveOpener
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Prova il percorso reale dei file .7z: lettura con Apache Commons Compress, analisi, preparazione del pacchetto e
 * rilettura del risultato. Gira sulla JVM con le stesse librerie dell'app.
 */
class SevenZipPipelineTest {

    private lateinit var dir: File
    private val limits = SafetyLimits()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mmi-7z").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun write7z(file: File, entries: Map<String, ByteArray>, method: SevenZMethod = SevenZMethod.COPY): File {
        SevenZOutputFile(file).use { out ->
            out.setContentCompression(method)
            for ((name, data) in entries) {
                val entry = SevenZArchiveEntry()
                entry.setName(name)
                if (name.endsWith("/")) {
                    entry.setDirectory(true)
                    entry.setHasStream(false)
                    out.putArchiveEntry(entry)
                    out.closeArchiveEntry()
                } else {
                    entry.setDirectory(false)
                    entry.setHasStream(true)
                    entry.setSize(data.size.toLong())
                    out.putArchiveEntry(entry)
                    out.write(data)
                    out.closeArchiveEntry()
                }
            }
            out.finish()
        }
        return file
    }

    private fun uuid(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private fun manifest(name: String, type: String, packSeed: String, deps: List<String> = emptyList()): ByteArray {
        val depsJson = if (deps.isEmpty()) "" else
            ",\"dependencies\":[" + deps.joinToString(",") { "{\"uuid\":\"$it\",\"version\":[1,0,0]}" } + "]"
        return ("{\"format_version\":2,\"header\":{\"name\":\"$name\",\"description\":\"prova\"," +
            "\"uuid\":\"${uuid(packSeed)}\",\"version\":[1,0,0],\"min_engine_version\":[1,20,0]}," +
            "\"modules\":[{\"type\":\"$type\",\"uuid\":\"${uuid(packSeed + "-module")}\",\"version\":[1,0,0]}]$depsJson}")
            .toByteArray(Charsets.UTF_8)
    }

    private fun opener() = AndroidArchiveOpener(limits, File(dir, "rar"))

    private fun analyze(file: File, name: String) = ContentAnalyzer(
        AnalyzeContext(workDir = File(dir, "work").also { it.mkdirs() }, opener = opener(), limits = limits)
    ).analyze(file, name)

    private fun zipEntries(file: File): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipArchiveReader.open(file, limits).use { reader ->
            reader.forEachEntry { entry, content ->
                if (content != null) {
                    val buffer = ByteArrayOutputStream()
                    content.copyTo(buffer)
                    result[entry.rawName] = buffer.toByteArray()
                }
            }
        }
        return result
    }

    @Test
    fun resourcePackIn7zIsReadAnalyzedAndRepackedAtTheRoot() {
        val archive = write7z(
            File(dir, "texture.7z"),
            linkedMapOf(
                "Pack/" to ByteArray(0),
                "Pack/manifest.json" to manifest("Texture di prova", "resources", "rp1"),
                "Pack/textures/blocks/a.txt" to "ciao".toByteArray()
            )
        )

        val report = analyze(archive, "texture.7z")
        assertEquals(ArchiveFormat.SEVEN_Z, report.source.format)
        assertEquals(1, report.items.size)
        val item = report.items[0]
        assertEquals(ContentKind.RESOURCE_PACK, item.kind)
        assertTrue("stato: ${item.status} ${item.findings}", item.installable)
        assertEquals(OutputFormat.MCPACK, item.output)

        val prepared = PackagePreparer(limits).prepare(item, File(dir, "out"), opener())
        assertEquals(OutputFormat.MCPACK, prepared.format)
        assertTrue(prepared.file.name.endsWith(".mcpack"))
        val entries = zipEntries(prepared.file)
        assertTrue("manifest alla radice: ${entries.keys}", entries.containsKey("manifest.json"))
        assertEquals("ciao", String(entries["textures/blocks/a.txt"]!!, Charsets.UTF_8))
        assertFalse(entries.keys.any { it.startsWith("Pack/") })
    }

    @Test
    fun addOnWithBehaviorAndResourcePackBecomesAMcaddon() {
        val rpUuid = uuid("rp2")
        val archive = write7z(
            File(dir, "addon.7z"),
            linkedMapOf(
                "MyAddon/RP/manifest.json" to manifest("Risorse", "resources", "rp2"),
                "MyAddon/BP/manifest.json" to manifest("Logica", "data", "bp2", listOf(rpUuid)),
                "MyAddon/BP/entities/mob.json" to "{}".toByteArray()
            ),
            SevenZMethod.DEFLATE
        )

        val report = analyze(archive, "addon.7z")
        val installable = report.items.filter { it.installable }
        assertTrue("nessun contenuto installabile: ${report.findings} ${report.items.map { it.findings }}", installable.isNotEmpty())
        val item = installable[0]
        assertEquals(ContentKind.ADDON, item.kind)

        val prepared = PackagePreparer(limits).prepare(item, File(dir, "out"), opener())
        assertEquals(OutputFormat.MCADDON, prepared.format)
        val entries = zipEntries(prepared.file)
        val manifests = entries.keys.filter { it.endsWith("manifest.json") }
        assertEquals("due pacchetti con il proprio manifest: ${entries.keys}", 2, manifests.size)
        assertTrue(manifests.all { it.contains("/") })
    }

    @Test
    fun corruptedDataIsDetectedByChecksum() {
        val archive = write7z(
            File(dir, "bad.7z"),
            linkedMapOf(
                "Pack/manifest.json" to manifest("Rovinato", "resources", "rp3"),
                "Pack/textures/a.txt" to ByteArray(2048) { (it % 251).toByte() }
            )
        )
        val bytes = archive.readBytes()
        // I dati non compressi iniziano subito dopo l'intestazione di 32 byte: se ne altera uno.
        bytes[32 + 100] = (bytes[32 + 100].toInt() xor 0x55).toByte()
        archive.writeBytes(bytes)

        val report = analyze(archive, "bad.7z")
        assertTrue(
            "un archivio alterato non deve risultare installabile: ${report.findings} ${report.items.map { it.status }}",
            report.items.none { it.status == ItemStatus.READY || it.status == ItemStatus.WARNINGS }
        )
        assertTrue(report.findings.any { it.code == FindingCode.CORRUPT_ARCHIVE })
    }

    @Test
    fun truncatedArchiveFailsCleanly() {
        val archive = write7z(
            File(dir, "cut.7z"),
            linkedMapOf("Pack/manifest.json" to manifest("Tagliato", "resources", "rp4"))
        )
        val bytes = archive.readBytes()
        archive.writeBytes(bytes.copyOf(bytes.size / 2))

        val report = analyze(archive, "cut.7z")
        assertTrue(report.items.none { it.installable })
        assertTrue(report.findings.any { it.code == FindingCode.CORRUPT_ARCHIVE })
        assertNotNull(report.findings.firstOrNull())
    }
}
