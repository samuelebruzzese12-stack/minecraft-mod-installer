package com.mmi.modinstaller.core

/**
 * Archivio centrale dell'app: sezioni con nome, ciascuna un valore JSON (mappe, liste, testi, numeri, booleani).
 *
 * Il file è uno solo e viene sempre scritto in modo atomico; ogni motore (mod, modpack, mondi, download…) tiene i
 * propri dati in una sezione e non conosce le altre. Dietro questa interfaccia l'archivio si può sostituire (ad
 * esempio con SQLite) senza toccare i motori.
 */
interface DocumentStore {

    /** Copia del valore della sezione (Map / List / String / Number / Boolean), o null se non esiste. */
    fun read(section: String): Any?

    /** Sostituisce la sezione (null la elimina) e salva. Restituisce false se il salvataggio non è riuscito. */
    fun write(section: String, value: Any?): Boolean

    /** Legge, trasforma e salva la sezione senza che altri thread possano inserirsi nel mezzo. */
    fun update(section: String, transform: (current: Any?) -> Any?): Boolean
}

/** Copia profonda di un valore JSON: chi riceve un valore dall'archivio può modificarlo senza alterare l'archivio. */
fun deepCopyJson(value: Any?): Any? = when (value) {
    is Map<*, *> -> {
        val copy = LinkedHashMap<String, Any?>()
        for ((k, v) in value) copy[k.toString()] = deepCopyJson(v)
        copy
    }
    is List<*> -> {
        val copy = ArrayList<Any?>(value.size)
        for (v in value) copy.add(deepCopyJson(v))
        copy
    }
    else -> value
}

/** Archivio solo in memoria: serve ai test dei motori e a chi non ha bisogno di persistenza. */
class MemoryDocumentStore : DocumentStore {
    private val lock = Any()
    private val sections = LinkedHashMap<String, Any?>()

    override fun read(section: String): Any? = synchronized(lock) { deepCopyJson(sections[section]) }

    override fun write(section: String, value: Any?): Boolean = synchronized(lock) {
        if (value == null) sections.remove(section) else sections[section] = deepCopyJson(value)
        true
    }

    override fun update(section: String, transform: (Any?) -> Any?): Boolean = synchronized(lock) {
        val next = transform(deepCopyJson(sections[section]))
        if (next == null) sections.remove(section) else sections[section] = deepCopyJson(next)
        true
    }
}
