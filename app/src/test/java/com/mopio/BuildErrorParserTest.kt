package com.mopio

import com.mopio.platformio.BuildErrorParser
import org.junit.Assert.*
import org.junit.Test

class BuildErrorParserTest {

    @Test
    fun `parses gcc error line`() {
        val line = "/workspace/src/main.cpp:12:5: error: 'foo' was not declared in this scope"
        val diag = BuildErrorParser.parseLine(line)
        assertNotNull(diag)
        assertEquals("/workspace/src/main.cpp", diag!!.file)
        assertEquals(12, diag.line)
        assertEquals(5, diag.col)
        assertTrue(diag.isError)
        assertEquals("'foo' was not declared in this scope", diag.message)
    }

    @Test
    fun `parses gcc warning line`() {
        val line = "/workspace/src/main.cpp:7:3: warning: unused variable 'x' [-Wunused-variable]"
        val diag = BuildErrorParser.parseLine(line)
        assertNotNull(diag)
        assertFalse(diag!!.isError)
        assertEquals(7, diag.line)
        assertEquals("unused variable 'x' [-Wunused-variable]", diag.message)
    }

    @Test
    fun `parses note line as non-error`() {
        val line = "/workspace/src/main.cpp:5:10: note: declared here"
        val diag = BuildErrorParser.parseLine(line)
        assertNotNull(diag)
        assertFalse(diag!!.isError)
        assertEquals(5, diag.line)
        assertEquals(10, diag.col)
    }

    @Test
    fun `returns null for plain build log line`() {
        assertNull(BuildErrorParser.parseLine("Compiling .pio/build/esp32dev/src/main.cpp.o"))
        assertNull(BuildErrorParser.parseLine("Linking .pio/build/esp32dev/firmware.elf"))
        assertNull(BuildErrorParser.parseLine("[ERROR] Missing platformio.ini"))
        assertNull(BuildErrorParser.parseLine(""))
    }

    @Test
    fun `returns null for linker error without line numbers`() {
        // arm-none-eabi-g++: error: unrecognised option — no :line:col: pattern
        assertNull(BuildErrorParser.parseLine("arm-none-eabi-g++: error: unrecognized command line option '-std=gnu++17'"))
    }

    @Test
    fun `parseLines filters only diagnostics from mixed output`() {
        val lines = listOf(
            "Compiling .pio/build/esp32dev/src/main.cpp.o",
            "/workspace/src/main.cpp:12:5: error: 'foo' was not declared in this scope",
            "/workspace/src/main.cpp:15:1: warning: control reaches end of non-void function [-Wreturn-type]",
            "arm-none-eabi-g++: error: unrecognized option",
            "[EXIT 1]"
        )
        val diags = BuildErrorParser.parseLines(lines)
        assertEquals(2, diags.size)
        assertTrue(diags[0].isError)
        assertFalse(diags[1].isError)
        assertEquals(12, diags[0].line)
        assertEquals(15, diags[1].line)
    }

    @Test
    fun `parseLines strips ansi codes before parsing`() {
        val lines = listOf(
            "/workspace/src/main.cpp:3:1: error: expected ';' before '}' token"
        )
        val diags = BuildErrorParser.parseLines(lines)
        assertEquals(1, diags.size)
        assertEquals(3, diags[0].line)
    }
}
