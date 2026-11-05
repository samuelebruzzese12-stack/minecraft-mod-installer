package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.ItemCategory

/** Dove serve un file secondo il modpack (campo «env» dell'indice). */
enum class Side { REQUIRED, OPTIONAL, UNSUPPORTED }

/** Un file che il modpack chiede di scaricare, già controllato dal lettore: percorso sicuro, impronta e indirizzi presenti. */
data class PackFile(
    /** Percorso relativo alla cartella di gioco del profilo («mods/sodium.jar»), con `/` e senza `..`. */
    val path: String,
    val sha1: String?,
    val sha512: String?,
    /** Dimensione dichiarata dal modpack, o -1 se non c'è. */
    val size: Long,
    val urls: List<String>,
    val client: Side,
    val server: Side
) {
    val fileName: String get() = path.substringAfterLast('/')

    /**
     * La categoria gestita dal MOD MANAGER se il file sta direttamente in `mods/`, `resourcepacks/`, `shaderpacks/` o
     * `datapacks/`; null per tutto il resto (impostazioni, script…), che si mette al suo posto ma non è un «elemento».
     */
    val category: ItemCategory? get() = categoryOf(path)

    companion object {
        /** Estensione in minuscolo del nome del file (senza punto), o "" se non ce l'ha. */
        fun extensionOf(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

        fun categoryOf(path: String): ItemCategory? {
            for (c in ItemCategory.values()) {
                val prefix = c.folder + "/"
                if (path.startsWith(prefix) && path.length > prefix.length && path.indexOf('/', prefix.length) < 0) return c
            }
            return null
        }
    }
}

/** Quanti file stanno nelle tre cartelle di «overrides» del modpack. */
data class OverrideCounts(val common: Int, val client: Int, val server: Int) {
    /** Quelli che questa app applica (comuni e solo-client); quelli per il server non servono a un'istanza di gioco. */
    val applied: Int get() = common + client
}

/** L'indice di un modpack Modrinth, letto e controllato. Non ha ancora toccato né scaricato nulla. */
class Pack(
    val name: String,
    val versionId: String?,
    val summary: String?,
    val minecraft: String?,
    val loader: ModLoader?,
    val loaderVersion: String?,
    val files: List<PackFile>,
    val overrides: OverrideCounts,
    /** Voci dell'indice scartate perché non valide (percorso pericoloso, impronta mancante…): il modpack è incompleto. */
    val problems: List<String>
)

/** Esito della lettura di un file scelto come modpack. */
sealed class PackReadResult {
    class Ok(val pack: Pack) : PackReadResult()

    /** Il file non si può usare, con il motivo da mostrare. */
    class Failed(val message: String, val detail: String? = null) : PackReadResult()
}
