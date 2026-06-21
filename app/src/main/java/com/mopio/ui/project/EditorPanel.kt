package com.mopio.ui.project

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.text.Content

/**
 * Wraps [CodeEditor] (sora-editor) in a Compose [AndroidView].
 *
 * The editor is configured with:
 *  - A dark colour scheme mirroring VSCode Dark+
 *  - Monospace typeface
 *  - Line numbers, word-wrap off (typical for code)
 *
 * Language/TextMate grammar is set via [language]; null = plain text.
 * Full TextMate C/C++ grammar integration: drop *.tmLanguage.json into assets/
 * and wire up via sora-editor's TextMateLanguage (Phase 2 polish task).
 */
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
                setTypeface(Typeface.MONOSPACE)
                isWordwrap = false
                isLineNumberEnabled = true
                colorScheme = buildDarkScheme()
                setText(content)
                subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) { _, _ ->
                    onContentChange(text.toString())
                }
            }
        },
        update = { editor ->
            // Only update if content differs to avoid cursor-jump on each keystroke
            if (editor.text.toString() != content) {
                val sel = editor.cursor.rightLine to editor.cursor.rightColumn
                editor.setText(content)
                runCatching {
                    editor.setSelection(sel.first.coerceAtMost(editor.lineCount - 1), 0)
                }
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
