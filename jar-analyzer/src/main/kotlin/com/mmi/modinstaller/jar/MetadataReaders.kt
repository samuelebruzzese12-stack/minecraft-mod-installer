package com.mmi.modinstaller.jar

import com.mmi.modinstaller.core.Finding
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.JsonParseException
import com.mmi.modinstaller.core.Severity
import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.int
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.obj
import com.mmi.modinstaller.core.str

/** Il file di descrizione non è utilizzabile: l'analisi del file si ferma qui con questo messaggio. */
internal class MetadataException(message: String) : Exception(message)

/** Esito della lettura di un file di descrizione. */
internal class ReadResult(
    val mods: List<ModMetadata>,
    val mixins: List<String> = emptyList(),
    val nestedPaths: List<String> = emptyList()
)

private fun problem(problems: MutableList<Finding>, code: FindingCode, severity: Severity, message: String, detail: String? = null) {
    problems.add(Finding(severity, code, message, detail))
}

private fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).removePrefix("﻿")

private fun cleanText(s: String?): String? = s?.replace(Regex("§."), "")?.trim()?.takeIf { it.isNotEmpty() }

/** Un campo che può essere stringa o elenco di stringhe, unito in una sola riga. */
private fun textOrList(value: Any?): String? = when (value) {
    is String -> cleanText(value)
    is List<*> -> value.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString(", ").ifEmpty { null }
    is Map<*, *> -> (value["name"] as? String) ?: (value["id"] as? String)
    else -> null
}

/** Autori: stringhe oppure oggetti con il campo `name`. */
private fun authorNames(value: Any?): List<String> {
    val out = ArrayList<String>()
    when (value) {
        is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
        is List<*> -> for (a in value) {
            when (a) {
                is String -> if (a.isNotBlank()) out.add(a.trim())
                is Map<*, *> -> (a["name"] as? String)?.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
            }
        }
        is Map<*, *> -> for (k in value.keys) (k as? String)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
    }
    return out
}

// ---------------------------------------------------------------------------------------------------- Fabric

internal object FabricReader {
    private val ID = Regex("^[a-z][a-z0-9_-]{1,63}$")

