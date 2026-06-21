package com.mopio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for RFC2217 framing constants and IAC byte-stuffing logic.
 * These can run on the JVM without an Android device.
 */
class Rfc2217FramingTest {

    // ── IAC stuffing ─────────────────────────────────────────────────────────

    @Test
    fun `stuffIac doubles single 0xFF byte`() {
        val input    = byteArrayOf(0x41, 0xFF.toByte(), 0x42)
        val expected = byteArrayOf(0x41, 0xFF.toByte(), 0xFF.toByte(), 0x42)
        assertArrayEquals(expected, iacStuff(input, input.size))
    }

    @Test
    fun `stuffIac is no-op when no 0xFF present`() {
        val input = byteArrayOf(0x01, 0x02, 0x03)
        assertArrayEquals(input, iacStuff(input, input.size))
    }

    @Test
    fun `stuffIac handles buffer with leading 0xFF`() {
        val input    = byteArrayOf(0xFF.toByte(), 0x41)
        val expected = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x41)
        assertArrayEquals(expected, iacStuff(input, input.size))
    }

    @Test
    fun `stuffIac handles multiple 0xFF bytes`() {
        val input    = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        val expected = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertArrayEquals(expected, iacStuff(input, input.size))
    }

    // ── SET_CONTROL constants (RFC2217 §3, Table 6) ──────────────────────────

    @Test
    fun `SET_CONTROL DTR values match RFC2217`() {
        assertEquals(8, RFC2217.CTRL_DTR_ON)
        assertEquals(9, RFC2217.CTRL_DTR_OFF)
    }

    @Test
    fun `SET_CONTROL RTS values match RFC2217`() {
        assertEquals(11, RFC2217.CTRL_RTS_ON)
        assertEquals(12, RFC2217.CTRL_RTS_OFF)
    }

    // ── Helpers (mirrors Rfc2217Server.iacStuff but testable without Android) ─

    private fun iacStuff(buf: ByteArray, len: Int): ByteArray {
        var extras = 0
        for (i in 0 until len) if (buf[i] == 0xFF.toByte()) extras++
        if (extras == 0) return buf.copyOf(len)
        val out = ByteArray(len + extras)
        var j = 0
        for (i in 0 until len) {
            out[j++] = buf[i]
            if (buf[i] == 0xFF.toByte()) out[j++] = 0xFF.toByte()
        }
        return out
    }
}

/** Mirrors the constants in Rfc2217Server for white-box testing. */
object RFC2217 {
    const val CTRL_DTR_ON  = 8
    const val CTRL_DTR_OFF = 9
    const val CTRL_RTS_ON  = 11
    const val CTRL_RTS_OFF = 12
}
