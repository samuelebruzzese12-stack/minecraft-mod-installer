package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.Severity
import com.mmi.modinstaller.jar.Compat
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.JarReport
import com.mmi.modinstaller.jar.ModMetadata
import com.mmi.modinstaller.jar.ModVersion
import com.mmi.modinstaller.jar.PackFormats
import com.mmi.modinstaller.jar.RangeSyntax

enum class PlanDecision { READY, NEEDS_CONFIRMATION, BLOCKED, ALREADY_INSTALLED }

/** Una mod richiesta che non c'è tra i file del profilo (o c'è in una versione che non va bene). */
data class MissingDependency(
    val id: String,
    val range: String?,
    val syntax: RangeSyntax,
    val requiredBy: String
)

/** Cosa succederebbe installando un file in un profilo. Non cambia nulla sul disco. */
class InstallPlan(
    val decision: PlanDecision,
    val category: ItemCategory?,
    val issues: List<PlanIssue>,
    val missing: List<MissingDependency>,
    /** File già presente che verrebbe messo da parte (aggiornamento, declassamento o stesso nome). */
    val replaces: ProfileItem?,
    /** File identico già installato. */
    val duplicateOf: ProfileItem?,
    /** Nome con cui il file verrebbe salvato nel profilo. */
    val targetName: String?,
    /** Cartella da togliere dal pacchetto prima di salvarlo (pacchetti avvolti). */
    val repackageWrapper: String?,
    /** Le descrizioni del file che valgono per questo profilo. */
    val applicableMods: List<ModMetadata>
) {
    val canInstall: Boolean get() = decision == PlanDecision.READY || decision == PlanDecision.NEEDS_CONFIRMATION
    val needsConfirmation: Boolean get() = decision == PlanDecision.NEEDS_CONFIRMATION
    val blockingReasons: List<PlanIssue> get() = issues.filter { it.level == IssueLevel.BLOCK }
    val confirmReasons: List<PlanIssue> get() = issues.filter { it.level == IssueLevel.CONFIRM }
    fun has(code: PlanCode): Boolean = issues.any { it.code == code }
}

/**
 * Decide se e come un file può entrare in un profilo, prima di toccare qualsiasi file: caricatore, versione di Minecraft e di
 * Java, dipendenze mancanti, conflitti, aggiornamenti e doppioni. «Blocca» ciò che non può funzionare (caricatore sbagliato,
 * incompatibilità dichiarata), chiede conferma per ciò che probabilmente non funziona, e informa del resto.
 */
object InstallPlanner {

