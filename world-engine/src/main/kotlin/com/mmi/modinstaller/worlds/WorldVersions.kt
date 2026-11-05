package com.mmi.modinstaller.worlds

import com.mmi.modinstaller.core.McVersion

/** Confronto tra la versione con cui è stato salvato un mondo Java e quella di un profilo. */
object WorldVersions {

    /**
     * Un avviso se le versioni non coincidono, o null. Aprire un mondo con una versione di Minecraft più vecchia di quella con cui
     * è stato salvato può rovinarlo; con una più nuova il gioco lo aggiorna e non si torna indietro. Versioni non leggibili
     * (snapshot come «24w14a», mondi senza versione) non danno avvisi: non si può dire nulla di certo.
     */
    fun warning(worldVersion: String?, profileMinecraft: String?): String? {
        val w = McVersion.parse(worldVersion) ?: return null
        val p = McVersion.parse(profileMinecraft) ?: return null
        val cmp = w.compareFirst(p, 3)
        return when {
            cmp > 0 -> "Il mondo è stato salvato con Minecraft $worldVersion, più recente del profilo ($profileMinecraft): aprirlo con " +
                "una versione più vecchia può rovinarlo. Scegli un profilo con Minecraft $worldVersion o successivo."
            cmp < 0 -> "Il profilo usa Minecraft $profileMinecraft, più recente del mondo ($worldVersion): il gioco aggiornerà il mondo " +
                "quando lo apri, e non si potrà riaprire con la versione vecchia. Conserva il file originale."
            else -> null
        }
    }
}
