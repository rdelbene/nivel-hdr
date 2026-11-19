package com.rdelbene.nivelhdr

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/*
 * Diferencia binaria entre el original de una foto y la versión que dejó la app, para la Papelera.
 * Con la versión actual y esta diferencia se rearma el original exacto, byte a byte.
 *
 * La imagen principal (desde el SOS hasta el EOI) es casi siempre idéntica en las dos: la app solo cambia
 * metadatos y el gain map. Se busca ese bloque en las dos versiones y se guarda solo lo que está antes y
 * después de él que cambió:
 *  - cambio de nivel (mismo tamaño): solo los tramos distintos, unos 20 bytes;
 *  - Ultra HDR agregado: casi nada (lo nuevo se saca, no hay que guardarlo);
 *  - cambio de estilo: el gain map original (~300 KB).
 * Si no encuentra el bloque, guarda lo distinto entre el comienzo y el final iguales (en el peor caso, todo).
 * No usa nada de Android, para poder probarlo en la computadora.
 */
object Delta {
    sealed class Op {
        /** Copiar [len] bytes de la versión actual, desde [off]. */
        class Copy(val off: Int, val len: Int) : Op()
        /** Bytes del original que no están en la versión actual. */
        class Data(val bytes: ByteArray) : Op()
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** Bytes del original que hay que guardar (lo que ocupa en la papelera, sin el encabezado). */
    fun storedBytes(ops: List<Op>): Long = ops.sumOf { if (it is Op.Data) it.bytes.size.toLong() + 5 else 9L }

    /** Comienzo del SOS y fin (después del EOI) de la imagen principal, o null si no es una JPEG que se entienda. */
    fun scanRange(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var p = 2
        while (p + 4 <= b.size) {
            if (b[p] != 0xFF.toByte()) return null
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xFF) { p++; continue }
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { p += 2; continue }
            if (m == 0xD9) return null
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            val end = p + 2 + len
            if (len < 2 || end > b.size) return null
            if (m == 0xDA) {
                // en los datos comprimidos un FF va seguido de 00 o de un marcador RST: el primer FF D9 es el fin
                var i = end
                while (i < b.size - 1) {
                    if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD9.toByte()) return p to i + 2
                    i++
                }
                return null
            }
            p = end
        }
        return null
    }

    private fun same(a: ByteArray, a0: Int, b: ByteArray, b0: Int, n: Int): Boolean {
        for (k in 0 until n) if (a[a0 + k] != b[b0 + k]) return false
        return true
    }

    /** Operaciones para rearmar [orig] a partir de [cur]. */
    fun diff(orig: ByteArray, cur: ByteArray): List<Op> {
        val ops = mutableListOf<Op>()
        val a = scanRange(orig)
        val b = scanRange(cur)
        if (a != null && b != null && a.second - a.first == b.second - b.first &&
            same(orig, a.first, cur, b.first, a.second - a.first)) {
            region(orig, 0, a.first, cur, 0, b.first, ops)
            ops += Op.Copy(b.first, b.second - b.first)
            region(orig, a.second, orig.size, cur, b.second, cur.size, ops)
        } else {
            region(orig, 0, orig.size, cur, 0, cur.size, ops)
        }
        return merge(ops)
    }

    /** Tramo [a0, a1) del original contra el tramo [b0, b1) de la versión actual. */
    private fun region(a: ByteArray, a0: Int, a1: Int, b: ByteArray, b0: Int, b1: Int, ops: MutableList<Op>) {
        val la = a1 - a0
        val lb = b1 - b0
        if (la == 0) return
        if (la == lb) {
            // mismo tamaño (cambio de nivel): solo los tramos distintos; se juntan los que están a menos de 32 bytes
            var i = 0
            var copyFrom = 0
            while (i < la) {
                if (a[a0 + i] == b[b0 + i]) { i++; continue }
                var last = i
                var k = i + 1
                while (k < la && k - last <= 32) { if (a[a0 + k] != b[b0 + k]) last = k; k++ }
                if (i > copyFrom) ops += Op.Copy(b0 + copyFrom, i - copyFrom)
                ops += Op.Data(a.copyOfRange(a0 + i, a0 + last + 1))
                copyFrom = last + 1
                i = last + 1
            }
            if (la > copyFrom) ops += Op.Copy(b0 + copyFrom, la - copyFrom)
            return
        }
        // distinto tamaño: comienzo y final iguales, y lo del medio se guarda
        val mn = minOf(la, lb)
        var p = 0
        while (p < mn && a[a0 + p] == b[b0 + p]) p++
        var s = 0
        while (s < mn - p && a[a1 - 1 - s] == b[b1 - 1 - s]) s++
        if (p > 0) ops += Op.Copy(b0, p)
        if (la - p - s > 0) ops += Op.Data(a.copyOfRange(a0 + p, a1 - s))
        if (s > 0) ops += Op.Copy(b1 - s, s)
    }

    private fun merge(ops: List<Op>): List<Op> {
        val out = mutableListOf<Op>()
        for (op in ops) {
            val last = out.lastOrNull()
            when {
                op is Op.Copy && op.len == 0 -> {}
                op is Op.Data && op.bytes.isEmpty() -> {}
                op is Op.Copy && last is Op.Copy && last.off + last.len == op.off -> out[out.size - 1] = Op.Copy(last.off, last.len + op.len)
                op is Op.Data && last is Op.Data -> out[out.size - 1] = Op.Data(last.bytes + op.bytes)
                else -> out += op
            }
        }
        return out
    }

    /** Rearma el original. Null si las operaciones no corresponden a esta versión (se salen del archivo). */
    fun apply(ops: List<Op>, cur: ByteArray): ByteArray? {
        val bos = ByteArrayOutputStream(cur.size + 1024)
        for (op in ops) when (op) {
            is Op.Copy -> { if (op.off < 0 || op.len < 0 || op.off + op.len > cur.size) return null; bos.write(cur, op.off, op.len) }
            is Op.Data -> bos.write(op.bytes)
        }
        return bos.toByteArray()
    }

    fun write(out: DataOutputStream, ops: List<Op>) {
        out.writeInt(ops.size)
        for (op in ops) when (op) {
            is Op.Copy -> { out.writeByte(0); out.writeInt(op.off); out.writeInt(op.len) }
            is Op.Data -> { out.writeByte(1); out.writeInt(op.bytes.size); out.write(op.bytes) }
        }
    }

    fun read(inp: DataInputStream): List<Op> {
        val n = inp.readInt()
        require(n in 0..1_000_000)
        val ops = ArrayList<Op>(n)
        repeat(n) {
            when (inp.readByte().toInt()) {
                0 -> ops += Op.Copy(inp.readInt(), inp.readInt())
                1 -> { val len = inp.readInt(); require(len in 0..200_000_000); ops += Op.Data(ByteArray(len).also { inp.readFully(it) }) }
                else -> throw IllegalStateException("operación desconocida")
            }
        }
        return ops
    }
}
