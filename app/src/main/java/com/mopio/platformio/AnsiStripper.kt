package com.mopio.platformio

/** Strips ANSI/VT100 escape sequences from PlatformIO build output. */
object AnsiStripper {
    // Matches ESC [ ... m color codes and other CSI sequences
    private val ANSI_RE = Regex("\\[[0-9;]*[A-Za-z]")

    fun strip(text: String): String = ANSI_RE.replace(text, "")

    /** Annotates a stripped line as error, warning, or plain. */
    enum class LineType { ERROR, WARNING, INFO }

    data class AnnotatedLine(val text: String, val type: LineType)

    fun annotate(raw: String): AnnotatedLine {
        val stripped = strip(raw)
        val type = when {
            stripped.contains(": error:")   -> LineType.ERROR
            stripped.contains(": warning:") -> LineType.WARNING
            stripped.startsWith("[ERROR]")  -> LineType.ERROR
            else                             -> LineType.INFO
        }
        return AnnotatedLine(stripped, type)
    }
}
