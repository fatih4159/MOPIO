package com.mopio

import com.mopio.platformio.AnsiStripper
import org.junit.Assert.*
import org.junit.Test

class AnsiStripperTest {

    @Test
    fun `strip removes color reset sequence`() {
        assertEquals("hello", AnsiStripper.strip("[0mhello"))
    }

    @Test
    fun `strip removes bold red color`() {
        assertEquals("error", AnsiStripper.strip("[1;31merror[0m"))
    }

    @Test
    fun `strip removes cursor movement sequences`() {
        assertEquals("abc", AnsiStripper.strip("a[2Kbc"))
    }

    @Test
    fun `strip is no-op on plain text`() {
        val text = "/workspace/src/main.cpp:12:5: error: undeclared"
        assertEquals(text, AnsiStripper.strip(text))
    }

    @Test
    fun `strip handles multiple sequences in one line`() {
        val raw = "[01m[K/path/to/file.cpp[m[K:3:1: [01;31m[Kerror:[m[K expected ';'"
        val result = AnsiStripper.strip(raw)
        assertTrue("Should keep path", result.contains("/path/to/file.cpp"))
        assertTrue("Should keep error text", result.contains("error:"))
        assertFalse("Should not contain ESC-bracket sequences", result.contains("["))
    }

    @Test
    fun `annotate classifies gcc error line`() {
        val ann = AnsiStripper.annotate("/workspace/src/main.cpp:12:5: error: undeclared")
        assertEquals(AnsiStripper.LineType.ERROR, ann.type)
    }

    @Test
    fun `annotate classifies gcc warning line`() {
        val ann = AnsiStripper.annotate("/workspace/src/main.cpp:7:3: warning: unused")
        assertEquals(AnsiStripper.LineType.WARNING, ann.type)
    }

    @Test
    fun `annotate classifies plain build line as info`() {
        val ann = AnsiStripper.annotate("Compiling .pio/build/esp32dev/src/main.cpp.o")
        assertEquals(AnsiStripper.LineType.INFO, ann.type)
    }

    @Test
    fun `annotate classifies platformio ERROR prefix`() {
        val ann = AnsiStripper.annotate("[ERROR] Missing platformio.ini")
        assertEquals(AnsiStripper.LineType.ERROR, ann.type)
    }

    @Test
    fun `annotate strips ansi before classifying`() {
        val ann = AnsiStripper.annotate("[01;31merror:[0m something bad")
        // "error:" alone is not the GCC format ": error:" so it's INFO
        // (the colon before "error" is required for ERROR classification)
        assertEquals(AnsiStripper.LineType.INFO, ann.type)
    }
}
