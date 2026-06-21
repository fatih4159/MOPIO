package com.mopio

import com.mopio.platformio.PlatformIoIniParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformIoIniParserTest {

    @Test
    fun `parses single env`() {
        val ini = """
            [env:esp32dev]
            platform = espressif32
            board    = esp32dev
            framework = arduino
        """.trimIndent()
        val cfg = PlatformIoIniParser.parse(ini)
        assertEquals(1, cfg.envs.size)
        assertEquals("esp32dev", cfg.envs[0].name)
        assertEquals("espressif32", cfg.envs[0].platform)
        assertEquals("esp32dev", cfg.envs[0].board)
        assertEquals("arduino", cfg.envs[0].framework)
    }

    @Test
    fun `parses multiple envs`() {
        val ini = """
            [env:wroom]
            board = esp32dev
            platform = espressif32
            framework = arduino

            [env:c3]
            board = esp32-c3-devkitm-1
            platform = espressif32
            framework = arduino
        """.trimIndent()
        val cfg = PlatformIoIniParser.parse(ini)
        assertEquals(2, cfg.envs.size)
        assertEquals("wroom", cfg.envs[0].name)
        assertEquals("c3", cfg.envs[1].name)
    }

    @Test
    fun `ignores comment lines`() {
        val ini = """
            ; this is a comment
            [env:test]
            # also a comment
            board = esp32dev
            platform = espressif32
        """.trimIndent()
        val cfg = PlatformIoIniParser.parse(ini)
        assertEquals(1, cfg.envs.size)
        assertEquals("esp32dev", cfg.envs[0].board)
    }

    @Test
    fun `inherits global env section`() {
        val ini = """
            [env]
            framework = arduino

            [env:main]
            board = esp32dev
            platform = espressif32
        """.trimIndent()
        val cfg = PlatformIoIniParser.parse(ini)
        assertEquals("arduino", cfg.envs[0].framework)
    }

    @Test
    fun `strips inline comments from values`() {
        val ini = """
            [env:test]
            board = esp32dev ; the classic dev board
            platform = espressif32
        """.trimIndent()
        val cfg = PlatformIoIniParser.parse(ini)
        assertEquals("esp32dev", cfg.envs[0].board)
    }
}
