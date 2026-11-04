package com.mmi.modinstaller.mods

import com.mmi.modinstaller.jar.Compat
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.LoaderCertainty
import com.mmi.modinstaller.jar.ModDependency
import com.mmi.modinstaller.jar.ModEnvironment
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.jar.ModMetadata
import com.mmi.modinstaller.jar.ModVersion

/**
 * Le regole che dicono se una mod può funzionare in un profilo. Sono le stesse quando si decide se installare un file nuovo
 * ([InstallPlanner]) e quando si controlla un profilo già pieno ([ProfileChecker]).
 */
internal object ModRules {

    /** Linguaggi che il caricatore conosce già: non sono mod da installare. */
    private val BUILT_IN_LANGUAGES = setOf("javafml", "lowcodefml", "minecraft", "mclanguage", "fml")

    /** I caricatori per cui una descrizione potrebbe valere: un `mods.toml` che non nomina né Forge né NeoForge vale per entrambi. */
    fun plausibleLoaders(m: ModMetadata): Set<ModLoader> =
        if (m.certainty == LoaderCertainty.GUESSED && m.descriptor.endsWith("mods.toml")) setOf(ModLoader.FORGE, ModLoader.NEOFORGE)
        else setOf(m.loader)

    /** Le descrizioni che valgono per il profilo. Se nessuna vale segnala il problema e restituisce un elenco vuoto. */
    fun applicableMods(mods: List<ModMetadata>, profile: Profile, issues: Issues): List<ModMetadata> {
        if (mods.isEmpty()) return emptyList()
        val direct = mods.filter { profile.loader.canLoadModsFor(it.loader) }
        if (direct.isNotEmpty()) {
            if (profile.loader == ModLoader.QUILT && direct.all { it.loader == ModLoader.FABRIC }) {
                issues.add(
                    IssueLevel.INFO, PlanCode.QUILT_FABRIC, "Mod per Fabric su Quilt",
                    "Quilt di solito carica le mod Fabric, ma alcune possono non funzionare."
                )
            }
            if (direct.all { it.certainty == LoaderCertainty.GUESSED }) {
                issues.add(
                    IssueLevel.INFO, PlanCode.LOADER_GUESSED, "Il caricatore non è dichiarato con certezza",
                    "Il file non dice se è per Forge o NeoForge: l'app lo ha dedotto dal tipo di file."
                )
            }
            return direct
        }
        val guessed = mods.filter { profile.loader in plausibleLoaders(it) }
        if (guessed.isNotEmpty()) {
            issues.add(
                IssueLevel.CONFIRM, PlanCode.LOADER_GUESSED, "Non è chiaro se è per ${profile.loader.label}",
                "Il file non nomina né Forge né NeoForge: potrebbe essere fatto per l'altro."
            )
            return guessed
        }
        val names = mods.map { it.loader.label }.distinct().joinToString(" e ")
        issues.add(
            IssueLevel.BLOCK, PlanCode.LOADER_MISMATCH, "È una mod per $names, il profilo usa ${profile.loader.label}",
            "Una mod funziona solo con il caricatore per cui è stata fatta."
        )
        return emptyList()
    }

    fun optifine(profile: Profile, issues: Issues) {
        if (profile.loader == ModLoader.FABRIC || profile.loader == ModLoader.QUILT) {
            issues.add(
                IssueLevel.BLOCK, PlanCode.OPTIFINE, "OptiFine non funziona con ${profile.loader.label}",
                "Per le stesse funzioni si usano Sodium e Iris."
            )
        } else {
            issues.add(
                IssueLevel.CONFIRM, PlanCode.OPTIFINE, "OptiFine funziona solo con versioni precise di ${profile.loader.label}",
                "L'app non può verificare che questo file vada bene con il profilo."
            )
        }
    }

