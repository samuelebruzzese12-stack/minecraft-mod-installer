package com.mmi.modinstaller.bytecode

import java.io.IOException
import java.util.IdentityHashMap

// ------------------------------------------------------------------------------------------------------------------ valori

/**
 * Valore astratto: quello che l'interprete sa di un dato mentre «legge» il codice. Non è mai un oggetto Java vero: il codice
 * della mod non viene eseguito, se ne seguono soltanto i passaggi con valori simbolici.
 */
sealed class V

/** Valore sconosciuto (dipende da qualcosa che l'analisi non può sapere). */
object Unk : V() { override fun toString() = "?" }

/** Seconda metà di un long/double sullo stack o nelle variabili (come nella JVM). */
object Top : V() { override fun toString() = "⊤" }

/** Risultato di un metodo void «gestito». */
object VoidV : V() { override fun toString() = "void" }

object NullV : V() { override fun toString() = "null" }

class IntV(val v: Int) : V() { override fun toString() = v.toString() }
class LongV(val v: Long) : V() { override fun toString() = v.toString() + "L" }
class FloatV(val v: Float) : V() { override fun toString() = v.toString() + "f" }
class DoubleV(val v: Double) : V() { override fun toString() = v.toString() + "d" }
class StrV(val v: String) : V() { override fun toString() = "\"" + v + "\"" }
class ClassV(val name: String) : V() { override fun toString() = "$name.class" }

/** Un campo statico di una classe che non è della mod (es. Items.WHEAT, Tiers.IRON, SoundType.METAL). */
class StaticV(val owner: String, val name: String, val desc: String) : V() {
    override fun toString() = owner.substringAfterLast('/') + "." + name
}

/** Una chiamata registrata: proprietario, nome, descrittore, argomenti. */
class Call(val owner: String, val name: String, val desc: String, val args: List<V>) {
    override fun toString() = owner.substringAfterLast('/') + "." + name + args.joinToString(", ", "(", ")")
}

/**
 * Un oggetto: creato con «new» ([args] del costruttore della sua classe, [superCall] il primo costruttore non della mod
 * raggiunto) oppure restituito da una chiamata ([origin], con [originReceiver]). [calls] sono i metodi «builder» chiamati su di
 * lui (che restituiscono l'oggetto stesso), [fields] i campi scritti dal codice letto.
 */
class Obj(val type: String) : V() {
    var args: List<V> = emptyList()
    var superCall: Call? = null
    var origin: Call? = null
    var originReceiver: V? = null
    val calls = ArrayList<Call>()
    val fields = LinkedHashMap<String, V>()
    var initialized = false
    /** Dati aggiunti dalla semantica (es. l'identificatore con cui l'oggetto è stato registrato). */
    var ext: Any? = null

    override fun toString(): String {
        val t = type.substringAfterLast('/')
        return when {
            origin != null -> "[$t ← $origin]"
            else -> "new $t" + args.joinToString(", ", "(", ")") + (if (calls.isEmpty()) "" else calls.joinToString("", ".") { it.name + it.args.joinToString(",", "(", ")") })
        }
    }
}

/** Una lambda o un riferimento a metodo («Item::new»): il metodo che la realizza e i valori catturati. */
class LamV(val impl: Handle, val captured: List<V>, val iface: String, val ifaceMethod: String) : V() {
    override fun toString() = "λ" + impl.owner.substringAfterLast('/') + "." + impl.name
}

class ArrV(val elemDesc: String, val items: Array<V>) : V() {
    override fun toString() = items.joinToString(", ", "[", "]")
}

/** Più valori possibili (es. un metodo che restituisce cose diverse secondo le condizioni). */
class AltV(val options: List<V>) : V() { override fun toString() = options.joinToString(" | ", "<", ">") }

/** Valore definito dalla semantica (es. un identificatore «mod:nome» già riconosciuto). */
class ExtV(val value: Any) : V() { override fun toString() = "ext($value)" }

fun V.int(): Int? = when (this) { is IntV -> v; is LongV -> v.toInt(); is FloatV -> v.toInt(); is DoubleV -> v.toInt(); else -> null }
fun V.num(): Double? = when (this) { is IntV -> v.toDouble(); is LongV -> v.toDouble(); is FloatV -> v.toDouble(); is DoubleV -> v; else -> null }
fun V.str(): String? = (this as? StrV)?.v

// ------------------------------------------------------------------------------------------------------------------ ambiente

/** Le classi della mod (con il codice), lette al bisogno. Le classi di Minecraft e delle librerie non ci sono. */
interface ClassSource {
    fun find(name: String): ClassFile?
}

/**
 * La conoscenza di Minecraft e dei caricatori: riconosce le chiamate che contano (registrazioni, costruttori di proprietà…).
 * Restituire null significa «non lo conosco»: l'interprete applica le regole generali.
 */
interface Semantics {
    fun invoke(ip: Interp, op: Int, ref: Ref, receiver: V?, args: List<V>, depth: Int): V? = null
    fun getStatic(ip: Interp, ref: Ref): V? = null
    fun putStatic(ip: Interp, ref: Ref, value: V) {}
    /** Un costruttore non della mod; true = gestito dalla semantica. */
    fun construct(ip: Interp, obj: Obj, ref: Ref, args: List<V>, depth: Int): Boolean = false
}

/** Limiti dell'analisi, perché una mod enorme o costruita male non blocchi il telefono. */
class Budget(var steps: Long = 4_000_000L, val maxDepth: Int = 14, val maxArray: Int = 4096) {
    class Exhausted : RuntimeException("Limite dell'analisi del codice raggiunto")
}

private class Frame(val locals: Array<V>, val stack: ArrayList<V>) {
    fun copy() = Frame(locals.copyOf(), ArrayList(stack))
}

/**
 * Interprete simbolico del bytecode: segue un metodo istruzione per istruzione con valori astratti. I salti condizionati non
 * vengono decisi: si seguono entrambe le strade e dove si riuniscono i valori diversi diventano sconosciuti. I cicli si leggono
 * una volta, con le variabili che cambiano nel ciclo sconosciute. Le chiamate a metodi della mod si seguono (fino a
 * [Budget.maxDepth]), quelle a Minecraft si registrano come [Call] negli oggetti o come origine dei valori restituiti.
 */
class Interp(val classes: ClassSource, val sem: Semantics, val budget: Budget = Budget()) {

