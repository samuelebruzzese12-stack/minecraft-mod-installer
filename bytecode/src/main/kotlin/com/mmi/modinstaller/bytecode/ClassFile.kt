package com.mmi.modinstaller.bytecode

import java.io.IOException

/** Nomi di classi e membri tradotti mentre si legge un file .class (es. dai nomi «intermediary» di Fabric a quelli di Mojang). */
interface NameMapper {
    /** Nome interno di una classe (con le barre), es. «net/minecraft/class_1792» → «net/minecraft/world/item/Item». */
    fun cls(name: String): String = name
    fun method(name: String): String = name
    fun field(name: String): String = name

    object Identity : NameMapper

    companion object {
        /** Traduce i nomi di classe dentro un descrittore («(Lnet/minecraft/class_1792;I)V»). */
        fun desc(m: NameMapper, d: String): String {
            if (m === Identity || d.indexOf('L') < 0) return d
            val sb = StringBuilder(d.length + 16)
            var i = 0
            while (i < d.length) {
                val c = d[i]
                if (c == 'L') {
                    val end = d.indexOf(';', i)
                    if (end < 0) { sb.append(d, i, d.length); break }
                    sb.append('L').append(m.cls(d.substring(i + 1, end))).append(';')
                    i = end + 1
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
    }
}

/** Riferimento a un campo o a un metodo (classe proprietaria, nome, descrittore), già con i nomi tradotti. */
class Ref(val owner: String, val name: String, val desc: String) {
    override fun toString() = "$owner.$name$desc"
}

/** Un «method handle» del pool delle costanti (usato dalle lambda e dai riferimenti a metodi come «Item::new»). */
class Handle(val kind: Int, val owner: String, val name: String, val desc: String, val isInterface: Boolean) {
    override fun toString() = "$owner.$name$desc"

    companion object {
        const val GET_FIELD = 1; const val GET_STATIC = 2; const val PUT_FIELD = 3; const val PUT_STATIC = 4
        const val INVOKE_VIRTUAL = 5; const val INVOKE_STATIC = 6; const val INVOKE_SPECIAL = 7
        const val NEW_INVOKE_SPECIAL = 8; const val INVOKE_INTERFACE = 9
    }
}

/** Una costante di tipo «Class» (ldc Foo.class) o «MethodType». */
class ClassConst(val name: String)
class MethodTypeConst(val desc: String)

/** Un'istruzione invokedynamic: metodo di avvio (bootstrap), nome e descrittore. */
class IndyRef(val bootstrap: Int, val name: String, val desc: String)
class Bootstrap(val method: Handle, val args: List<Any?>)

class FieldInfo(val access: Int, val name: String, val desc: String, val constant: Any?, val signature: String?) {
    val isStatic get() = access and 0x0008 != 0
}

class Handler(val start: Int, val end: Int, val handler: Int, val type: String?)

class Code(val maxStack: Int, val maxLocals: Int, val bytes: ByteArray, val handlers: List<Handler>)

class MethodInfo(val access: Int, val name: String, val desc: String, val code: Code?, val signature: String?, val annotations: List<String> = emptyList()) {
    val isStatic get() = access and 0x0008 != 0
    val isAbstract get() = access and 0x0400 != 0
    override fun toString() = "$name$desc"
}

/** Il pool delle costanti: le voci si risolvono al bisogno, con i nomi già tradotti. */
class ConstantPool internal constructor(
    private val tags: ByteArray,
    private val offsets: IntArray,
    private val data: ByteArray,
    private val mapper: NameMapper
) {
    val size: Int get() = tags.size
    private val cache = arrayOfNulls<Any>(tags.size)

    fun tag(i: Int): Int = if (i in 1 until tags.size) tags[i].toInt() else 0

    private fun u2(o: Int) = ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
    private fun s4(o: Int) = ((data[o].toInt() and 0xFF) shl 24) or ((data[o + 1].toInt() and 0xFF) shl 16) or
        ((data[o + 2].toInt() and 0xFF) shl 8) or (data[o + 3].toInt() and 0xFF)
    private fun s8(o: Int) = (s4(o).toLong() shl 32) or (s4(o + 4).toLong() and 0xFFFFFFFFL)

    /** Il testo di una voce UTF8 (senza tradurre). */
    fun utf8(i: Int): String {
        if (tag(i) != UTF8) throw IOException("La voce $i del pool non è un testo")
        (cache[i] as? String)?.let { return it }
        val o = offsets[i]
        val len = u2(o)
        val s = decodeModifiedUtf8(data, o + 2, len)
        cache[i] = s
        return s
    }

    /** Nome (tradotto) di una voce «Class». */
    fun className(i: Int): String {
        if (tag(i) != CLASS) throw IOException("La voce $i del pool non è una classe")
        val raw = utf8(u2(offsets[i]))
        return if (raw.startsWith("[")) NameMapper.desc(mapper, raw) else mapper.cls(raw)
    }

    /** Nome grezzo (non tradotto) di una voce «Class», per la ricerca veloce. */
    fun rawClassName(i: Int): String? = if (tag(i) == CLASS) utf8(u2(offsets[i])) else null

    fun ref(i: Int): Ref {
        (cache[i] as? Ref)?.let { return it }
        val t = tag(i)
        if (t != FIELD && t != METHOD && t != IMETHOD) throw IOException("La voce $i del pool non è un riferimento")
        val o = offsets[i]
        val owner = className(u2(o))
        val nt = offsets[u2(o + 2)]
        val rawName = utf8(u2(nt))
        val desc = NameMapper.desc(mapper, utf8(u2(nt + 2)))
        val name = if (t == FIELD) mapper.field(rawName) else mapper.method(rawName)
        return Ref(owner, name, desc).also { cache[i] = it }
    }

    fun isInterfaceRef(i: Int): Boolean = tag(i) == IMETHOD

    fun indy(i: Int): IndyRef {
        val o = offsets[i]
        val nt = offsets[u2(o + 2)]
        return IndyRef(u2(o), mapper.method(utf8(u2(nt))), NameMapper.desc(mapper, utf8(u2(nt + 2))))
    }

    fun handle(i: Int): Handle {
        if (tag(i) != METHOD_HANDLE) throw IOException("La voce $i del pool non è un method handle")
        val o = offsets[i]
        val kind = data[o].toInt() and 0xFF
        val refIndex = u2(o + 1)
        val r = ref(refIndex)
        return Handle(kind, r.owner, r.name, r.desc, isInterfaceRef(refIndex))
    }

    /** Il valore di una costante caricabile (ldc): Int, Long, Float, Double, String, ClassConst, MethodTypeConst o Handle. */
    fun constant(i: Int): Any? = when (tag(i)) {
        INT -> s4(offsets[i])
        FLOAT -> java.lang.Float.intBitsToFloat(s4(offsets[i]))
        LONG -> s8(offsets[i])
        DOUBLE -> java.lang.Double.longBitsToDouble(s8(offsets[i]))
        STRING -> utf8(u2(offsets[i]))
        CLASS -> ClassConst(className(i))
        METHOD_TYPE -> MethodTypeConst(NameMapper.desc(mapper, utf8(u2(offsets[i]))))
        METHOD_HANDLE -> handle(i)
        else -> null
    }

    /** Tutti i nomi di classe citati dal pool, grezzi (per decidere in fretta se una classe va letta tutta). */
    fun rawClassNames(): List<String> {
        val out = ArrayList<String>()
        for (i in 1 until tags.size) if (tags[i].toInt() == CLASS) out.add(utf8(u2(offsets[i])))
        return out
    }

    /** I riferimenti a metodi citati dal pool (proprietario e nome tradotti), per lo stesso scopo. */
    fun methodRefs(): List<Ref> {
        val out = ArrayList<Ref>()
        for (i in 1 until tags.size) {
            val t = tags[i].toInt()
            if (t == METHOD || t == IMETHOD) out.add(ref(i))
        }
        return out
    }

    companion object {
        const val UTF8 = 1; const val INT = 3; const val FLOAT = 4; const val LONG = 5; const val DOUBLE = 6; const val CLASS = 7
        const val STRING = 8; const val FIELD = 9; const val METHOD = 10; const val IMETHOD = 11; const val NAME_TYPE = 12
        const val METHOD_HANDLE = 15; const val METHOD_TYPE = 16; const val DYNAMIC = 17; const val INVOKE_DYNAMIC = 18
        const val MODULE = 19; const val PACKAGE = 20

        /** UTF-8 «modificato» della JVM (lo zero su due byte, i caratteri fuori dal piano base come coppie). */
        fun decodeModifiedUtf8(b: ByteArray, start: Int, len: Int): String {
            val out = CharArray(len)
            var n = 0
            var i = start
            val end = start + len
            while (i < end) {
                val c = b[i].toInt() and 0xFF
                when {
                    c < 0x80 -> { out[n++] = c.toChar(); i++ }
                    c and 0xE0 == 0xC0 && i + 1 < end -> {
                        out[n++] = (((c and 0x1F) shl 6) or (b[i + 1].toInt() and 0x3F)).toChar(); i += 2
                    }
                    c and 0xF0 == 0xE0 && i + 2 < end -> {
                        out[n++] = (((c and 0x0F) shl 12) or ((b[i + 1].toInt() and 0x3F) shl 6) or (b[i + 2].toInt() and 0x3F)).toChar(); i += 3
                    }
                    else -> { out[n++] = '?'; i++ }
                }
            }
            return String(out, 0, n)
        }
    }
}

/** Un file .class letto. Con [withCode] = false si salta il corpo dei metodi (più veloce, basta per la gerarchia). */
class ClassFile(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
    val fields: List<FieldInfo>,
    val methods: List<MethodInfo>,
    val pool: ConstantPool,
    val bootstrap: List<Bootstrap>,
    val signature: String?,
    val majorVersion: Int,
    /** Annotazioni della classe (descrittori, es. «Lnet/neoforged/fml/common/Mod;»). */
    val annotations: List<String> = emptyList()
) {
    val isInterface get() = access and 0x0200 != 0
    val isEnum get() = access and 0x4000 != 0
    val isAbstract get() = access and 0x0400 != 0

    fun method(name: String, desc: String? = null): MethodInfo? = methods.firstOrNull { it.name == name && (desc == null || it.desc == desc) }
    fun field(name: String): FieldInfo? = fields.firstOrNull { it.name == name }

    override fun toString() = name

    companion object {
        /** Limiti contro i file malformati o costruiti apposta. */
        const val MAX_POOL = 65_535
        const val MAX_CODE = 65_535

        fun parse(bytes: ByteArray, mapper: NameMapper = NameMapper.Identity, withCode: Boolean = true): ClassFile =
            try {
                Reader(bytes, mapper).read(withCode)
            } catch (e: IndexOutOfBoundsException) {
                throw IOException("File .class troncato o malformato")
            } catch (e: NegativeArraySizeException) {
                throw IOException("File .class malformato")
            }
    }

    private class Reader(val b: ByteArray, val mapper: NameMapper) {
        var p = 0
        fun u1() = b[p++].toInt() and 0xFF
        fun u2(): Int { val v = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2; return v }
        fun s4(): Int {
            val v = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            p += 4; return v
        }

        fun read(withCode: Boolean): ClassFile {
            if (b.size < 10 || s4() != 0xCAFEBABE.toInt()) throw IOException("Non è un file .class")
            u2()
            val major = u2()
            val count = u2()
            if (count < 1 || count > MAX_POOL) throw IOException("Pool delle costanti non valido")
            val tags = ByteArray(count)
            val offsets = IntArray(count)
            var i = 1
            while (i < count) {
                val t = u1()
                tags[i] = t.toByte()
                offsets[i] = p
                when (t) {
                    ConstantPool.UTF8 -> { val len = u2(); p += len }
                    ConstantPool.INT, ConstantPool.FLOAT -> p += 4
                    ConstantPool.LONG, ConstantPool.DOUBLE -> { p += 8; i++ }
                    ConstantPool.CLASS, ConstantPool.STRING, ConstantPool.METHOD_TYPE, ConstantPool.MODULE, ConstantPool.PACKAGE -> p += 2
                    ConstantPool.FIELD, ConstantPool.METHOD, ConstantPool.IMETHOD, ConstantPool.NAME_TYPE,
                    ConstantPool.DYNAMIC, ConstantPool.INVOKE_DYNAMIC -> p += 4
                    ConstantPool.METHOD_HANDLE -> p += 3
                    else -> throw IOException("Voce del pool sconosciuta: $t")
                }
                i++
            }
            val pool = ConstantPool(tags, offsets, b, mapper)
            val access = u2()
            val name = pool.className(u2())
            val superIdx = u2()
            val superName = if (superIdx == 0) null else pool.className(superIdx)
            val interfaces = (0 until u2()).map { pool.className(u2()) }
            val fields = (0 until u2()).map { readField(pool) }
            val methods = (0 until u2()).map { readMethod(pool, withCode) }
            var bootstrap: List<Bootstrap> = emptyList()
            var signature: String? = null
            val annotations = ArrayList<String>()
            repeat(u2()) {
                val an = pool.utf8(u2())
                val len = s4()
                val end = p + len
                when (an) {
                    "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> readAnnotations(pool, annotations)
                    "BootstrapMethods" -> {
                        bootstrap = (0 until u2()).map {
                            val h = pool.handle(u2())
                            val args = (0 until u2()).map { pool.constant(u2()) }
                            Bootstrap(h, args)
                        }
                    }
                    "Signature" -> signature = pool.utf8(u2())
                }
                p = end
            }
            return ClassFile(name, superName, interfaces, access, fields, methods, pool, bootstrap, signature, major, annotations)
        }

        fun readField(pool: ConstantPool): FieldInfo {
            val access = u2()
            val name = mapper.field(pool.utf8(u2()))
            val desc = NameMapper.desc(mapper, pool.utf8(u2()))
            var constant: Any? = null
            var signature: String? = null
            repeat(u2()) {
                val an = pool.utf8(u2())
                val len = s4()
                val end = p + len
                when (an) {
                    "ConstantValue" -> constant = pool.constant(u2())
                    "Signature" -> signature = pool.utf8(u2())
                }
                p = end
            }
            return FieldInfo(access, name, desc, constant, signature)
        }

        fun readMethod(pool: ConstantPool, withCode: Boolean): MethodInfo {
            val access = u2()
            val name = mapper.method(pool.utf8(u2()))
            val desc = NameMapper.desc(mapper, pool.utf8(u2()))
            var code: Code? = null
            var signature: String? = null
            val annotations = ArrayList<String>()
            repeat(u2()) {
                val an = pool.utf8(u2())
                val len = s4()
                val end = p + len
                if (an == "RuntimeVisibleAnnotations" || an == "RuntimeInvisibleAnnotations") {
                    readAnnotations(pool, annotations)
                } else if (an == "Code" && withCode) {
                    val maxStack = u2()
                    val maxLocals = u2()
                    val codeLen = s4()
                    if (codeLen < 0 || codeLen > MAX_CODE) throw IOException("Codice del metodo troppo lungo")
                    val bytes = b.copyOfRange(p, p + codeLen)
                    p += codeLen
                    val handlers = (0 until u2()).map {
                        val s = u2(); val e = u2(); val h = u2(); val t = u2()
                        Handler(s, e, h, if (t == 0) null else pool.className(t))
                    }
                    code = Code(maxStack, maxLocals, bytes, handlers)
                } else if (an == "Signature") {
                    signature = pool.utf8(u2())
                }
                p = end
            }
            return MethodInfo(access, name, desc, code, signature, annotations)
        }

        fun readAnnotations(pool: ConstantPool, out: MutableList<String>) {
            repeat(u2()) { out.add(NameMapper.desc(mapper, readAnnotation(pool))) }
        }

        /** Legge un'annotazione e ne restituisce il tipo; i valori si saltano. */
        fun readAnnotation(pool: ConstantPool): String {
            val type = pool.utf8(u2())
            repeat(u2()) { u2(); skipElement(pool) }
            return type
        }

        fun skipElement(pool: ConstantPool) {
            when (u1().toChar()) {
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> p += 2
                'e' -> p += 4
                '@' -> readAnnotation(pool)
                '[' -> repeat(u2()) { skipElement(pool) }
                else -> throw IOException("Annotazione malformata")
            }
        }
    }
}

/** Utilità sui descrittori dei metodi. */
object Desc {
    /** I tipi dei parametri, uno per voce («I», «Ljava/lang/String;», «[F»…). */
    fun params(desc: String): List<String> {
        val out = ArrayList<String>()
        var i = desc.indexOf('(') + 1
        while (i < desc.length && desc[i] != ')') {
            val start = i
            while (desc[i] == '[') i++
            if (desc[i] == 'L') i = desc.indexOf(';', i)
            i++
            out.add(desc.substring(start, i))
        }
        return out
    }

    fun returnType(desc: String): String = desc.substring(desc.indexOf(')') + 1)

    /** «Lnet/minecraft/world/item/Item;» → «net/minecraft/world/item/Item»; null per i tipi primitivi e gli array. */
    fun objectType(t: String): String? = if (t.startsWith("L") && t.endsWith(";")) t.substring(1, t.length - 1) else null

    fun isWide(t: String): Boolean = t == "J" || t == "D"
}
