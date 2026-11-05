package com.mmi.modinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La struttura della dashboard: le sezioni richieste, nell'ordine richiesto, ciascuna con il suo stato reale. */
class SectionTest {

    @Test
    fun elevenSectionsInTheRequestedOrder() {
        val titles = Section.values().map { it.title }
        assertEquals(
            listOf(
                "HOME", "INSTALLER BEDROCK", "JAVA EDITION", "MOD MANAGER", "MODPACK MANAGER", "WORLD MANAGER",
                "WORLD CONVERTER", "JAVA TO BEDROCK", "DOWNLOAD MANAGER", "DIAGNOSTICA", "IMPOSTAZIONI"
            ),
            titles
        )
    }

    @Test
    fun titlesAreUnique() {
        val titles = Section.values().map { it.title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun onlyTheEnginesThatExistAreMarkedReady() {
        val ready = Section.values().filter { it.availability == Availability.READY }.map { it.name }.toSet()
        assertEquals(setOf("HOME", "INSTALLER", "DOWNLOADS", "DIAGNOSTICS", "SETTINGS"), ready)
    }

    @Test
    fun javaEditionIsPartialBecauseThereIsNoRuntime() {
        assertEquals(Availability.PARTIAL, Section.JAVA.availability)
    }

    @Test
    fun modManagerIsPartialBecauseItOnlyManagesFiles() {
        // Gestisce profili e file, ma non ha Modrinth/CurseForge e non avvia il gioco: non può dirsi «Pronto».
        assertEquals(Availability.PARTIAL, Section.MODS.availability)
    }

    @Test
    fun modpackManagerIsPartialBecauseOnlyModrinthPacksAreSupported() {
        // Installa i .mrpack di Modrinth; i modpack CurseForge no (serve una chiave personale): non può dirsi «Pronto».
        assertEquals(Availability.PARTIAL, Section.MODPACKS.availability)
    }

    @Test
    fun worldManagerIsPartialBecauseItDoesNotConvertWorlds() {
        // Importa i mondi Java in un profilo e passa i Bedrock all'INSTALLER BEDROCK; non converte tra le edizioni.
        assertEquals(Availability.PARTIAL, Section.WORLDS.availability)
    }

    @Test
    fun theEnginesNotBuiltYetAreMarkedComingSoon() {
        val soon = Section.values().filter { it.availability == Availability.SOON }.map { it.name }.toSet()
        assertEquals(setOf("CONVERTER"), soon)
    }

    @Test
    fun everySectionHasASummary() {
        for (section in Section.values()) assertTrue(section.name, section.summary.isNotBlank())
    }

    @Test
    fun availabilityLabelsAreDistinctAndInItalian() {
        val labels = Availability.values().map { it.label }
        assertEquals(listOf("Pronto", "Parziale", "In arrivo"), labels)
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun theAppStartsOnTheDashboard() {
        val state = UiState()
        assertEquals(Section.HOME, state.section)
        assertNotEquals(Section.INSTALLER, state.section)
        assertEquals(Screen.HOME, state.screen)
        assertFalse(state.unconfirmed.isNotEmpty())
    }
}
