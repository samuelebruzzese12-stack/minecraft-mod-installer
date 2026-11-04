package com.mmi.modinstaller.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quando va chiesto «Minecraft ha importato il contenuto?»: solo al ritorno nell'app dopo un invio senza risposta. */
class ConfirmationTriggerTest {

    @Test
    fun `dopo un invio, uscita e ritorno chiedono l'esito`() {
        val t = ConfirmationTrigger()
        t.sent()
        t.onPause(awaiting = true)
        assertTrue(t.onResume(awaiting = true))
    }

    @Test
    fun `se non si è mai usciti non si chiede nulla`() {
        val t = ConfirmationTrigger()
        t.sent()
        assertFalse(t.onResume(awaiting = true))
    }

    @Test
    fun `la domanda compare una sola volta per ogni uscita`() {
        val t = ConfirmationTrigger()
        t.sent()
        t.onPause(true)
        assertTrue(t.onResume(true))
        assertFalse(t.onResume(true))
        t.onPause(true)
        assertTrue(t.onResume(true))
    }

    @Test
    fun `senza invio in attesa uscire e rientrare non chiede nulla`() {
        val t = ConfirmationTrigger()
        t.onPause(awaiting = false)
        assertFalse(t.onResume(awaiting = false))
    }

    @Test
    fun `una pausa avvenuta prima dell'invio non conta`() {
        val t = ConfirmationTrigger()
        t.onPause(awaiting = true)
        t.sent()
        assertFalse(t.onResume(awaiting = true))
    }

    @Test
    fun `se la risposta arriva prima del ritorno la domanda non compare`() {
        val t = ConfirmationTrigger()
        t.sent()
        t.onPause(true)
        assertFalse(t.onResume(awaiting = false))
        // E non resta armata per il prossimo ritorno, nemmeno se nel frattempo c'è un altro invio.
        assertFalse(t.onResume(awaiting = true))
    }

    @Test
    fun `finestre affiancate - la pausa senza arresto basta`() {
        // Con Minecraft in una finestra a comparsa l'app resta visibile (niente onStop) ma passa per pausa e ripresa.
        val t = ConfirmationTrigger()
        t.sent()
        t.onPause(true)
        assertTrue(t.onResume(true))
    }

    @Test
    fun `un nuovo invio azzera l'uscita precedente`() {
        val t = ConfirmationTrigger()
        t.sent()
        t.onPause(true)
        t.sent()
        assertFalse(t.onResume(true))
    }
}
