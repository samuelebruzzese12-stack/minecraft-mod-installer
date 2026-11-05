package com.mmi.modinstaller.convert.code

import com.mmi.modinstaller.bytecode.Budget
import com.mmi.modinstaller.bytecode.ClassFile
import com.mmi.modinstaller.bytecode.ClassSource
import com.mmi.modinstaller.bytecode.Insns
import com.mmi.modinstaller.bytecode.Interp
import com.mmi.modinstaller.bytecode.Obj
import com.mmi.modinstaller.bytecode.Op
import com.mmi.modinstaller.bytecode.Unk
import com.mmi.modinstaller.bytecode.V
import com.mmi.modinstaller.convert.JavaJar
import java.io.IOException

/** Le classi di una mod lette dal suo JAR, con i nomi di Minecraft portati a quelli di Mojang. */
internal class JarClassSource(private val jar: JavaJar) : ClassSource {
    private val entries: Set<String> = jar.classEntries.toHashSet()
    private val cache = HashMap<String, ClassFile?>()
    var parsed = 0
        private set
    val unreadable = ArrayList<String>()

    val classNames: List<String> get() = jar.classEntries.filter { !it.startsWith("META-INF/") }.map { it.removeSuffix(".class") }

    fun has(name: String) = "$name.class" in entries

    /** Lettura veloce (senza il codice dei metodi, senza memoria): per decidere se la classe va seguita. */
    fun header(name: String): ClassFile? = try {
        jar.classBytes("$name.class")?.let { ClassFile.parse(it, McNames, withCode = false) }
    } catch (e: IOException) {
        unreadable.add(name)
        null
    }

    override fun find(name: String): ClassFile? {
        if (cache.containsKey(name)) return cache[name]
        val entry = "$name.class"
        val cf = if (entry !in entries) null else try {
            jar.classBytes(entry)?.let { ClassFile.parse(it, McNames, withCode = true).also { parsed++ } }
        } catch (e: IOException) {
            unreadable.add(name)
            null
        }
        cache[name] = cf
        return cf
    }
}

/** Esito dell'analisi del codice di una mod. */
class CodeAnalysis internal constructor(
    internal val semantics: CodeSemantics,
    internal val interp: Interp,
    internal val source: JarClassSource,
    /** Classi della mod lette, classi da cui partire, metodi seguiti. */
    val classCount: Int,
    val entryClasses: Int,
    val entryMethods: Int,
    /** L'analisi si è fermata per il limite di passi (mod enorme): i risultati sono parziali. */
    val exhausted: Boolean,
    val problems: List<String>
) {
    val registrations: List<CodeRegistration> get() = semantics.registrations

    /** Le registrazioni senza doppioni (stesso registro e identificatore: vale la prima). */
    val unique: List<CodeRegistration> by lazy {
        val seen = HashSet<String>()
        semantics.registrations.filter { seen.add(it.registry + "|" + it.id) }
    }

    fun byRegistry(name: String): List<CodeRegistration> = unique.filter { it.registry == name }

    /** Quante registrazioni per registro (es. item → 120), per il rapporto. */
    val counts: Map<String, Int> get() = unique.groupingBy { it.registry }.eachCount().toSortedMap()

    /** L'identificatore con cui un valore è stato registrato. */
    fun idOf(v: V?): com.mmi.modinstaller.convert.Rl? = (v as? Obj)?.let { semantics.byValue[it]?.id }
}

/**
 * Analisi statica del bytecode di una mod: trova le classi che registrano contenuto (o renderer, attributi…), ne legge
 * l'inizializzazione e i metodi che registrano, e raccoglie le registrazioni con i valori costruiti. Il codice non viene mai
 * eseguito: lo legge l'interprete simbolico ([Interp]).
 */
object CodeAnalyzer {

    /** Quanti punti d'ingresso della mod sono stati trovati nell'ultima analisi (per le prove). */
    @Volatile var rootCount = 0
        private set

    /**
     * I punti d'ingresso come li usa il caricatore: «entrypoints» di fabric.mod.json / quilt.mod.json, le classi con @Mod
     * (Forge/NeoForge) di cui si legge il costruttore.
     */
    internal fun roots(jar: JavaJar, source: JarClassSource): List<Pair<ClassFile, com.mmi.modinstaller.bytecode.MethodInfo>> {
        val out = ArrayList<Pair<ClassFile, com.mmi.modinstaller.bytecode.MethodInfo>>()
        fun add(className: String, method: String?, defaults: List<String>) {
            val cf = source.find(className.replace('.', '/')) ?: return
            val names = if (method != null) listOf(method) else defaults
            for (n in names) cf.methods.firstOrNull { it.name == n && it.code != null }?.let { out.add(cf to it) }
        }
        fun entries(v: Any?): List<String> = when (v) {
            is String -> listOf(v)
            is Map<*, *> -> listOfNotNull(v["value"] as? String)
            is List<*> -> v.flatMap { entries(it) }
            else -> emptyList()
        }
        val fabric = jar.json("fabric.mod.json", 1 shl 20) as? Map<*, *>
        val quilt = (jar.json("quilt.mod.json", 1 shl 20) as? Map<*, *>)?.get("quilt_loader") as? Map<*, *>
        val ep = (fabric?.get("entrypoints") ?: quilt?.get("entrypoints")) as? Map<*, *>
        if (ep != null) {
            val defaults = mapOf(
                "main" to listOf("onInitialize"), "init" to listOf("onInitialize"), "client" to listOf("onInitializeClient"),
                "client_init" to listOf("onInitializeClient"), "server" to listOf("onInitializeServer"), "server_init" to listOf("onInitializeServer")
            )
            for ((k, v) in ep) {
                val d = defaults[k as? String ?: continue] ?: continue
                for (e in entries(v)) {
                    val cls = e.substringBefore("::")
                    val m = e.substringAfter("::", "").ifEmpty { null }
                    add(cls, m, d)
                }
            }
        }
        // Forge/NeoForge: le classi con @Mod (solo quelle: le altre si raggiungono da lì o dalle registrazioni).
        for (name in source.classNames) {
            val head = source.header(name) ?: continue
            if (head.annotations.none { it.endsWith("/fml/common/Mod;") }) continue
            val cf = source.find(name) ?: continue
            cf.methods.filter { it.name == "<init>" && it.code != null }.maxByOrNull { com.mmi.modinstaller.bytecode.Desc.params(it.desc).size }?.let { out.add(cf to it) }
        }
        return out
    }

