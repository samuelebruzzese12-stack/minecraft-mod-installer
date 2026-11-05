package com.mmi.modinstaller.convert

/**
 * BLOCKBRIDGE — registro dei componenti: ogni cosa trovata nella mod (registrazioni nel codice, file del JAR) ha una voce con
 * cosa faceva in Java, cosa è diventata in Bedrock, i test e le differenze. Niente sparisce senza essere registrato.
 */
enum class ComponentStatus(val label: String, val rank: Int) {
    /** Passato con lo stesso significato (dati: texture, nomi, ricette, valori letti dal codice…). */
    CONVERTED("Convertito", 0),
    /** Ricostruito con componenti o Script API di Bedrock: si comporta come l'originale nei limiti dichiarati. */
    RECONSTRUCTED("Ricostruito", 1),
    /** Funziona in Bedrock, ma con le differenze elencate. */
    APPROXIMATED("Approssimato", 2),
    /** Non ricostruito: i dati originali restano nel progetto esportato e il motivo è scritto. */
    DATA_KEPT("Dati conservati", 3),
    /** Bedrock non offre agli add-on nulla che lo possa riprodurre (il motivo è scritto). */
    NOT_RECONSTRUCTABLE("Non ricostruibile", 4)
}

class Component(
    /** Che cos'è: «oggetto», «blocco», «creatura», «ricetta», «suono», «codice», «file»… */
    val kind: String,
    /** Identificatore originale («mod:nome») o percorso del file nel JAR. */
    val originalId: String,
    /** Cosa fa in Java (classe, valori letti). */
    val originalFunction: String,
    /** Da cosa dipende (altre voci, altre mod). */
    val dependencies: List<String>,
    /** Cosa si è fatto per portarlo in Bedrock. */
    val conversion: String,
    /** Identificatore Bedrock, se esiste. */
    val bedrockId: String?,
    /** Cosa fa in Bedrock. */
    val bedrockFunction: String,
    /** Prove che lo riguardano (statiche o nel server Bedrock vero), con il loro esito quando note. */
    val tests: List<String>,
    /** Differenze rispetto all'originale. */
    val differences: List<String>,
    val status: ComponentStatus,
    /** Quanti file o voci rappresenta (per i gruppi di file). */
    val count: Int = 1
)
