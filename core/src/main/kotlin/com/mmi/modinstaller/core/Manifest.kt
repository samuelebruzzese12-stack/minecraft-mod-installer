package com.mmi.modinstaller.core

enum class PackKind { RESOURCE, BEHAVIOR, SKIN, WORLD_TEMPLATE, UNKNOWN }

data class ModuleInfo(val type: String?, val uuid: String?, val versionText: String, val language: String?)

data class DependencyInfo(val uuid: String?, val moduleName: String?, val versionText: String)

class PackManifest(
    val formatVersion: Int?,
    val name: String?,
    val description: String?,
    val uuid: String?,
    val version: McVersion?,
    val versionText: String,
    val minEngineVersion: McVersion?,
    val modules: List<ModuleInfo>,
    val dependencies: List<DependencyInfo>,
    val authors: List<String>,
    val license: String?,
    val url: String?,
    val capabilities: List<String>,
    val kind: PackKind
)

class ManifestResult(
    val manifest: PackManifest?,
    val findings: List<Finding>,
    /** False se il JSON non somiglia a un manifest Bedrock (es. un manifest.json di altro tipo). */
    val looksLikeBedrock: Boolean
)

object ManifestParser {
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val SEMVER_RE = Regex("^\\d+\\.\\d+\\.\\d+([-+].*)?$")

    val KNOWN_MODULE_TYPES: Set<String> = setOf(
        "resources", "data", "script", "client_data", "interface", "world_template", "skin_pack", "javascript"
    )

    fun isValidUuid(text: String?): Boolean = text != null && UUID_RE.matches(text)

