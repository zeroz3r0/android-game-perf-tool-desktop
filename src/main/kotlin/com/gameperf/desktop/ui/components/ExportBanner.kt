package com.gameperf.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.viewmodel.AppViewModel
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.net.URI

/**
 * Inline status banner consumed by HomeScreen, ResultsScreen and ComparisonScreen to
 * provide unobtrusive feedback for the PDF export pipeline.
 *
 * Visible only for [AppViewModel.ExportStatus.InProgress], [AppViewModel.ExportStatus.Success]
 * and [AppViewModel.ExportStatus.Error]. Auto-dismisses after 3 seconds for terminal
 * states (Success / Error) by calling [onDismiss], which the screen wires to
 * `vm.resetExportStatus()`.
 *
 * The Error branch renders an inline action button when both `actionUrl` and
 * `actionLabel` are non-null (used for the "Descargar Chrome" CTA when no
 * Chromium-based browser is detected).
 */
@Composable
fun ExportBanner(
    status: AppViewModel.ExportStatus,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(status) {
        if (status is AppViewModel.ExportStatus.Success || status is AppViewModel.ExportStatus.Error) {
            delay(3000)
            onDismiss()
        }
    }
    when (status) {
        is AppViewModel.ExportStatus.InProgress -> {
            Surface(
                color = Color(0xFF1E3A8A), // deep blue
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Exportando PDF...",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }
        is AppViewModel.ExportStatus.Success -> {
            Surface(
                color = Color(0xFF10B981), // green
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircleOutline,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "PDF exportado: ${status.path}",
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        is AppViewModel.ExportStatus.Error -> {
            Surface(
                color = Color(0xFFEF4444), // red
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        status.message,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (status.actionUrl != null && status.actionLabel != null) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                runCatching {
                                    if (Desktop.isDesktopSupported() &&
                                        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                                    ) {
                                        Desktop.getDesktop().browse(URI(status.actionUrl))
                                    }
                                }.onFailure {
                                    System.err.println("ExportBanner: Desktop.browse falló: ${it.message}")
                                }
                            }
                        ) {
                            Text(status.actionLabel, color = Color.White)
                        }
                    }
                }
            }
        }
        else -> {
            // Idle: render nothing.
        }
    }
}