    /** Valori dei campi statici scritti dal codice letto («proprietario.nome»). */
    val statics = HashMap<String, V>()
    private val clinitDone = HashSet<String>()
    private val decoded = IdentityHashMap<MethodInfo, List<Insn>>()
    private val loops = IdentityHashMap<MethodInfo, Map<Int, LoopInfo>>()
    /** Classe e metodo in lettura (per sapere da dove arriva una registrazione). */
    val callStack = ArrayList<Pair<ClassFile, MethodInfo>>()
    val problems = ArrayList<String>()

    private class LoopInfo(val locals: Set<Int>, val bodyStart: Boolean)

    // -------------------------------------------------------------------------------------------------------- ingressi

    /** Legge [m] di [cls] con [thisV] e [args]; restituisce il valore restituito (o [Unk]). */
    fun run(cls: ClassFile, m: MethodInfo, thisV: V?, args: List<V>, depth: Int = 0): V {
        val code = m.code ?: return Unk
        if (depth > budget.maxDepth) return Unk
        if (callStack.size > budget.maxDepth * 2) return Unk
        val insns = decoded.getOrPut(m) { Insns.decode(code.bytes) }
        val loopInfo = loops.getOrPut(m) { loopsOf(insns) }
        val locals = Array<V>(maxOf(code.maxLocals, 1)) { Unk }
        var slot = 0
        if (!m.isStatic) locals[slot++] = thisV ?: Unk
        val params = Desc.params(m.desc)
        for ((i, p) in params.withIndex()) {
            if (slot >= locals.size) break
            locals[slot++] = args.getOrNull(i) ?: Unk
            if (Desc.isWide(p) && slot < locals.size) locals[slot++] = Top
        }
        callStack.add(cls to m)
        try {
            return sweep(cls, m, insns, loopInfo, Frame(locals, ArrayList()), code, depth)
        } finally {
            callStack.removeAt(callStack.size - 1)
        }
    }