    /** Passi totali: una base più una quota per classe (le mod grandi hanno più codice da leggere). */
    fun budgetFor(jar: JavaJar): Budget = Budget(steps = 6_000_000L + 4_000L * jar.classEntries.size)

    fun analyze(jar: JavaJar, budget: Budget = budgetFor(jar), cancel: () -> Unit = {}): CodeAnalysis {
        val source = JarClassSource(jar)
        val sem = CodeSemantics()
        val ip = Interp(source, sem, budget)
        val problems = ArrayList<String>()
        val entryClasses = ArrayList<ClassFile>()
        val entryMethods = ArrayList<Pair<ClassFile, com.mmi.modinstaller.bytecode.MethodInfo>>()
        var scanned = 0
        for ((i, name) in source.classNames.withIndex()) {
            if (i % 200 == 0) cancel()
            val head = source.header(name) ?: continue
            scanned++
            val refs = try { head.pool.methodRefs() } catch (e: IOException) { continue }
            if (refs.none { CodeSemantics.isEntryRef(it) }) continue
            val cf = source.find(name) ?: continue
            entryClasses.add(cf)
            for (m in cf.methods) {
                val code = m.code ?: continue
                if (m.name == "<clinit>") continue
                val insns = try { Insns.decode(code.bytes) } catch (e: IOException) { continue }
                val calls = insns.any { ins ->
                    (ins.op == Op.INVOKESTATIC || ins.op == Op.INVOKEVIRTUAL || ins.op == Op.INVOKEINTERFACE || ins.op == Op.INVOKESPECIAL) &&
                        try { CodeSemantics.isEntryRef(cf.pool.ref(ins.a)) } catch (e: IOException) { false }
                }
                if (calls) entryMethods.add(cf to m)
            }
        }
        var exhausted = false
        var truncated = 0
        val roots = roots(jar, source)
        /** Ogni ingresso ha una fetta dei passi: uno troppo pesante si ferma da solo e non toglie spazio agli altri. */
        fun <T> slice(limit: Long, block: () -> T): T? {
            val total = budget.steps
            if (total <= 0) throw Budget.Exhausted()
            val mine = minOf(limit, total)
            budget.steps = mine
            try {
                return block()
            } catch (e: Budget.Exhausted) {
                truncated++
                return null
            } finally {
                val used = mine - maxOf(budget.steps, 0)
                budget.steps = total - used
            }
        }
        try {
            // Prima i punti d'ingresso della mod (come li chiamerebbe il caricatore), poi le classi che registrano.
            for ((cf, m) in roots) {
                cancel()
                try { slice(4_000_000L) {
                    val instance = Obj(cf.name)
                    if (m.name == "<init>") {
                        ip.construct(instance, com.mmi.modinstaller.bytecode.Ref(cf.name, "<init>", m.desc), List(com.mmi.modinstaller.bytecode.Desc.params(m.desc).size) { Unk }, 0)
                    } else {
                        if (!m.isStatic) ip.construct(instance, com.mmi.modinstaller.bytecode.Ref(cf.name, "<init>", "()V"), emptyList(), 0)
                        ip.run(cf, m, if (m.isStatic) null else instance, List(com.mmi.modinstaller.bytecode.Desc.params(m.desc).size) { Unk })
                    }
                } } catch (e: Budget.Exhausted) {
                    throw e
                } catch (e: IOException) {
                    problems.add("${cf.name.substringAfterLast('/')}.${m.name}: ${e.message}")
                } catch (e: RuntimeException) {
                    problems.add("${cf.name.substringAfterLast('/')}.${m.name}: ${e.javaClass.simpleName}")
                }
            }
            for (cf in entryClasses) {
                cancel()
                slice(1_500_000L) { ip.ensureInit(cf.name, 0) }
            }
            for ((cf, m) in entryMethods) {
                cancel()
                try {
                    val thisV = if (m.isStatic) null else Obj(cf.name).also { it.initialized = true }
                    slice(400_000L) { ip.run(cf, m, thisV, List(com.mmi.modinstaller.bytecode.Desc.params(m.desc).size) { Unk }) }
                } catch (e: Budget.Exhausted) {
                    throw e
                } catch (e: IOException) {
                    problems.add("${cf.name.substringAfterLast('/')}.${m.name}: ${e.message}")
                } catch (e: RuntimeException) {
                    problems.add("${cf.name.substringAfterLast('/')}.${m.name}: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Budget.Exhausted) {
            exhausted = true
        }
        problems.addAll(ip.problems.take(50))
        if (truncated > 0) problems.add("Parti di codice lette solo in parte (troppo lunghe): $truncated")
        rootCount = roots.size
        if (source.unreadable.isNotEmpty()) problems.add("Classi non leggibili: ${source.unreadable.size}")
        return CodeAnalysis(sem, ip, source, scanned, entryClasses.size, entryMethods.size, exhausted, problems)
    }
}
