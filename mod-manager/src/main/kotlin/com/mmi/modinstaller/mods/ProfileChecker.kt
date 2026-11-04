package com.mmi.modinstaller.mods

import com.mmi.modinstaller.jar.Compat
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.ModVersion
import com.mmi.modinstaller.jar.PackFormats

/** Esito del controllo di un intero profilo. */
class ProfileCheck(val issues: List<PlanIssue>, val checkedItems: Int) {
    /** Problemi che impedirebbero l'avvio o fanno quasi certamente fallire il gioco. */
    val blocking: List<PlanIssue> get() = issues.filter { it.level == IssueLevel.BLOCK }
    val needsAttention: List<PlanIssue> get() = issues.filter { it.level == IssueLevel.CONFIRM }
    val warnings: List<PlanIssue> get() = issues.filter { it.level == IssueLevel.WARNING }
    val isClean: Boolean get() = issues.none { it.level == IssueLevel.BLOCK || it.level == IssueLevel.CONFIRM }
}

/**
 * Controlla tutti i file attivi di un profilo, insieme: caricatore, versione di Minecraft, dipendenze, conflitti e mod
 * duplicate. Non cambia nulla.
 */
object ProfileChecker {

    private val SHADER_LOADERS = setOf("iris", "oculus", "optifine", "optifabric", "optifine-wrapper")

    fun check(profile: Profile, items: List<ProfileItem>, missingFiles: Set<String> = emptySet()): ProfileCheck {
        val issues = Issues()
        val index = InstalledIndex(items)
        val enabledMods = items.filter { it.enabled && it.category == ItemCategory.MOD }
        var checked = 0

        for (item in items) {
            issues.itemId = item.id
            if (item.id in missingFiles) {
                issues.add(
                    IssueLevel.BLOCK, PlanCode.FILE_MISSING, "«${item.displayName}» non c'è più sul telefono",
                    "Il file ${item.physicalName} è stato eliminato fuori dall'app."
                )
                continue
            }
            if (!item.enabled) continue
            checked++
            when (item.category) {
                ItemCategory.MOD -> checkMod(item, profile, index, issues)
                ItemCategory.RESOURCE_PACK -> {
                    if (PackFormats.test(item.packFormat, ModVersion.parse(profile.minecraft)) == Compat.NO) {
                        issues.add(
                            IssueLevel.WARNING, PlanCode.PACK_FORMAT, "«${item.displayName}» è per un'altra versione di Minecraft",
                            "Si carica lo stesso ma Minecraft mostra un avviso."
                        )
                    }
                }
                ItemCategory.SHADER_PACK -> {
                    if (enabledMods.none { m -> m.providedIds.any { it in SHADER_LOADERS } }) {
                        issues.add(
                            IssueLevel.WARNING, PlanCode.SHADER_MOD, "«${item.displayName}» richiede una mod per gli shader",
                            "Nel profilo non c'è Iris, Oculus né OptiFine."
                        )
                    }
                }
                ItemCategory.DATA_PACK -> {}
            }
        }

        issues.itemId = null
        // Due file attivi che offrono la stessa mod: i caricatori si rifiutano di partire.
        val byId = LinkedHashMap<String, MutableList<ProfileItem>>()
        for (item in enabledMods) {
            if (item.id in missingFiles) continue
            val seen = HashSet<String>()
            for (m in item.mods) {
                if (!profile.loader.canLoadModsFor(m.loader)) continue
                if (seen.add(m.id)) byId.getOrPut(m.id) { ArrayList() }.add(item)
            }
        }
        for ((id, list) in byId) {
            if (list.size < 2) continue
            issues.itemId = list[1].id
            issues.add(
                IssueLevel.BLOCK, PlanCode.DUPLICATE_MOD, "La mod «$id» è presente due volte",
                "File: " + list.joinToString(", ") { it.fileName } + ". Il gioco non parte con due copie: disattiva o rimuovi una."
            )
        }
        issues.itemId = null
        return ProfileCheck(issues.toList(), checked)
    }

    private fun checkMod(item: ProfileItem, profile: Profile, index: InstalledIndex, issues: Issues) {
        when (item.kind) {
            ContentKind.OPTIFINE -> ModRules.optifine(profile, issues)
            ContentKind.LIBRARY -> issues.add(IssueLevel.INFO, PlanCode.LIBRARY, "«${item.displayName}» è una libreria senza descrizione di mod")
            else -> {}
        }
        if (item.mods.isEmpty()) return
        val applicable = ModRules.applicableMods(item.mods, profile, issues)
        if (applicable.isEmpty()) return
        val own = LinkedHashMap<String, String?>()
        for (m in item.mods) for (id in m.allIds) own[id] = m.version
        for (m in item.nested) for (id in m.allIds) own.putIfAbsent(id, m.version)
        ModRules.check(applicable, own, profile, index, item.id, issues, null)
        val java = profile.effectiveJava
        val needed = item.javaMajor
        if (java != null && needed != null && needed > java && !issues.has(PlanCode.JAVA_TOO_OLD)) {
            issues.add(IssueLevel.CONFIRM, PlanCode.JAVA_TOO_OLD, "«${item.displayName}» richiede Java $needed o superiore", "Il profilo usa Java $java.")
        }
    }
}
