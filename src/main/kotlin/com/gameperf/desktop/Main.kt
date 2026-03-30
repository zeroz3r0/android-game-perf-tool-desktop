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
import java.awt.Dimension
import com.gameperf.desktop.ui.screens.CaptureScreen
import com.gameperf.desktop.ui.screens.HomeScreen
import com.gameperf.desktop.ui.screens.ResultsScreen
import com.gameperf.desktop.ui.theme.AppColors
import com.gameperf.desktop.ui.theme.DarkBg
import com.gameperf.desktop.viewmodel.AppScreen
import com.gameperf.desktop.viewmodel.AppViewModel

fun main() = application {
    val vm = remember { AppViewModel() }

    LaunchedEffect(Unit) {
        vm.init()
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Game Performance Tool",
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
                }
            }
        }
    }
}
