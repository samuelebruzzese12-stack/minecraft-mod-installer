package com.mmi.modinstaller.bytecode

import java.io.IOException

/**
 * Un'istruzione JVM decodificata. [a] e [b] sono gli operandi (indice del pool, variabile locale, costante, salto relativo già
 * trasformato in posizione assoluta…). Per gli switch: [a] è il salto di default, [targets] e [keys] i casi.
 */
class Insn(val off: Int, val op: Int, val a: Int = 0, val b: Int = 0, val targets: IntArray? = null, val keys: IntArray? = null) {
    override fun toString() = "$off:${Op.name(op)} $a $b"
}

/** Codici operativi della JVM (solo quelli che servono per nome). */
object Op {
    const val NOP = 0; const val ACONST_NULL = 1; const val ICONST_M1 = 2; const val ICONST_5 = 8; const val LCONST_0 = 9; const val LCONST_1 = 10
    const val FCONST_0 = 11; const val FCONST_2 = 13; const val DCONST_0 = 14; const val DCONST_1 = 15; const val BIPUSH = 16; const val SIPUSH = 17
    const val LDC = 18; const val LDC_W = 19; const val LDC2_W = 20
    const val ILOAD = 21; const val LLOAD = 22; const val FLOAD = 23; const val DLOAD = 24; const val ALOAD = 25
    const val ILOAD_0 = 26; const val ALOAD_3 = 45
    const val IALOAD = 46; const val SALOAD = 53
    const val ISTORE = 54; const val LSTORE = 55; const val FSTORE = 56; const val DSTORE = 57; const val ASTORE = 58
    const val ISTORE_0 = 59; const val ASTORE_3 = 78
    const val IASTORE = 79; const val SASTORE = 86
    const val POP = 87; const val POP2 = 88; const val DUP = 89; const val DUP_X1 = 90; const val DUP_X2 = 91; const val DUP2 = 92
    const val DUP2_X1 = 93; const val DUP2_X2 = 94; const val SWAP = 95
    const val IADD = 96; const val LXOR = 131; const val IINC = 132
    const val I2L = 133; const val I2S = 147; const val LCMP = 148; const val DCMPG = 152
    const val IFEQ = 153; const val IF_ACMPNE = 166; const val GOTO = 167; const val JSR = 168; const val RET = 169
    const val TABLESWITCH = 170; const val LOOKUPSWITCH = 171
    const val IRETURN = 172; const val ARETURN = 176; const val RETURN = 177
    const val GETSTATIC = 178; const val PUTSTATIC = 179; const val GETFIELD = 180; const val PUTFIELD = 181
    const val INVOKEVIRTUAL = 182; const val INVOKESPECIAL = 183; const val INVOKESTATIC = 184; const val INVOKEINTERFACE = 185
    const val INVOKEDYNAMIC = 186; const val NEW = 187; const val NEWARRAY = 188; const val ANEWARRAY = 189; const val ARRAYLENGTH = 190
    const val ATHROW = 191; const val CHECKCAST = 192; const val INSTANCEOF = 193; const val MONITORENTER = 194; const val MONITOREXIT = 195
    const val WIDE = 196; const val MULTIANEWARRAY = 197; const val IFNULL = 198; const val IFNONNULL = 199; const val GOTO_W = 200; const val JSR_W = 201

    fun isBranch(op: Int) = op in IFEQ..JSR || op == IFNULL || op == IFNONNULL || op == GOTO_W || op == JSR_W
    fun isUnconditional(op: Int) = op == GOTO || op == GOTO_W || op == JSR || op == JSR_W
    fun isStore(op: Int) = op in ISTORE..ASTORE || op in ISTORE_0..ASTORE_3

    fun name(op: Int): String = when (op) {
        INVOKEVIRTUAL -> "invokevirtual"; INVOKESPECIAL -> "invokespecial"; INVOKESTATIC -> "invokestatic"
        INVOKEINTERFACE -> "invokeinterface"; INVOKEDYNAMIC -> "invokedynamic"; GETSTATIC -> "getstatic"; PUTSTATIC -> "putstatic"
        GETFIELD -> "getfield"; PUTFIELD -> "putfield"; NEW -> "new"; LDC, LDC_W, LDC2_W -> "ldc"
        else -> "op$op"
    }
}

/** Decodifica il corpo di un metodo in istruzioni. Lancia IOException se il codice è malformato. */
object Insns {

