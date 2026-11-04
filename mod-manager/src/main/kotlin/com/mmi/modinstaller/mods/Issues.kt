package com.mmi.modinstaller.mods

/** Gravità di un problema trovato dai controlli, dalla più lieve alla più seria. */
enum class IssueLevel {
    /** Solo informazione. */
    INFO,

    /** Non impedisce nulla, ma è bene saperlo. */
    WARNING,

    /** Probabilmente non funziona: serve una conferma esplicita dell'utente. */
    CONFIRM,

    /** Non può funzionare: l'app non lo installa. */
    BLOCK
}

enum class PlanCode {
    REPORT_ERROR, NOT_INSTALLABLE, LOADER_MISMATCH, LOADER_GUESSED, MC_MISMATCH, MC_UNKNOWN, JAVA_TOO_OLD,
    LOADER_VERSION, ENVIRONMENT, DEP_MISSING, DEP_VERSION, DEP_DISABLED, DEP_RECOMMENDED, BREAKS, BREAKS_BY_INSTALLED,
    CONFLICT, CONFLICT_BY_INSTALLED, DEPENDENT_BROKEN, UPDATE, DOWNGRADE, SAME_VERSION, REPLACES_NAME, ALREADY_INSTALLED,
    RENAMED, PACK_FORMAT, SHADER_MOD, DATAPACK_WORLD, REPACKAGED, NATIVE_LIBS, LIBRARY, OPTIFINE, QUILT_FABRIC,
    LANGUAGE_PROVIDER, FILE_MISSING, DUPLICATE_MOD
}

data class PlanIssue(
    val level: IssueLevel,
    val code: PlanCode,
    val message: String,
    val detail: String? = null,
    /** Nel controllo di un profilo: il file a cui il problema si riferisce. */
    val itemId: String? = null
)

/** Raccoglie i problemi trovati da un controllo. */
class Issues {
    private val list = ArrayList<PlanIssue>()

    /** Se impostato, è assegnato a ogni problema aggiunto (serve al controllo di un intero profilo). */
    var itemId: String? = null

    fun add(level: IssueLevel, code: PlanCode, message: String, detail: String? = null) {
        list.add(PlanIssue(level, code, message, detail, itemId))
    }

    /** C'è già un problema di questo tipo per il file corrente? */
    fun has(code: PlanCode): Boolean = list.any { it.code == code && it.itemId == itemId }

    val blocked: Boolean get() = list.any { it.level == IssueLevel.BLOCK }

    fun toList(): List<PlanIssue> = ArrayList(list)
}