    fun parse(text: String, where: String): ManifestResult {
        val root: Map<String, Any?> = try {
            Json.parseObject(text)
        } catch (e: JsonParseException) {
            return ManifestResult(
                null,
                listOf(
                    Finding(
                        Severity.ERROR, FindingCode.MANIFEST_INVALID,
                        "manifest.json non valido: ${e.message}", where
                    )
                ),
                true
            )
        }
        if (!root.containsKey("header") && !root.containsKey("modules") && !root.containsKey("format_version")) {
            return ManifestResult(null, emptyList(), false)
        }

        val findings = ArrayList<Finding>()
        fun error(code: FindingCode, msg: String) {
            findings.add(Finding(Severity.ERROR, code, msg, where))
        }
        fun warn(code: FindingCode, msg: String) {
            findings.add(Finding(Severity.WARNING, code, msg, where))
        }

        val formatVersion = (root["format_version"] as? Number)?.toInt()
        if (root["format_version"] == null) {
            warn(FindingCode.FORMAT_VERSION, "format_version mancante nel manifest")
        } else if (root["format_version"] is String) {
            // format_version 3 usa stringhe semver: accettato
        } else if (formatVersion == null || formatVersion !in 1..3) {
            warn(FindingCode.FORMAT_VERSION, "format_version sconosciuto: ${root["format_version"]}")
        }

        val header = root.obj("header")
        var name: String? = null
        var description: String? = null
        var uuid: String? = null
        var version: McVersion? = null
        var versionText = ""
        var minEngine: McVersion? = null
        if (header == null) {
            error(FindingCode.MANIFEST_FIELD, "Il manifest non contiene la sezione 'header'")
        } else {
            name = header.str("name")
            description = header.str("description")
            uuid = header.str("uuid")
            if (uuid == null) {
                error(FindingCode.UUID_INVALID, "UUID mancante in header.uuid")
            } else if (!isValidUuid(uuid)) {
                error(FindingCode.UUID_INVALID, "UUID non valido in header.uuid: $uuid")
            }
            val rawVersion = header["version"]
            version = McVersion.fromJson(rawVersion)
            versionText = versionToText(rawVersion)
            val versionOk = when (rawVersion) {
                is List<*> -> rawVersion.size == 3 && version != null
                is String -> SEMVER_RE.matches(rawVersion.trim())
                else -> false
            }
            if (!versionOk) {
                error(FindingCode.VERSION_INVALID, "header.version mancante o non valida (serve [major, minor, patch])")
            }
            val rawMin = header["min_engine_version"]
            if (rawMin != null) {
                minEngine = McVersion.fromJson(rawMin)
                if (minEngine == null) warn(FindingCode.MANIFEST_FIELD, "min_engine_version non interpretabile: $rawMin")
            }
            if (name.isNullOrBlank()) warn(FindingCode.MANIFEST_FIELD, "header.name mancante: il pacchetto non avrà un nome")
        }

        val modules = ArrayList<ModuleInfo>()
        val rawModules = root.list("modules")
        if (rawModules == null || rawModules.isEmpty()) {
            error(FindingCode.MANIFEST_FIELD, "Il manifest non contiene moduli ('modules')")
        } else {
            for ((idx, m) in rawModules.withIndex()) {
                val mo = m.asJsonObject()
                if (mo == null) {
                    error(FindingCode.MANIFEST_FIELD, "modules[$idx] non è un oggetto")
                    continue
                }
                val type = mo.str("type")
                val mUuid = mo.str("uuid")
                val mVersionRaw = mo["version"]
                if (type == null) {
                    error(FindingCode.MANIFEST_FIELD, "modules[$idx].type mancante")
                } else if (type !in KNOWN_MODULE_TYPES) {
                    warn(FindingCode.MODULE_UNKNOWN, "Tipo di modulo non riconosciuto: '$type'")
                }
                if (mUuid == null || !isValidUuid(mUuid)) {
                    error(FindingCode.UUID_INVALID, "modules[$idx].uuid mancante o non valido")
                } else if (uuid != null && mUuid.equals(uuid, ignoreCase = true)) {
                    warn(
                        FindingCode.MODULE_UUID_SAME,
                        "L'UUID del modulo coincide con quello del pacchetto (dovrebbero essere diversi)"
                    )
                }
                modules.add(ModuleInfo(type, mUuid, versionToText(mVersionRaw), mo.str("language")))
            }
        }

        val deps = ArrayList<DependencyInfo>()
        val rawDeps = root.list("dependencies")
        if (rawDeps != null) {
            for ((idx, d) in rawDeps.withIndex()) {
                val dobj = d.asJsonObject()
                if (dobj == null) {
                    warn(FindingCode.MANIFEST_FIELD, "dependencies[$idx] non è un oggetto")
                    continue
                }
                val dUuid = dobj.str("uuid")
                val dModule = dobj.str("module_name")
                if (dUuid == null && dModule == null) {
                    warn(FindingCode.MANIFEST_FIELD, "dependencies[$idx] senza uuid né module_name")
                    continue
                }
                if (dUuid != null && !isValidUuid(dUuid)) {
                    warn(FindingCode.UUID_INVALID, "dependencies[$idx].uuid non valido: $dUuid")
                }
                deps.add(DependencyInfo(dUuid, dModule, versionToText(dobj["version"])))
            }
        }

        val metadata = root.obj("metadata")
        val authors = ArrayList<String>()
        val rawAuthors = metadata?.get("authors")
        if (rawAuthors is String) {
            if (rawAuthors.isNotBlank()) authors.add(rawAuthors.trim())
        } else if (rawAuthors is List<*>) {
            for (a in rawAuthors) if (a is String && a.isNotBlank()) authors.add(a.trim())
        }
        val capabilities = ArrayList<String>()
        val rawCaps = root.list("capabilities")
        if (rawCaps != null) for (c in rawCaps) if (c is String) capabilities.add(c)

        val kind = classify(modules)
        if (modules.any { it.type == "editorExtension" || it.type == "editor_extension" }) {
            warn(FindingCode.EDITOR_EXTENSION, "Estensione per l'Editor di Minecraft: richiede la modalità Editor")
        }

        val manifest = PackManifest(
            formatVersion, name, description, uuid, version, versionText, minEngine, modules, deps,
            authors, metadata?.str("license"), metadata?.str("url"), capabilities, kind
        )
        return ManifestResult(manifest, findings, true)
    }

    fun classify(modules: List<ModuleInfo>): PackKind {
        val types = HashSet<String>()
        for (m in modules) m.type?.let { types.add(it.lowercase()) }
        return when {
            "world_template" in types -> PackKind.WORLD_TEMPLATE
            "skin_pack" in types -> PackKind.SKIN
            types.any { it == "data" || it == "script" || it == "javascript" } -> PackKind.BEHAVIOR
            types.any { it == "resources" || it == "interface" || it == "client_data" } -> PackKind.RESOURCE
            else -> PackKind.UNKNOWN
        }
    }

    private fun versionToText(raw: Any?): String = when (raw) {
        is List<*> -> raw.joinToString(".") { (it as? Number)?.toLong()?.toString() ?: "?" }
        is String -> raw
        null -> ""
        else -> raw.toString()
    }
}

/** File di localizzazione `texts/en_US.lang` (chiave=valore) per risolvere nomi tipo `pack.name`. */
object LangFile {
    fun parse(bytes: ByteArray): Map<String, String> {
        val map = HashMap<String, String>()
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).substringBefore("\t#").trim()
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }

    /** Se [name] è una chiave presente nel file lingua restituisce la traduzione, altrimenti [name]. */
    fun resolve(name: String?, lang: Map<String, String>?): String? {
        if (name == null) return null
        if (lang == null) return name
        return lang[name]?.takeIf { it.isNotBlank() } ?: name
    }
}
