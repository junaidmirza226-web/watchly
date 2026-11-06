package com.watchly.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.watchly.app.ui.nav.WatchlyApp
import com.watchly.app.ui.theme.WatchlyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchlyTheme {
                WatchlyApp()
            }
        }
    }
}
