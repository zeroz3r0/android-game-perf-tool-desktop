package com.gameperf.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.res.painterResource
import java.awt.Dimension
import com.gameperf.desktop.ui.screens.CaptureScreen
import com.gameperf.desktop.ui.screens.ComparisonScreen
import com.gameperf.desktop.ui.screens.HomeScreen
import com.gameperf.desktop.ui.screens.ResultsScreen
import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.ui.theme.AppColors
import com.gameperf.desktop.ui.theme.DarkBg
import com.gameperf.desktop.viewmodel.AppScreen
import com.gameperf.desktop.viewmodel.AppViewModel
import javafx.application.Platform

fun main() {
    // Initialize JavaFX toolkit ONCE before any JFXPanel usage.
    // Platform.startup is idempotent — safe if already started.
    try {
        Platform.startup {}
        Platform.setImplicitExit(false)
    } catch (_: IllegalStateException) {
        // Already initialized — ignore
    }

    application {
    val vm = remember { AppViewModel() }

    LaunchedEffect(Unit) {
        vm.init()
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = AppVersion.FULL,
        icon = painterResource("app-icon.png"),
        state = WindowState(size = DpSize(960.dp, 700.dp))
    ) {
        window.minimumSize = Dimension(800, 600)
        MaterialTheme(colorScheme = AppColors) {
            val screen by vm.screen.collectAsState()

            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().background(DarkBg)
            ) {
                when (screen) {
                    AppScreen.HOME -> HomeScreen(vm)
                    AppScreen.CAPTURING -> CaptureScreen(vm)
                    AppScreen.RESULTS -> ResultsScreen(vm)
                    AppScreen.COMPARISON -> ComparisonScreen(vm)
                }
            }
        }
    }
}
}