    /** Inizializzazione statica di una classe della mod (una volta sola, come nella JVM). */
    fun ensureInit(owner: String, depth: Int) {
        if (!clinitDone.add(owner)) return
        val cf = classes.find(owner) ?: return
        cf.superName?.let { ensureInit(it, depth) }
        for (f in cf.fields) if (f.isStatic && f.constant != null) statics.putIfAbsent(owner + "." + f.name, constantValue(f.constant))
        val clinit = cf.method("<clinit>", "()V") ?: return
        try {
            run(cf, clinit, null, emptyList(), depth + 1)
        } catch (e: Budget.Exhausted) {
            throw e
        } catch (e: Exception) {
            problems.add("Inizializzazione di ${owner.substringAfterLast('/')}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun isInitialized(owner: String) = owner in clinitDone

    /** Chiama una lambda o un riferimento a metodo con [args] (dopo i valori catturati). */
    fun callLambda(l: LamV, args: List<V>, depth: Int): V {
        val h = l.impl
        val all = l.captured + args
        return when (h.kind) {
            Handle.INVOKE_STATIC -> dispatch(Op.INVOKESTATIC, Ref(h.owner, h.name, h.desc), null, all.take(Desc.params(h.desc).size), depth)
            Handle.NEW_INVOKE_SPECIAL -> {
                val o = Obj(h.owner)
                construct(o, Ref(h.owner, "<init>", h.desc), all.take(Desc.params(h.desc).size), depth)
                o
            }
            Handle.INVOKE_VIRTUAL, Handle.INVOKE_INTERFACE, Handle.INVOKE_SPECIAL -> {
                val recv = all.firstOrNull() ?: Unk
                val op = if (h.kind == Handle.INVOKE_SPECIAL) Op.INVOKESPECIAL else Op.INVOKEVIRTUAL
                dispatch(op, Ref(h.owner, h.name, h.desc), recv, all.drop(1).take(Desc.params(h.desc).size), depth)
            }
            else -> Unk
        }.let { if (it === VoidV) Unk else it }
    }

    /** Chiama un metodo come farebbe l'istruzione [op] (semantica prima, poi codice della mod, poi regole generali). */
    fun dispatch(op: Int, ref: Ref, receiver: V?, args: List<V>, depth: Int): V {
        sem.invoke(this, op, ref, receiver, args, depth)?.let { return it }
        return defaultInvoke(op, ref, receiver, args, depth)
    }

    /** Il metodo [name][desc] cercato in [owner] e nelle classi della mod da cui eredita (null se non è codice della mod). */
    fun resolve(owner: String, name: String, desc: String): Pair<ClassFile, MethodInfo>? {
        var c: String? = owner
        var guard = 0
        while (c != null && guard++ < 64) {
            val cf = classes.find(c) ?: return null
            cf.method(name, desc)?.let { m -> if (!m.isAbstract && m.code != null) return cf to m }
            for (i in cf.interfaces) {
                val icf = classes.find(i) ?: continue
                icf.method(name, desc)?.let { m -> if (!m.isAbstract && m.code != null) return icf to m }
            }
            c = cf.superName
        }
        return null
    }

    /** Catena delle superclassi (prima la classe stessa) finché si trovano classi della mod; poi la prima non della mod. */
    fun superChain(name: String): List<String> {
        val out = ArrayList<String>()
        var c: String? = name
        var guard = 0
        while (c != null && guard++ < 64) {
            out.add(c)
            val cf = classes.find(c) ?: break
            c = cf.superName
        }
        return out
    }

    fun construct(obj: Obj, ref: Ref, args: List<V>, depth: Int) {
        if (sem.construct(this, obj, ref, args, depth)) { obj.initialized = true; return }
        val cf = classes.find(ref.owner)
        if (cf != null) {
            if (!obj.initialized && ref.owner == obj.type) obj.args = args
            obj.initialized = true
            ensureInit(ref.owner, depth)
            val m = cf.method("<init>", ref.desc) ?: return
            run(cf, m, obj, args, depth + 1)
        } else {
            if (!obj.initialized) obj.args = args
            if (obj.superCall == null) obj.superCall = Call(ref.owner, "<init>", ref.desc, args)
            obj.initialized = true
        }
    }

    // -------------------------------------------------------------------------------------------------------- regole generali

    private fun defaultInvoke(op: Int, ref: Ref, receiver: V?, args: List<V>, depth: Int): V {
        val ret = Desc.returnType(ref.desc)
        if (ref.name == "<init>") {
            if (receiver is Obj) construct(receiver, ref, args, depth)
            return VoidV
        }
        // Una lambda chiamata attraverso la sua interfaccia (Supplier.get, Function.apply…).
        if (receiver is LamV && op == Op.INVOKEINTERFACE && ref.name == receiver.ifaceMethod) return callLambda(receiver, args, depth)
        // Codice della mod: si segue.
        val start = when {
            op == Op.INVOKESTATIC || op == Op.INVOKESPECIAL -> ref.owner
            receiver is Obj && classes.find(receiver.type) != null -> receiver.type
            else -> ref.owner
        }
        if (op == Op.INVOKESTATIC) ensureInit(ref.owner, depth)
        val target = resolve(start, ref.name, ref.desc)
        if (target != null) {
            val v = run(target.first, target.second, if (op == Op.INVOKESTATIC) null else receiver, args, depth + 1)
            return if (ret == "V") VoidV else v
        }
        // Metodi puri della libreria Java sulle costanti (testi, enum).
        javaLang(ref, receiver, args)?.let { return it }
        // «Builder»: il metodo restituisce lo stesso tipo dell'oggetto su cui è chiamato → si registra la chiamata.
        if (receiver is Obj && (ret == "L" + ref.owner + ";" || ret == "L" + receiver.type + ";" ||
                (ret.startsWith("L") && Desc.objectType(ret)!!.let { rt -> rt != "java/lang/Object" && superChain(receiver.type).contains(rt) }))
        ) {
            receiver.calls.add(Call(ref.owner, ref.name, ref.desc, args))
            return receiver
        }
        if (ret == "V") {
            if (receiver is Obj) receiver.calls.add(Call(ref.owner, ref.name, ref.desc, args))
            return VoidV
        }
        val rt = Desc.objectType(ret) ?: return Unk
        return Obj(rt).also { it.origin = Call(ref.owner, ref.name, ref.desc, args); it.originReceiver = receiver; it.initialized = true }
    }

    /** Contenuto noto di una mappa o di una lista costruita dal codice letto. */
    class MapData { val entries = ArrayList<Pair<V, V>>() }
    class ListData { val items = ArrayList<V>() }

    fun mapOf(o: Obj): MapData = (o.ext as? MapData) ?: MapData().also { o.ext = it }
    fun listOf(o: Obj): ListData = (o.ext as? ListData) ?: ListData().also { o.ext = it }

    /** Cerca una chiave in una mappa (stesso valore o stesso oggetto). */
    fun mapGet(o: Obj, key: V): V? = (o.ext as? MapData)?.entries?.lastOrNull { same(it.first, key) }?.second

    private fun collections(ref: Ref, receiver: V?, args: List<V>): V? {
        val o = ref.owner
        val isMap = o == "java/util/Map" || o.endsWith("Map") && (o.startsWith("java/util/") || o.startsWith("com/google/common/collect/") || o.startsWith("it/unimi/dsi/fastutil/"))
        val isList = o == "java/util/List" || o == "java/util/Collection" || o == "java/util/Set" ||
            (o.startsWith("java/util/") && (o.endsWith("List") || o.endsWith("Set"))) ||
            (o.startsWith("com/google/common/collect/") && (o.endsWith("List") || o.endsWith("Set")))
        if (receiver == null) {
            // Fabbriche statiche: Map.of, List.of, ImmutableList.of, Arrays.asList, Set.of…
            if ((isMap) && (ref.name == "of" || ref.name == "ofEntries" || ref.name == "copyOf")) {
                val m = Obj(o).also { it.initialized = true }
                val d = mapOf(m)
                if (ref.name == "of") for (i in 0 until args.size / 2) d.entries.add(args[2 * i] to args[2 * i + 1])
                return m
            }
            if ((isList && (ref.name == "of" || ref.name == "copyOf")) || (o == "java/util/Arrays" && ref.name == "asList")) {
                val l = Obj(if (o == "java/util/Arrays") "java/util/List" else o).also { it.initialized = true }
                val d = listOf(l)
                for (a in args) if (a is ArrV) d.items.addAll(a.items) else d.items.add(a)
                return l
            }
            return null
        }
        if (receiver is ArrV && ref.name == "clone") return ArrV(receiver.elemDesc, receiver.items.copyOf())
        if (receiver !is Obj) return null
        val ext = receiver.ext
        if (ref.name == "iterator" && (ext is ListData || ext is MapData)) {
            val items = if (ext is ListData) ext.items.toList() else (ext as MapData).entries.map { it.first }
            return Obj("java/util/Iterator").also { it.initialized = true; it.ext = IterData(items) }
        }
        if (ext is IterData) {
            when (ref.name) {
                "hasNext" -> return IntV(if (ext.idx < ext.items.size) 1 else 0)
                "next" -> return if (ext.idx < ext.items.size) ext.items[ext.idx++] else Unk
            }
        }
        if (ext is Pair<*, *> && receiver.type == "java/util/Map\$Entry") {
            when (ref.name) { "getKey" -> return ext.first as? V ?: Unk; "getValue" -> return ext.second as? V ?: Unk }
        }
        if (ext is MapData && (ref.name == "entrySet" || ref.name == "keySet" || ref.name == "values")) {
            val l = Obj("java/util/Set").also { it.initialized = true }
            val d = listOf(l)
            for ((k, v) in ext.entries) d.items.add(
                when (ref.name) {
                    "keySet" -> k
                    "values" -> v
                    else -> Obj("java/util/Map\$Entry").also { e -> e.initialized = true; e.ext = Pair(k, v) }
                }
            )
            return l
        }
        if (ref.name == "forEach" && args.size == 1 && args[0] is LamV) {
            val fn = args[0] as LamV
            when (ext) {
                is ListData -> for (x in ext.items.toList()) callLambda(fn, listOf(x), callStack.size)
                is MapData -> for ((k, v) in ext.entries.toList()) callLambda(fn, listOf(k, v), callStack.size)
            }
            if (ext is ListData || ext is MapData) return VoidV
        }
        if (isMap || receiver.ext is MapData) {
            when (ref.name) {
                "put", "putIfAbsent" -> { if (args.size == 2) mapOf(receiver).entries.add(args[0] to args[1]); return Unk }
                "get", "getOrDefault" -> return mapGet(receiver, args.firstOrNull() ?: Unk) ?: args.getOrNull(1) ?: Unk
                "put" + "All" -> { (args.firstOrNull() as? Obj)?.ext?.let { e -> if (e is MapData) mapOf(receiver).entries.addAll(e.entries) }; return VoidV }
                "build", "buildOrThrow" -> return receiver
                "containsKey" -> return IntV(if (mapGet(receiver, args.firstOrNull() ?: Unk) != null) 1 else 0)
            }
        }
        if (isList || receiver.ext is ListData) {
            when (ref.name) {
                "add" -> { if (args.size == 1) listOf(receiver).items.add(args[0]); return if (Desc.returnType(ref.desc) == "Z") IntV(1) else VoidV }
                "addAll" -> { ((args.lastOrNull() as? Obj)?.ext as? ListData)?.let { listOf(receiver).items.addAll(it.items) }; return IntV(1) }
                "get" -> { val i = args.firstOrNull()?.int(); val d = receiver.ext as? ListData; return if (i != null && d != null && i in d.items.indices) d.items[i] else Unk }
                "size" -> return (receiver.ext as? ListData)?.let { IntV(it.items.size) } ?: Unk
                "build" -> return receiver
            }
        }
        return null
    }

    private fun javaLang(ref: Ref, receiver: V?, args: List<V>): V? {
        collections(ref, receiver, args)?.let { return it }
        when (ref.owner) {
            "java/lang/String" -> {
                val s = (receiver as? StrV)?.v
                return when (ref.name) {
                    "toLowerCase" -> s?.let { StrV(it.lowercase()) }
                    "toUpperCase" -> s?.let { StrV(it.uppercase()) }
                    "concat" -> { val o = args.firstOrNull()?.str(); if (s != null && o != null) StrV(s + o) else Unk }
                    "replace" -> {
                        val a = args.getOrNull(0); val b = args.getOrNull(1)
                        if (s != null && a is StrV && b is StrV) StrV(s.replace(a.v, b.v)) else Unk
                    }
                    "valueOf" -> args.firstOrNull()?.let { a -> text(a)?.let { StrV(it) } } ?: Unk
                    "equals" -> { val o = args.firstOrNull()?.str(); if (s != null && o != null) IntV(if (s == o) 1 else 0) else Unk }
                    "length" -> s?.let { IntV(it.length) }
                    "isEmpty" -> s?.let { IntV(if (it.isEmpty()) 1 else 0) }
                    else -> null
                }
            }
            "java/lang/StringBuilder" -> {
                if (receiver !is Obj) return null
                when (ref.name) {
                    "append" -> {
                        val cur = (receiver.ext as? StringBuilder) ?: if (receiver.ext == null && receiver.calls.isEmpty()) StringBuilder(
                            receiver.args.firstOrNull()?.let { text(it) } ?: ""
                        ).also { receiver.ext = it } else null
                        val t = args.firstOrNull()?.let { text(it) }
                        if (cur != null && t != null) cur.append(t) else receiver.ext = UNKNOWN_TEXT
                        return receiver
                    }
                    "toString" -> return (receiver.ext as? StringBuilder)?.let { StrV(it.toString()) }
                        ?: if (receiver.ext == null) receiver.args.firstOrNull()?.let { a -> text(a)?.let { StrV(it) } } ?: Unk else Unk
                }
                return null
            }
            "java/lang/Enum" -> {
                if (receiver is Obj && (ref.name == "name" || ref.name == "toString")) return receiver.args.getOrNull(0) as? StrV ?: Unk
                if (receiver is Obj && ref.name == "ordinal") return receiver.args.getOrNull(1) as? IntV ?: Unk
            }
            "java/lang/Integer", "java/lang/Float", "java/lang/Double", "java/lang/Long", "java/lang/Boolean" -> {
                if (ref.name == "valueOf" && args.size == 1 && args[0] !is StrV) return args[0]
                if (ref.name.endsWith("Value") && receiver != null && receiver.num() != null) {
                    val n = receiver.num()!!
                    return when (ref.name) {
                        "intValue" -> IntV(n.toInt()); "floatValue" -> FloatV(n.toFloat()); "doubleValue" -> DoubleV(n)
                        "longValue" -> LongV(n.toLong()); "booleanValue" -> IntV(n.toInt()); else -> null
                    }
                }
            }
            "java/lang/Math" -> {
                val a = args.getOrNull(0)?.num(); val b = args.getOrNull(1)?.num()
                val r: Double? = when (ref.name) {
                    "max" -> if (a != null && b != null) maxOf(a, b) else null
                    "min" -> if (a != null && b != null) minOf(a, b) else null
                    "abs" -> a?.let { Math.abs(it) }
                    "toRadians" -> a?.let { Math.toRadians(it) }
                    "toDegrees" -> a?.let { Math.toDegrees(it) }
                    "sqrt" -> a?.let { Math.sqrt(it) }
                    else -> null
                }
                if (r != null) return numberOf(Desc.returnType(ref.desc), r)
            }
        }
        return null
    }

    private fun text(v: V): String? = when (v) {
        is StrV -> v.v
        is IntV -> v.v.toString()
        is LongV -> v.v.toString()
        is FloatV -> v.v.toString()
        is DoubleV -> v.v.toString()
        else -> null
    }

    // -------------------------------------------------------------------------------------------------------- lettura

    private fun loopsOf(insns: List<Insn>): Map<Int, LoopInfo> {
        val out = HashMap<Int, MutableSet<Int>>()
        val index = HashMap<Int, Int>()
        insns.forEachIndexed { i, ins -> index[ins.off] = i }
        for ((i, ins) in insns.withIndex()) {
            val targets = ArrayList<Int>()
            if (Op.isBranch(ins.op)) targets.add(ins.a)
            if (ins.op == Op.TABLESWITCH || ins.op == Op.LOOKUPSWITCH) { targets.add(ins.a); ins.targets?.forEach { targets.add(it) } }
            for (t in targets) {
                if (t > ins.off) continue
                val from = index[t] ?: continue
                val set = out.getOrPut(t) { HashSet() }
                for (k in from..i) {
                    val l = Insns.storedLocal(insns[k])
                    if (l >= 0) { set.add(l); if (Insns.storesWide(insns[k])) set.add(l + 1) }
                }
            }
        }
        return out.mapValues { LoopInfo(it.value, true) }
    }

    /** Dati di un iteratore su una collezione nota (per leggere i cicli «for (x : lista)» elemento per elemento). */
    class IterData(val items: List<V>) { var idx = 0 }

    private class Ctl { var backTo = -1 }

    private fun loadSlot(i: Insn): Int = when (i.op) {
        Op.ILOAD, Op.LLOAD, Op.FLOAD, Op.DLOAD, Op.ALOAD -> i.a
        in Op.ILOAD_0..Op.ALOAD_3 -> (i.op - Op.ILOAD_0) % 4
        else -> -1
    }

    private fun isIload(i: Insn?) = i != null && (i.op == Op.ILOAD || i.op in Op.ILOAD_0..(Op.ILOAD_0 + 3))
    private fun isAload(i: Insn?) = i != null && (i.op == Op.ALOAD || i.op in (Op.ILOAD_0 + 16)..Op.ALOAD_3)
    private fun isIcmp(i: Insn?) = i != null && i.op in (Op.IFEQ + 6)..(Op.IFEQ + 11)

    /** Il ciclo che comincia in [k] ha una condizione calcolabile con i valori di adesso (iteratore noto, contatore e limite noti). */
    private fun concreteLoop(pool: ConstantPool, insns: List<Insn>, k: Int, f: Frame): Boolean {
        val a = insns.getOrNull(k) ?: return false
        val b = insns.getOrNull(k + 1) ?: return false
        if (isAload(a) && (b.op == Op.INVOKEINTERFACE || b.op == Op.INVOKEVIRTUAL)) {
            val r = try { pool.ref(b.a) } catch (e: IOException) { return false }
            if (r.name == "hasNext") { val it = f.locals.getOrNull(loadSlot(a)); return it is Obj && it.ext is IterData }
            return false
        }
        if (isIload(a) && f.locals.getOrNull(loadSlot(a)) is IntV) {
            return when {
                isIload(b) -> f.locals.getOrNull(loadSlot(b)) is IntV && isIcmp(insns.getOrNull(k + 2))
                isAload(b) && insns.getOrNull(k + 2)?.op == Op.ARRAYLENGTH -> f.locals.getOrNull(loadSlot(b)) is ArrV && isIcmp(insns.getOrNull(k + 3))
                b.op in Op.ICONST_M1..Op.ICONST_5 || b.op == Op.BIPUSH || b.op == Op.SIPUSH -> isIcmp(insns.getOrNull(k + 2))
                else -> false
            }
        }
        return false
    }

    private fun sweep(cls: ClassFile, m: MethodInfo, insns: List<Insn>, loopInfo: Map<Int, LoopInfo>, start: Frame, code: Code, depth: Int): V {
        val pending = HashMap<Int, Frame>()
        val handlers = code.handlers.map { it.handler }.toHashSet()
        val returns = ArrayList<V>()
        var cur: Frame? = start
        val pool = cls.pool
        val index = HashMap<Int, Int>(insns.size * 2)
        insns.forEachIndexed { i, ins -> index[ins.off] = i }
        val concrete = HashSet<Int>()
        val iterations = HashMap<Int, Int>()
        val ctl = Ctl()
        var back = false
        var decidedBack = false

        fun save(target: Int, f: Frame, here: Int) {
            if (target <= here) return
            val old = pending[target]
            pending[target] = if (old == null) f.copy() else merge(old, f)
        }

        var k = 0
        while (k < insns.size) {
            val ins = insns[k]
            pending.remove(ins.off)?.let { inc -> cur = cur?.let { merge(it, inc) } ?: inc }
            if (cur == null && ins.off in handlers) {
                cur = Frame(Array(start.locals.size) { Unk }, arrayListOf<V>(Obj("java/lang/Throwable").also { it.initialized = true }))
            }
            val f = cur
            if (f == null) { k++; back = false; continue }
            loopInfo[ins.off]?.let { li ->
                val n = if (back) (iterations[ins.off] ?: 0) + 1 else 0
                iterations[ins.off] = n
                val keep = n <= MAX_ITERATIONS && (if (back && decidedBack) true else concreteLoop(pool, insns, k, f))
                if (keep) concrete.add(ins.off) else {
                    concrete.remove(ins.off)
                    for (l in li.locals) if (l < f.locals.size) f.locals[l] = Unk
                }
            }
            back = false
            decidedBack = false
            if (--budget.steps < 0) throw Budget.Exhausted()
            ctl.backTo = -1
            val next: Frame? = try {
                step(cls, pool, ins, f, depth, returns, ctl, concrete) { t -> save(t, f, ins.off) }
            } catch (e: Budget.Exhausted) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: RuntimeException) {
                problems.add("${cls.name.substringAfterLast('/')}.${m.name}: ${e.javaClass.simpleName} a ${ins.off}")
                null
            }
            if (ctl.backTo >= 0) {
                val target = index[ctl.backTo]
                if (target != null && target <= k) {
                    decidedBack = !Op.isUnconditional(ins.op)
                    cur = f
                    k = target
                    back = true
                    continue
                }
            }
            cur = next
            // Ciclo con la condizione in fondo («goto COND; CORPO: …; COND: if … goto CORPO»): il corpo si legge comunque.
            if (next == null && Op.isUnconditional(ins.op) && k + 1 < insns.size && insns[k + 1].off in loopInfo && insns[k + 1].off !in pending) {
                pending[insns[k + 1].off] = f.copy().also { c -> loopInfo[insns[k + 1].off]?.locals?.forEach { if (it < c.locals.size) c.locals[it] = Unk } }
            }
            k++
        }
        val distinct = ArrayList<V>()
        for (r in returns) if (distinct.none { same(it, r) }) distinct.add(r)
        return when (distinct.size) {
            0 -> Unk
            1 -> distinct[0]
            else -> AltV(distinct)
        }
    }

    /** Esito di un salto condizionato con valori noti: true = salta, false = prosegue, null = non si sa. */
    private fun decide(op: Int, a: V, b: V?): Boolean? {
        fun nonNull(v: V) = v is Obj || v is StrV || v is ArrV || v is LamV || v is ClassV
        return when (op) {
            in Op.IFEQ..(Op.IFEQ + 5) -> {
                val x = (a as? IntV)?.v ?: return null
                when (op - Op.IFEQ) { 0 -> x == 0; 1 -> x != 0; 2 -> x < 0; 3 -> x >= 0; 4 -> x > 0; else -> x <= 0 }
            }
            in (Op.IFEQ + 6)..(Op.IFEQ + 11) -> {
                val x = (b as? IntV)?.v ?: return null
                val y = (a as? IntV)?.v ?: return null
                when (op - Op.IFEQ - 6) { 0 -> x == y; 1 -> x != y; 2 -> x < y; 3 -> x >= y; 4 -> x > y; else -> x <= y }
            }
            Op.IFEQ + 12, Op.IF_ACMPNE -> {
                val x = b ?: return null
                val eq: Boolean = when {
                    x === NullV && a === NullV -> true
                    (x === NullV && nonNull(a)) || (a === NullV && nonNull(x)) -> false
                    x === a && x is Obj -> true
                    else -> return null
                }
                if (op == Op.IF_ACMPNE) !eq else eq
            }
            Op.IFNULL -> if (a === NullV) true else if (nonNull(a)) false else null
            Op.IFNONNULL -> if (a === NullV) false else if (nonNull(a)) true else null
            else -> null
        }
    }

    private fun merge(a: Frame, b: Frame): Frame {
        val locals = Array(a.locals.size) { i -> val x = a.locals[i]; val y = b.locals.getOrElse(i) { Unk }; if (same(x, y)) x else Unk }
        val stack = ArrayList<V>(a.stack.size)
        for (i in a.stack.indices) {
            val x = a.stack[i]; val y = b.stack.getOrElse(i) { Unk }
            stack.add(if (same(x, y)) x else if (x === Top || y === Top) Top else Unk)
        }
        return Frame(locals, stack)
    }

    private fun same(x: V, y: V): Boolean = x === y || when (x) {
        is IntV -> y is IntV && y.v == x.v
        is LongV -> y is LongV && y.v == x.v
        is FloatV -> y is FloatV && y.v == x.v
        is DoubleV -> y is DoubleV && y.v == x.v
        is StrV -> y is StrV && y.v == x.v
        is ClassV -> y is ClassV && y.name == x.name
        is StaticV -> y is StaticV && y.owner == x.owner && y.name == x.name
        else -> false
    }

    private fun constantValue(c: Any?): V = when (c) {
        is Int -> IntV(c); is Long -> LongV(c); is Float -> FloatV(c); is Double -> DoubleV(c); is String -> StrV(c)
        is ClassConst -> ClassV(c.name); else -> Unk
    }

    private fun numberOf(type: String, d: Double): V = when (type) {
        "I", "Z", "B", "C", "S" -> IntV(d.toInt()); "J" -> LongV(d.toLong()); "F" -> FloatV(d.toFloat()); else -> DoubleV(d)
    }

    /** Esegue un'istruzione su [f]; restituisce lo stato dopo l'istruzione o null se il flusso non prosegue. */
    private fun step(
        cls: ClassFile, pool: ConstantPool, ins: Insn, f: Frame, depth: Int, returns: MutableList<V>, ctl: Ctl, concrete: Set<Int>,
        jump: (Int) -> Unit
    ): Frame? {
        val s = f.stack
        fun push(v: V) { s.add(v) }
        fun pushW(v: V) { s.add(v); s.add(Top) }
        fun pop(): V = if (s.isEmpty()) Unk else s.removeAt(s.size - 1)
        fun popW(): V { pop(); return pop() }
        fun popType(t: String): V = if (Desc.isWide(t)) popW() else pop()
        fun pushType(t: String, v: V) { if (Desc.isWide(t)) pushW(v) else if (t != "V") push(v) }
        fun load(i: Int, wide: Boolean) { val v = f.locals.getOrElse(i) { Unk }; if (wide) pushW(v) else push(v) }
        fun store(i: Int, wide: Boolean) {
            val v = if (wide) popW() else pop()
            if (i < f.locals.size) f.locals[i] = v
            if (wide && i + 1 < f.locals.size) f.locals[i + 1] = Top
        }

        val op = ins.op
        when (op) {
            Op.NOP -> {}
            Op.ACONST_NULL -> push(NullV)
            in Op.ICONST_M1..Op.ICONST_5 -> push(IntV(op - 3))
            Op.LCONST_0, Op.LCONST_1 -> pushW(LongV((op - Op.LCONST_0).toLong()))
            in Op.FCONST_0..Op.FCONST_2 -> push(FloatV((op - Op.FCONST_0).toFloat()))
            Op.DCONST_0, Op.DCONST_1 -> pushW(DoubleV((op - Op.DCONST_0).toDouble()))
            Op.BIPUSH, Op.SIPUSH -> push(IntV(ins.a))
            Op.LDC, Op.LDC_W, Op.LDC2_W -> {
                val c = pool.constant(ins.a)
                val v = when (c) {
                    is Handle -> Unk
                    is MethodTypeConst -> Unk
                    else -> constantValue(c)
                }
                if (c is Long || c is Double) pushW(v) else push(v)
            }
            Op.ILOAD, Op.FLOAD, Op.ALOAD -> load(ins.a, false)
            Op.LLOAD, Op.DLOAD -> load(ins.a, true)
            in Op.ILOAD_0..Op.ALOAD_3 -> { val g = (op - Op.ILOAD_0) / 4; load((op - Op.ILOAD_0) % 4, g == 1 || g == 3) }
            in Op.IALOAD..Op.SALOAD -> {
                val idx = pop(); val arr = pop()
                val v = if (arr is ArrV && idx is IntV && idx.v in arr.items.indices) arr.items[idx.v] else Unk
                if (op == Op.IALOAD + 1 || op == Op.IALOAD + 3) pushW(v) else push(v)
            }
            Op.ISTORE, Op.FSTORE, Op.ASTORE -> store(ins.a, false)
            Op.LSTORE, Op.DSTORE -> store(ins.a, true)
            in Op.ISTORE_0..Op.ASTORE_3 -> { val g = (op - Op.ISTORE_0) / 4; store((op - Op.ISTORE_0) % 4, g == 1 || g == 3) }
            in Op.IASTORE..Op.SASTORE -> {
                val wide = op == Op.IASTORE + 1 || op == Op.IASTORE + 3
                val v = if (wide) popW() else pop()
                val idx = pop(); val arr = pop()
                if (arr is ArrV && idx is IntV && idx.v in arr.items.indices) arr.items[idx.v] = v
            }
            Op.POP -> pop()
            Op.POP2 -> { pop(); pop() }
            Op.DUP -> { val v = pop(); push(v); push(v) }
            Op.DUP_X1 -> { val a = pop(); val b = pop(); push(a); push(b); push(a) }
            Op.DUP_X2 -> { val a = pop(); val b = pop(); val c = pop(); push(a); push(c); push(b); push(a) }
            Op.DUP2 -> { val a = pop(); val b = pop(); push(b); push(a); push(b); push(a) }
            Op.DUP2_X1 -> { val a = pop(); val b = pop(); val c = pop(); push(b); push(a); push(c); push(b); push(a) }
            Op.DUP2_X2 -> { val a = pop(); val b = pop(); val c = pop(); val d = pop(); push(b); push(a); push(d); push(c); push(b); push(a) }
            Op.SWAP -> { val a = pop(); val b = pop(); push(a); push(b) }
            in Op.IADD..Op.LXOR -> arith(op, ::pop, ::popW, ::push, ::pushW)
            Op.IINC -> {
                val v = f.locals.getOrElse(ins.a) { Unk }
                if (ins.a < f.locals.size) f.locals[ins.a] = if (v is IntV) IntV(v.v + ins.b) else Unk
            }
            in Op.I2L..Op.I2S -> convert(op, ::pop, ::popW, ::push, ::pushW)
            Op.LCMP -> { val b = popW(); val a = popW(); push(if (a is LongV && b is LongV) IntV(a.v.compareTo(b.v)) else Unk) }
            Op.LCMP + 1, Op.LCMP + 2 -> { val b = pop(); val a = pop(); push(if (a is FloatV && b is FloatV) IntV(a.v.compareTo(b.v)) else Unk) }
            Op.LCMP + 3, Op.DCMPG -> { val b = popW(); val a = popW(); push(if (a is DoubleV && b is DoubleV) IntV(a.v.compareTo(b.v)) else Unk) }
            in Op.IFEQ..(Op.IFEQ + 5), Op.IFNULL, Op.IFNONNULL, in (Op.IFEQ + 6)..Op.IF_ACMPNE -> {
                val two = op in (Op.IFEQ + 6)..Op.IF_ACMPNE
                val a = pop()
                val b = if (two) pop() else null
                when (decide(op, a, b)) {
                    true -> { if (ins.a > ins.off) jump(ins.a) else ctl.backTo = ins.a; return null }
                    false -> {}
                    null -> jump(ins.a)
                }
            }
            Op.GOTO, Op.GOTO_W -> {
                if (ins.a <= ins.off && ins.a in concrete) ctl.backTo = ins.a else jump(ins.a)
                return null
            }
            Op.JSR, Op.JSR_W -> { push(Unk); jump(ins.a); return null }
            Op.RET -> return null
            Op.TABLESWITCH, Op.LOOKUPSWITCH -> { pop(); jump(ins.a); ins.targets?.forEach { jump(it) }; return null }
            in Op.IRETURN..Op.ARETURN -> {
                val wide = op == Op.IRETURN + 1 || op == Op.IRETURN + 3
                returns.add(if (wide) popW() else pop())
                return null
            }
            Op.RETURN -> return null
            Op.GETSTATIC -> {
                val r = pool.ref(ins.a)
                val key = r.owner + "." + r.name
                var v = statics[key]
                if (v == null && classes.find(r.owner) != null) { ensureInit(r.owner, depth); v = statics[key] }
                if (v == null) v = sem.getStatic(this, r)
                if (v == null) v = if (classes.find(r.owner) != null) Unk else StaticV(r.owner, r.name, r.desc)
                pushType(r.desc, v)
            }
            Op.PUTSTATIC -> {
                val r = pool.ref(ins.a)
                val v = popType(r.desc)
                statics[r.owner + "." + r.name] = v
                sem.putStatic(this, r, v)
            }
            Op.GETFIELD -> {
                val r = pool.ref(ins.a)
                val o = pop()
                val v: V = if (o is Obj) {
                    o.fields[r.name] ?: Desc.objectType(r.desc)?.let { t ->
                        Obj(t).also { it.origin = Call(r.owner, "field:" + r.name, r.desc, emptyList()); it.originReceiver = o; it.initialized = true; o.fields[r.name] = it }
                    } ?: Unk
                } else Unk
                pushType(r.desc, v)
            }
            Op.PUTFIELD -> {
                val r = pool.ref(ins.a)
                val v = popType(r.desc)
                val o = pop()
                if (o is Obj) o.fields[r.name] = v
            }
            Op.INVOKEVIRTUAL, Op.INVOKESPECIAL, Op.INVOKESTATIC, Op.INVOKEINTERFACE -> {
                val r = pool.ref(ins.a)
                val params = Desc.params(r.desc)
                val args = ArrayList<V>(params.size)
                for (p in params.asReversed()) args.add(popType(p))
                args.reverse()
                val receiver = if (op == Op.INVOKESTATIC) null else pop()
                val result = try {
                    dispatch(op, r, receiver, args, depth)
                } catch (e: Budget.Exhausted) {
                    throw e
                } catch (e: IOException) {
                    problems.add("${r.owner.substringAfterLast('/')}.${r.name}: ${e.message}")
                    Unk
                } catch (e: RuntimeException) {
                    problems.add("${r.owner.substringAfterLast('/')}.${r.name}: ${e.javaClass.simpleName}")
                    Unk
                }
                val ret = Desc.returnType(r.desc)
                if (ret != "V") pushType(ret, if (result === VoidV) Unk else result)
            }
            Op.INVOKEDYNAMIC -> {
                val indy = pool.indy(ins.a)
                val params = Desc.params(indy.desc)
                val args = ArrayList<V>(params.size)
                for (p in params.asReversed()) args.add(popType(p))
                args.reverse()
                val bsm = cls.bootstrap.getOrNull(indy.bootstrap)
                val ret = Desc.returnType(indy.desc)
                val v: V = when (bsm?.method?.owner) {
                    "java/lang/invoke/LambdaMetafactory" -> {
                        val impl = bsm.args.getOrNull(1) as? Handle
                        if (impl != null) LamV(impl, args, Desc.objectType(ret) ?: "", indy.name) else Unk
                    }
                    "java/lang/invoke/StringConcatFactory" -> concat(bsm.args, args)
                    else -> Unk
                }
                pushType(ret, v)
            }
            Op.NEW -> push(Obj(pool.className(ins.a)))
            Op.NEWARRAY -> {
                val n = pop()
                val desc = when (ins.a) { 4 -> "Z"; 5 -> "C"; 6 -> "F"; 7 -> "D"; 8 -> "B"; 9 -> "S"; 10 -> "I"; else -> "J" }
                val zero: V = when (desc) { "F" -> FloatV(0f); "D" -> DoubleV(0.0); "J" -> LongV(0); else -> IntV(0) }
                push(if (n is IntV && n.v in 0..budget.maxArray) ArrV(desc, Array(n.v) { zero }) else Unk)
            }
            Op.ANEWARRAY -> {
                val n = pop()
                val t = pool.className(ins.a)
                push(if (n is IntV && n.v in 0..budget.maxArray) ArrV(if (t.startsWith("[")) t else "L$t;", Array(n.v) { NullV }) else Unk)
            }
            Op.ARRAYLENGTH -> { val a = pop(); push(if (a is ArrV) IntV(a.items.size) else Unk) }
            Op.ATHROW -> { pop(); return null }
            Op.CHECKCAST -> {}
            Op.INSTANCEOF -> { pop(); push(Unk) }
            Op.MONITORENTER, Op.MONITOREXIT -> pop()
            Op.MULTIANEWARRAY -> { repeat(ins.b) { pop() }; push(Unk) }
            else -> throw IOException("Istruzione non gestita: $op")
        }
        return f
    }

    private fun concat(bsmArgs: List<Any?>, args: List<V>): V {
        val recipe = bsmArgs.getOrNull(0) as? String ?: return Unk
        val sb = StringBuilder()
        var ai = 0
        var ci = 1
        for (ch in recipe) {
            when (ch) {
                '\u0001' -> {
                    val t = args.getOrNull(ai++)?.let { text(it) } ?: return Unk
                    sb.append(t)
                }
                '\u0002' -> sb.append(bsmArgs.getOrNull(ci++)?.toString() ?: return Unk)
                else -> sb.append(ch)
            }
        }
        return StrV(sb.toString())
    }

    private inline fun arith(op: Int, pop: () -> V, popW: () -> V, push: (V) -> Unit, pushW: (V) -> Unit) {
        // Ordine della JVM: iadd ladd fadd dadd, isub…, imul…, idiv…, irem…, ineg…, ishl lshl ishr lshr iushr lushr, iand land ior lor ixor lxor
        val k = op - Op.IADD
        if (k < 20) {
            val kind = k % 4
            val fn = k / 4
            val b = if (kind == 1 || kind == 3) popW() else pop()
            val a = if (kind == 1 || kind == 3) popW() else pop()
            val r: V = when (kind) {
                0 -> if (a is IntV && b is IntV) when (fn) { 0 -> IntV(a.v + b.v); 1 -> IntV(a.v - b.v); 2 -> IntV(a.v * b.v); 3 -> if (b.v != 0) IntV(a.v / b.v) else Unk; else -> if (b.v != 0) IntV(a.v % b.v) else Unk } else Unk
                1 -> if (a is LongV && b is LongV) when (fn) { 0 -> LongV(a.v + b.v); 1 -> LongV(a.v - b.v); 2 -> LongV(a.v * b.v); 3 -> if (b.v != 0L) LongV(a.v / b.v) else Unk; else -> if (b.v != 0L) LongV(a.v % b.v) else Unk } else Unk
                2 -> if (a is FloatV && b is FloatV) when (fn) { 0 -> FloatV(a.v + b.v); 1 -> FloatV(a.v - b.v); 2 -> FloatV(a.v * b.v); 3 -> FloatV(a.v / b.v); else -> FloatV(a.v % b.v) } else Unk
                else -> if (a is DoubleV && b is DoubleV) when (fn) { 0 -> DoubleV(a.v + b.v); 1 -> DoubleV(a.v - b.v); 2 -> DoubleV(a.v * b.v); 3 -> DoubleV(a.v / b.v); else -> DoubleV(a.v % b.v) } else Unk
            }
            if (kind == 1 || kind == 3) pushW(r) else push(r)
            return
        }
        if (k < 24) { // neg
            val kind = k - 20
            val a = if (kind == 1 || kind == 3) popW() else pop()
            val r: V = when (a) { is IntV -> IntV(-a.v); is LongV -> LongV(-a.v); is FloatV -> FloatV(-a.v); is DoubleV -> DoubleV(-a.v); else -> Unk }
            if (kind == 1 || kind == 3) pushW(r) else push(r)
            return
        }
        // Spostamenti e operazioni bit a bit: ishl(120) lshl ishr lshr iushr lushr iand(126) land ior lor ixor lxor(131)
        val isLong = (op - 120) % 2 == 1
        val b = if (isLong && op < 126) pop() else if (isLong) popW() else pop()
        val a = if (isLong) popW() else pop()
        val r: V = if (!isLong && a is IntV && b is IntV) when (op) {
            120 -> IntV(a.v shl b.v); 122 -> IntV(a.v shr b.v); 124 -> IntV(a.v ushr b.v)
            126 -> IntV(a.v and b.v); 128 -> IntV(a.v or b.v); else -> IntV(a.v xor b.v)
        } else if (isLong && a is LongV) when (op) {
            121 -> (b as? IntV)?.let { LongV(a.v shl it.v) } ?: Unk
            123 -> (b as? IntV)?.let { LongV(a.v shr it.v) } ?: Unk
            125 -> (b as? IntV)?.let { LongV(a.v ushr it.v) } ?: Unk
            127 -> (b as? LongV)?.let { LongV(a.v and it.v) } ?: Unk
            129 -> (b as? LongV)?.let { LongV(a.v or it.v) } ?: Unk
            else -> (b as? LongV)?.let { LongV(a.v xor it.v) } ?: Unk
        } else Unk
        if (isLong) pushW(r) else push(r)
    }

    private inline fun convert(op: Int, pop: () -> V, popW: () -> V, push: (V) -> Unit, pushW: (V) -> Unit) {
        // i2l i2f i2d l2i l2f l2d f2i f2l f2d d2i d2l d2f i2b i2c i2s
        val fromWide = op in 136..138 || op in 142..144
        val a = if (fromWide) popW() else pop()
        val n = a.num()
        val r: V = if (n == null) Unk else when (op) {
            133, 140, 143 -> LongV(n.toLong())
            134, 137, 144 -> FloatV(n.toFloat())
            135, 138, 141 -> DoubleV(n)
            136, 139, 142 -> IntV(n.toInt())
            145 -> IntV(n.toInt().toByte().toInt())
            146 -> IntV(n.toInt() and 0xFFFF)
            else -> IntV(n.toInt().toShort().toInt())
        }
        val toWide = op in intArrayOf(133, 135, 138, 140, 141, 143)
        if (toWide) pushW(r) else push(r)
    }

    companion object {
        private val UNKNOWN_TEXT = Any()
        /** Giri al massimo per ciclo «concreto» (es. registrazioni dei 16 colori). */
        const val MAX_ITERATIONS = 4096
    }
}