    fun plan(report: JarReport, profile: Profile, installed: List<ProfileItem>): InstallPlan {
        val issues = Issues()
        for (f in report.findings) {
            if (f.severity == Severity.ERROR) issues.add(IssueLevel.BLOCK, PlanCode.REPORT_ERROR, f.message, f.detail)
        }
        val category = ItemCategory.forKind(report.kind)
        if (category == null) {
            if (!(report.kind == ContentKind.UNKNOWN && issues.blocked)) {
                issues.add(IssueLevel.BLOCK, PlanCode.NOT_INSTALLABLE, notInstallableText(report.kind))
            }
            return blocked(issues, null)
        }
        if (issues.blocked) return blocked(issues, category)

        val sameFile = if (report.sha256.isEmpty()) null else installed.firstOrNull { it.category == category && it.hasSameContentAs(report.sha256) }
        if (sameFile != null) {
            val note = if (sameFile.enabled) "" else " (ma è disattivato)"
            issues.add(IssueLevel.INFO, PlanCode.ALREADY_INSTALLED, "È già installato come «${sameFile.displayName}»$note")
            return InstallPlan(PlanDecision.ALREADY_INSTALLED, category, issues.toList(), emptyList(), null, sameFile, null, null, emptyList())
        }

        val missing = ArrayList<MissingDependency>()
        var applicable: List<ModMetadata> = emptyList()
        var replaces: ProfileItem? = null
        var repackage: String? = null

        when (category) {
            ItemCategory.MOD -> {
                when (report.kind) {
                    ContentKind.LIBRARY -> issues.add(
                        IssueLevel.CONFIRM, PlanCode.LIBRARY, "Non dice di che mod fa parte",
                        "Il file contiene codice Java ma nessuna descrizione di mod: installalo solo se sai cosa è."
                    )
                    ContentKind.OPTIFINE -> ModRules.optifine(profile, issues)
                    else -> {}
                }
                if (report.kind == ContentKind.MOD) {
                    applicable = ModRules.applicableMods(report.mods, profile, issues)
                    if (applicable.isNotEmpty()) {
                        replaces = replacement(profile, installed, applicable, issues)
                        val others = installed.filter { it.id != replaces?.id }
                        val provided = providedIds(report, applicable)
                        ModRules.check(applicable, provided, profile, InstalledIndex(others), null, issues, missing)
                        reverseConflicts(provided, others, issues)
                        if (replaces != null) dependentsOfReplaced(provided, replaces, others, issues)
                    }
                }
                if (report.nativeLibraries.isNotEmpty()) {
                    issues.add(
                        IssueLevel.WARNING, PlanCode.NATIVE_LIBS, "Contiene librerie native",
                        "Su Android possono non funzionare."
                    )
                }
                val java = profile.effectiveJava
                val needed = report.javaMajor
                if (java != null && needed != null && needed > java && !issues.has(PlanCode.JAVA_TOO_OLD)) {
                    issues.add(
                        IssueLevel.CONFIRM, PlanCode.JAVA_TOO_OLD, "Richiede Java $needed o superiore",
                        "Il profilo usa Java $java."
                    )
                }
            }
            ItemCategory.RESOURCE_PACK, ItemCategory.DATA_PACK, ItemCategory.SHADER_PACK -> {
                val pack = report.pack
                repackage = pack?.wrapperFolder
                if (repackage != null) {
                    issues.add(
                        IssueLevel.INFO, PlanCode.REPACKAGED, "Il pacchetto verrà risistemato",
                        "Il contenuto era dentro la cartella «$repackage»: l'app crea una copia con la struttura giusta."
                    )
                }
                if (category == ItemCategory.RESOURCE_PACK) {
                    val c = PackFormats.test(pack?.packFormat, ModVersion.parse(profile.minecraft))
                    if (c == Compat.NO) {
                        issues.add(
                            IssueLevel.WARNING, PlanCode.PACK_FORMAT, "Fatto per un'altra versione di Minecraft",
                            "È per Minecraft ${PackFormats.describe(pack?.packFormat)}, il profilo usa ${profile.minecraft}. " +
                                "Si carica lo stesso ma Minecraft mostra un avviso e qualche texture può mancare."
                        )
                    }
                }
                if (category == ItemCategory.SHADER_PACK) {
                    if (installed.none { it.enabled && it.category == ItemCategory.MOD && it.providedIds.any { id -> id in SHADER_LOADERS } }) {
                        issues.add(
                            IssueLevel.WARNING, PlanCode.SHADER_MOD, "Serve una mod per usare gli shader",
                            "Nel profilo non c'è Iris, Oculus né OptiFine: senza non succede nulla."
                        )
                    }
                }
                if (category == ItemCategory.DATA_PACK) {
                    issues.add(
                        IssueLevel.INFO, PlanCode.DATAPACK_WORLD, "I data pack servono a un mondo",
                        "Resta nel profilo; si collega a un mondo da WORLD MANAGER, quando sarà disponibile."
                    )
                }
                val same = installed.firstOrNull { it.category == category && it.origin.equals(report.fileName, ignoreCase = true) }
                if (same != null) {
                    replaces = same
                    issues.add(
                        IssueLevel.CONFIRM, PlanCode.REPLACES_NAME, "C'è già un pacchetto con lo stesso nome («${same.origin}»)",
                        "Se continui, quello vecchio viene messo da parte (puoi annullare)."
                    )
                }
            }
        }

        val taken = installed.filter { it.category == category && it.id != replaces?.id }.map { it.fileName.lowercase() }.toSet()
        val wanted = FileNames.forTarget(report.fileName, category)
        val target = FileNames.unique(wanted, taken)
        if (target != wanted) {
            issues.add(IssueLevel.INFO, PlanCode.RENAMED, "Verrà salvato come «$target»", "C'era già un file con lo stesso nome.")
        }
        val decision = when {
            issues.blocked -> PlanDecision.BLOCKED
            issues.toList().any { it.level == IssueLevel.CONFIRM } -> PlanDecision.NEEDS_CONFIRMATION
            else -> PlanDecision.READY
        }
        return InstallPlan(decision, category, issues.toList(), missing, replaces, null, target, repackage, applicable)
    }

