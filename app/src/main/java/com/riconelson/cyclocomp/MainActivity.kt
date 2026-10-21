package com.riconelson.cyclocomp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.riconelson.cyclocomp.theme.CyclocompTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CyclocompTheme {
                com.riconelson.cyclocomp.ui.CyclocompApp()
            }
        }
    }
}
