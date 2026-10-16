package com.pocketlm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketlm.app.ui.PocketLmApp
import com.pocketlm.app.ui.PocketLmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: PocketViewModel = viewModel()
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            PocketLmTheme(mode) {
                PocketLmApp(vm)
            }
        }
    }
}
