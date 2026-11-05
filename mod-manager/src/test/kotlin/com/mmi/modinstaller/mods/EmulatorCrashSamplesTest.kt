package com.mmi.modinstaller.mods

import com.mmi.modinstaller.jar.JarAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Analizza con il codice vero i testi di crash di `ci/make_samples.py` (Crash_Fabric.txt, Log_Memoria.log.gz), così le attese
 * della prova su emulatore (DIAGNOSTICA, parte «mods») sono quelle che l'app deve davvero mostrare. Le mod del profilo sono lette
 * dai .jar di prova con l'analizzatore vero. Si esegue solo con MMI_SAMPLES impostata.
 */
class EmulatorCrashSamplesTest {

    private var dir: File? = null

    @Before fun setUp() {
        dir = System.getenv("MMI_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }
        assumeTrue("MMI_SAMPLES non impostata: prova saltata", dir != null)
    }

    private fun read(name: String): CrashText.Result = File(dir, name).inputStream().use { CrashText.read(it)!! }

    @Test fun fabricCrashReport() {
        val r = read("Crash_Fabric.txt")
        val a = CrashAnalyzer.analyze(r.text)
        assertTrue(a.isCrashReport)
        assertEquals("1.20.1", a.minecraft)
        assertEquals("Fabric", a.loader)
        assertEquals(
            listOf(
                "«Con Dipendenza» (condipendenza) vuole «Libreria Esempio» version 3.0.0 or later, ma c'è la versione 2.1.0.",
                "«Esempio Fabric» (esempiofabric) ha bisogno di «sodium» (any version), che non c'è."
            ),
            a.findings.map { it.text }.sorted()
        )
        assertEquals(listOf("Esempio Fabric (esempiofabric)"), a.suspectedMods)

        // Le mod di prova che la fase MOD MANAGER mette nel profilo Fabric: l'analisi le deve ritrovare tutte e tre.
        val profile = Fx.profile()
        val analyzer = JarAnalyzer(tempDir = Fx.tempDir())
        val items = listOf("Mod_Fabric_Esempio.jar", "Mod_Con_Dipendenza.jar", "Libreria_Esempio.jar", "Mod_Scaricata.jar").map { n ->
            val report = analyzer.analyze(File(dir, n), n)
            Fx.item(n, mods = report.mods, fileName = n)
        }
        val found = CrashMatcher.find(a, listOf(profile to items)).map { it.modId + "=" + it.item.fileName }.sorted()
        assertEquals(listOf("condipendenza=Mod_Con_Dipendenza.jar", "esempiofabric=Mod_Fabric_Esempio.jar", "libesempio=Libreria_Esempio.jar"), found)
    }

    @Test fun compressedLogOutOfMemory() {
        val r = read("Log_Memoria.log.gz")
        assertTrue(r.compressed)
        val a = CrashAnalyzer.analyze(r.text)
        assertEquals(listOf(CrashCause.OUT_OF_MEMORY), a.findings.map { it.cause })
        assertEquals("Fabric", a.loader)
        assertTrue(CrashMatcher.ids(a).isEmpty())
    }
}
