package com.mopio.platformio

import java.io.File

/**
 * Parses GCC-style error/warning lines from PlatformIO build output.
 * Format: <file>:<line>:<col>: (error|warning): <message>
 */
object BuildErrorParser {

    data class BuildDiagnostic(
        val file: String,
        val line: Int,
        val col: Int,
        val isError: Boolean,
        val message: String
    )

    // Matches: /path/to/file.cpp:12:5: error: 'foo' was not declared in this scope
    private val GCC_RE = Regex("""^(.+?):(\d+):(\d+):\s+(error|warning|note):\s+(.+)$""")

    fun parseLine(stripped: String): BuildDiagnostic? {
        val m = GCC_RE.matchEntire(stripped.trim()) ?: return null
        return BuildDiagnostic(
            file    = m.groupValues[1],
            line    = m.groupValues[2].toIntOrNull() ?: 0,
            col     = m.groupValues[3].toIntOrNull() ?: 0,
            isError = m.groupValues[4] == "error",
            message = m.groupValues[5]
        )
    }

    fun parseLines(lines: List<String>): List<BuildDiagnostic> =
        lines.mapNotNull { parseLine(AnsiStripper.strip(it)) }

    /** Returns the path relative to the project root if possible. */
    fun relativePath(diag: BuildDiagnostic, projectDir: File): String =
        try { File(diag.file).relativeTo(projectDir).path } catch (_: Exception) { diag.file }
}
