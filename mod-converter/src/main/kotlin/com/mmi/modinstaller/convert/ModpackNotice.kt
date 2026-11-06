package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Un modpack (CurseForge o Modrinth) dato al convertitore al posto di una mod: dentro ci sono solo l'elenco delle mod e le
 * impostazioni, non le mod. Lo si dice chiaramente, con l'elenco, invece di «niente da convertire».
 */
internal object ModpackNotice {

    class Pack(val kind: String, val name: String, val minecraft: String?, val loader: String?, val mods: List<String>, val urls: List<String>)

    fun read(file: File): Pack? = try {
        ZipFile(file).use { z ->
            val cf = z.getEntry("manifest.json")?.let { e -> if (e.size in 1..4_000_000) z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } else null }
            val mr = z.getEntry("modrinth.index.json")?.let { e -> if (e.size in 1..16_000_000) z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } else null }
            when {
                cf != null -> {
                    val m = Json.parse(cf).asJsonObject() ?: return null
                    if (m["manifestType"] != "minecraftModpack") return null
                    val mc = m["minecraft"].asJsonObject()
                    val loader = mc?.get("modLoaders").asJsonList()?.firstOrNull().asJsonObject()?.get("id") as? String
                    val list = z.getEntry("modlist.html")?.let { e -> if (e.size in 1..4_000_000) z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } else null }
                    val links = list?.let { Regex("<a href=\"([^\"]+)\">([^<]+)</a>").findAll(it).map { r -> r.groupValues[1] to r.groupValues[2].replace("&amp;", "&") }.toList() }.orEmpty()
                    val count = m["files"].asJsonList()?.size ?: links.size
                    Pack(
                        "CurseForge", m["name"] as? String ?: file.name, mc?.get("version") as? String, loader,
                        if (links.isNotEmpty()) links.map { it.second } else List(count) { "progetto CurseForge" },
                        links.map { it.first }
                    )
                }
                mr != null -> {
                    val m = Json.parse(mr).asJsonObject() ?: return null
                    val files = m["files"].asJsonList().orEmpty().mapNotNull { it.asJsonObject()?.get("path") as? String }.filter { it.startsWith("mods/") }
                    val deps = m["dependencies"].asJsonObject().orEmpty()
                    Pack(
                        "Modrinth", m["name"] as? String ?: file.name, deps["minecraft"] as? String,
                        listOf("forge", "neoforge", "fabric-loader", "quilt-loader").firstOrNull { it in deps }?.let { "$it ${deps[it]}" },
                        files.map { it.removePrefix("mods/") }, emptyList()
                    )
                }
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    fun report(label: String, p: Pack): ModReport {
        val what = "È un modpack di ${p.kind} («${p.name}»${p.minecraft?.let { ", Minecraft $it" } ?: ""}${p.loader?.let { ", $it" } ?: ""}): " +
            "contiene solo l'elenco di ${p.mods.size} mod e le impostazioni, non le mod. Scarica le mod (per esempio da «CERCA ONLINE», " +
            "che usa Modrinth) e convertile qui: armi di TACZ e pacchetti di armi si convertono."
        val notes = ArrayList<String>()
        notes.add("Mod nell'elenco: " + p.mods.take(60).joinToString(", ") + if (p.mods.size > 60) "…" else "")
        if (p.kind == "CurseForge") notes.add("I file di CurseForge non si scaricano dall'app (servono una chiave personale e il permesso dell'autore)")
        return ModReport(label, p.name, p.loader, p.minecraft, emptyList(), emptyList(), 0, 0, emptyMap(), 0, 0, 0, emptyList(), emptyList(), emptyList(), notes, what)
    }
}