    /**
     * Controlla le descrizioni [mods] di un file: ambiente, Minecraft, Java, caricatore e dipendenze verso le altre mod del
     * profilo ([index], dove [skipItemId] è il file stesso o quello che sta per essere sostituito). [own] sono gli
     * identificatori messi a disposizione dal file (compresi i .jar contenuti). Le dipendenze da aggiungere finiscono in
     * [missing].
     */
    fun check(
        mods: List<ModMetadata>,
        own: Map<String, String?>,
        profile: Profile,
        index: InstalledIndex,
        skipItemId: String?,
        issues: Issues,
        missing: MutableList<MissingDependency>?
    ) {
        if (mods.isEmpty()) return
        val mcVersion = ModVersion.parse(profile.minecraft)
        var mcDeclaredByAny = false
        var mcUnknownNoted = false
        for (m in mods) {
            val who = m.displayName
            if (m.environment == ModEnvironment.SERVER) {
                issues.add(
                    IssueLevel.CONFIRM, PlanCode.ENVIRONMENT, "«$who» è una mod solo per server",
                    "Su questo telefono, che fa da client, non serve."
                )
            }
            var sawMinecraft = false
            for (dep in m.dependencies) {
                if (dep.kind == DependencyKind.REQUIRED && dep.side.equals("SERVER", ignoreCase = true)) continue
                when {
                    dep.id == "minecraft" && dep.kind == DependencyKind.REQUIRED -> {
                        sawMinecraft = true
                        mcDeclaredByAny = true
                        when (DependencyEvaluator.platformCompat(dep, profile)) {
                            Compat.NO -> issues.add(
                                IssueLevel.CONFIRM, PlanCode.MC_MISMATCH, "«$who» non è per Minecraft ${profile.minecraft}",
                                "Dichiara di funzionare con Minecraft ${dep.constraint.describe()}."
                            )
                            Compat.UNKNOWN -> if (!mcUnknownNoted) {
                                mcUnknownNoted = true
                                val why = if (mcVersion == null || !mcVersion.comparable) {
                                    "La versione ${profile.minecraft} del profilo non si può confrontare."
                                } else {
                                    "Il requisito «${dep.range}» non è in un formato riconosciuto."
                                }
                                issues.add(IssueLevel.INFO, PlanCode.MC_UNKNOWN, "Non posso verificare la versione di Minecraft", why)
                            }
                            Compat.YES -> {}
                        }
                    }
                    dep.id == "java" && dep.kind == DependencyKind.REQUIRED -> {
                        if (DependencyEvaluator.platformCompat(dep, profile) == Compat.NO && !issues.has(PlanCode.JAVA_TOO_OLD)) {
                            issues.add(
                                IssueLevel.CONFIRM, PlanCode.JAVA_TOO_OLD, "«$who» richiede Java ${dep.constraint.describe()}",
                                "Il profilo usa Java ${profile.effectiveJava}."
                            )
                        }
                    }
                    DependencyEvaluator.isLoaderId(dep.id, profile.loader) && dep.kind == DependencyKind.REQUIRED -> {
                        if (DependencyEvaluator.platformCompat(dep, profile) == Compat.NO) {
                            issues.add(
                                IssueLevel.CONFIRM, PlanCode.LOADER_VERSION,
                                "«$who» richiede ${profile.loader.label} ${dep.constraint.describe()}",
                                "Il profilo ha la versione ${profile.loaderVersion}."
                            )
                        }
                    }
                    dep.isPlatform -> {}
                    else -> otherModDependency(dep, who, index, own, skipItemId, issues, missing)
                }
            }
            if (!sawMinecraft && m.minecraftHint != null) {
                mcDeclaredByAny = true
                val hint = ModVersion.parse(m.minecraftHint)
                if (hint != null && hint.comparable && mcVersion != null && mcVersion.comparable) {
                    val sameLine = hint.component(0) == mcVersion.component(0) && hint.component(1) == mcVersion.component(1)
                    if (!sameLine) {
                        issues.add(
                            IssueLevel.CONFIRM, PlanCode.MC_MISMATCH, "«$who» è per Minecraft ${m.minecraftHint}",
                            "Il profilo usa ${profile.minecraft}."
                        )
                    }
                }
            }
            languageProvider(m, who, index, own, skipItemId, profile, issues)
        }
        if (!mcDeclaredByAny && !mcUnknownNoted) {
            issues.add(IssueLevel.INFO, PlanCode.MC_UNKNOWN, "Il file non dice per quale Minecraft è fatto")
        }
    }

