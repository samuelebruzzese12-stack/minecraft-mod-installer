package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.JarReport
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.jar.ModVersion
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>()
    data class Fail(val message: String, val detail: String? = null) : Outcome<Nothing>()
}

sealed class InstallResult {
    data class Installed(val item: ProfileItem, val replaced: ProfileItem?, val plan: InstallPlan) : InstallResult()
    data class AlreadyInstalled(val item: ProfileItem) : InstallResult()

    /** Il controllo ha dato BLOCCATO, oppure serve una conferma che non è stata data. */
    data class NotAllowed(val plan: InstallPlan) : InstallResult()
    data class Failed(val message: String, val detail: String? = null) : InstallResult()
}

/** Cosa è finito nello ZIP di esportazione di un profilo. */
class ExportSummary(
    val files: Int,
    val bytes: Long,
    /** Quanti file di ogni categoria. */
    val perCategory: Map<ItemCategory, Int>,
    /** File attivi registrati ma non più sul telefono: non sono nello ZIP. */
    val missing: List<String>,
    /** File presenti ma diversi da come erano quando sono stati installati (modificati fuori dall'app). */
    val changed: List<String>,
    /** Quanti dei file scritti non sono mod o pacchetti ma impostazioni e altri file dell'istanza (di un modpack). */
    val extras: Int = 0
)

/** Un file che sta già nella cartella di un profilo (messo lì da un'installazione di modpack), con la sua analisi. */
class PlacedFile(
    val category: ItemCategory,
    /** Nome del file nella cartella della categoria. */
    val fileName: String,
    /** L'analisi del file; null se non è stato possibile leggerlo (si registra lo stesso: il file è nel profilo e va esportato). */
    val report: JarReport?,
    /** Da dove viene (nome mostrato all'utente). */
    val origin: String,
    val note: String? = null
)

/** I dati con cui si crea o si modifica un profilo. */
data class ProfileDraft(
    val name: String,
    val minecraft: String,
    val loader: ModLoader,
    val loaderVersion: String? = null,
    val javaMajor: Int? = null
)

/** Cosa è cambiato rispetto a quanto l'app aveva registrato, quando si riallinea un profilo al disco. */
class Reconciliation(
    /** Registrati ma spariti dal disco: sono stati tolti dall'elenco. */
    val missing: List<ProfileItem>,
    /** Rimessi al loro posto dal cestino dopo un'operazione interrotta. */
    val recovered: List<ProfileItem>,
    /** Attivati o disattivati fuori dall'app: l'elenco ora riflette il disco. */
    val changedOutside: List<ProfileItem>,
    /** File nelle cartelle del profilo che l'app non conosce (non vengono toccati). */
    val strays: List<String>,
    /** File temporanei lasciati da operazioni interrotte, cancellati. */
    val tempFilesRemoved: Int
) {
    val hasChanges: Boolean get() = missing.isNotEmpty() || recovered.isNotEmpty() || changedOutside.isNotEmpty() || tempFilesRemoved > 0
}

/**
 * Profili Java e file al loro interno, sul disco: installa, attiva, disattiva, rimuove e annulla, con il cestino del profilo
 * e la cronologia delle operazioni. Ogni modifica ai file è fatta in modo da poter essere ripetuta o annullata: si scrive in
 * un file temporaneo, si verifica, si rinomina; quello che viene sostituito finisce nel cestino; se il salvataggio
 * dell'elenco non riesce, tutto torna com'era.
 *
 * La cartella di un profilo è `<root>/<id>/{mods,resourcepacks,shaderpacks,datapacks,.trash}`.
 */
