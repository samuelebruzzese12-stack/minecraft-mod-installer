package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Un identificatore Java «namespace:percorso» (es. `farmersdelight:tomato`, `minecraft:block/cube_all`). */
data class Rl(val ns: String, val path: String) : Comparable<Rl> {
    override fun toString(): String = "$ns:$path"
    override fun compareTo(other: Rl): Int = toString().compareTo(other.toString())

    companion object {
        /** «stick» vale «minecraft:stick»; i caratteri non ammessi da Minecraft rendono null. */
        fun parse(text: String?, defaultNs: String = "minecraft"): Rl? {
            if (text.isNullOrBlank()) return null
            val t = text.trim()
            val i = t.indexOf(':')
            val ns = if (i < 0) defaultNs else t.substring(0, i)
            val path = if (i < 0) t else t.substring(i + 1)
            if (ns.isEmpty() || path.isEmpty()) return null
            if (!ns.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }) return null
            if (!path.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' || it == '/' }) return null
            return Rl(ns, path)
        }
    }
}

/** I limiti della lettura di una mod: oltre si ignora il file (e il rapporto lo dice). */
data class ConvertLimits(
    val maxJarBytes: Long = 1024L * 1024 * 1024,
    val maxEntries: Int = 200_000,
    val maxJsonBytes: Int = 4 * 1024 * 1024,
    val maxPngBytes: Int = 16 * 1024 * 1024,
    val maxNestedJarBytes: Int = 32 * 1024 * 1024,
    val maxNestedJars: Int = 64,
    /** Somma dei file letti in memoria per i JAR annidati. */
    val maxNestedTotalBytes: Long = 64L * 1024 * 1024
)

/**
 * Il contenuto di una mod Java visto come file: solo le voci che servono alla conversione (`assets/`, `data/`, i descrittori),
 * lette su richiesta dal JAR o, per i JAR annidati (Fabric «jar in jar»), da una copia in memoria. Non esegue nulla.
 */
