package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.JarAnalyzer
import com.mmi.modinstaller.jar.JarReport
import com.mmi.modinstaller.jar.ModLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Controlla i file Java che crea `ci/make_samples.py` per la prova su emulatore con l'analizzatore e il gestore dei
 * profili VERI. Lo script dell'emulatore si aspetta certi esiti (pronto, bloccato, serve conferma…): qui si verifica che
 * quelle attese siano giuste, così se falliscono sull'emulatore la causa è l'interfaccia e non i file di prova.
 *
 * Si esegue solo se la variabile d'ambiente MMI_SAMPLES indica la cartella con i file (altrimenti è saltato):
 *   python3 ci/make_samples.py /tmp/samples && MMI_SAMPLES=/tmp/samples gradle :mod-manager:test
 */
class EmulatorSamplesTest {

    private var dir: File? = null
    private lateinit var base: File
    private lateinit var mm: ModManager
    private lateinit var pid: String
    private lateinit var profile: Profile

    @Before fun setUp() {
        dir = System.getenv("MMI_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }
        assumeTrue("MMI_SAMPLES non impostata: prova saltata", dir != null)
        base = Fx.tempDir()
        var n = 0
        mm = ModManager(ProfileRepository(MemoryDocumentStore()), File(base, "profiles"), newId = { "id" + (++n) })
        // Lo stesso profilo che lo script crea dal modulo «Nuovo profilo» con i valori proposti dal primo file.
        val created = mm.createProfile(ProfileDraft("Fabric 1.20.1", "1.20.1", ModLoader.FABRIC, null)) as Outcome.Ok
        profile = created.value
        pid = profile.id
    }

    @After fun tearDown() {
        if (::base.isInitialized) base.deleteRecursively()
    }

    private fun sample(name: String): File = File(dir!!, name).also { assertTrue("manca il file di prova $name", it.isFile) }

    private fun analyze(name: String): JarReport = JarAnalyzer(tempDir = base).analyze(sample(name), name)

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun install(name: String, confirmed: Boolean = false): InstallResult {
        val report = analyze(name)
        return mm.install(pid, sample(name), report, confirmed, origin = name, sourceLabel = "file")
    }

    // ------------------------------------------------------------------ le attese dello script, una per una

    @Test fun fabricModIsReadyAndProposesTheProfileTheScriptTypes() {
        val r = analyze("Mod_Fabric_Esempio.jar")
        assertEquals(ContentKind.MOD, r.kind)
        assertEquals(1, r.mods.size)
        assertEquals("esempiofabric", r.mods[0].id)
        assertEquals("Esempio Fabric", r.mods[0].displayName)
        val suggestion = ProfileSuggester.fromReport(r)
        assertEquals(ModLoader.FABRIC, suggestion.loader)
        assertEquals("1.20.1", suggestion.minecraft)
        val plan = mm.plan(pid, r)!!
        assertEquals(plan.issues.toString(), PlanDecision.READY, plan.decision)
        assertEquals(ItemCategory.MOD, plan.category)
        assertTrue(plan.missing.isEmpty())
    }

    @Test fun forgeModIsBlockedInAFabricProfileAndOffersAnotherProfile() {
        val r = analyze("Mod_Forge_Esempio.jar")
        assertEquals(ContentKind.MOD, r.kind)
        assertEquals(ModLoader.FORGE, r.mods[0].loader)
        val plan = mm.plan(pid, r)!!
        assertEquals(PlanDecision.BLOCKED, plan.decision)
        assertTrue(plan.has(PlanCode.LOADER_MISMATCH))
        val result = install("Mod_Forge_Esempio.jar", confirmed = true)
        assertTrue("anche con la conferma un file bloccato non si installa: $result", result is InstallResult.NotAllowed)
        assertTrue(mm.items(pid).isEmpty())
    }

    @Test fun missingLibraryNeedsConfirmationAndInstallingItClearsTheProfileCheck() {
        val r = analyze("Mod_Con_Dipendenza.jar")
        val plan = mm.plan(pid, r)!!
        assertEquals(plan.issues.toString(), PlanDecision.NEEDS_CONFIRMATION, plan.decision)
        assertTrue(plan.has(PlanCode.DEP_MISSING))
        assertEquals(listOf("libesempio"), plan.missing.map { it.id })

        // Senza conferma non succede nulla; con la conferma si installa.
        assertTrue(install("Mod_Con_Dipendenza.jar", confirmed = false) is InstallResult.NotAllowed)
        assertTrue(mm.items(pid).isEmpty())
        assertTrue(install("Mod_Con_Dipendenza.jar", confirmed = true) is InstallResult.Installed)

        val before = mm.check(pid)!!.first
        assertFalse("il controllo del profilo deve segnalare la libreria mancante", before.isClean)
        assertTrue(before.issues.any { it.code == PlanCode.DEP_MISSING })

        val lib = mm.plan(pid, analyze("Libreria_Esempio.jar"))!!
        assertEquals(lib.issues.toString(), PlanDecision.READY, lib.decision)
        assertTrue(install("Libreria_Esempio.jar") is InstallResult.Installed)
        val after = mm.check(pid)!!.first
        assertTrue("dopo la libreria il profilo deve essere pulito: ${after.issues}", after.isClean)
        assertEquals(2, after.checkedItems)
    }

    @Test fun bundleListsThreeJarsAndEachOneIsAReadyMod() {
        val r = analyze("Raccolta_Mod.zip")
        assertEquals(ContentKind.MOD_BUNDLE, r.kind)
        assertEquals(listOf("mods/Uno.jar", "mods/Due.jar", "mods/Tre.jar").sorted(), r.bundleJars.sorted())
        val analyzer = JarAnalyzer(tempDir = base)
        for (path in r.bundleJars) {
            val target = File(base, "bundle-" + path.substringAfterLast('/'))
            assertTrue(JarAnalyzer.extractEntry(sample("Raccolta_Mod.zip"), path, target, 10_000_000L))
            val inner = analyzer.analyze(target, path.substringAfterLast('/'))
            assertEquals(path, ContentKind.MOD, inner.kind)
            assertEquals(path, PlanDecision.READY, mm.plan(pid, inner)!!.decision)
        }
    }

    @Test fun javaResourcePackIsReadyInTheResourcePackTab() {
        val r = analyze("Texture_Java.zip")
        assertEquals(ContentKind.RESOURCE_PACK, r.kind)
        val plan = mm.plan(pid, r)!!
        assertEquals(plan.issues.toString(), PlanDecision.READY, plan.decision)
        assertEquals(ItemCategory.RESOURCE_PACK, plan.category)
        val result = install("Texture_Java.zip")
        assertTrue(result.toString(), result is InstallResult.Installed)
        assertEquals(1, mm.items(pid).count { it.category == ItemCategory.RESOURCE_PACK })
    }

    @Test fun catalogSamplesInstallInDependencyOrderAndLeaveACleanProfile() {
        // La mod del catalogo richiede la sua libreria: da sola serve conferma, con la libreria nel profilo è pronta.
        val mod = mm.plan(pid, analyze("Mod_Catalogo.jar"))!!
        assertEquals(mod.issues.toString(), PlanDecision.NEEDS_CONFIRMATION, mod.decision)
        assertEquals(listOf("libcatalogo"), mod.missing.map { it.id })
        val lib = mm.plan(pid, analyze("Libreria_Catalogo.jar"))!!
        assertEquals(lib.issues.toString(), PlanDecision.READY, lib.decision)
        assertTrue(install("Libreria_Catalogo.jar") is InstallResult.Installed)
        val modAfter = mm.plan(pid, analyze("Mod_Catalogo.jar"))!!
        assertEquals(modAfter.issues.toString(), PlanDecision.READY, modAfter.decision)
        assertTrue(install("Mod_Catalogo.jar") is InstallResult.Installed)
        assertTrue(mm.check(pid)!!.first.isClean)

        val texture = mm.plan(pid, analyze("Texture_Catalogo.zip"))!!
        assertEquals(texture.issues.toString(), PlanDecision.READY, texture.decision)
        assertEquals(ItemCategory.RESOURCE_PACK, texture.category)
        assertTrue(install("Texture_Catalogo.zip") is InstallResult.Installed)
    }

    @Test fun downloadedModIsReady() {
        val plan = mm.plan(pid, analyze("Mod_Scaricata.jar"))!!
        assertEquals(plan.issues.toString(), PlanDecision.READY, plan.decision)
    }

    // ------------------------------------------------------------------ il ciclo completo che lo script ripete sul telefono

    @Test fun installToggleUndoRemoveAndExportKeepTheFilesByteForByte() {
        val file = sample("Mod_Fabric_Esempio.jar")
        val installed = install("Mod_Fabric_Esempio.jar") as InstallResult.Installed
        val item = installed.item
        val modsDir = File(base, "profiles/$pid/mods")
        // Il file nel profilo è identico a quello scelto dall'utente, byte per byte.
        val onDisk = File(modsDir, item.physicalName)
        assertTrue(onDisk.isFile)
        assertEquals(sha256(file), sha256(onDisk))
        assertEquals(sha256(file), item.sha256)

        // Disattivare = aggiungere «.disabled» al nome, e basta.
        assertTrue(mm.setEnabled(pid, item.id, false) is Outcome.Ok)
        assertEquals(listOf(item.fileName + ".disabled"), modsDir.list()!!.filter { !it.startsWith(".") })
        // Annullare riporta il file com'era.
        assertTrue(mm.undo(pid) is Outcome.Ok)
        assertEquals(listOf(item.fileName), modsDir.list()!!.filter { !it.startsWith(".") })
        assertEquals(sha256(file), sha256(File(modsDir, item.fileName)))

        // L'esportazione contiene solo i file attivi, con le cartelle di Minecraft.
        val zip = File(base, "export.zip")
        val summary = zip.outputStream().use { mm.exportZip(pid, it) } as Outcome.Ok
        assertEquals(1, summary.value.files)
        ZipFile(zip).use { z ->
            val entry = z.getEntry("mods/" + item.fileName)
            assertNotNull("manca mods/${item.fileName} nello ZIP: ${z.entries().toList().map { it.name }}", entry)
            assertEquals(sha256(file), MessageDigest.getInstance("SHA-256").digest(z.getInputStream(entry).readBytes()).joinToString("") { "%02x".format(it) })
        }

        // Rimuovere mette il file da parte e annullare lo rimette.
        assertTrue(mm.remove(pid, item.id) is Outcome.Ok)
        assertTrue(modsDir.list()!!.none { it.endsWith(".jar") || it.endsWith(".disabled") })
        assertTrue(mm.undo(pid) is Outcome.Ok)
        assertEquals(sha256(file), sha256(File(modsDir, item.fileName)))
    }
}
