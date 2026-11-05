package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadRecord
import com.mmi.modinstaller.download.DownloadRequest
import com.mmi.modinstaller.download.DownloadState
import com.mmi.modinstaller.download.EnqueueResult
import com.mmi.modinstaller.download.ExpectedHash
import com.mmi.modinstaller.jar.JarReport
import com.mmi.modinstaller.mods.ItemCategory
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Outcome
import com.mmi.modinstaller.mods.PlacedFile
import com.mmi.modinstaller.mods.Profile
import com.mmi.modinstaller.mods.ProfileDraft
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

enum class Phase(val label: String) {
    PREPARING("Preparo il profilo"),
    DOWNLOADING("Scarico i file"),
    EXTRACTING("Applico le impostazioni"),
    CHECKING("Leggo i file scaricati"),
    DONE("Fatto")
}

class InstallProgress(
    val phase: Phase,
    val doneFiles: Int,
    val totalFiles: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    /** Quanti file sono in pausa o in attesa nel DOWNLOAD MANAGER. */
    val paused: Int = 0,
    val note: String? = null
)

/** Un file del modpack che non è arrivato al suo posto, con il motivo. */
class FileFailure(val path: String, val reason: String)

class InstallRequest(
    val pack: Pack,
    /** Il file .mrpack da cui prendere le impostazioni («overrides»); null = nessuna da applicare. */
    val archive: File?,
    /** Nome del nuovo profilo. */
    val profileName: String,
    /** Profilo di un'installazione interrotta o incompleta: si completa invece di crearne uno nuovo. */
    val resumeProfileId: String? = null
)

class ModpackResult(
    val profile: Profile?,
    /** Il profilo è stato creato da questa installazione. */
    val createdProfile: Boolean,
    /** Se non null, l'installazione si è fermata prima di finire: è il motivo. */
    val fatal: String?,
    /** File da scaricare secondo il piano, esclusi quelli che il modpack dice di non usare. */
    val totalFiles: Int,
    /** Di quelli, quanti sono al loro posto nel profilo con l'impronta verificata (scaricati ora o già presenti). */
    val placedFiles: Int,
    val skippedByEnv: List<String>,
    val failures: List<FileFailure>,
    val overridesApplied: Int,
    val overridesSkipped: List<String>,
    val overridesIgnored: Int,
    /** Elementi registrati nel MOD MANAGER. */
    val registered: Int,
    /** File al loro posto ma non leggibili come mod o pacchetto (registrati comunque, con una nota). */
    val unreadable: List<String>,
    /** Voci dell'indice scartate perché non valide. */
    val problems: List<String>
) {
    /** Tutto quello che il modpack prevede è al suo posto: nessun file mancante, nessuna voce scartata, nessuna impostazione saltata. */
    val complete: Boolean
        get() = fatal == null && failures.isEmpty() && problems.isEmpty() && overridesSkipped.isEmpty()
}

/**
 * Installa un modpack Modrinth in un profilo del MOD MANAGER:
 *  1. crea il profilo (o riprende quello di un'installazione interrotta);
 *  2. fa scaricare i file dal motore dei download, che verifica l'impronta (SHA-512 o SHA-1) di ognuno, e li mette al
 *     loro posto nel profilo solo dopo aver controllato di nuovo l'impronta;
 *  3. applica gli «overrides» (impostazioni) dentro il profilo ([OverridesExtractor]);
 *  4. legge i file delle mod e dei pacchetti (senza eseguirli) e li registra nel MOD MANAGER con un'unica voce di cronologia.
 *
 * Non dichiara mai «completo» ([ModpackResult.complete]) se manca anche un solo file. Se l'utente annulla, quanto creato da
 * questa installazione sparisce (profilo compreso); se invece era una ripresa, il profilo resta com'era.
 */