    fun read(bytes: ByteArray, problems: MutableList<Finding>): ReadResult {
        val root = try {
            Json.parse(decode(bytes)).asJsonObject()
        } catch (e: JsonParseException) {
            throw MetadataException("fabric.mod.json non è un JSON valido: ${e.message}")
        } ?: throw MetadataException("fabric.mod.json non contiene un oggetto JSON")

        val id = root.str("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MetadataException("In fabric.mod.json manca il campo «id»")
        if (!ID.matches(id)) {
            problem(
                problems, FindingCode.JAVA_ID_INVALID, Severity.WARNING,
                "L'identificatore «$id» non rispetta le regole di Fabric",
                "Deve avere 2-64 caratteri, iniziare con una lettera minuscola e usare solo minuscole, numeri, - e _. Fabric potrebbe rifiutare la mod."
            )
        }
        val version = root.str("version")?.trim()?.takeIf { it.isNotEmpty() }
        if (version == null) {
            problem(problems, FindingCode.JAVA_METADATA_INVALID, Severity.WARNING, "In fabric.mod.json manca la versione della mod", "Fabric richiede il campo «version»: la mod potrebbe non avviarsi.")
        } else if (version.contains("\${")) {
            problem(problems, FindingCode.JAVA_VERSION_UNRESOLVED, Severity.WARNING, "La versione della mod non è stata compilata («$version»)", "Il file sembra costruito male: puoi installarlo, ma non è possibile confrontare la sua versione.")
        }
        val contact = root.obj("contact")
        val deps = ArrayList<ModDependency>()
        addDeps(deps, root.obj("depends") ?: root.obj("requires"), DependencyKind.REQUIRED)
        addDeps(deps, root.obj("recommends"), DependencyKind.RECOMMENDED)
        addDeps(deps, root.obj("suggests"), DependencyKind.SUGGESTED)
        addDeps(deps, root.obj("conflicts"), DependencyKind.CONFLICTS)
        addDeps(deps, root.obj("breaks"), DependencyKind.BREAKS)

        val provides = ArrayList<String>()
        for (p in root.list("provides").orEmpty()) {
            when (p) {
                is String -> provides.add(p)
                is Map<*, *> -> (p["id"] as? String)?.let { provides.add(it) }
            }
        }
        val environment = when ((root["environment"] as? String)?.lowercase()) {
            "client" -> ModEnvironment.CLIENT
            "server" -> ModEnvironment.SERVER
            else -> ModEnvironment.BOTH
        }
        val mixins = ArrayList<String>()
        when (val m = root["mixins"]) {
            is String -> mixins.add(m)
            is List<*> -> for (e in m) {
                when (e) {
                    is String -> mixins.add(e)
                    is Map<*, *> -> (e["config"] as? String)?.let { mixins.add(it) }
                }
            }
        }
        val nested = ArrayList<String>()
        for (j in root.list("jars").orEmpty()) (j.asJsonObject()?.str("file"))?.let { nested.add(it) }

        val meta = ModMetadata(
            loader = ModLoader.FABRIC,
            certainty = LoaderCertainty.CERTAIN,
            id = id,
            name = cleanText(root.str("name")),
            version = version,
            description = cleanText(root.str("description")),
            authors = authorNames(root["authors"]),
            license = textOrList(root["license"]),
            homepage = contact?.str("homepage"),
            environment = environment,
            provides = provides,
            dependencies = deps,
            descriptor = "fabric.mod.json"
        )
        return ReadResult(listOf(meta), mixins, nested)
    }

    private fun addDeps(out: MutableList<ModDependency>, map: Map<String, Any?>?, kind: DependencyKind) {
        if (map == null) return
        for ((depId, value) in map) {
            val range = when (value) {
                is String -> value.trim()
                is List<*> -> value.mapNotNull { (it as? String)?.trim() }.joinToString(" || ")
                null -> null
                else -> Json.stringify(value)
            }
            out.add(ModDependency(depId, kind, range?.takeIf { it.isNotEmpty() }, RangeSyntax.SEMVER))
        }
    }
}

// ---------------------------------------------------------------------------------------------------- Quilt

internal object QuiltReader {
    private val ID = Regex("^[a-z][a-z0-9_-]{1,63}$")

    fun read(bytes: ByteArray, problems: MutableList<Finding>): ReadResult {
        val root = try {
            Json.parse(decode(bytes)).asJsonObject()
        } catch (e: JsonParseException) {
            throw MetadataException("quilt.mod.json non è un JSON valido: ${e.message}")
        } ?: throw MetadataException("quilt.mod.json non contiene un oggetto JSON")
        val loader = root.obj("quilt_loader") ?: throw MetadataException("In quilt.mod.json manca la sezione «quilt_loader»")
        val id = loader.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: throw MetadataException("In quilt.mod.json manca il campo «id»")
        if (!ID.matches(id)) {
            problem(problems, FindingCode.JAVA_ID_INVALID, Severity.WARNING, "L'identificatore «$id» non rispetta le regole di Quilt")
        }
        val version = loader.str("version")?.trim()?.takeIf { it.isNotEmpty() }
        if (version == null) {
            problem(problems, FindingCode.JAVA_METADATA_INVALID, Severity.WARNING, "In quilt.mod.json manca la versione della mod")
        } else if (version.contains("\${")) {
            problem(problems, FindingCode.JAVA_VERSION_UNRESOLVED, Severity.WARNING, "La versione della mod non è stata compilata («$version»)")
        }
        val metadata = loader.obj("metadata")
        val deps = ArrayList<ModDependency>()
        addDeps(deps, loader.list("depends"), DependencyKind.REQUIRED)
        addDeps(deps, loader.list("breaks"), DependencyKind.BREAKS)

        val provides = ArrayList<String>()
        for (p in loader.list("provides").orEmpty()) {
            when (p) {
                is String -> provides.add(p)
                is Map<*, *> -> (p["id"] as? String)?.let { provides.add(it) }
            }
        }
        val environment = when ((root.obj("minecraft")?.str("environment"))?.lowercase()) {
            "client" -> ModEnvironment.CLIENT
            "dedicated_server" -> ModEnvironment.SERVER
            else -> ModEnvironment.BOTH
        }
        val mixins = ArrayList<String>()
        when (val m = root["mixin"]) {
            is String -> mixins.add(m)
            is List<*> -> for (e in m) (e as? String)?.let { mixins.add(it) }
        }
        val nested = ArrayList<String>()
        for (j in loader.list("jars").orEmpty()) (j as? String)?.let { nested.add(it) }

        val meta = ModMetadata(
            loader = ModLoader.QUILT,
            certainty = LoaderCertainty.CERTAIN,
            id = id,
            name = cleanText(metadata?.str("name")),
            version = version,
            description = cleanText(metadata?.str("description")),
            authors = authorNames(metadata?.get("contributors")),
            license = textOrList(metadata?.get("license")),
            homepage = metadata?.obj("contact")?.str("homepage"),
            environment = environment,
            provides = provides,
            dependencies = deps,
            descriptor = "quilt.mod.json"
        )
        return ReadResult(listOf(meta), mixins, nested)
    }