    fun decode(code: ByteArray): List<Insn> {
        val out = ArrayList<Insn>(code.size / 2 + 4)
        var p = 0
        fun u1(at: Int) = code[at].toInt() and 0xFF
        fun s1(at: Int) = code[at].toInt()
        fun u2(at: Int) = (u1(at) shl 8) or u1(at + 1)
        fun s2(at: Int) = (s1(at) shl 8) or u1(at + 1)
        fun s4(at: Int) = (s1(at) shl 24) or (u1(at + 1) shl 16) or (u1(at + 2) shl 8) or u1(at + 3)
        try {
            while (p < code.size) {
                val off = p
                val op = u1(p)
                when (op) {
                    Op.BIPUSH -> { out.add(Insn(off, op, s1(p + 1))); p += 2 }
                    Op.SIPUSH -> { out.add(Insn(off, op, s2(p + 1))); p += 3 }
                    Op.LDC -> { out.add(Insn(off, op, u1(p + 1))); p += 2 }
                    Op.LDC_W, Op.LDC2_W -> { out.add(Insn(off, op, u2(p + 1))); p += 3 }
                    in Op.ILOAD..Op.ALOAD, in Op.ISTORE..Op.ASTORE, Op.RET -> { out.add(Insn(off, op, u1(p + 1))); p += 2 }
                    Op.NEWARRAY -> { out.add(Insn(off, op, u1(p + 1))); p += 2 }
                    Op.IINC -> { out.add(Insn(off, op, u1(p + 1), s1(p + 2))); p += 3 }
                    in Op.IFEQ..Op.JSR, Op.IFNULL, Op.IFNONNULL -> { out.add(Insn(off, op, off + s2(p + 1))); p += 3 }
                    Op.GOTO_W, Op.JSR_W -> { out.add(Insn(off, op, off + s4(p + 1))); p += 5 }
                    in Op.GETSTATIC..Op.INVOKESTATIC, Op.NEW, Op.ANEWARRAY, Op.CHECKCAST, Op.INSTANCEOF -> { out.add(Insn(off, op, u2(p + 1))); p += 3 }
                    Op.INVOKEINTERFACE, Op.INVOKEDYNAMIC -> { out.add(Insn(off, op, u2(p + 1))); p += 5 }
                    Op.MULTIANEWARRAY -> { out.add(Insn(off, op, u2(p + 1), u1(p + 3))); p += 4 }
                    Op.TABLESWITCH -> {
                        var q = (p + 4) and 3.inv()
                        val def = off + s4(q); val lo = s4(q + 4); val hi = s4(q + 8); q += 12
                        val n = hi - lo + 1
                        if (n < 0 || n > 65536) throw IOException("tableswitch non valido")
                        val t = IntArray(n) { off + s4(q + it * 4) }
                        val k = IntArray(n) { lo + it }
                        out.add(Insn(off, op, def, 0, t, k)); p = q + n * 4
                    }
                    Op.LOOKUPSWITCH -> {
                        var q = (p + 4) and 3.inv()
                        val def = off + s4(q); val n = s4(q + 4); q += 8
                        if (n < 0 || n > 65536) throw IOException("lookupswitch non valido")
                        val k = IntArray(n) { s4(q + it * 8) }
                        val t = IntArray(n) { off + s4(q + it * 8 + 4) }
                        out.add(Insn(off, op, def, 0, t, k)); p = q + n * 8
                    }
                    Op.WIDE -> {
                        val inner = u1(p + 1)
                        if (inner == Op.IINC) { out.add(Insn(off, Op.IINC, u2(p + 2), s2(p + 4))); p += 6 }
                        else { out.add(Insn(off, inner, u2(p + 2))); p += 4 }
                    }
                    else -> {
                        if (op > Op.JSR_W) throw IOException("Codice operativo sconosciuto: $op")
                        out.add(Insn(off, op)); p += 1
                    }
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            throw IOException("Codice del metodo troncato")
        }
        return out
    }

    /** Variabile locale scritta da un'istruzione di memorizzazione, o -1. */
    fun storedLocal(i: Insn): Int = when (i.op) {
        in Op.ISTORE..Op.ASTORE -> i.a
        in Op.ISTORE_0..Op.ASTORE_3 -> (i.op - Op.ISTORE_0) % 4
        Op.IINC -> i.a
        else -> -1
    }

    fun storesWide(i: Insn): Boolean = when (i.op) {
        Op.LSTORE, Op.DSTORE -> true
        in Op.ISTORE_0..Op.ASTORE_3 -> ((i.op - Op.ISTORE_0) / 4).let { it == 1 || it == 3 }
        else -> false
    }
}