    private val SHADER_LOADERS = setOf("iris", "oculus", "optifine", "optifabric", "optifine-wrapper")

    private fun blocked(issues: Issues, category: ItemCategory?) =
        InstallPlan(PlanDecision.BLOCKED, category, issues.toList(), emptyList(), null, null, null, null, emptyList())

    private fun providedIds(report: JarReport, mods: List<ModMetadata>): Map<String, String?> {
        val own = LinkedHashMap<String, String?>()
        for (m in mods) for (id in m.allIds) own[id] = m.version
        for (n in report.nested) for (m in n.mods) for (id in m.allIds) own.putIfAbsent(id, m.version)
        return own
    }

    private fun notInstallableText(kind: ContentKind): String = when (kind) {
        ContentKind.MODPACK_MODRINTH, ContentKind.MODPACK_CURSEFORGE ->
            "È un modpack: si importa da MODPACK MANAGER, non in un profilo come file singolo"
        ContentKind.MOD_BUNDLE -> "È una raccolta di file: ognuno si installa separatamente"
        ContentKind.BEDROCK_PACK -> "È un pacchetto per Bedrock: si installa da INSTALLER BEDROCK"
        ContentKind.JAVA_WORLD -> "È un mondo: si importa da WORLD MANAGER"
        ContentKind.SERVER_PLUGIN -> "È un plugin per server: non funziona nel gioco"
        ContentKind.UNSUPPORTED_LOADER -> "Il caricatore di questa mod non è supportato"
        else -> "Questo tipo di file non si può installare in un profilo"
    }

    // ------------------------------------------------------------------------------------------ aggiornamenti

    private fun replacement(profile: Profile, installed: List<ProfileItem>, mods: List<ModMetadata>, issues: Issues): ProfileItem? {
        val ids = mods.map { it.id }.toSet()
        val old = installed.firstOrNull { item ->
            item.category == ItemCategory.MOD && item.mods.any { it.id in ids && profile.loader.canLoadModsFor(it.loader) }
        } ?: return null
        val oldMeta = old.mods.first { it.id in ids && profile.loader.canLoadModsFor(it.loader) }
        val newMeta = mods.first { it.id == oldMeta.id }
        val oldV = oldMeta.version ?: "?"
        val newV = newMeta.version ?: "?"
        val cmp = DependencyEvaluator.compareVersions(newMeta.version, oldMeta.version)
        when {
            cmp == null -> issues.add(
                IssueLevel.CONFIRM, PlanCode.SAME_VERSION, "C'è già «${oldMeta.displayName}» ($oldV)",
                "Non si può dire se $newV sia più recente: sostituirà il file attuale."
            )
            cmp > 0 -> issues.add(IssueLevel.INFO, PlanCode.UPDATE, "Aggiorna «${oldMeta.displayName}» da $oldV a $newV")
            cmp < 0 -> issues.add(
                IssueLevel.CONFIRM, PlanCode.DOWNGRADE, "È più vecchia di quella installata",
                "«${oldMeta.displayName}» passerebbe da $oldV a $newV."
            )
            else -> issues.add(
                IssueLevel.CONFIRM, PlanCode.SAME_VERSION, "Stessa versione ($oldV), ma file diverso",
                "Sostituirà il file attuale."
            )
        }
        return old
    }

