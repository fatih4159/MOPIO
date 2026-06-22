package com.mopio.ui.project

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

@Composable
fun EditorPanel(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = MaterialTheme.colorScheme.surface

    AndroidView(
        factory = { ctx ->
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                isWordwrap = false
                isLineNumberEnabled = true
                colorScheme = buildDarkScheme()
                // suppress[0] = true while we're programmatically setting text to break
                // the recomposition loop: update → setText → ContentChangeEvent → onContentChange → recompose → update
                tag = booleanArrayOf(false)
                setText(content)
                subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) { _ ->
                    val suppress = tag as? BooleanArray
                    if (suppress == null || !suppress[0]) onContentChange(text.toString())
                }
            }
        },
        update = { editor ->
            if (editor.text.toString() != content) {
                val suppress = editor.tag as? BooleanArray
                suppress?.set(0, true)
                val savedLine = editor.cursor.rightLine
                editor.setText(content)
                runCatching { editor.setSelection(savedLine.coerceAtMost(editor.lineCount - 1), 0) }
                suppress?.set(0, false)
            }
        },
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
    )
}

private fun buildDarkScheme(): EditorColorScheme = EditorColorScheme().apply {
    // Background colours
    setColor(EditorColorScheme.WHOLE_BACKGROUND,       0xFF1E1E1E.toInt())
    setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, 0xFF1E1E1E.toInt())
    setColor(EditorColorScheme.LINE_NUMBER,            0xFF858585.toInt())
    setColor(EditorColorScheme.TEXT_NORMAL,            0xFFD4D4D4.toInt())
    setColor(EditorColorScheme.SELECTION_INSERT,       0xFF569CD6.toInt())
    setColor(EditorColorScheme.CURRENT_LINE,           0xFF2D2D2D.toInt())
}
