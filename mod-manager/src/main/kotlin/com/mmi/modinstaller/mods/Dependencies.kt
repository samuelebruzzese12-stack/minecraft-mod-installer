package com.mmi.modinstaller.mods

import com.mmi.modinstaller.jar.Compat
import com.mmi.modinstaller.jar.ModDependency
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.jar.ModVersion

/** Chi, tra i file già nel profilo e attivi, mette a disposizione un identificatore di mod. */
class InstalledIndex(items: List<ProfileItem>) {

    class Provider(val item: ProfileItem, val version: String?)

    private val active = HashMap<String, MutableList<Provider>>()
    private val inactive = HashMap<String, MutableList<Provider>>()

    init {
        for (item in items) {
            val target = if (item.enabled) active else inactive
            val seen = HashSet<String>()
            for (m in item.mods + item.nested) {
                for (id in m.allIds) {
                    if (seen.add(id + "|" + m.version)) target.getOrPut(id) { ArrayList() }.add(Provider(item, m.version))
                }
            }
        }
    }

    fun providers(id: String): List<Provider> = active[id].orEmpty()

    fun disabledProviders(id: String): List<Provider> = inactive[id].orEmpty()
}

enum class DepState {
    /** C'è e la versione va bene. */
    SATISFIED,

    /** C'è, ma la sua versione non si può confrontare: non si dà un giudizio. */
    SATISFIED_UNKNOWN,

    /** C'è, ma la versione non rispetta il vincolo. */
    WRONG_VERSION,

    /** Non c'è tra i file attivi, ma c'è disattivata. */
    DISABLED,

    /** Non c'è. */
    MISSING
}

class DepResult(val state: DepState, val foundVersion: String? = null, val provider: ProfileItem? = null)

object DependencyEvaluator {

    /** Identificatori di Minecraft, Java e del caricatore: il loro «fornitore» è il profilo stesso. */
    fun platformCompat(dep: ModDependency, profile: Profile): Compat = when {
        dep.id == "minecraft" -> dep.test(profile.minecraft)
        dep.id == "java" -> profile.effectiveJava?.let { dep.test(it.toString()) } ?: Compat.UNKNOWN
        isLoaderId(dep.id, profile.loader) -> profile.loaderVersion?.let { dep.test(it) } ?: Compat.UNKNOWN
        else -> Compat.UNKNOWN
    }

    fun isLoaderId(id: String, loader: ModLoader): Boolean = when (loader) {
        ModLoader.FABRIC -> id == "fabricloader" || id == "fabric-loader"
        ModLoader.QUILT -> id == "quilt_loader" || id == "quilt-loader"
        ModLoader.FORGE -> id == "forge"
        ModLoader.NEOFORGE -> id == "neoforge"
    }

    /**
     * Cerca chi soddisfa [dep] tra le mod messe a disposizione dal file stesso ([own]: identificatore → versione) e tra i file
     * attivi del profilo ([index]), ignorando quello che sta per essere sostituito ([skipItemId]).
     */
    fun evaluate(dep: ModDependency, index: InstalledIndex, own: Map<String, String?>, skipItemId: String? = null): DepResult {
        var wrong: DepResult? = null
        if (own.containsKey(dep.id)) {
            val v = own[dep.id]
            when (dep.test(v)) {
                Compat.YES -> return DepResult(DepState.SATISFIED, v)
                Compat.UNKNOWN -> return DepResult(DepState.SATISFIED_UNKNOWN, v)
                Compat.NO -> wrong = DepResult(DepState.WRONG_VERSION, v)
            }
        }
        var unknown: DepResult? = null
        for (p in index.providers(dep.id)) {
            if (p.item.id == skipItemId) continue
            when (dep.test(p.version)) {
                Compat.YES -> return DepResult(DepState.SATISFIED, p.version, p.item)
                Compat.UNKNOWN -> if (unknown == null) unknown = DepResult(DepState.SATISFIED_UNKNOWN, p.version, p.item)
                Compat.NO -> if (wrong == null) wrong = DepResult(DepState.WRONG_VERSION, p.version, p.item)
            }
        }
        if (unknown != null) return unknown
        if (wrong != null) return wrong
        val off = index.disabledProviders(dep.id).firstOrNull { it.item.id != skipItemId }
        if (off != null) return DepResult(DepState.DISABLED, off.version, off.item)
        return DepResult(DepState.MISSING)
    }

    /** Confronto di versioni dei file: positivo se [a] è più recente di [b]; null se non si possono confrontare. */
    fun compareVersions(a: String?, b: String?): Int? {
        val va = ModVersion.parse(a)?.takeIf { it.comparable } ?: return null
        val vb = ModVersion.parse(b)?.takeIf { it.comparable } ?: return null
        return va.compareTo(vb)
    }
}
