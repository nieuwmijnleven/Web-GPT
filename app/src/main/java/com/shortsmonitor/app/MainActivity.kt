package com.shortsmonitor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shortsmonitor.app.ui.ShortsMonitorApp
import com.shortsmonitor.core.design.ShortsMonitorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShortsMonitorTheme {
                ShortsMonitorApp()
            }
        }
    }
}
