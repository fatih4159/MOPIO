package com.mopio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mopio.phase0.Phase0Screen
import com.mopio.ui.theme.MopioTheme

/**
 * Single-activity host. Navigation graph is added in Phase 2+.
 * During Phase 0 the Phase0Screen (hardware spike runner) is shown directly.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MopioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Phase0Screen()
                }
            }
        }
    }
}
