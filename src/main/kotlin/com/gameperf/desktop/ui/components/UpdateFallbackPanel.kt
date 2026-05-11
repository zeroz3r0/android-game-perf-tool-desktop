package com.gameperf.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.core.update.UpdateAttempt
import com.gameperf.desktop.core.update.UpdateFallbackReason
import com.gameperf.desktop.core.update.UpdateFallbackState
import com.gameperf.desktop.core.update.UpdateOutcome
import com.gameperf.desktop.ui.theme.Cyan
import com.gameperf.desktop.ui.theme.Orange
import com.gameperf.desktop.ui.theme.TextDim
import com.gameperf.desktop.ui.theme.TextPrimary
import com.gameperf.desktop.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.4.1 — Fallback panel rendered when [UpdateFallbackState] is non-null.
 *
 * Spec REQ "Fallback panel display" (P1, P2, P3) + REQ "Fallback panel dismissal" (D1):
 *  - Spanish (tuteo formal) heading + subtitle that varies per [UpdateFallbackReason]
 *  - Two action buttons: "Descargar manualmente vX.Y.Z" and "Ver guía de instalación"
 *  - Expandable "Detalles técnicos" section showing the last 10 [recentAttempts]
 *  - Dismiss icon (X) wired to [onDismiss]
 *
 * Pure presentational composable: takes pre-collected state + callbacks, no
 * StateFlow / coroutine knowledge. Wiring lives in `HomeScreen`.
 *
 * @param state           non-null fallback state to render
 * @param onDismiss       invoked when the user clicks the close icon
 * @param onOpenDownload  invoked when the user clicks "Descargar manualmente"
 * @param onOpenGuide     invoked when the user clicks "Ver guía de instalación"
 * @param recentAttempts  history snapshot; rendered inside "Detalles técnicos"
 */
@Composable
fun UpdateFallbackPanel(
    state: UpdateFallbackState,
    onDismiss: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenGuide: () -> Unit,
    recentAttempts: List<UpdateAttempt>,
    modifier: Modifier = Modifier,
) {
    var detailsExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Orange.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            // ===== Header row: icon + heading + dismiss =====
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Orange,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Actualización fallida",
                        color = Orange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = subtitleFor(state.reason),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = TextDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Action buttons =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onOpenDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Descargar manualmente v${state.attemptedVersion}",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
                OutlinedButton(
                    onClick = onOpenGuide,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = Cyan,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Ver guía de instalación",
                        color = Cyan,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== Expandable "Detalles técnicos" =====
            TextButton(
                onClick = { detailsExpanded = !detailsExpanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Icon(
                    if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Detalles técnicos",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (detailsExpanded) {
                TechnicalDetails(recentAttempts = recentAttempts)
            }
        }
    }
}

/**
 * Renders the last ~10 [UpdateAttempt] rows in a compact monospace block
 * showing `timestamp  fromVersion → toVersion  outcome`. Empty list shows
 * an explanatory placeholder.
 */
@Composable
private fun TechnicalDetails(recentAttempts: List<UpdateAttempt>) {
    val rows = recentAttempts.takeLast(MAX_DETAIL_ROWS).reversed() // newest first
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (rows.isEmpty()) {
            Text(
                text = "No hay intentos previos registrados.",
                color = TextDim,
                fontSize = 11.sp,
            )
        } else {
            Column(
                modifier = Modifier.heightInDp(MAX_DETAIL_HEIGHT_DP),
            ) {
                rows.forEach { row ->
                    Text(
                        text = formatAttemptRow(row),
                        color = TextPrimary.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Width-constrained vertical scroll wrapper to keep the panel from growing unboundedly. */
@Composable
private fun Modifier.heightInDp(maxDp: Int): Modifier =
    this
        .height(maxDp.dp)
        .verticalScroll(rememberScrollState())

private val ROW_TS_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

private fun formatAttemptRow(row: UpdateAttempt): String {
    val ts = ROW_TS_FORMAT.format(Date(row.timestamp))
    val outcome = outcomeShortName(row.outcome)
    return "$ts  ${row.fromVersion} → ${row.toVersion}  $outcome"
}

private fun outcomeShortName(outcome: UpdateOutcome): String = when (outcome) {
    is UpdateOutcome.Success -> "Success"
    is UpdateOutcome.FailedUacDenied -> "FailedUacDenied"
    is UpdateOutcome.FailedWatchdogTimeout -> "FailedWatchdogTimeout"
    is UpdateOutcome.FailedDownload -> "FailedDownload"
    is UpdateOutcome.FailedHelperCrash -> "FailedHelperCrash"
    is UpdateOutcome.FailedUnknown -> "FailedUnknown"
}

private fun subtitleFor(reason: UpdateFallbackReason): String = when (reason) {
    UpdateFallbackReason.USER_CANCELLED_UAC ->
        "Cancelaste la elevación de permisos. Volvé a probar y aceptá el mensaje de Windows."
    UpdateFallbackReason.HELPER_TIMEOUT ->
        "El proceso de elevación tardó demasiado. Probá descargar manualmente la nueva versión."
    UpdateFallbackReason.DOWNLOAD_FAILED ->
        "No pudimos descargar la nueva versión. Revisá tu conexión y probá de nuevo."
    UpdateFallbackReason.HELPER_CRASHED ->
        "El proceso de actualización falló inesperadamente. Probá descargar manualmente."
    UpdateFallbackReason.UNKNOWN ->
        "Ocurrió un error inesperado al actualizar. Probá descargar manualmente."
}

private const val MAX_DETAIL_ROWS = 10
private const val MAX_DETAIL_HEIGHT_DP = 140
