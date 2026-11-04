package com.mmi.modinstaller.jar

import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.JsonParseException
import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.obj
import com.mmi.modinstaller.core.str

/** Lettura (solo descrittiva) degli indici dei modpack: l'installazione vera e propria è un altro passo. */
internal object ModpackIndexReader {

    private fun parse(bytes: ByteArray): Map<String, Any?>? = try {
        Json.parse(String(bytes, Charsets.UTF_8)).asJsonObject()
    } catch (e: JsonParseException) {
        null
    }

    fun readModrinth(bytes: ByteArray, names: Set<String>): ModpackSummary? {
        val root = parse(bytes) ?: return null
        val files = root.list("files") ?: return null
        val deps = root.obj("dependencies").orEmpty()
        var loader: ModLoader? = null
        var loaderVersion: String? = null
        for ((key, value) in deps) {
            val l = when (key) {
                "fabric-loader" -> ModLoader.FABRIC
                "quilt-loader" -> ModLoader.QUILT
                "forge" -> ModLoader.FORGE
                "neoforge" -> ModLoader.NEOFORGE
                else -> null
            }
            if (l != null) {
                loader = l
                loaderVersion = value as? String
            }
        }
        val overrides = names.count {
            it.startsWith("overrides/") || it.startsWith("client-overrides/") || it.startsWith("server-overrides/")
        }
        return ModpackSummary(
            format = "modrinth",
            name = root.str("name"),
            version = root.str("versionId"),
            author = null,
            minecraft = deps["minecraft"] as? String,
            loader = loader,
            loaderVersion = loaderVersion,
            fileCount = files.size,
            overrideCount = overrides
        )
    }

    fun readCurseForge(bytes: ByteArray, names: Set<String>): ModpackSummary? {
        val root = parse(bytes) ?: return null
        val mc = root.obj("minecraft") ?: return null
        val files = root.list("files") ?: return null
        var loader: ModLoader? = null
        var loaderVersion: String? = null
        val loaders = mc.list("modLoaders").orEmpty().mapNotNull { it.asJsonObject() }
        val chosen = loaders.firstOrNull { it["primary"] == true } ?: loaders.firstOrNull()
        val id = chosen?.str("id")
        if (id != null) {
            val dash = id.indexOf('-')
            val name = if (dash > 0) id.substring(0, dash) else id
            loader = when (name.lowercase()) {
                "fabric" -> ModLoader.FABRIC
                "quilt" -> ModLoader.QUILT
                "forge" -> ModLoader.FORGE
                "neoforge" -> ModLoader.NEOFORGE
                else -> null
            }
            if (dash > 0) loaderVersion = id.substring(dash + 1)
        }
        val folder = (root.str("overrides") ?: "overrides").trim('/').ifEmpty { "overrides" }
        return ModpackSummary(
            format = "curseforge",
            name = root.str("name"),
            version = root.str("version"),
            author = root.str("author"),
            minecraft = mc.str("version"),
            loader = loader,
            loaderVersion = loaderVersion,
            fileCount = files.size,
            overrideCount = names.count { it.startsWith("$folder/") }
        )
    }
}
