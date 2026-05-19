package com.gameperf.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.DarkCard
import com.gameperf.desktop.ui.theme.Purple
import com.gameperf.desktop.ui.theme.Red
import com.gameperf.desktop.ui.theme.TextPrimary
import com.gameperf.desktop.ui.theme.TextSecondary
import com.gameperf.desktop.viewmodel.AppViewModel

/**
 * v4.3.7 — Layer 4 of the session-history loss-prevention rollout.
 *
 * Shown when [AppViewModel.evictionPending] is non-null. The user is about to insert a
 * new history entry that would evict a REAL non-favorite session — we show the name of
 * what's about to be lost and let them choose to:
 *  - "Marcar favorita" (recommended) — star the evictable so it can never be auto-evicted
 *  - "Eliminar de todas formas" — proceed with the eviction, deleting orphan report + video
 *  - "Cancelar" — discard the new entry
 *
 * UI strings: neutral Castilian Spanish (Spain) **"tú" form** (per CLAUDE.md), e.g. "vas a perder", "márcala".
 */
@Composable
fun EvictionConfirmDialog(
    pending: AppViewModel.EvictionPendingState,
    onDecision: (AppViewModel.EvictionDecision) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDecision(AppViewModel.EvictionDecision.CANCEL) },
        containerColor = DarkCard,
        shape = RoundedCornerShape(16.dp),
        icon = {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Red)
        },
        title = {
            Text(
                "¿Eliminar la sesión más antigua?",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            // The user-visible text mirrors the spec: name what's about to be lost so the
            // choice is concrete, not abstract.
            Text(
                buildString {
                    append("Vas a perder la sesión más antigua: «")
                    append(pending.evictableEntry.name)
                    append("». ¿Quieres marcarla como favorita primero para conservarla?")
                },
                color = TextSecondary,
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            // Primary CTA = the SAFE option (favorite the existing entry). The user has to
            // make a deliberate choice to actually evict.
            androidx.compose.material3.Button(
                onClick = { onDecision(AppViewModel.EvictionDecision.FAVORITE_EXISTING) },
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Marcar favorita", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            // Secondary actions — destructive option visually muted via OutlinedButton.
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onDecision(AppViewModel.EvictionDecision.EVICT) },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Eliminar de todas formas", color = Red, fontSize = 12.sp)
                }
                TextButton(
                    onClick = { onDecision(AppViewModel.EvictionDecision.CANCEL) },
                ) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        },
    )
}