class JavaJar private constructor(
    val label: String,
    private val zip: ZipFile?,
    private val memory: Map<String, ByteArray>,
    names: List<String>,
    val nested: List<JavaJar>,
    val classCount: Int,
    val notes: List<String>
) : java.io.Closeable {

    val names: Set<String> = LinkedHashSet(names)

    fun has(name: String): Boolean = name in names || nested.any { it.has(name) }

    /** Tutti i nomi, anche quelli dei JAR annidati. */
    fun allNames(): Sequence<String> = names.asSequence() + nested.asSequence().flatMap { it.allNames() }

    fun bytes(name: String, max: Int): ByteArray? {
        if (name in names) {
            memory[name]?.let { return if (it.size <= max) it else null }
            val z = zip ?: return null
            val e = z.getEntry(name) ?: return null
            if (e.size > max) return null
            return try {
                z.getInputStream(e).use { readLimited(it, max) }
            } catch (ex: IOException) {
                null
            }
        }
        for (n in nested) n.bytes(name, max)?.let { return it }
        return null
    }

    fun text(name: String, max: Int): String? = bytes(name, max)?.let { String(it, Charsets.UTF_8) }

    /** Il JSON di una voce, o null se manca o non si legge. */
    fun json(name: String, max: Int): Any? = text(name, max)?.let {
        try {
            Json.parse(it)
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        try {
            zip?.close()
        } catch (_: IOException) {
        }
        nested.forEach { it.close() }
    }

    companion object {

        private fun wanted(name: String): Boolean =
            name.startsWith("assets/") || name.startsWith("data/") || name == "fabric.mod.json" || name == "quilt.mod.json" ||
                name == "META-INF/mods.toml" || name == "META-INF/neoforge.mods.toml" || name == "mcmod.info" ||
                name == "pack.mcmeta" || (name.endsWith(".png") && !name.contains('/')) || name.endsWith("mixins.json")

        fun open(file: File, label: String, limits: ConvertLimits, cancel: CancelCheck): JavaJar {
            if (file.length() > limits.maxJarBytes) throw IOException("Il file «$label» è troppo grande per la conversione")
            val zip = ZipFile(file)
            try {
                val names = ArrayList<String>()
                val nested = ArrayList<JavaJar>()
                val notes = ArrayList<String>()
                var classes = 0
                var count = 0
                var nestedBytes = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (++count > limits.maxEntries) {
                        notes.add("Il JAR ha più di ${limits.maxEntries} voci: le altre non sono state lette.")
                        break
                    }
                    if (count % 2000 == 0) cancel()
                    if (e.isDirectory) continue
                    val n = e.name
                    if (n.endsWith(".class")) {
                        classes++
                    } else if (wanted(n)) {
                        names.add(n)
                    } else if (n.startsWith("META-INF/jars/") && n.endsWith(".jar") && nested.size < limits.maxNestedJars) {
                        if (e.size in 1..limits.maxNestedJarBytes.toLong() && nestedBytes + e.size <= limits.maxNestedTotalBytes) {
                            nestedBytes += e.size
                            val inner = try {
                                zip.getInputStream(e).use { readLimited(it, limits.maxNestedJarBytes) }
                            } catch (ex: IOException) {
                                null
                            }
                            if (inner != null) fromBytes(inner, n.substringAfterLast('/'), limits, cancel)?.let { nested.add(it) }
                        }
                    }
                }
                return JavaJar(label, zip, emptyMap(), names, nested, classes + nested.sumOf { it.classCount }, notes)
            } catch (t: Throwable) {
                zip.close()
                throw t
            }
        }

        /** Un JAR annidato: si tengono in memoria solo le voci utili. */
        private fun fromBytes(bytes: ByteArray, label: String, limits: ConvertLimits, cancel: CancelCheck): JavaJar? {
            val memory = LinkedHashMap<String, ByteArray>()
            var classes = 0
            var count = 0
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    var e: ZipEntry? = zin.nextEntry
                    while (e != null) {
                        if (++count > limits.maxEntries) break
                        if (count % 2000 == 0) cancel()
                        val n = e.name
                        if (!e.isDirectory) {
                            if (n.endsWith(".class")) classes++
                            else if (wanted(n)) {
                                val max = if (n.endsWith(".png")) limits.maxPngBytes else limits.maxJsonBytes
                                readLimited(zin, max)?.let { memory[n] = it }
                            }
                        }
                        e = zin.nextEntry
                    }
                }
            } catch (ex: IOException) {
                return null
            }
            return JavaJar(label, null, memory, memory.keys.toList(), emptyList(), classes, emptyList())
        }

        /** Per le prove: un JAR fatto di voci in memoria. */
        fun ofEntries(label: String, entries: Map<String, ByteArray>, classCount: Int = 0): JavaJar =
            JavaJar(label, null, entries, entries.keys.filter { wanted(it) }, emptyList(), classCount, emptyList())

        internal fun readLimited(input: InputStream, max: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/** Il nome e l'identificatore della mod, letti dai descrittori (senza eseguire nulla). */
class ModIdentity(val modId: String?, val name: String?, val version: String?, val loader: String?, val iconPath: String?)

internal object ModIdentityReader {

    private val TOML_ID = Regex("""(?m)^\s*modId\s*=\s*"([^"]+)"""")
    private val TOML_NAME = Regex("""(?m)^\s*displayName\s*=\s*"([^"]+)"""")
    private val TOML_VERSION = Regex("""(?m)^\s*version\s*=\s*"([^"]+)"""")
    private val TOML_LOGO = Regex("""(?m)^\s*logoFile\s*=\s*"([^"]+)"""")

    fun read(jar: JavaJar): ModIdentity {
        (jar.json("fabric.mod.json", 1 shl 20) ?: jar.json("quilt.mod.json", 1 shl 20))?.asJsonObject()?.let { o ->
            val q = o["quilt_loader"].asJsonObject()
            val meta = q?.get("metadata").asJsonObject()
            val icon = when (val i = o["icon"] ?: meta?.get("icon")) {
                is String -> i
                is Map<*, *> -> i.values.lastOrNull() as? String
                else -> null
            }
            return ModIdentity(
                (o["id"] ?: q?.get("id")) as? String,
                (o["name"] ?: meta?.get("name")) as? String,
                (o["version"] ?: q?.get("version")) as? String,
                if (q != null) "Quilt" else "Fabric",
                icon
            )
        }
        for ((file, loader) in listOf("META-INF/neoforge.mods.toml" to "NeoForge", "META-INF/mods.toml" to "Forge")) {
            val t = jar.text(file, 1 shl 20) ?: continue
            fun clean(v: String?) = v?.takeUnless { it.contains("\${") }
            return ModIdentity(
                clean(TOML_ID.find(t)?.groupValues?.get(1)), clean(TOML_NAME.find(t)?.groupValues?.get(1)),
                clean(TOML_VERSION.find(t)?.groupValues?.get(1)), loader, clean(TOML_LOGO.find(t)?.groupValues?.get(1))
            )
        }
        jar.json("mcmod.info", 1 shl 20)?.let { v ->
            val o = (v.asJsonList()?.firstOrNull() ?: v.asJsonObject()?.get("modList").asJsonList()?.firstOrNull()).asJsonObject()
            if (o != null) return ModIdentity(o["modid"] as? String, o["name"] as? String, o["version"] as? String, "Forge", o["logoFile"] as? String)
        }
        return ModIdentity(null, null, null, null, null)
    }
}
