package com.mmi.modinstaller.convert

/** Un file di suono da copiare nell'add-on: voce del JAR → percorso Bedrock (senza «.ogg»). */
internal class SoundFileOut(val mod: JavaModContent, val entry: String, val path: String, val volume: Double, val pitch: Double, val weight: Int, val stream: Boolean, val size: Long)

/** Un evento sonoro della mod come «sound definition» di Bedrock. */
internal class SoundOut(val mod: JavaModContent, val java: Rl, val id: String, val category: String, val files: List<SoundFileOut>)

/**
 * Suoni (BLOCKBRIDGE): gli eventi di `assets/<ns>/sounds.json` diventano le «sound definitions» del pacchetto di risorse, con
 * i file .ogg della mod (Bedrock li legge così come sono). I suoni delle creature letti dal codice (getAmbientSound,
 * getHurtSound, getDeathSound) vanno negli eventi della creatura. Oltre [MAX_BYTES] di file si tengono prima quelli delle
 * creature, poi gli altri finché c'è posto, e il rapporto dice quanti ne restano fuori.
 */
internal object SoundConverter {
    const val MAX_BYTES = 48L * 1024 * 1024

    fun bedrockName(rl: Rl): String = rl.ns + "." + rl.path.replace('/', '.')

    private fun category(rl: Rl): String {
        val p = rl.path
        return when {
            p.startsWith("block") -> "block"
            p.startsWith("music") || p.contains("record") || p.contains("disc") -> "record"
            p.startsWith("ambient") -> "ambient"
            p.startsWith("item") -> "player"
            p.contains("hostile") -> "hostile"
            else -> "neutral"
        }
    }

    class Result(val sounds: List<SoundOut>, val dropped: Map<JavaModContent, Int>)

    fun plan(mods: List<JavaModContent>, priority: Set<Rl>): Result {
        val all = LinkedHashMap<Rl, Pair<JavaModContent, JSound>>()
        for (m in mods) for ((k, v) in m.sounds) if (k !in all) all[k] = m to v
        fun files(rl: Rl, depth: Int): List<Pair<JSoundFile, JavaModContent>> {
            val (mod, s) = all[rl] ?: return emptyList()
            if (depth > 4) return emptyList()
            return s.files.flatMap { f -> if (f.event) files(f.name, depth + 1) else listOf(f to mod) }
        }
        val out = ArrayList<SoundOut>()
        val dropped = HashMap<JavaModContent, Int>()
        var total = 0L
        val order = all.keys.sortedBy { if (it in priority) 0 else 1 }
        for (rl in order) {
            val (mod, _) = all.getValue(rl)
            val list = ArrayList<SoundFileOut>()
            for ((f, owner) in files(rl, 0)) {
                val entry = "assets/${f.name.ns}/sounds/${f.name.path}.ogg"
                val jar = mods.firstOrNull { it.jar.has(entry) } ?: owner
                val size = jar.jar.size(entry) ?: continue
                list.add(SoundFileOut(jar, entry, "sounds/mmi/${f.name.ns}/${f.name.path}", f.volume, f.pitch, f.weight, f.stream, size))
            }
            if (list.isEmpty()) continue
            val bytes = list.distinctBy { it.entry }.sumOf { it.size }
            if (total + bytes > MAX_BYTES) { dropped[mod] = (dropped[mod] ?: 0) + 1; continue }
            total += bytes
            out.add(SoundOut(mod, rl, bedrockName(rl), category(rl), list))
        }
        return Result(out, dropped)
    }

    /** sounds/sound_definitions.json */
    fun definitions(sounds: List<SoundOut>): Map<String, Any?> {
        val defs = LinkedHashMap<String, Any?>()
        for (s in sounds) {
            defs[s.id] = linkedMapOf(
                "category" to s.category,
                "sounds" to s.files.map { f ->
                    val m = linkedMapOf<String, Any?>("name" to f.path)
                    if (f.volume != 1.0) m["volume"] = f.volume
                    if (f.pitch != 1.0) m["pitch"] = f.pitch
                    if (f.weight != 1) m["weight"] = f.weight.toLong()
                    if (f.stream) m["stream"] = true
                    m
                }
            )
        }
        return linkedMapOf("format_version" to "1.20.20", "sound_definitions" to defs)
    }

    /** sounds.json: i suoni delle creature (eventi «ambient», «hurt», «death»). */
    fun entitySounds(entities: Map<String, Map<String, String>>): Map<String, Any?> {
        val ents = LinkedHashMap<String, Any?>()
        for ((id, events) in entities) ents[id] = linkedMapOf("volume" to 1.0, "pitch" to listOf(0.8, 1.2), "events" to LinkedHashMap(events))
        return linkedMapOf("entity_sounds" to linkedMapOf("entities" to ents))
    }
}