    /** Forge e NeoForge: un linguaggio che non è quello di serie (es. Kotlin for Forge) è una mod da installare. */
    private fun languageProvider(
        m: ModMetadata,
        who: String,
        index: InstalledIndex,
        own: Map<String, String?>,
        skipItemId: String?,
        profile: Profile,
        issues: Issues
    ) {
        if (profile.loader != ModLoader.FORGE && profile.loader != ModLoader.NEOFORGE) return
        val lang = m.languageLoader?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        if (lang in BUILT_IN_LANGUAGES) return
        if (own.containsKey(lang)) return
        if (index.providers(lang).any { it.item.id != skipItemId }) return
        issues.add(
            IssueLevel.CONFIRM, PlanCode.LANGUAGE_PROVIDER, "«$who» usa il linguaggio «$lang»",
            "Di solito serve installare anche la mod con quel nome; nel profilo non c'è."
        )
    }

    private fun otherModDependency(
        dep: ModDependency,
        who: String,
        index: InstalledIndex,
        own: Map<String, String?>,
        skipItemId: String?,
        issues: Issues,
        missing: MutableList<MissingDependency>?
    ) {
        val r = DependencyEvaluator.evaluate(dep, index, own, skipItemId)
        val range = dep.range?.takeIf { it.isNotBlank() && it != "*" }
        val rangeText = if (range != null) " (${dep.constraint.describe()})" else ""
        when (dep.kind) {
            DependencyKind.REQUIRED -> when (r.state) {
                DepState.MISSING -> {
                    issues.add(IssueLevel.CONFIRM, PlanCode.DEP_MISSING, "«$who» richiede «${dep.id}»$rangeText", "Non c'è nel profilo.")
                    missing?.add(MissingDependency(dep.id, dep.range, dep.syntax, who))
                }
                DepState.DISABLED -> issues.add(
                    IssueLevel.CONFIRM, PlanCode.DEP_DISABLED, "«$who» richiede «${dep.id}»$rangeText",
                    "C'è nel profilo ma è disattivata."
                )
                DepState.WRONG_VERSION -> {
                    issues.add(
                        IssueLevel.CONFIRM, PlanCode.DEP_VERSION, "«$who» richiede «${dep.id}»$rangeText",
                        "Nel profilo c'è la versione ${r.foundVersion ?: "sconosciuta"}."
                    )
                    missing?.add(MissingDependency(dep.id, dep.range, dep.syntax, who))
                }
                DepState.SATISFIED, DepState.SATISFIED_UNKNOWN -> {}
            }
            DependencyKind.RECOMMENDED -> if (r.state == DepState.MISSING || r.state == DepState.WRONG_VERSION) {
                issues.add(IssueLevel.INFO, PlanCode.DEP_RECOMMENDED, "«$who» consiglia «${dep.id}»")
            }
            DependencyKind.BREAKS -> if (r.state == DepState.SATISFIED || r.state == DepState.SATISFIED_UNKNOWN) {
                val name = r.provider?.displayName ?: dep.id
                val level = if (r.state == DepState.SATISFIED) IssueLevel.BLOCK else IssueLevel.CONFIRM
                issues.add(level, PlanCode.BREAKS, "«$who» non funziona con «$name»", dep.reason ?: "Il caricatore rifiuterebbe di avviare il gioco.")
            }
            DependencyKind.CONFLICTS -> if (r.state == DepState.SATISFIED || r.state == DepState.SATISFIED_UNKNOWN) {
                val name = r.provider?.displayName ?: dep.id
                issues.add(IssueLevel.CONFIRM, PlanCode.CONFLICT, "«$who» dà problemi con «$name»", dep.reason ?: "Il caricatore avvisa di un possibile conflitto.")
            }
            DependencyKind.OPTIONAL, DependencyKind.SUGGESTED -> {}
        }
    }
}
