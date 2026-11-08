package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.jar.ModLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Un modpack CurseForge come quelli esportati dall'app di CurseForge: manifest.json, modlist.html e la cartella overrides. */
class CurseForgePackTest {

    private val manifest = """
        {"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.4.28","primary":true}]},"manifestType":"minecraftModpack",
         "manifestVersion":1,"name":"TACZ X SBW More Tanks - More Guns","version":"V0.1-1","author":"",
         "files":[{"projectID":1028108,"fileID":9037989,"required":true},{"projectID":1218165,"fileID":8104849,"required":true},
                  {"projectID":388172,"fileID":8285794,"required":true}],"overrides":"overrides"}
    """.trimIndent().toByteArray()

    private val modlist = """
        <ul><li><a href="https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero">[TaCZ] Timeless and Classics Zero Guns (by nekocrane)</a></li>
        <li><a href="https://www.curseforge.com/minecraft/mc-mods/superb-warfare">[SBW] Superb Warfare (by Mercurows)</a></li>
        <li><a href="https://www.curseforge.com/minecraft/mc-mods/geckolib">GeckoLib (by Gecko)</a></li></ul>
    """.trimIndent().toByteArray()

    private fun archive(): File {
        val f = Files.createTempFile("cf", ".zip").toFile()
        f.writeBytes(Fx.zip(linkedMapOf("manifest.json" to manifest, "modlist.html" to modlist, "overrides/config/tacz-common.toml" to "a=1".toByteArray())))
        return f
    }

    @Test fun readsManifestAndModList() {
        val f = archive()
        assertTrue(CurseForgePackReader.looksLike(f))
        assertFalse(ModrinthPackReader.read(f) is PackReadResult.Ok)
        val r = CurseForgePackReader.read(f) as CurseForgePackReader.Result.Ok
        val p = r.pack
        assertEquals("TACZ X SBW More Tanks - More Guns", p.name)
        assertEquals("1.20.1", p.minecraft)
        assertEquals(ModLoader.FORGE, p.loader)
        assertEquals("47.4.28", p.loaderVersion)
        assertEquals(3, p.mods.size)
        assertEquals("timeless-and-classics-zero", p.mods[0].slug)
        assertEquals("Timeless and Classics Zero Guns", p.mods[0].searchName)
        assertEquals("nekocrane", p.mods[0].author)
        assertEquals(1, p.overrides.common)
    }

    @Test fun resolvesThroughAnotherServiceAndNamesWhatIsMissing() {
        val cf = (CurseForgePackReader.read(archive()) as CurseForgePackReader.Result.Ok).pack
        val finder = object : ModFinder {
            override fun find(mod: CurseForgeMod, minecraft: String?, loader: ModLoader?): ModFinder.Found? = when (mod.slug) {
                "timeless-and-classics-zero" -> ModFinder.Found("TaCZ", "tacz-1.20.1-1.1.7.jar", "https://cdn.modrinth.com/x/tacz.jar", 1000, "a".repeat(40), null)
                "geckolib" -> ModFinder.Found("GeckoLib", "geckolib.jar", "http://insecure/geckolib.jar", 10, "b".repeat(40), null)
                else -> null
            }
        }
        val seen = ArrayList<String>()
        val pack = CurseForgeResolver.resolve(cf, finder) { _, _, n -> seen.add(n) }
        assertEquals(1, pack.files.size)
        assertEquals("mods/tacz-1.20.1-1.1.7.jar", pack.files[0].path)
        assertEquals(listOf("https://cdn.modrinth.com/x/tacz.jar"), pack.files[0].urls)
        assertTrue(pack.problems.any { it.startsWith("[SBW] Superb Warfare (by Mercurows): non è su Modrinth per Minecraft 1.20.1 con Forge") })
        assertTrue(pack.problems.any { it.contains("GeckoLib") && it.contains("non sicuro") })
        assertTrue(seen.contains("[SBW] Superb Warfare (by Mercurows)"))
        assertEquals(ModLoader.FORGE, pack.loader)
        assertNull(pack.files.firstOrNull { it.path.contains("geckolib") })
    }

    @Test fun plainZipIsNotACurseForgePack() {
        val f = Files.createTempFile("x", ".zip").toFile()
        f.writeBytes(Fx.zip(linkedMapOf("manifest.json" to """{"format_version":2,"header":{}}""".toByteArray())))
        assertFalse(CurseForgePackReader.looksLike(f))
    }
}
