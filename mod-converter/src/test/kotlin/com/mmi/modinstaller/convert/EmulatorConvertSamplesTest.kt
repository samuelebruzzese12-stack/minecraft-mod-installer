package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.AnalyzeContext
import com.mmi.modinstaller.core.ContentAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Converte con il codice vero la mod di prova di `ci/make_samples.py` (Mod_Da_Convertire.jar) e una mod di solo codice
 * (Mod_Fabric_Esempio.jar): sono le attese che la prova su emulatore (ci/convert_phase.sh) controlla sullo schermo e sul file.
 * Si esegue solo con MMI_SAMPLES impostata.
 */
class EmulatorConvertSamplesTest {

    private fun samples(): File {
        val d = System.getenv("MMI_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }
        assumeTrue("MMI_SAMPLES non impostata: prova saltata", d != null)
        return d!!
    }

    @Test fun contentModBecomesAnAddon() {
        val dir = samples()
        // Il finto Minecraft della prova su emulatore è la 1.21.50: l'app converte per quella versione (senza Script API).
        val conv = ModConverter(ConvertLimits(bedrockTarget = listOf(1, 21, 50)))
        val tmp = Files.createTempDirectory("conv-emu").toFile()
        try {
            conv.analyze(listOf(ConvertSource(File(dir, "Mod_Da_Convertire.jar"), "Mod_Da_Convertire.jar"))).use { plan ->
                val r = plan.reports.single()
                assertEquals("Rubini e Pomodori", r.title)
                assertEquals(4, r.items.size)
                assertEquals(2, r.blocks.size)
                assertEquals(1, r.entities.size)
                assertEquals(3, r.recipesConverted)
                assertEquals(4, r.recipesTotal)
                assertEquals(1, r.ores)
                assertEquals(1, r.armorModels)
                assertEquals(1, r.lootTables)
                // Nel rapporto i nomi in italiano, se la mod li ha
                assertEquals(setOf("Rubino", "Spada di rubino", "Elmo di rubino", "Pomodoro"), r.items.map { it.name }.toSet())
                assertEquals(setOf("Blocco di rubino", "Minerale di rubino"), r.blocks.map { it.name }.toSet())
                assertEquals("Coccinella", r.entities.single().name)
                val out = File(tmp, "Rubini e Pomodori.mcaddon")
                val w = conv.write(plan, "Rubini e Pomodori", out)
                assertEquals(36, w.fileCount)
                ZipFile(out).use { z ->
                    for (n in listOf(
                        "Rubini e Pomodori BP/items/rubini/ruby_sword.json", "Rubini e Pomodori BP/blocks/rubini/ruby_ore.json",
                        "Rubini e Pomodori BP/recipes/rubini/ruby_sword.json", "Rubini e Pomodori BP/features/ore_ruby_feature.json",
                        "Rubini e Pomodori BP/entities/rubini/ladybug.json", "Rubini e Pomodori RP/texts/it_IT.lang",
                        "Rubini e Pomodori RP/attachables/rubini/ruby_helmet.json"
                    )) assertTrue(n, z.getEntry(n) != null)
                    val it = String(z.getInputStream(z.getEntry("Rubini e Pomodori RP/texts/it_IT.lang")).readBytes())
                    assertTrue(it.contains("item.rubini.ruby_sword=Spada di rubino"))
                }
                val report = ContentAnalyzer(AnalyzeContext(File(tmp, "work"))).analyze(out, out.name)
                assertTrue(report.hasInstallableItems)
                assertEquals("Rubini e Pomodori", report.items.single().title.substringBefore(" (").trim())
            }
            conv.analyze(listOf(ConvertSource(File(dir, "Mod_Fabric_Esempio.jar"), "Mod_Fabric_Esempio.jar"))).use { plan ->
                assertFalse(plan.hasContent)
                assertEquals("Niente da convertire: è una mod di solo codice (o una libreria)", plan.reports.single().verdict)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
