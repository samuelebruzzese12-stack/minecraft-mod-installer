package com.mmi.modinstaller.core

/**
 * Decide quando chiedere all'utente «Minecraft ha importato il contenuto?».
 *
 * La domanda va fatta quando l'utente TORNA nell'app dopo che un invio a Minecraft è partito. Il segnale di "uscita" è la
 * pausa dell'attività (onPause), non il suo arresto (onStop): l'arresto non avviene se Minecraft si apre in una finestra
 * affiancata o a comparsa (frequente sui Samsung) e può non avvenire se si torna all'app durante una transizione lenta.
 * La pausa invece precede sempre qualunque uscita. Il ritorno è il passaggio in primo piano (onResume).
 *
 * Se la domanda non compare comunque (per esempio perché Minecraft non si è aperto), resta la rete di sicurezza:
 * toccando di nuovo «INSTALLA» l'app chiede prima l'esito dell'invio rimasto senza risposta.
 */
class ConfirmationTrigger {
    private var left = false

    /** È partito un nuovo invio: finché l'app non perde il primo piano non c'è nulla da chiedere. */
    fun sent() {
        left = false
    }

    /** L'app perde il primo piano. [awaiting] = esiste un invio a Minecraft senza risposta. */
    fun onPause(awaiting: Boolean) {
        if (awaiting) left = true
    }

    /** L'app torna in primo piano: restituisce true se va mostrata la domanda (una sola volta per ogni uscita). */
    fun onResume(awaiting: Boolean): Boolean {
        val show = awaiting && left
        left = false
        return show
    }
}
