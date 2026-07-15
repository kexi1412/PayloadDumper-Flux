package com.flux.payload.dumper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flux.payload.dumper.ui.DumperScreen
import com.flux.payload.dumper.ui.theme.PayloadDumperFluxTheme
import com.flux.payload.dumper.viewmodel.DumperViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PayloadDumperFluxTheme {
                val vm: DumperViewModel = viewModel()
                DumperScreen(vm)
            }
        }
    }
}