    private fun addDeps(out: MutableList<ModDependency>, list: List<Any?>?, kind: DependencyKind) {
        if (list == null) return
        for (e in list) {
            when (e) {
                is String -> if (e.isNotBlank()) out.add(ModDependency(e.trim(), kind))
                is Map<*, *> -> {
                    val depId = (e["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val optional = e["optional"] == true
                    val effective = if (kind == DependencyKind.REQUIRED && optional) DependencyKind.OPTIONAL else kind
                    out.add(
                        ModDependency(
                            depId, effective, versions(e["versions"]), RangeSyntax.SEMVER,
                            reason = e["reason"] as? String
                        )
                    )
                }
            }
        }
    }

    /** `versions` di Quilt: testo, elenco (basta uno) oppure {"all": [...]} / {"any": [...]}. */
    private fun versions(value: Any?): String? = when (value) {
        null -> null
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is List<*> -> value.mapNotNull { (it as? String)?.trim() }.joinToString(" || ").ifEmpty { null }
        is Map<*, *> -> {
            val all = (value["all"] as? List<*>)?.mapNotNull { (it as? String)?.trim() }
            val any = (value["any"] as? List<*>)?.mapNotNull { (it as? String)?.trim() }
            when {
                all != null && any == null -> all.joinToString(" ").ifEmpty { null }
                any != null && all == null -> any.joinToString(" || ").ifEmpty { null }
                else -> Json.stringify(value)
            }
        }
        else -> Json.stringify(value)
    }
}

// ---------------------------------------------------------------------------------------------------- Forge / NeoForge

internal object ForgeTomlReader {

    fun read(bytes: ByteArray, descriptor: String, manifest: Map<String, String>, problems: MutableList<Finding>): ReadResult {
        val root = try {
            Toml.parse(decode(bytes))
        } catch (e: TomlException) {
            throw MetadataException("$descriptor non è valido: ${e.message}")
        }
        val mods = (root["mods"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        if (mods.isEmpty()) throw MetadataException("In $descriptor non c'è nessuna voce [[mods]]")

        val languageLoader = (root["modLoader"] as? String)?.trim()
        val license = (root["license"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val clientSideOnly = root["clientSideOnly"] == true

        val allDeps = LinkedHashMap<String, List<ModDependency>>()
        val result = ArrayList<ModMetadata>()
        var neo = descriptor.endsWith("neoforge.mods.toml")
        var forge = false
        for (m in mods) {
            val modId = (m["modId"] as? String)?.trim().orEmpty()
            if (modId.isEmpty()) throw MetadataException("In $descriptor una voce [[mods]] non ha «modId»")
            val deps = dependenciesFor(root, modId)
            allDeps[modId] = deps
            if (deps.any { it.id == "neoforge" }) neo = true
            if (deps.any { it.id == "forge" }) forge = true
        }
        val loader: ModLoader
        val certainty: LoaderCertainty
        when {
            descriptor.endsWith("neoforge.mods.toml") -> {
                loader = ModLoader.NEOFORGE
                certainty = LoaderCertainty.CERTAIN
            }
            neo && !forge -> {
                loader = ModLoader.NEOFORGE
                certainty = LoaderCertainty.CERTAIN
            }
            forge -> {
                loader = ModLoader.FORGE
                certainty = LoaderCertainty.CERTAIN
            }
            else -> {
                loader = ModLoader.FORGE
                certainty = LoaderCertainty.GUESSED
            }
        }
        for (m in mods) {
            val modId = (m["modId"] as String).trim()
            var version = (m["version"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (version != null && version.contains("\${file.jarVersion}")) {
                val fromJar = manifest["Implementation-Version"]?.trim()?.takeIf { it.isNotEmpty() }
                version = if (fromJar != null) version.replace("\${file.jarVersion}", fromJar) else null
                if (fromJar == null) {
                    problem(
                        problems, FindingCode.JAVA_VERSION_UNRESOLVED, Severity.WARNING,
                        "La versione di «$modId» non si può ricavare dal file",
                        "Forge la leggerebbe dal manifest del jar, che non la indica."
                    )
                }
            } else if (version != null && version.contains("\${")) {
                problem(
                    problems, FindingCode.JAVA_VERSION_UNRESOLVED, Severity.WARNING,
                    "La versione di «$modId» non è stata compilata («$version»)"
                )
                version = null
            }
            if (version == null && m["version"] == null) {
                problem(problems, FindingCode.JAVA_VERSION_UNRESOLVED, Severity.INFO, "«$modId» non indica la sua versione")
            }
            val provides = (m["provides"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            result.add(
                ModMetadata(
                    loader = loader,
                    certainty = certainty,
                    id = modId,
                    name = cleanText(m["displayName"] as? String),
                    version = version,
                    description = cleanText(m["description"] as? String),
                    authors = authorNames(m["authors"]),
                    license = license,
                    homepage = (m["displayURL"] as? String)?.takeIf { it.isNotBlank() },
                    environment = if (clientSideOnly) ModEnvironment.CLIENT else ModEnvironment.BOTH,
                    provides = provides,
                    dependencies = allDeps[modId].orEmpty(),
                    descriptor = descriptor,
                    languageLoader = languageLoader
                )
            )
        }
        val mixins = (root["mixins"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("config") as? String }.orEmpty()
        return ReadResult(result, mixins)
    }

    private fun dependenciesFor(root: Map<String, Any?>, modId: String): List<ModDependency> {
        val table = root["dependencies"] as? Map<*, *> ?: return emptyList()
        val entries = when (val v = table[modId]) {
            is List<*> -> v
            is Map<*, *> -> listOf(v)
            else -> return emptyList()
        }
        val out = ArrayList<ModDependency>()
        for (e in entries) {
            val d = e as? Map<*, *> ?: continue
            val depId = (d["modId"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val type = (d["type"] as? String)?.lowercase()
            val mandatory = d["mandatory"] as? Boolean
            val kind = when (type) {
                "optional" -> DependencyKind.OPTIONAL
                "incompatible" -> DependencyKind.BREAKS
                "discouraged" -> DependencyKind.CONFLICTS
                "required" -> DependencyKind.REQUIRED
                else -> if (mandatory == false) DependencyKind.OPTIONAL else DependencyKind.REQUIRED
            }
            out.add(
                ModDependency(
                    depId, kind, (d["versionRange"] as? String)?.trim()?.takeIf { it.isNotEmpty() }, RangeSyntax.MAVEN,
                    side = (d["side"] as? String)?.uppercase(),
                    reason = d["reason"] as? String
                )
            )
        }
        return out
    }
}

// ---------------------------------------------------------------------------------------------------- Forge precedente alla 1.13

internal object LegacyForgeReader {
    fun read(bytes: ByteArray): ReadResult {
        val root = try {
            Json.parse(decode(bytes))
        } catch (e: JsonParseException) {
            throw MetadataException("mcmod.info non è un JSON valido: ${e.message}")
        }
        val entries: List<Any?> = when (root) {
            is List<*> -> root
            is Map<*, *> -> (root["modList"] as? List<*>) ?: listOf(root)
            else -> throw MetadataException("mcmod.info ha un formato non riconosciuto")
        }
        val out = ArrayList<ModMetadata>()
        for (e in entries) {
            val m = e.asJsonObject() ?: continue
            val id = m.str("modid")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val deps = ArrayList<ModDependency>()
            for (d in m.list("requiredMods").orEmpty()) (d as? String)?.let { deps.add(ModDependency(it.trim(), DependencyKind.REQUIRED, null, RangeSyntax.MAVEN)) }
            for (d in m.list("dependencies").orEmpty()) (d as? String)?.let { deps.add(ModDependency(it.trim(), DependencyKind.RECOMMENDED, null, RangeSyntax.MAVEN)) }
            out.add(
                ModMetadata(
                    loader = ModLoader.FORGE,
                    certainty = LoaderCertainty.GUESSED,
                    id = id,
                    name = cleanText(m.str("name")),
                    version = m.str("version")?.trim()?.takeIf { it.isNotEmpty() },
                    description = cleanText(m.str("description")),
                    authors = authorNames(m["authorList"] ?: m["authors"]),
                    license = null,
                    homepage = m.str("url")?.takeIf { it.isNotBlank() },
                    dependencies = deps,
                    descriptor = "mcmod.info",
                    minecraftHint = m.str("mcversion")?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
        if (out.isEmpty()) throw MetadataException("mcmod.info non descrive nessuna mod")
        return ReadResult(out)
    }
}

// ---------------------------------------------------------------------------------------------------- pack.mcmeta

internal class PackMeta(val description: String?, val packFormat: Int?, val minFormat: Int?, val maxFormat: Int?)

internal object PackMetaReader {

    fun read(bytes: ByteArray): PackMeta {
        val root = try {
            Json.parse(decode(bytes)).asJsonObject()
        } catch (e: JsonParseException) {
            throw MetadataException("pack.mcmeta non è un JSON valido: ${e.message}")
        } ?: throw MetadataException("pack.mcmeta non contiene un oggetto JSON")
        val pack = root.obj("pack") ?: throw MetadataException("In pack.mcmeta manca la sezione «pack»")
        val format = pack.int("pack_format")
        var min = numberOrFirst(pack["min_format"])
        var max = numberOrFirst(pack["max_format"])
        when (val s = pack["supported_formats"]) {
            is Number -> {
                if (min == null) min = s.toInt()
                if (max == null) max = s.toInt()
            }
            is List<*> -> {
                if (min == null) min = (s.getOrNull(0) as? Number)?.toInt()
                if (max == null) max = (s.getOrNull(1) as? Number)?.toInt()
            }
            is Map<*, *> -> {
                if (min == null) min = (s["min_inclusive"] as? Number)?.toInt()
                if (max == null) max = (s["max_inclusive"] as? Number)?.toInt()
            }
        }
        if (format == null && min == null && max == null) {
            throw MetadataException("In pack.mcmeta manca «pack_format»")
        }
        return PackMeta(plainText(pack["description"]), format, min, max)
    }

    private fun numberOrFirst(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is List<*> -> (v.getOrNull(0) as? Number)?.toInt()
        else -> null
    }

    /** La descrizione può essere un testo o un componente di testo di Minecraft (oggetto o elenco). */
    private fun plainText(v: Any?, depth: Int = 0): String? {
        if (depth > 6) return null
        val s = when (v) {
            is String -> v
            is Map<*, *> -> {
                val sb = StringBuilder()
                (v["text"] as? String)?.let { sb.append(it) }
                if (v["text"] == null) (v["translate"] as? String)?.let { sb.append(it) }
                for (x in (v["extra"] as? List<*>).orEmpty()) plainText(x, depth + 1)?.let { sb.append(it) }
                sb.toString()
            }
            is List<*> -> v.mapNotNull { plainText(it, depth + 1) }.joinToString("")
            else -> null
        }
        return cleanText(s)?.take(300)
    }

    fun minecraftFor(format: Int?): String? = PackFormats.describe(format)
}

// ---------------------------------------------------------------------------------------------------- MANIFEST.MF

internal object JarManifestReader {
    /** Solo la sezione principale, con le righe di continuazione ricomposte. */
    fun parseMain(text: String): Map<String, String> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = LinkedHashMap<String, String>()
        var lastKey: String? = null
        for (line in lines) {
            if (line.isEmpty()) {
                if (result.isNotEmpty()) break else continue
            }
            if (line[0] == ' ') {
                val k = lastKey
                if (k != null) result[k] = (result[k] ?: "") + line.substring(1)
                continue
            }
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx)
            result[key] = line.substring(idx + 1).trim()
            lastKey = key
        }
        return result
    }
}
