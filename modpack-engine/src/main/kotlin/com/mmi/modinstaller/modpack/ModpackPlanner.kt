package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.download.UrlChecker
import com.mmi.modinstaller.download.UrlVerdict
import com.mmi.modinstaller.mods.Digests
import com.mmi.modinstaller.mods.ProfileItem
import java.io.File
import java.security.MessageDigest

/** Cosa si fa con un file del modpack. */
enum class FileAction {
    /** Va scaricato. */
    DOWNLOAD,

    /** C'è già al suo posto nel profilo, con l'impronta giusta: non si riscarica (ripresa di un'installazione). */
    PRESENT,

    /** Il modpack dice che non serve su un dispositivo (client): non si scarica. */
    SKIPPED_ENV,

    /** Non si può scaricare: nessun indirizzo ammesso dalle regole. */
    BLOCKED
}

class PlannedFile(
    val file: PackFile,
    val action: FileAction,
    /** Gli indirizzi che le regole ammettono, nell'ordine dell'indice (si prova il successivo se uno non risponde). */
    val urls: List<String>,
    /** Perché non si scarica (SKIPPED_ENV e BLOCKED). */
    val reason: String? = null,
    /** Sito sicuro ma non ancora consentito dall'utente: se lo consente, il file si può scaricare. */
    val hostToApprove: String? = null
)

/** Cosa farebbe l'installazione di un modpack, prima di toccare qualsiasi cosa. */
class ModpackPlan(
    val pack: Pack,
    val files: List<PlannedFile>,
    /** Motivi per cui il modpack non si può installare per niente. */
    val blockers: List<String>
) {
    val toDownload: List<PlannedFile> get() = files.filter { it.action == FileAction.DOWNLOAD }
    val present: List<PlannedFile> get() = files.filter { it.action == FileAction.PRESENT }
    val skipped: List<PlannedFile> get() = files.filter { it.action == FileAction.SKIPPED_ENV }
    val blocked: List<PlannedFile> get() = files.filter { it.action == FileAction.BLOCKED }

    /** Byte da scaricare secondo le dimensioni dichiarate (quelle non dichiarate non si contano). */
    val downloadBytes: Long get() = toDownload.sumOf { it.file.size.coerceAtLeast(0L) }
    val unknownSizes: Int get() = toDownload.count { it.file.size < 0 }

    /** Siti da consentire perché il modpack si scarichi per intero. */
    val hostsToApprove: List<String> get() = blocked.mapNotNull { it.hostToApprove }.distinct().sorted()

    val canInstall: Boolean get() = blockers.isEmpty()

    /** Non manca nulla: nessun file bloccato e nessuna voce scartata dall'indice. */
    val complete: Boolean get() = canInstall && blocked.isEmpty() && pack.problems.isEmpty()
}

object ModpackPlanner {

    /**
     * Prepara il piano. [checker] sono le regole sugli indirizzi del motore dei download (solo https, solo siti consentiti).
     * Se [profileDir] è dato (ripresa di un'installazione), i file già al loro posto con l'impronta giusta non si riscaricano.
     */
    fun plan(pack: Pack, checker: UrlChecker, profileDir: File? = null, cancel: CancelCheck = {}): ModpackPlan {
        val blockers = ArrayList<String>()
        if (pack.minecraft == null) blockers.add("Il modpack non dice per quale versione di Minecraft è fatto.")
        if (pack.loader == null) {
            blockers.add("Il modpack non indica il caricatore (Fabric, Quilt, Forge o NeoForge): non so come prepararne il profilo.")
        }
        if (pack.files.isEmpty() && pack.overrides.applied == 0) blockers.add("Il modpack è vuoto: non contiene né file da scaricare né impostazioni.")

        val planned = ArrayList<PlannedFile>(pack.files.size)
        for (f in pack.files) {
            cancel()
            if (f.client == Side.UNSUPPORTED) {
                planned.add(PlannedFile(f, FileAction.SKIPPED_ENV, emptyList(), "Il modpack dice che non serve sul client (solo server)"))
                continue
            }
            val allowed = ArrayList<String>()
            var needHost: String? = null
            var rejected: String? = null
            for (url in f.urls) {
                when (val v = checker.check(url)) {
                    is UrlVerdict.Allowed -> allowed.add(url)
                    is UrlVerdict.HostNotAllowed -> if (needHost == null) needHost = v.host
                    is UrlVerdict.Rejected -> if (rejected == null) rejected = v.message
                }
            }
            if (allowed.isEmpty()) {
                val reason = if (needHost != null) "Scarica da «$needHost», un sito che non hai ancora consentito" else (rejected ?: "Nessun indirizzo ammesso")
                planned.add(PlannedFile(f, FileAction.BLOCKED, emptyList(), reason, needHost))
                continue
            }
            if (profileDir != null && isPresentOrDisabled(f, File(profileDir, f.path), cancel)) {
                planned.add(PlannedFile(f, FileAction.PRESENT, allowed))
            } else {
                planned.add(PlannedFile(f, FileAction.DOWNLOAD, allowed))
            }
        }
        return ModpackPlan(pack, planned, blockers)
    }

    /** Come [isPresent], ma vale anche il file che l'utente ha disattivato (stesso nome con il suffisso del MOD MANAGER): non si riporta in vita. */
    private fun isPresentOrDisabled(f: PackFile, dest: File, cancel: CancelCheck): Boolean =
        isPresent(f, dest, cancel) || isPresent(f, File(dest.parentFile, dest.name + ProfileItem.DISABLED_SUFFIX), cancel)

    /** Il file c'è già: stessa dimensione (se dichiarata) e impronta uguale (SHA-512 se il modpack la dà, altrimenti SHA-1). */
    internal fun isPresent(f: PackFile, dest: File, cancel: CancelCheck): Boolean {
        if (!dest.isFile) return false
        if (f.size >= 0 && dest.length() != f.size) return false
        return matches(f, dest, cancel)
    }

    /** L'impronta del file sul disco è quella dichiarata dal modpack. */
    internal fun matches(f: PackFile, file: File, cancel: CancelCheck = {}): Boolean {
        val (algorithm, expected) = when {
            f.sha512 != null -> "SHA-512" to f.sha512
            f.sha1 != null -> "SHA-1" to f.sha1
            else -> return false
        }
        val md = MessageDigest.getInstance(algorithm)
        try {
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    cancel()
                }
            }
        } catch (e: java.io.IOException) {
            return false
        }
        return Digests.hex(md.digest()) == expected
    }
}