class ModManager(
    private val repo: ProfileRepository,
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").substring(0, 12) },
    private val limits: SafetyLimits = SafetyLimits(),
    private val maxJournal: Int = 30
) {
    private val lock = Any()

    // ------------------------------------------------------------------------------------------ lettura

    fun profiles(): List<Profile> = repo.list()

    fun activeProfile(): Profile? = repo.activeId()?.let { repo.get(it) } ?: repo.list().firstOrNull()

    fun profile(id: String): Profile? = repo.get(id)

    fun items(profileId: String): List<ProfileItem> = repo.items(profileId)

    fun journal(profileId: String): List<JournalEntry> = repo.journal(profileId)

    fun profileDir(profileId: String): File = File(root, profileId)

    fun folder(profileId: String, category: ItemCategory): File = File(profileDir(profileId), category.folder)

    /** Il file di [item] sul disco (nome con o senza «.disabled» secondo lo stato registrato). */
    fun fileOf(profileId: String, item: ProfileItem): File = File(folder(profileId, item.category), item.physicalName)

    private fun trashDir(profileId: String, opId: String): File = File(File(profileDir(profileId), TRASH), opId)

    // ------------------------------------------------------------------------------------------ profili

    fun createProfile(draft: ProfileDraft): Outcome<Profile> = synchronized(lock) {
        val clean = validate(draft, null)
        if (clean is Outcome.Fail) return@synchronized clean
        val d = (clean as Outcome.Ok).value
        val profile = Profile(newId(), d.name, d.minecraft, d.loader, d.loaderVersion, d.javaMajor, clock())
        try {
            for (c in ItemCategory.values()) {
                val dir = folder(profile.id, c)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Non riesco a creare la cartella ${c.folder}")
            }
            File(profileDir(profile.id), TRASH).mkdirs()
        } catch (e: IOException) {
            Digests.deleteRecursively(profileDir(profile.id))
            return@synchronized Outcome.Fail("Non riesco a preparare le cartelle del profilo", e.message)
        }
        if (!repo.add(profile)) {
            Digests.deleteRecursively(profileDir(profile.id))
            return@synchronized Outcome.Fail("Non sono riuscito a salvare il profilo", "Controlla lo spazio libero sul telefono.")
        }
        Outcome.Ok(profile)
    }

    fun editProfile(id: String, draft: ProfileDraft): Outcome<Profile> = synchronized(lock) {
        val old = repo.get(id) ?: return@synchronized Outcome.Fail("Il profilo non esiste più")
        val clean = validate(draft, id)
        if (clean is Outcome.Fail) return@synchronized clean
        val d = (clean as Outcome.Ok).value
        val updated = old.copy(name = d.name, minecraft = d.minecraft, loader = d.loader, loaderVersion = d.loaderVersion, javaMajor = d.javaMajor)
        if (!repo.update(id) { updated }) return@synchronized Outcome.Fail("Non sono riuscito a salvare il profilo")
        Outcome.Ok(updated)
    }

    /** Elimina il profilo e tutti i suoi file. Chi chiama deve aver chiesto conferma all'utente. */
    fun deleteProfile(id: String): Outcome<Unit> = synchronized(lock) {
        if (repo.get(id) == null) return@synchronized Outcome.Fail("Il profilo non esiste più")
        if (!repo.remove(id)) return@synchronized Outcome.Fail("Non sono riuscito a salvare la modifica")
        Digests.deleteRecursively(profileDir(id))
        Outcome.Ok(Unit)
    }

    fun setActive(id: String): Outcome<Unit> = synchronized(lock) {
        if (repo.get(id) == null) return@synchronized Outcome.Fail("Il profilo non esiste più")
        if (!repo.setActive(id)) return@synchronized Outcome.Fail("Non sono riuscito a salvare la modifica")
        Outcome.Ok(Unit)
    }

    private fun validate(d: ProfileDraft, editingId: String?): Outcome<ProfileDraft> {
        val name = d.name.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return Outcome.Fail("Scrivi un nome per il profilo")
        if (name.length > MAX_NAME || name.any { it.isISOControl() }) return Outcome.Fail("Il nome del profilo è troppo lungo o contiene caratteri non validi")
        if (repo.list().any { it.id != editingId && it.name.equals(name, ignoreCase = true) }) {
            return Outcome.Fail("Esiste già un profilo con questo nome")
        }
        val mc = d.minecraft.trim()
        if (!VERSION_TEXT.matches(mc)) return Outcome.Fail("La versione di Minecraft non è valida", "Esempio: 1.20.1")
        val lv = d.loaderVersion?.trim()?.takeIf { it.isNotEmpty() }
        if (lv != null && !VERSION_TEXT.matches(lv)) return Outcome.Fail("La versione del caricatore non è valida", "Esempio: 0.15.11")
        val java = d.javaMajor
        if (java != null && java !in 8..40) return Outcome.Fail("La versione di Java non è valida")
        return Outcome.Ok(ProfileDraft(name, mc, d.loader, lv, java))
    }

    // ------------------------------------------------------------------------------------------ installazione

    /** Il controllo di compatibilità, senza toccare nulla. */
    fun plan(profileId: String, report: JarReport): InstallPlan? {
        val profile = repo.get(profileId) ?: return null
        return InstallPlanner.plan(report, profile, repo.items(profileId))
    }

    /**
     * Installa [source] (già analizzato in [report]) nel profilo. Rifà il controllo con lo stato attuale del profilo: se è
     * BLOCCATO non fa nulla, se serve conferma e [confirmed] è falso non fa nulla. Il file viene copiato in un temporaneo,
     * verificato (la sua impronta deve essere quella dell'analisi), poi rinominato; quello che sostituisce va nel cestino.
     */
    fun install(
        profileId: String,
        source: File,
        report: JarReport,
        confirmed: Boolean,
        origin: String = report.fileName,
        sourceLabel: String = "file",
        cancel: CancelCheck = {},
        progress: ((done: Long, total: Long) -> Unit)? = null
    ): InstallResult = synchronized(lock) {
        val profile = repo.get(profileId) ?: return@synchronized InstallResult.Failed("Il profilo non esiste più")
        val before = repo.items(profileId)
        val plan = InstallPlanner.plan(report, profile, before)
        when (plan.decision) {
            PlanDecision.ALREADY_INSTALLED -> return@synchronized InstallResult.AlreadyInstalled(plan.duplicateOf!!)
            PlanDecision.BLOCKED -> return@synchronized InstallResult.NotAllowed(plan)
            PlanDecision.NEEDS_CONFIRMATION -> if (!confirmed) return@synchronized InstallResult.NotAllowed(plan)
            PlanDecision.READY -> {}
        }
        val category = plan.category ?: return@synchronized InstallResult.NotAllowed(plan)
        if (!source.isFile) return@synchronized InstallResult.Failed("Il file da installare non c'è più")

        val dir = folder(profileId, category)
        if (!dir.isDirectory && !dir.mkdirs()) return@synchronized InstallResult.Failed("Non riesco a preparare la cartella del profilo")
        val free = dir.usableSpace
        val needed = source.length() * 2 + MARGIN_BYTES
        if (free in 1 until needed) {
            return@synchronized InstallResult.Failed(
                "Spazio insufficiente sul telefono",
                "Servono circa ${formatBytes(needed)} liberi, ce ne sono ${formatBytes(free)}."
            )
        }

        val opId = newId()
        val part = File(dir, ".$opId.part")
        var finalFile: File? = null
        var trashed: Pair<File, File>? = null // (posto originale, posto nel cestino)
        var committed = false
        try {
            // 1. copia verificata in un file temporaneo
            val digests = try {
                val wrapper = plan.repackageWrapper
                if (wrapper != null) {
                    val check = Digests.of(source, cancel)
                    if (check.sha256 != report.sha256) {
                        return@synchronized InstallResult.Failed("Il file è cambiato dopo il controllo", "Selezionalo di nuovo.")
                    }
                    PackRepackager.repackage(source, wrapper, part, limits, cancel)
                    Digests.of(part, cancel)
                } else {
                    val d = source.inputStream().use { Digests.copy(it, part, source.length(), cancel, progress) }
                    if (d.sha256 != report.sha256) {
                        return@synchronized InstallResult.Failed("Il file è cambiato dopo il controllo", "Selezionalo di nuovo.")
                    }
                    d
                }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: IOException) {
                return@synchronized InstallResult.Failed("Non sono riuscito a copiare il file", e.message)
            }

            // 2. quello che viene sostituito va nel cestino
            val replaced = plan.replaces
            if (replaced != null) {
                val old = fileOf(profileId, replaced)
                if (old.exists()) {
                    val inTrash = File(trashDir(profileId, opId), replaced.physicalName)
                    Digests.move(old, inTrash)
                    trashed = old to inTrash
                }
            }

            // 3. nome definitivo (mai sopra un file che l'app non conosce) e rinomina
            val taken = HashSet<String>()
            dir.list()?.forEach { n ->
                if (!n.endsWith(".part")) taken.add(n.removeSuffix(ProfileItem.DISABLED_SUFFIX).lowercase())
            }
            val finalName = FileNames.unique(plan.targetName ?: FileNames.forTarget(report.fileName, category), taken)
            val dest = File(dir, finalName)
            Digests.move(part, dest)
            finalFile = dest

            // 4. elenco e cronologia
            val item = ProfileItem(
                id = newId(),
                category = category,
                fileName = finalName,
                enabled = true,
                sha256 = digests.sha256,
                sha1 = digests.sha1,
                sizeBytes = digests.size,
                installedAt = clock(),
                origin = origin,
                source = sourceLabel,
                kind = report.kind,
                mods = report.mods,
                nested = report.nested.flatMap { it.mods },
                javaMajor = report.javaMajor,
                packFormat = report.pack?.packFormat,
                sourceSha256 = if (plan.repackageWrapper != null) report.sha256 else null
            )
            val steps = ArrayList<JournalStep>()
            if (replaced != null && trashed != null) steps.add(JournalStep.Removed(replaced, replaced.physicalName))
            steps.add(JournalStep.Added(item))
            val label = labelForInstall(item, replaced)
            val dropped = ArrayList<JournalEntry>()
            val saved = repo.modify(profileId) { items, journal ->
                if (replaced != null) items.removeAll { it.id == replaced.id }
                items.add(item)
                journal.add(JournalEntry(opId, clock(), label, steps))
                dropOld(journal, dropped)
            }
            if (!saved) {
                rollbackInstall(finalFile, trashed)
                finalFile = null
                trashed = null
                return@synchronized InstallResult.Failed("Non sono riuscito a salvare l'elenco", "Controlla lo spazio libero sul telefono. Non è cambiato nulla.")
            }
            committed = true
            for (e in dropped) Digests.deleteRecursively(trashDir(profileId, e.id))
            InstallResult.Installed(item, if (trashed != null) replaced else null, plan)
        } catch (e: java.util.concurrent.CancellationException) {
            if (!committed) rollbackInstall(finalFile, trashed)
            throw e
        } catch (e: IOException) {
            if (!committed) rollbackInstall(finalFile, trashed)
            InstallResult.Failed("Non sono riuscito a completare l'installazione", e.message)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun rollbackInstall(newFile: File?, trashed: Pair<File, File>?) {
        try {
            newFile?.delete()
        } catch (_: Exception) {
        }
        if (trashed != null) {
            try {
                if (!trashed.first.exists()) Digests.move(trashed.second, trashed.first)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Il file di questi elementi è stato riscritto da un'operazione più recente (un modpack ripreso): le voci precedenti non
     * possono più rimetterlo com'era e, se restassero, bloccherebbero l'annullamento di quelle ancora più vecchie.
     */
    private fun forgetSuperseded(journal: MutableList<JournalEntry>, ids: Set<String>) {
        val it = journal.listIterator()
        while (it.hasNext()) {
            val e = it.next()
            val kept = e.steps.filter { s -> !((s is JournalStep.Added && s.item.id in ids) || (s is JournalStep.Toggled && s.itemId in ids)) }
            if (kept.size == e.steps.size) continue
            if (kept.isEmpty()) it.remove() else it.set(e.copy(steps = kept))
        }
    }

    private fun dropOld(journal: MutableList<JournalEntry>, dropped: MutableList<JournalEntry>) {
        while (journal.size > maxJournal) dropped.add(journal.removeAt(0))
    }

    private fun labelForInstall(item: ProfileItem, replaced: ProfileItem?): String {
        val name = item.mods.firstOrNull()?.displayName ?: item.origin
        return if (replaced != null) "Aggiornato «$name»" else "Installato «${item.displayName}»"
    }

    // ------------------------------------------------------------------------------------------ attiva, disattiva, rimuovi

    fun setEnabled(profileId: String, itemId: String, enabled: Boolean): Outcome<ProfileItem> = synchronized(lock) {
        if (repo.get(profileId) == null) return@synchronized Outcome.Fail("Il profilo non esiste più")
        val item = repo.items(profileId).firstOrNull { it.id == itemId } ?: return@synchronized Outcome.Fail("Il file non è più nell'elenco")
        if (item.enabled == enabled) return@synchronized Outcome.Ok(item)
        val from = fileOf(profileId, item)
        val updated = item.copy(enabled = enabled)
        val to = fileOf(profileId, updated)
        if (!from.exists()) return@synchronized Outcome.Fail("Il file non c'è più sul telefono", item.physicalName)
        try {
            Digests.move(from, to)
        } catch (e: IOException) {
            return@synchronized Outcome.Fail("Non sono riuscito a cambiare lo stato del file", e.message)
        }
        val dropped = ArrayList<JournalEntry>()
        val label = (if (enabled) "Attivato «" else "Disattivato «") + item.displayName + "»"
        val ok = repo.modify(profileId) { items, journal ->
            val i = items.indexOfFirst { it.id == itemId }
            if (i >= 0) items[i] = updated
            journal.add(JournalEntry(newId(), clock(), label, listOf(JournalStep.Toggled(itemId, item.enabled))))
            dropOld(journal, dropped)
        }
        if (!ok) {
            try {
                Digests.move(to, from)
            } catch (_: Exception) {
            }
            return@synchronized Outcome.Fail("Non sono riuscito a salvare la modifica", "Non è cambiato nulla.")
        }
        for (e in dropped) Digests.deleteRecursively(trashDir(profileId, e.id))
        Outcome.Ok(updated)
    }

    /** Toglie il file dal profilo: finisce nel cestino e si può ripristinare con [undo]. */
    fun remove(profileId: String, itemId: String): Outcome<ProfileItem> = synchronized(lock) {
        if (repo.get(profileId) == null) return@synchronized Outcome.Fail("Il profilo non esiste più")
        val item = repo.items(profileId).firstOrNull { it.id == itemId } ?: return@synchronized Outcome.Fail("Il file non è più nell'elenco")
        val opId = newId()
        val from = fileOf(profileId, item)
        val inTrash = File(trashDir(profileId, opId), item.physicalName)
        var moved = false
        if (from.exists()) {
            try {
                Digests.move(from, inTrash)
                moved = true
            } catch (e: IOException) {
                return@synchronized Outcome.Fail("Non sono riuscito a mettere da parte il file", e.message)
            }
        }
        val dropped = ArrayList<JournalEntry>()
        val steps = if (moved) listOf<JournalStep>(JournalStep.Removed(item, item.physicalName)) else emptyList()
        val ok = repo.modify(profileId) { items, journal ->
            items.removeAll { it.id == itemId }
            if (steps.isNotEmpty()) {
                journal.add(JournalEntry(opId, clock(), "Rimosso «${item.displayName}»", steps))
                dropOld(journal, dropped)
            }
        }
        if (!ok) {
            if (moved) {
                try {
                    Digests.move(inTrash, from)
                } catch (_: Exception) {
                }
            }
            return@synchronized Outcome.Fail("Non sono riuscito a salvare la modifica", "Non è cambiato nulla.")
        }
        for (e in dropped) Digests.deleteRecursively(trashDir(profileId, e.id))
        Outcome.Ok(item)
    }

    // ------------------------------------------------------------------------------------------ file di un modpack

    /**
     * I file della cartella del profilo che non sono mod o pacchetti registrati: impostazioni (`config/…`), script e altro che
     * un modpack porta con sé, e anche i file messi nelle cartelle `mods`, `resourcepacks`… che l'elenco non conosce
     * (sottocartelle, tipi diversi da .jar/.zip). Percorsi relativi con `/`, in ordine. Non contano i file registrati (attivi
     * o disattivati), il cestino, i nomi che iniziano con un punto (temporanei) e i collegamenti simbolici.
     */
    fun extraFiles(profileId: String): List<String> {
        val base = profileDir(profileId)
        if (!base.isDirectory) return emptyList()
        val items = repo.items(profileId)
        val managed = ItemCategory.values().associate { c -> c.folder to items.filter { it.category == c }.map { it.fileName }.toSet() }
        val out = ArrayList<String>()
        fun walk(dir: File, prefix: String, depth: Int, known: Set<String>?) {
            if (depth > MAX_EXTRA_DEPTH || out.size >= MAX_EXTRA_FILES) return
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                val path = if (prefix.isEmpty()) f.name else prefix + "/" + f.name
                if (f.isDirectory) {
                    walk(f, path, depth + 1, if (depth == 0) managed[f.name] else null)
                } else if (f.isFile && out.size < MAX_EXTRA_FILES) {
                    // Direttamente dentro una cartella gestita: i file registrati (anche disattivati) non sono «extra».
                    if (known != null && f.name.removeSuffix(ProfileItem.DISABLED_SUFFIX) in known) continue
                    out.add(path)
                }
            }
        }
        walk(base, "", 0, null)
        return out
    }

    /**
     * Registra come elementi del profilo dei file che sono già nelle sue cartelle: li ha messi lì un'installazione di modpack,
     * dopo aver verificato l'impronta di ciascuno. Non copia né sposta nulla: calcola le impronte del file sul disco e
     * controlla che sia quello analizzato. Tutto in un'unica voce della cronologia (si può annullare in blocco). I file
     * già registrati con lo stesso contenuto restano com'erano. Restituisce gli elementi aggiunti.
     */
    fun registerPlaced(
        profileId: String,
        label: String,
        files: List<PlacedFile>,
        source: String = "modpack",
        cancel: CancelCheck = {}
    ): Outcome<List<ProfileItem>> = synchronized(lock) {
        if (repo.get(profileId) == null) return@synchronized Outcome.Fail("Il profilo non esiste più")
        val opId = newId()
        val existing = repo.items(profileId)
        val added = ArrayList<ProfileItem>()
        for (p in files) {
            cancel()
            val file = File(folder(profileId, p.category), p.fileName)
            if (!file.isFile) continue
            val d = try {
                Digests.of(file, cancel)
            } catch (e: IOException) {
                return@synchronized Outcome.Fail("Non riesco a leggere ${p.fileName}", e.message)
            }
            val r = p.report
            if (r != null && r.sha256.isNotEmpty() && d.sha256 != r.sha256) {
                return@synchronized Outcome.Fail("Il file ${p.fileName} è cambiato dopo il controllo", "Non è stato registrato nulla.")
            }
            val same = existing.firstOrNull { it.category == p.category && it.fileName == p.fileName }
            if (same != null && same.sha256 == d.sha256) continue
            added.add(
                ProfileItem(
                    id = newId(), category = p.category, fileName = p.fileName, enabled = true,
                    sha256 = d.sha256, sha1 = d.sha1, sizeBytes = d.size, installedAt = clock(),
                    origin = p.origin, source = source, kind = r?.kind ?: ContentKind.UNKNOWN, mods = r?.mods.orEmpty(),
                    nested = r?.nested.orEmpty().flatMap { it.mods }, javaMajor = r?.javaMajor, packFormat = r?.pack?.packFormat,
                    note = p.note
                )
            )
        }
        if (added.isEmpty()) return@synchronized Outcome.Ok(emptyList())
        val dropped = ArrayList<JournalEntry>()
        val saved = repo.modify(profileId) { items, journal ->
            val replaced = items.filter { old -> added.any { it.category == old.category && it.fileName == old.fileName } }.map { it.id }.toSet()
            items.removeAll { it.id in replaced }
            items.addAll(added)
            if (replaced.isNotEmpty()) forgetSuperseded(journal, replaced)
            journal.add(JournalEntry(opId, clock(), label, added.map { JournalStep.Added(it) }))
            dropOld(journal, dropped)
        }
        if (!saved) return@synchronized Outcome.Fail("Non sono riuscito a salvare l'elenco", "Controlla lo spazio libero sul telefono.")
        for (e in dropped) Digests.deleteRecursively(trashDir(profileId, e.id))
        Outcome.Ok(added)
    }

    // ------------------------------------------------------------------------------------------ annulla

    fun lastOperation(profileId: String): JournalEntry? = repo.journal(profileId).lastOrNull()

    /** Annulla l'ultima operazione del profilo: rimette i file com'erano. Restituisce la descrizione di ciò che è stato annullato. */
    fun undo(profileId: String): Outcome<String> = synchronized(lock) {
        if (repo.get(profileId) == null) return@synchronized Outcome.Fail("Il profilo non esiste più")
        val entry = repo.journal(profileId).lastOrNull() ?: return@synchronized Outcome.Fail("Non c'è nessuna operazione da annullare")
        val items = repo.items(profileId).toMutableList()
        val undoDir = trashDir(profileId, "u-${entry.id}")
        val compensations = ArrayList<() -> Unit>()

        fun fail(message: String, detail: String? = null): Outcome<String> {
            for (c in compensations.asReversed()) {
                try {
                    c()
                } catch (_: Exception) {
                }
            }
            Digests.deleteRecursively(undoDir)
            return Outcome.Fail(message, detail)
        }

        for (step in entry.steps.asReversed()) {
            when (step) {
                is JournalStep.Added -> {
                    val current = items.firstOrNull { it.id == step.item.id }
                        ?: return@synchronized fail("Il file aggiunto non è più nell'elenco")
                    val f = fileOf(profileId, current)
                    if (f.exists()) {
                        val tmp = File(undoDir, current.physicalName)
                        try {
                            Digests.move(f, tmp)
                        } catch (e: IOException) {
                            return@synchronized fail("Non sono riuscito a togliere il file", e.message)
                        }
                        compensations.add { Digests.move(tmp, f) }
                    }
                    items.removeAll { it.id == current.id }
                }
                is JournalStep.Removed -> {
                    val src = File(trashDir(profileId, entry.id), step.trashName)
                    val dst = fileOf(profileId, step.item)
                    if (!src.exists()) return@synchronized fail("Il file messo da parte non c'è più", step.item.fileName)
                    if (dst.exists()) return@synchronized fail("Esiste già un file con lo stesso nome", step.item.fileName)
                    try {
                        Digests.move(src, dst)
                    } catch (e: IOException) {
                        return@synchronized fail("Non sono riuscito a rimettere il file", e.message)
                    }
                    compensations.add { Digests.move(dst, src) }
                    items.removeAll { it.id == step.item.id }
                    items.add(step.item)
                }
                is JournalStep.Toggled -> {
                    val i = items.indexOfFirst { it.id == step.itemId }
                    if (i < 0) return@synchronized fail("Il file non è più nell'elenco")
                    val current = items[i]
                    val restored = current.copy(enabled = step.wasEnabled)
                    val from = fileOf(profileId, current)
                    val to = fileOf(profileId, restored)
                    if (from != to) {
                        if (!from.exists()) return@synchronized fail("Il file non c'è più sul telefono", current.physicalName)
                        try {
                            Digests.move(from, to)
                        } catch (e: IOException) {
                            return@synchronized fail("Non sono riuscito a cambiare lo stato del file", e.message)
                        }
                        compensations.add { Digests.move(to, from) }
                    }
                    items[i] = restored
                }
            }
        }
        val ok = repo.modify(profileId) { its, jr ->
            its.clear()
            its.addAll(items)
            jr.removeAll { it.id == entry.id }
        }
        if (!ok) return@synchronized fail("Non sono riuscito a salvare la modifica", "Non è cambiato nulla.")
        Digests.deleteRecursively(trashDir(profileId, entry.id))
        Digests.deleteRecursively(undoDir)
        Outcome.Ok(entry.label)
    }

    // ------------------------------------------------------------------------------------------ esportazione

    /**
     * Scrive in [out] uno ZIP con i file ATTIVI del profilo, nella disposizione di una cartella di gioco
     * (`mods/…`, `resourcepacks/…`, `shaderpacks/…`, `datapacks/…`), più le impostazioni e gli altri file dell'istanza se il
     * profilo viene da un modpack ([extraFiles]). I file disattivati restano fuori. I file del profilo non vengono toccati.
     * Chi chiama apre e chiude [out]; se l'esportazione fallisce o viene annullata lo ZIP è incompleto e va cancellato.
     */
    fun exportZip(
        profileId: String,
        out: OutputStream,
        cancel: CancelCheck = {},
        progress: ((done: Long, total: Long) -> Unit)? = null
    ): Outcome<ExportSummary> = synchronized(lock) {
        if (repo.get(profileId) == null) return@synchronized Outcome.Fail("Il profilo non esiste più")
        val items = repo.items(profileId)
            .filter { it.enabled }
            .sortedWith(compareBy<ProfileItem>({ it.category.ordinal }, { it.fileName.lowercase() }))
        val extraPaths = extraFiles(profileId)
        val total = items.sumOf { it.sizeBytes } + extraPaths.sumOf { File(profileDir(profileId), it).length() }
        val missing = ArrayList<String>()
        val changed = ArrayList<String>()
        val perCategory = LinkedHashMap<ItemCategory, Int>()
        var files = 0
        var bytes = 0L
        try {
            val zip = ZipOutputStream(out)
            // Mod e pacchetti sono già compressi: si scrivono senza ricomprimerli.
            zip.setLevel(Deflater.NO_COMPRESSION)
            val buffer = ByteArray(64 * 1024)
            for (item in items) {
                cancel()
                val file = fileOf(profileId, item)
                if (!file.isFile) {
                    missing.add(item.displayName)
                    continue
                }
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(item.category.folder + "/" + item.fileName))
                file.inputStream().use { input ->
                    while (true) {
                        cancel()
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        bytes += n
                        progress?.invoke(bytes, total)
                    }
                }
                zip.closeEntry()
                if (Digests.hex(digest.digest()) != item.sha256) changed.add(item.displayName)
                files++
                perCategory[item.category] = (perCategory[item.category] ?: 0) + 1
            }
            // Impostazioni e altri file dell'istanza (ci sono se il profilo viene da un modpack): sono testi, si comprimono.
            zip.setLevel(Deflater.BEST_SPEED)
            var extras = 0
            for (path in extraPaths) {
                cancel()
                val file = File(profileDir(profileId), path)
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { input ->
                    while (true) {
                        cancel()
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        bytes += n
                        progress?.invoke(bytes, total)
                    }
                }
                zip.closeEntry()
                extras++
                files++
            }
            zip.finish()
            zip.flush()
            return@synchronized Outcome.Ok(ExportSummary(files, bytes, perCategory, missing, changed, extras))
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: IOException) {
            return@synchronized Outcome.Fail("Non sono riuscito a scrivere lo ZIP", e.message)
        }
    }

    // ------------------------------------------------------------------------------------------ controlli

    /** Riallinea il profilo al disco e poi controlla tutti i suoi file. */
    fun check(profileId: String): Pair<ProfileCheck, Reconciliation>? {
        val rec = reconcile(profileId) ?: return null
        val profile = repo.get(profileId) ?: return null
        return ProfileChecker.check(profile, repo.items(profileId)) to rec
    }

    /**
     * Il disco è la verità: se un file è stato attivato o disattivato (o cancellato) fuori dall'app, l'elenco lo segue.
     * Se un'operazione è stata interrotta a metà, il file messo da parte nel cestino viene rimesso a posto. I file che
     * l'app non conosce non vengono mai toccati, solo segnalati.
     */
    fun reconcile(profileId: String): Reconciliation? = synchronized(lock) {
        if (repo.get(profileId) == null) return@synchronized null
        var temps = 0
        for (c in ItemCategory.values()) {
            folder(profileId, c).listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".part")) {
                    if (f.delete()) temps++
                }
            }
        }
        val journalIds = repo.journal(profileId).map { it.id }.toSet()
        val orphanTrash = File(profileDir(profileId), TRASH).listFiles()?.filter { it.isDirectory && it.name !in journalIds && !it.name.startsWith("u-") }.orEmpty()

        val missing = ArrayList<ProfileItem>()
        val recovered = ArrayList<ProfileItem>()
        val changed = ArrayList<ProfileItem>()
        val items = repo.items(profileId)
        val next = ArrayList<ProfileItem>()
        for (item in items) {
            val enabledFile = File(folder(profileId, item.category), item.fileName)
            val disabledFile = File(folder(profileId, item.category), item.fileName + ProfileItem.DISABLED_SUFFIX)
            val hasOn = enabledFile.exists()
            val hasOff = disabledFile.exists()
            when {
                hasOn && hasOff -> next.add(item)
                hasOn -> if (!item.enabled) {
                    val u = item.copy(enabled = true)
                    next.add(u)
                    changed.add(u)
                } else next.add(item)
                hasOff -> if (item.enabled) {
                    val u = item.copy(enabled = false)
                    next.add(u)
                    changed.add(u)
                } else next.add(item)
                else -> {
                    val back = recoverFromTrash(profileId, item, orphanTrash)
                    if (back) {
                        recovered.add(item)
                        next.add(item)
                    } else missing.add(item)
                }
            }
        }
        if (missing.isNotEmpty() || changed.isNotEmpty()) {
            val gone = missing.map { it.id }.toSet()
            val byId = changed.associateBy { it.id }
            repo.modify(profileId) { its, _ ->
                val kept = its.filter { it.id !in gone }.map { byId[it.id] ?: it }
                its.clear()
                its.addAll(kept)
            }
        }
        for (d in orphanTrash) Digests.deleteRecursively(d)

        val known = HashMap<ItemCategory, MutableSet<String>>()
        for (item in next) known.getOrPut(item.category) { HashSet() }.add(item.fileName)
        val strays = ArrayList<String>()
        for (c in ItemCategory.values()) {
            val names = known[c].orEmpty()
            folder(profileId, c).list()?.sorted()?.forEach { n ->
                if (n.startsWith(".")) return@forEach
                val base = n.removeSuffix(ProfileItem.DISABLED_SUFFIX)
                if (base !in names) strays.add("${c.folder}/$n")
            }
        }
        Reconciliation(missing, recovered, changed, strays, temps)
    }

    private fun recoverFromTrash(profileId: String, item: ProfileItem, orphans: List<File>): Boolean {
        for (d in orphans) {
            val f = File(d, item.physicalName)
            if (!f.isFile) continue
            val target = fileOf(profileId, item)
            try {
                Digests.move(f, target)
                return true
            } catch (_: IOException) {
            }
        }
        return false
    }

    /** Spazio occupato dai file del profilo. */
    fun sizeOf(profileId: String): Long = repo.items(profileId).sumOf { it.sizeBytes }

    companion object {
        const val TRASH = ".trash"
        private const val MAX_EXTRA_DEPTH = 16
        private const val MAX_EXTRA_FILES = 100_000
        private const val MAX_NAME = 60
        private const val MARGIN_BYTES = 8L * 1024 * 1024
        private val VERSION_TEXT = Regex("[0-9A-Za-z][0-9A-Za-z._+\\-]{0,39}")
    }
}

/** Valori di partenza per un profilo nuovo, ricavati dal file che l'utente sta aggiungendo. */
data class ProfileSuggestion(val minecraft: String?, val loader: ModLoader?)

object ProfileSuggester {

    fun fromReport(report: JarReport): ProfileSuggestion {
        val mod = report.mods.firstOrNull { it.certainty == com.mmi.modinstaller.jar.LoaderCertainty.CERTAIN } ?: report.mods.firstOrNull()
        val loader = mod?.loader
        var mc: String? = null
        val dep = mod?.dependencies?.firstOrNull { it.id == "minecraft" && it.kind == com.mmi.modinstaller.jar.DependencyKind.REQUIRED }
        val lowest = dep?.constraint?.lowestVersion()
        if (lowest != null && lowest.comparable) mc = lowest.raw
        if (mc == null && mod?.minecraftHint != null && ModVersion.parse(mod.minecraftHint)?.comparable == true) mc = mod.minecraftHint
        if (mc == null) {
            val known = report.pack?.knownMinecraft
            if (known != null) mc = known.substringBefore(' ').takeIf { ModVersion.parse(it)?.comparable == true }
        }
        return ProfileSuggestion(mc, loader)
    }
}