    /** Le mod già installate che dichiarano di non funzionare con questa. */
    private fun reverseConflicts(provided: Map<String, String?>, others: List<ProfileItem>, issues: Issues) {
        for (item in others) {
            if (!item.enabled || item.category != ItemCategory.MOD) continue
            for (m in item.mods) for (dep in m.dependencies) {
                if (dep.kind != DependencyKind.BREAKS && dep.kind != DependencyKind.CONFLICTS) continue
                if (!provided.containsKey(dep.id)) continue
                val c = dep.test(provided[dep.id])
                if (c == Compat.NO) continue
                val breaks = dep.kind == DependencyKind.BREAKS
                val level = if (breaks && c == Compat.YES) IssueLevel.BLOCK else IssueLevel.CONFIRM
                issues.add(
                    level, if (breaks) PlanCode.BREAKS_BY_INSTALLED else PlanCode.CONFLICT_BY_INSTALLED,
                    "«${item.displayName}», già nel profilo, non va d'accordo con questa mod",
                    dep.reason ?: if (level == IssueLevel.BLOCK) "Il caricatore rifiuterebbe di avviare il gioco." else null
                )
            }
        }
    }

    /**
     * Se si sostituisce una mod, le altre che la richiedono devono accettare la nuova versione, e quello che la vecchia
     * metteva a disposizione e la nuova no non deve servire a nessuno.
     */
    private fun dependentsOfReplaced(provided: Map<String, String?>, replaced: ProfileItem, others: List<ProfileItem>, issues: Issues) {
        val index = InstalledIndex(others)
        val gone = replaced.providedIds.filter { it !in provided.keys }
        for (item in others) {
            if (!item.enabled) continue
            for (m in item.mods) for (dep in m.dependencies) {
                if (dep.kind != DependencyKind.REQUIRED || dep.isPlatform) continue
                if (provided.containsKey(dep.id)) {
                    if (dep.test(provided[dep.id]) == Compat.NO) {
                        issues.add(
                            IssueLevel.CONFIRM, PlanCode.DEPENDENT_BROKEN,
                            "«${m.displayName}» non accetta questa versione di «${dep.id}»",
                            "Richiede ${dep.constraint.describe()}; qui c'è ${provided[dep.id] ?: "una versione sconosciuta"}."
                        )
                    }
                } else if (dep.id in gone && index.providers(dep.id).none { it.item.id != item.id }) {
                    issues.add(
                        IssueLevel.CONFIRM, PlanCode.DEPENDENT_BROKEN,
                        "«${m.displayName}» richiede «${dep.id}», che la nuova versione non offre più",
                        "Con questo aggiornamento «${m.displayName}» smetterebbe di funzionare."
                    )
                }
            }
        }
    }
}

/** Nomi dei file nel profilo. */
object FileNames {

    private const val MAX_STEM = 100

    /** Nome sicuro anche per schede SD e computer: niente caratteri speciali, estensione giusta per la categoria. */
    fun forTarget(original: String, category: ItemCategory): String {
        val last = original.substringAfterLast('/').substringAfterLast('\\').trim()
        var stem = last.substringBeforeLast('.', last)
        if (stem.isEmpty()) stem = "file"
        val sb = StringBuilder()
        for (c in stem) {
            val ok = c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == '+' || c == '(' || c == ')' || c == '[' || c == ']' || c == ' '
            sb.append(if (ok) c else '_')
        }
        var clean = sb.toString().replace(Regex("_{2,}"), "_").replace(Regex("\\.{2,}"), ".").trim().trim('.').trim()
        if (clean.isEmpty()) clean = "file"
        if (clean.length > MAX_STEM) clean = clean.substring(0, MAX_STEM).trim()
        return "$clean.${category.extension}"
    }

    /** Se [wanted] esiste già (senza badare alle maiuscole) aggiunge «-2», «-3»… */
    fun unique(wanted: String, takenLowercase: Set<String>): String {
        if (wanted.lowercase() !in takenLowercase) return wanted
        val dot = wanted.lastIndexOf('.')
        val stem = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$stem-$n$ext"
            if (candidate.lowercase() !in takenLowercase) return candidate
            n++
        }
    }
}
