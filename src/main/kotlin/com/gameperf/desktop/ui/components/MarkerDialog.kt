package com.gameperf.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.ui.util.formatTimeMs
import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker

/** Preset colors for the marker color picker. */
private val PRESET_COLORS = listOf(
    "#FF0044" to "Rojo",
    "#FF6600" to "Naranja",
    "#FFAA00" to "Amarillo",
    "#00FF88" to "Verde",
    "#00D4FF" to "Cian",
    "#3B82F6" to "Azul",
    "#7B2CBF" to "Púrpura",
    "#EC4899" to "Rosa",
    "#FFFFFF" to "Blanco",
    "#94A3B8" to "Gris"
)

/**
 * Dialog for adding or editing a timeline marker.
 *
 * @param timestampMs position of the marker in milliseconds
 * @param existingMarker if non-null, we're editing this marker
 * @param onConfirm called with the marker details on save
 * @param onDelete called when the user deletes an existing marker (only shown in edit mode)
 * @param onDismiss called to close the dialog
 */
@Composable
fun MarkerDialog(
    timestampMs: Long,
    existingMarker: SessionMarker? = null,
    onConfirm: (title: String, note: String, colorHex: String, type: MarkerType) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val isEdit = existingMarker != null
    var title by remember { mutableStateOf(existingMarker?.title ?: "") }
    var note by remember { mutableStateOf(existingMarker?.note ?: "") }
    var selectedColorHex by remember { mutableStateOf(existingMarker?.colorHex ?: "#FF0044") }
    var selectedType by remember { mutableStateOf(existingMarker?.type ?: MarkerType.CUSTOM) }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bookmark,
                    null,
                    tint = parseColorHex(selectedColorHex),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        if (isEdit) "Editar marcador" else "Nuevo marcador",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Marcador en ${formatTimeMs(timestampMs)}",
                        color = TextDim,
                        fontSize = 12.sp
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Title field
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título", color = TextDim) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                        cursorColor = Cyan,
                        focusedLabelColor = Cyan,
                        unfocusedLabelColor = TextDim
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Note field
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (opcional)", color = TextDim) },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                        cursorColor = Cyan,
                        focusedLabelColor = Cyan,
                        unfocusedLabelColor = TextDim
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Type selector
                Text("Tipo", color = TextSecondary, fontSize = 12.sp)
                Box {
                    OutlinedButton(
                        onClick = { typeMenuExpanded = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedType.label)
                    }
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false }
                    ) {
                        MarkerType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label) },
                                onClick = {
                                    selectedType = type
                                    // Also update color to match type default if color hasn't been changed
                                    if (existingMarker == null || selectedColorHex == existingMarker.type.colorHex) {
                                        selectedColorHex = type.colorHex
                                    }
                                    typeMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Color picker
                Text("Color", color = TextSecondary, fontSize = 12.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PRESET_COLORS.forEach { (hex, _) ->
                        val isSelected = hex.equals(selectedColorHex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(parseColorHex(hex), CircleShape)
                                .then(
                                    if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                )
                                .clickable { selectedColorHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isEdit && onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = Red)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Eliminar")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar", color = TextSecondary)
                }
                Button(
                    onClick = {
                        val finalTitle = title.ifBlank { selectedType.label }
                        onConfirm(finalTitle, note, selectedColorHex, selectedType)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = null  // Handled within confirmButton row
    )
}
