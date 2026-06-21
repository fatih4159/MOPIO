package com.mopio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mopio.ui.nav.AppNav
import com.mopio.ui.theme.MopioTheme

/**
 * Single-activity host.
 *
 * Phase 0: Phase0Screen shown directly (via AppNav which routes to Phase0 when
 *          needed for hardware testing — accessible from Settings in later phases).
 * Phase 1+: [AppNav] handles all routing. Start destination is determined by whether
 *           the Linux container is already bootstrapped.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MopioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
}
