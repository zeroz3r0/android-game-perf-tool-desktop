package com.gameperf.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.Cyan
import com.gameperf.desktop.ui.theme.DarkCard
import com.gameperf.desktop.ui.theme.TextPrimary
import com.gameperf.desktop.ui.theme.TextSecondary

/**
 * One-time privacy disclaimer surfaced before the first "Enlace temporal"
 * upload. Per `v4.7.1` design + per the temp.sh operator's published
 * guidelines for client software: programs that upload user files MUST get
 * explicit consent BEFORE the first upload and disclose the nature of the
 * service (public, anonymous host, third-party operator).
 *
 * When the user accepts, [com.gameperf.desktop.viewmodel.AppViewModel.confirmTempLinkShare]
 * persists the acceptance in [com.gameperf.desktop.core.Settings] so this
 * dialog does not appear again on subsequent uploads. Cancel-out leaves the
 * preference unchanged and the dialog will return on the next attempt.
 *
 * Spanish-tuteo copy per CLAUDE.md i18n convention.
 */
@Composable
fun TempLinkDisclaimerDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = DarkCard,
        shape = RoundedCornerShape(16.dp),
        icon = {
            Icon(Icons.Default.Info, contentDescription = null, tint = Cyan)
        },
        title = {
            Text(
                "Compartir como enlace temporal",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(
                    "El informe HTML se subirá a temp.sh, un servicio público y anónimo " +
                        "operado por terceros en Alemania. Cualquier persona con el " +
                        "enlace podrá abrirlo en el navegador.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Qué se sube:",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "• El informe HTML con métricas, gráficos y nombre del paquete del juego.\n" +
                        "• El nombre del dispositivo (modelo + marca).\n" +
                        "• NO se sube el vídeo de la sesión.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Retención: el archivo se borra automáticamente a los ~3 días. " +
                        "Si necesitas que dure más, usa el botón de compartir local (la " +
                        "carpeta) para enviar el archivo por tu canal habitual.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Solo verás este aviso una vez. Si aceptas, los próximos enlaces se " +
                        "subirán directamente.",
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Continuar y subir", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        },
    )
}
