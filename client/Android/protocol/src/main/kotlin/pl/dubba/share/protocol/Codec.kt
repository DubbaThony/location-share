package pl.dubba.share.protocol

import java.io.ByteArrayOutputStream

/**
 * Little-endian stateful encoder. Mirrors the Go reference
 * (server/protocol/marshaller/abstract_en_de_coder.go).
 *
 * Variable-length byte fields are uint16-length-prefixed via [bytes];
 * fixed-size arrays use [bytesNoHeader]. 64-bit values are [Long],
 * smaller values [Int] — bit patterns round-trip identically to Go's
 * unsigned types regardless of sign interpretation.
 */
class StatefulEncoder {
    private val out = ByteArrayOutputStream(64)

    fun u8(v: Int) = apply { out.write(v and 0xFF) }

    fun u16(v: Int) = apply {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    fun u32(v: Long) = apply {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }

    fun u64(v: Long) = apply {
        var x = v
        repeat(8) {
            out.write((x and 0xFF).toInt())
            x = x ushr 8
        }
    }

    fun bytes(b: ByteArray) = apply {
        u16(b.size)
        out.write(b)
    }

    fun bytesNoHeader(b: ByteArray) = apply { out.write(b) }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Little-endian stateful decoder. Mirrors the Go reference. Tracks a fail flag;
 * once any read runs past the end, [ok] stays false. [ok] additionally requires
 * that the entire input was consumed (catches over-long frames), matching the
 * Go `Ok()` semantics. Failed reads return zero values / zero-filled arrays so
 * callers can defer the [ok] check to the end.
 */
class StatefulDecoder(private val input: ByteArray) {
    private var ptr = 0
    private var failed = false

    val ok: Boolean get() = !failed && ptr == input.size

    private fun assertLeft(n: Int): Boolean {
        if (failed) return false
        if (ptr + n > input.size) {
            failed = true
            return false
        }
        return true
    }

    fun u8(): Int {
        if (!assertLeft(1)) return 0
        return input[ptr++].toInt() and 0xFF
    }

    fun u16(): Int {
        if (!assertLeft(2)) return 0
        val v = (input[ptr].toInt() and 0xFF) or
            ((input[ptr + 1].toInt() and 0xFF) shl 8)
        ptr += 2
        return v
    }

    fun u32(): Long {
        if (!assertLeft(4)) return 0
        var v = 0L
        for (i in 0 until 4) {
            v = v or ((input[ptr + i].toLong() and 0xFF) shl (8 * i))
        }
        ptr += 4
        return v
    }

    fun u64(): Long {
        if (!assertLeft(8)) return 0
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((input[ptr + i].toLong() and 0xFF) shl (8 * i))
        }
        ptr += 8
        return v
    }

    fun bytes(): ByteArray {
        val n = u16()
        if (n == 0) return ByteArray(0)
        return bytesNoHeader(n)
    }

    fun bytesNoHeader(n: Int): ByteArray {
        if (!assertLeft(n)) return ByteArray(n)
        val slice = input.copyOfRange(ptr, ptr + n)
        ptr += n
        return slice
    }
}
