package com.mmi.modinstaller.convert

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Converte le mod vere che trova nella cartella MMI_REAL_MODS (in CI: mod scaricate da Modrinth) e scrive gli add-on e un
 * rapporto in MMI_REAL_OUT. I file prodotti si controllano poi con gli schemi ufficiali di Mojang (ci/validate_addon.py).
 */
class RealModsTest {

    @Test fun convertRealMods() {
        val dir = System.getenv("MMI_REAL_MODS")?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
        assumeTrue("MMI_REAL_MODS non impostata: prova saltata", dir != null)
        val out = File(System.getenv("MMI_REAL_OUT")?.takeIf { it.isNotBlank() } ?: "build/real-mods-out").apply { mkdirs() }
        val report = StringBuilder()
        val jars = dir!!.listFiles { f -> f.name.endsWith(".jar") }!!.sortedBy { it.name }
        assumeTrue("nessun .jar in $dir", jars.isNotEmpty())
        val conv = ModConverter()
        var withContent = 0
        for (jar in jars) {
            val t0 = System.currentTimeMillis()
            conv.analyze(listOf(ConvertSource(jar, jar.name))).use { plan ->
                val r = plan.reports.single()
                report.append("== ").append(jar.name).append(" — ").append(r.title).append(" (").append(r.loader).append(")\n")
                report.append("   ").append(r.verdict).append('\n')
                report.append("   oggetti ").append(r.items.size).append(", blocchi ").append(r.blocks.size)
                    .append(", ricette ").append(r.recipesConverted).append("/").append(r.recipesTotal)
                    .append(", bottini ").append(r.lootTables).append(", minerali ").append(r.ores).append(", armature ").append(r.armorModels).append('\n')
                for ((why, n) in r.recipeSkips.entries.sortedByDescending { it.value }.take(6)) report.append("   ricette non convertite: ").append(n).append(" — ").append(why).append('\n')
                for (s in r.simplified) report.append("   semplificato: ").append(s).append('\n')
                for (s in r.estimates) report.append("   stimato: ").append(s).append('\n')
                for (s in r.notConverted) report.append("   non convertito: ").append(s).append('\n')
                // Per capire i casi nuovi: dove la mod tiene i modelli delle creature (se ne ha)
                val models = java.util.zip.ZipFile(jar).use { z ->
                    z.entries().asSequence().map { it.name }.filter { (it.contains("/geo/") || it.contains("/geckolib/")) && it.endsWith(".json") }.take(4).toList()
                }
                if (models.isNotEmpty()) report.append("   modelli trovati nel JAR (es.): ").append(models.joinToString(", ")).append('\n')
                if (r.entities.isNotEmpty()) report.append("   creature convertite: ").append(r.entities.size).append(" (es. ").append(r.entities.take(4).joinToString(", ") { it.name }).append(")\n")
                if (plan.hasContent) {
                    withContent++
                    val file = File(out, jar.name.removeSuffix(".jar") + ".mcaddon")
                    val w = conv.write(plan, r.title, file)
                    report.append("   add-on: ").append(file.name).append(", ").append(w.fileCount).append(" file, ")
                        .append(w.sizeBytes / 1024).append(" KB, in ").append(System.currentTimeMillis() - t0).append(" ms\n")
                }
            }
        }
        File(out, "rapporto.txt").writeText(report.toString())
        println(report)
        assertTrue("nessuna mod con contenuto convertibile", withContent > 0)
    }
}