class ModpackInstaller(
    private val manager: ModManager,
    private val engine: DownloadEngine,
    /** Legge un file Java (senza eseguirlo): l'app usa il suo analizzatore, le prove quello vero o uno finto. */
    private val analyze: (file: File, displayName: String, cancel: CancelCheck) -> JarReport,
    private val limits: SafetyLimits = SafetyLimits(),
    private val windowSize: Int = 12,
    private val pollMs: Long = 150
) {

    /** Un indirizzo da scaricare e i file del modpack che lo usano (di norma uno solo). */
    private class Job(val targets: List<PlannedFile>, val urls: List<String>) {
        var urlPos = 0
        var recordId: String? = null
        var settled = false

        /** Il download esiste già nel DOWNLOAD MANAGER per scelta dell'utente: lo si usa ma non lo si sposta né lo si toglie. */
        var foreign = false
        var placedBytes = 0L
        val declared: Long get() = targets.first().file.size.coerceAtLeast(0L)
    }

    fun install(request: InstallRequest, cancel: CancelCheck = {}, progress: (InstallProgress) -> Unit = {}): ModpackResult {
        val pack = request.pack
        progress(InstallProgress(Phase.PREPARING, 0, 0, 0, 0))
        cancel()

        // 0. il piano: prima di creare qualsiasi cosa si sa se il modpack si può installare
        val resumeId = request.resumeProfileId
        val resumeProfile = if (resumeId != null) manager.profile(resumeId) ?: return fatal("Il profilo di questa installazione non esiste più", pack, null, false) else null
        val plan = ModpackPlanner.plan(pack, engine.checker, resumeProfile?.let { manager.profileDir(it.id) }, cancel)
        if (!plan.canInstall) return fatal(plan.blockers.first(), pack, resumeProfile, false)

        // 1. il profilo
        var created = false
        val profile: Profile
        if (resumeProfile != null) {
            profile = resumeProfile
        } else {
            val draft = ProfileDraft(request.profileName, pack.minecraft.orEmpty(), pack.loader!!, pack.loaderVersion)
            when (val r = manager.createProfile(draft)) {
                is Outcome.Ok -> {
                    profile = r.value
                    created = true
                }
                is Outcome.Fail -> return fatal(r.message + (r.detail?.let { " ($it)" } ?: ""), pack, null, false)
            }
        }
        val profileDir = manager.profileDir(profile.id)
        val jobs = ArrayList<Job>()
        try {
            return run(request, plan, profile, profileDir, created, jobs, cancel, progress)
        } catch (e: CancellationException) {
            abandon(jobs)
            if (created) manager.deleteProfile(profile.id)
            throw e
        } catch (e: IOException) {
            abandon(jobs)
            return fatal("Non sono riuscito a completare l'installazione: " + (e.message ?: e.javaClass.simpleName), pack, manager.profile(profile.id), created)
        } catch (e: RuntimeException) {
            abandon(jobs)
            return fatal("Errore imprevisto: " + (e.message ?: e.javaClass.simpleName), pack, manager.profile(profile.id), created)
        }
    }

    private fun run(
        request: InstallRequest,
        plan: ModpackPlan,
        profile: Profile,
        profileDir: File,
        created: Boolean,
        jobs: ArrayList<Job>,
        cancel: CancelCheck,
        progress: (InstallProgress) -> Unit
    ): ModpackResult {
        val pack = request.pack
        val failures = ArrayList<FileFailure>()
        for (b in plan.blocked) failures.add(FileFailure(b.file.path, b.reason ?: "Non si può scaricare"))

        // I file con lo stesso indirizzo si scaricano una volta sola e si copiano in tutti i posti.
        val byUrl = LinkedHashMap<String, MutableList<PlannedFile>>()
        for (f in plan.toDownload) byUrl.getOrPut(f.urls.first()) { ArrayList() }.add(f)
        for ((_, targets) in byUrl) jobs.add(Job(targets, targets.first().urls))

        val needed = plan.downloadBytes + SPACE_MARGIN
        val free = profileDir.usableSpace
        if (free in 1 until needed) {
            if (created) manager.deleteProfile(profile.id)
            return fatal("Spazio insufficiente sul telefono: servono circa ${formatBytes(needed)}, ce ne sono ${formatBytes(free)}", pack, if (created) null else profile, false)
        }

        // 2. i download, a gruppi: la lista del DOWNLOAD MANAGER resta leggibile e il salvataggio non rallenta con i modpack grandi
        val totalFiles = plan.toDownload.size + plan.present.size
        var placedFiles = plan.present.size
        val pending = ArrayDeque<Job>(jobs)
        val active = ArrayList<Job>()
        val placed = LinkedHashSet<String>() // percorsi dei file del modpack al loro posto
        for (p in plan.present) placed.add(p.file.path)
        val wake = Object()
        val stopListening = engine.addListener { synchronized(wake) { wake.notifyAll() } }
        try {
            while (pending.isNotEmpty() || active.isNotEmpty()) {
                cancel()
                while (active.size < windowSize && pending.isNotEmpty()) {
                    val job = pending.removeFirst()
                    start(job, pack, failures)
                    if (!job.settled) active.add(job)
                }
                var paused = 0
                var doneBytes = 0L
                var totalBytes = 0L
                val iter = active.iterator()
                while (iter.hasNext()) {
                    val job = iter.next()
                    when (check(job, profileDir, pack, failures, placed)) {
                        JobState.PLACED -> {
                            placedFiles += job.targets.size
                            iter.remove()
                        }
                        JobState.FAILED -> iter.remove()
                        JobState.WAITING -> {
                            val rec = job.recordId?.let { engine.record(it) }
                            if (rec?.state == DownloadState.PAUSED) paused++
                            doneBytes += rec?.doneBytes ?: 0L
                            totalBytes += maxOf(rec?.totalBytes ?: 0L, job.declared)
                        }
                    }
                }
                var placedBytes = 0L
                for (j in jobs) placedBytes += j.placedBytes
                val total = plan.downloadBytes
                progress(
                    InstallProgress(
                        Phase.DOWNLOADING, placedFiles, totalFiles, placedBytes + doneBytes, maxOf(total, placedBytes + totalBytes), paused,
                        active.firstOrNull()?.targets?.first()?.file?.path
                    )
                )
                if (pending.isEmpty() && active.isEmpty()) break
                synchronized(wake) { wake.wait(pollMs) }
            }
        } finally {
            stopListening()
        }
        cancel()

        // 3. le impostazioni
        var overrides = OverridesResult(emptyList(), emptyList(), 0)
        if (request.archive != null && pack.overrides.applied > 0) {
            progress(InstallProgress(Phase.EXTRACTING, placedFiles, totalFiles, 0, 0, 0, null))
            overrides = OverridesExtractor.extract(request.archive, profileDir, limits, cancel) { n ->
                progress(InstallProgress(Phase.EXTRACTING, placedFiles, totalFiles, 0, 0, 0, "$n file"))
            }
        }
        cancel()

        // 4. lettura e registrazione nel MOD MANAGER
        val toRegister = LinkedHashMap<String, Pair<ItemCategory, String>>() // percorso → (categoria, origine)
        for (path in placed) {
            val category = PackFile.categoryOf(path) ?: continue
            if (PackFile.extensionOf(path) == category.extension) toRegister[path] = category to path
        }
        for (path in overrides.applied) {
            val category = PackFile.categoryOf(path) ?: continue
            if (PackFile.extensionOf(path) == category.extension) toRegister[path] = category to path
        }
        val placedList = ArrayList<PlacedFile>()
        val unreadable = ArrayList<String>()
        var n = 0
        for ((path, info) in toRegister) {
            cancel()
            progress(InstallProgress(Phase.CHECKING, ++n, toRegister.size, 0, 0, 0, path))
            val (category, origin) = info
            val file = File(profileDir, path)
            if (!file.isFile) continue
            var readError: String? = null
            val report = try {
                analyze(file, file.name, cancel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                readError = e.message ?: e.javaClass.simpleName
                null
            }
            val note = when {
                report == null -> "Non sono riuscito a leggere questo file: è stato tenuto così com'era nel modpack."
                report.hasErrors -> "Il file non sembra " + (if (category == ItemCategory.MOD) "una mod valida" else "un pacchetto valido") +
                    ": è stato tenuto così com'era nel modpack."
                else -> "Dal modpack «${pack.name}»"
            }
            if (report == null) unreadable.add("$path: $readError") else if (report.hasErrors) unreadable.add(path)
            placedList.add(PlacedFile(category, file.name, report, origin, note))
        }
        var registered = 0
        if (placedList.isNotEmpty()) {
            val label = "Modpack «" + pack.name + "»" + (pack.versionId?.let { " $it" } ?: "") + ": " + placedList.size + " file"
            when (val r = manager.registerPlaced(profile.id, label, placedList, "modpack", cancel)) {
                is Outcome.Ok -> registered = r.value.size
                is Outcome.Fail -> failures.add(FileFailure("(elenco del MOD MANAGER)", r.message + (r.detail?.let { " ($it)" } ?: "")))
            }
        }
        progress(InstallProgress(Phase.DONE, placedFiles, totalFiles, 0, 0, 0, null))
        return ModpackResult(
            profile = manager.profile(profile.id), createdProfile = created, fatal = null,
            totalFiles = totalFiles, placedFiles = placedFiles, skippedByEnv = plan.skipped.map { it.file.path },
            failures = failures, overridesApplied = overrides.applied.size, overridesSkipped = overrides.skipped,
            overridesIgnored = overrides.ignored, registered = registered, unreadable = unreadable, problems = pack.problems
        )
    }

    // ------------------------------------------------------------------ download

    private enum class JobState { WAITING, PLACED, FAILED }

    /** Mette in coda il file (o riprende quello rimasto da un tentativo precedente). */
    private fun start(job: Job, pack: Pack, failures: MutableList<FileFailure>) {
        val url = job.urls[job.urlPos]
        val first = job.targets.first().file
        val existing = engine.records().firstOrNull { it.url == url }
        if (existing != null) {
            job.recordId = existing.id
            job.foreign = existing.note?.startsWith(NOTE_PREFIX) != true
            if (existing.state == DownloadState.PAUSED || existing.state == DownloadState.FAILED || existing.state == DownloadState.CANCELED) {
                engine.resume(existing.id)
            }
            return
        }
        val hash = (first.sha512?.let { ExpectedHash.parse("sha512:$it") }) ?: first.sha1?.let { ExpectedHash.parse("sha1:$it") }
        val note = NOTE_PREFIX + pack.name + "»: " + first.path
        when (val r = engine.enqueue(DownloadRequest(url, first.fileName, hash, note))) {
            is EnqueueResult.Added -> job.recordId = r.record.id
            is EnqueueResult.Rejected -> fail(job, failures, r.message)
        }
    }

    private fun fail(job: Job, failures: MutableList<FileFailure>, reason: String) {
        job.settled = true
        for (t in job.targets) failures.add(FileFailure(t.file.path, reason))
    }

    private fun check(job: Job, profileDir: File, pack: Pack, failures: MutableList<FileFailure>, placed: MutableSet<String>): JobState {
        val id = job.recordId ?: return JobState.FAILED
        val rec = engine.record(id)
        if (rec == null) {
            fail(job, failures, "Il download è stato tolto dalla lista prima di finire")
            return JobState.FAILED
        }
        return when (rec.state) {
            DownloadState.COMPLETED -> if (place(job, rec, profileDir, failures, placed)) JobState.PLACED else JobState.FAILED
            DownloadState.FAILED -> {
                if (!job.foreign) engine.remove(id)
                if (job.urlPos + 1 < job.urls.size) {
                    job.urlPos++
                    job.recordId = null
                    start(job, pack, failures)
                    if (job.settled) JobState.FAILED else JobState.WAITING
                } else {
                    fail(job, failures, rec.error ?: "Download non riuscito")
                    JobState.FAILED
                }
            }
            DownloadState.CANCELED -> {
                if (!job.foreign) engine.remove(id)
                fail(job, failures, "Download annullato")
                JobState.FAILED
            }
            DownloadState.PAUSED -> {
                val host = rec.pendingHost
                if (host != null) {
                    if (!job.foreign) {
                        engine.cancel(id)
                        engine.remove(id)
                    }
                    fail(job, failures, "Il server rimanda a «$host», un sito che non hai consentito")
                    JobState.FAILED
                } else {
                    JobState.WAITING
                }
            }
            DownloadState.QUEUED, DownloadState.RUNNING -> JobState.WAITING
        }
    }

    /** Controlla di nuovo l'impronta del file scaricato e lo mette al suo posto (l'ultimo posto lo riceve spostato, gli altri copiati). */
    private fun place(job: Job, rec: DownloadRecord, profileDir: File, failures: MutableList<FileFailure>, placed: MutableSet<String>): Boolean {
        val src = engine.file(rec)
        if (!src.isFile) {
            fail(job, failures, "Il file scaricato non c'è più")
            if (!job.foreign) engine.remove(rec.id)
            return false
        }
        var allOk = true
        val ok = job.targets.filter { t ->
            if (ModpackPlanner.matches(t.file, src)) {
                true
            } else {
                failures.add(FileFailure(t.file.path, "L'impronta del file scaricato non corrisponde a quella del modpack"))
                allOk = false
                false
            }
        }
        for ((i, t) in ok.withIndex()) {
            val dest = File(profileDir, t.file.path)
            try {
                if (i == ok.lastIndex && !job.foreign) moveInto(src, dest) else copyInto(src, dest)
                placed.add(t.file.path)
                job.placedBytes += if (t.file.size >= 0) t.file.size else dest.length()
            } catch (e: IOException) {
                failures.add(FileFailure(t.file.path, "Non riesco a salvarlo nel profilo: " + (e.message ?: e.javaClass.simpleName)))
                allOk = false
            }
        }
        if (!job.foreign) engine.remove(rec.id)
        job.settled = true
        return allOk && ok.isNotEmpty()
    }

    private fun moveInto(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        try {
            Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            copyInto(src, dest)
            src.delete()
        }
    }

    private fun copyInto(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "." + dest.name + ".part")
        try {
            Files.copy(src.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Ferma i download di questa installazione e li toglie dalla lista (chi ha annullato non vuole file in giro). */
    private fun abandon(jobs: List<Job>) {
        val ids = jobs.filter { !it.foreign }.mapNotNull { it.recordId }
        for (id in ids) engine.cancel(id)
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end && ids.any { engine.record(it)?.state == DownloadState.RUNNING }) {
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                break
            }
        }
        for (id in ids) engine.remove(id)
    }

    private fun fatal(message: String, pack: Pack, profile: Profile?, created: Boolean): ModpackResult =
        ModpackResult(
            profile = profile, createdProfile = created, fatal = message, totalFiles = 0, placedFiles = 0,
            skippedByEnv = emptyList(), failures = emptyList(), overridesApplied = 0, overridesSkipped = emptyList(),
            overridesIgnored = 0, registered = 0, unreadable = emptyList(), problems = pack.problems
        )

    companion object {
        private const val SPACE_MARGIN = 32L * 1024 * 1024

        /** Inizio della nota dei download di questa installazione: così si distinguono da quelli che l'utente ha aggiunto da sé. */
        private const val NOTE_PREFIX = "Modpack «"
    }
}
