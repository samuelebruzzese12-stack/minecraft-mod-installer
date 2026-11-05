package com.mmi.modinstaller.convert.code

import com.mmi.modinstaller.bytecode.NameMapper
import java.util.zip.GZIPInputStream

/**
 * Porta ai nomi di Mojang i nomi con cui le mod citano Minecraft nel bytecode:
 *  - Fabric/Quilt: «intermediary» (net/minecraft/class_1792, method_7889, field_41178);
 *  - Forge 1.17–1.20: classi con i nomi di Mojang, membri «SRG» (m_41487_, f_256969_);
 *  - NeoForge 1.20.5+: già i nomi di Mojang (nessuna traduzione).
 * I due schemi non si confondono mai con i nomi veri, quindi la stessa tabella vale per tutte le mod. La tabella
 * (mc_names.txt.gz, generata da ci/make_mc_names.py) copre solo i pacchetti di Minecraft che servono alla conversione.
 */
object McNames : NameMapper {

    private class Tables(val classes: Map<String, String>, val methods: Map<Int, String>, val fields: Map<Int, String>, val srgMethods: Map<Int, String>, val srgFields: Map<Int, String>)

    private val tables: Tables by lazy { load() }

    private fun load(): Tables {
        val classes = HashMap<String, String>(8192)
        val methods = HashMap<Int, String>(32768)
        val fields = HashMap<Int, String>(32768)
        val srgM = HashMap<Int, String>(32768)
        val srgF = HashMap<Int, String>(32768)
        val stream = McNames::class.java.getResourceAsStream("/com/mmi/modinstaller/convert/mc_names.txt.gz")
            ?: return Tables(classes, methods, fields, srgM, srgF)
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val a = line.indexOf('\t'); val b = line.indexOf('\t', a + 1)
                if (a < 0 || b < 0) continue
                val key = line.substring(a + 1, b); val value = line.substring(b + 1)
                when (line[0]) {
                    'C' -> classes["net/minecraft/$key"] = value
                    'm' -> key.toIntOrNull()?.let { methods[it] = value }
                    'f' -> key.toIntOrNull()?.let { fields[it] = value }
                    'M' -> key.toIntOrNull()?.let { srgM[it] = value }
                    'F' -> key.toIntOrNull()?.let { srgF[it] = value }
                }
            }
        }
        return Tables(classes, methods, fields, srgM, srgF)
    }

    /** Quante voci ha la tabella (per la diagnostica). */
    val size: Int get() = tables.classes.size + tables.methods.size + tables.fields.size + tables.srgMethods.size + tables.srgFields.size

    override fun cls(name: String): String {
        if (!name.startsWith("net/minecraft/class_")) return name
        tables.classes[name]?.let { return it }
        // Classe interna non in tabella: si traduce la parte esterna.
        val d = name.indexOf('$')
        if (d > 0) tables.classes[name.substring(0, d)]?.let { return it + name.substring(d) }
        return name
    }

    override fun method(name: String): String {
        if (name.length > 7 && name.startsWith("method_")) {
            name.substring(7).toIntOrNull()?.let { id -> tables.methods[id]?.let { return it } }
        } else if (name.length > 3 && name.startsWith("m_") && name.endsWith("_")) {
            name.substring(2, name.length - 1).toIntOrNull()?.let { id -> tables.srgMethods[id]?.let { return it } }
        }
        return name
    }

    override fun field(name: String): String {
        if (name.length > 6 && name.startsWith("field_")) {
            name.substring(6).toIntOrNull()?.let { id -> tables.fields[id]?.let { return it } }
        } else if (name.length > 3 && name.startsWith("f_") && name.endsWith("_")) {
            name.substring(2, name.length - 1).toIntOrNull()?.let { id -> tables.srgFields[id]?.let { return it } }
        }
        return name
    }
}
