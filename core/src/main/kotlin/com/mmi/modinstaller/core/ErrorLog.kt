package com.mmi.modinstaller.core

data class ErrorRecord(val at: Long, val where: String, val message: String, val detail: String?)

/**
 * Registro degli errori dell'app (ultimi [MAX]), nell'archivio centrale. Serve alla Diagnostica: l'utente può vedere
 * cosa è andato storto e condividere il rapporto, invece di dover descrivere a voce un errore già sparito.
 */
class ErrorLog(
    private val store: DocumentStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** [at] = quando è avvenuto, se è diverso da adesso (es. un arresto avvenuto prima del riavvio dell'app). */
    fun record(where: String, message: String, detail: String? = null, at: Long? = null) {
        val entry = linkedMapOf<String, Any?>(
            "at" to (at ?: clock()),
            "where" to where.take(80),
            "message" to message.take(500),
            "detail" to detail?.take(4000)
        )
        store.update(SECTION) { current ->
            val list = ArrayList<Any?>()
            list.add(entry)
            for (old in current.asJsonList().orEmpty()) {
                if (list.size >= MAX) break
                list.add(old)
            }
            list
        }
    }

    /** Dal più recente. */
    fun recent(limit: Int = MAX): List<ErrorRecord> {
        val result = ArrayList<ErrorRecord>()
        for (item in store.read(SECTION).asJsonList().orEmpty()) {
            val o = item.asJsonObject() ?: continue
            result.add(ErrorRecord(o.long("at") ?: 0L, o.str("where") ?: "", o.str("message") ?: "", o.str("detail")))
            if (result.size >= limit) break
        }
        return result
    }

    fun count(): Int = store.read(SECTION).asJsonList()?.size ?: 0

    fun clear() {
        store.write(SECTION, null)
    }

    companion object {
        const val SECTION = "errors"
        const val MAX = 100
    }
}

/**
 * Come un arresto inatteso dell'app viene scritto su disco un attimo prima che il processo muoia e riletto al
 * successivo avvio per finire nel registro degli errori. Formato: millisecondi, nome del thread, poi la traccia.
 */
object CrashReport {

    fun encode(at: Long, threadName: String, stackTrace: String): String =
        at.toString() + "\n" + threadName.replace('\n', ' ').replace('\r', ' ') + "\n" + stackTrace

    /** Restituisce null se il testo non è un rapporto valido (file troncato o estraneo). */
    fun decode(text: String): ErrorRecord? {
        val parts = text.split("\n", limit = 3)
        if (parts.size < 3) return null
        val at = parts[0].trim().toLongOrNull() ?: return null
        val stack = parts[2].trim()
        val first = stack.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return ErrorRecord(at, "Arresto inatteso dell'app", first, "Thread: " + parts[1].trim() + "\n" + stack)
    }
}
