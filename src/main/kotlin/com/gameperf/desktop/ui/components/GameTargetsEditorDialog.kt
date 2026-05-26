package com.gameperf.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gameperf.desktop.core.GameTargets
import com.gameperf.desktop.core.GameTargetsCatalog
import com.gameperf.desktop.ui.theme.DarkCard
import com.gameperf.desktop.ui.theme.Green
import com.gameperf.desktop.ui.theme.Red
import com.gameperf.desktop.ui.theme.TextPrimary
import com.gameperf.desktop.ui.theme.TextSecondary

/**
 * v5.2.0 — In-app editor for the per-game targets catalog.
 *
 * Local-state pattern: the dialog clones [initialCatalog] into a mutable list
 * of `(package, GameTargets)` pairs and lets the user add / edit / remove
 * rows freely. Only when the user clicks "Guardar" do the edits leave this
 * component via [onSave] (which the ViewModel then persists through
 * `GameTargetsCatalogIO.save`). "Cancelar" simply dismisses the dialog,
 * leaving disk state untouched — this is what the spec calls the
 * "Mid-edit app close" guarantee: if the process dies before Save, no
 * partial write ever happens.
 *
 * All numeric fields reject negative input at the `onValueChange` level
 * (`toXOrNull()?.takeIf { it >= 0 }`), so the on-save snapshot never
 * contains negatives. Cross-field validation (`p1 ≤ avg`) is deliberately
 * deferred to v5.3.0 — out of scope per engram #522.
 *
 * UI strings are in castellano formal tuteo to match the rest of the app.
 *
 * @since v5.2.0
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameTargetsEditorDialog(
    initialCatalog: GameTargetsCatalog,
    onSave: (GameTargetsCatalog) -> Unit,
    onExport: (GameTargetsCatalog) -> Unit,
    onDismiss: () -> Unit,
) {
    // Use a list of pairs (not Map) so insertion order is stable while the
    // user is editing — adding then immediately editing a row would otherwise
    // reorder the LazyColumn under their fingers.
    var entries by remember {
        mutableStateOf(initialCatalog.targets.entries.map { it.key to it.value })
    }
    var newPackageInput by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(12.dp),
            color = DarkCard,
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth().fillMaxHeight()) {
                Text(
                    "Editor de objetivos por juego",
                    color = Green,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Define los objetivos de rendimiento por package del juego. " +
                        "Los valores vacíos no se comparan en el reporte. " +
                        "Pulsa Guardar cuando termines, o Cancelar para descartar.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(entries, key = { it.first }) { (pkg, targets) ->
                            TargetRow(
                                packageName = pkg,
                                targets = targets,
                                onUpdate = { updated ->
                                    entries = entries.map { if (it.first == pkg) pkg to updated else it }
                                },
                                onDelete = {
                                    entries = entries.filterNot { it.first == pkg }
                                },
                            )
                            Divider(color = TextSecondary.copy(alpha = 0.2f))
                        }
                    }
                }

                // Add new game row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = newPackageInput,
                        onValueChange = { newPackageInput = it.trim() },
                        label = { Text("Package del juego (p. ej. com.tu.juego)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newPackageInput.isNotBlank() &&
                                entries.none { it.first == newPackageInput }
                            ) {
                                entries = entries + (newPackageInput to GameTargets())
                                newPackageInput = ""
                            }
                        },
                        enabled = newPackageInput.isNotBlank() &&
                            entries.none { it.first == newPackageInput },
                    ) { Text("Añadir") }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    OutlinedButton(
                        onClick = { onExport(GameTargetsCatalog(targets = entries.toMap())) },
                    ) { Text("Exportar a HTML") }
                    Button(
                        onClick = { onSave(GameTargetsCatalog(targets = entries.toMap())) },
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                    ) { Text("Guardar") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetRow(
    packageName: String,
    targets: GameTargets,
    onUpdate: (GameTargets) -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                packageName,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = Red)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = targets.displayName ?: "",
            onValueChange = { onUpdate(targets.copy(displayName = it.ifBlank { null })) },
            label = { Text("Nombre legible") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Numeric fields in 3 rows of 3 columns each (10 KPIs + filler)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField("FPS medio", targets.targetAvgFps?.toString() ?: "") { v ->
                onUpdate(targets.copy(targetAvgFps = v?.toIntOrNull()?.takeIf { it >= 0 }))
            }
            NumberField("FPS p1", targets.targetP1Fps?.toString() ?: "") { v ->
                onUpdate(targets.copy(targetP1Fps = v?.toIntOrNull()?.takeIf { it >= 0 }))
            }
            NumberField("Frame ms", targets.maxAvgFrameTimeMs?.toString() ?: "") { v ->
                onUpdate(targets.copy(maxAvgFrameTimeMs = v?.toDoubleOrNull()?.takeIf { it >= 0 }))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField("Temp piel °C", targets.maxTempSkinC?.toString() ?: "") { v ->
                onUpdate(targets.copy(maxTempSkinC = v?.toDoubleOrNull()?.takeIf { it >= 0 }))
            }
            NumberField("Temp CPU °C", targets.maxTempCpuC?.toString() ?: "") { v ->
                onUpdate(targets.copy(maxTempCpuC = v?.toDoubleOrNull()?.takeIf { it >= 0 }))
            }
            NumberField("RAM MB", targets.maxPeakRamMb?.toString() ?: "") { v ->
                onUpdate(targets.copy(maxPeakRamMb = v?.toLongOrNull()?.takeIf { it >= 0 }))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField("CPU %", targets.maxAvgCpuPct?.toString() ?: "") { v ->
                onUpdate(targets.copy(maxAvgCpuPct = v?.toIntOrNull()?.takeIf { it >= 0 }))
            }
            NumberField("FPower mW/f", targets.maxFPowerMwFrame?.toString() ?: "") { v ->
                onUpdate(targets.copy(maxFPowerMwFrame = v?.toDoubleOrNull()?.takeIf { it >= 0 }))
            }
            NumberField("Drenaje %", targets.maxBatteryDrainPct?.toString() ?: "") { v ->
                onUpdate(targets.copy(maxBatteryDrainPct = v?.toIntOrNull()?.takeIf { it >= 0 }))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = targets.notes ?: "",
            onValueChange = { onUpdate(targets.copy(notes = it.ifBlank { null })) },
            label = { Text("Notas") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.NumberField(label: String, value: String, onChange: (String?) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            // Accept only digits and at most one decimal point. Empty string
            // means "unset" → we pass null so the model field becomes null.
            if (newValue.isEmpty() || newValue.matches(Regex("""^\d*\.?\d*$"""))) {
                onChange(newValue.takeIf { it.isNotEmpty() })
            }
        },
        label = { Text(label, maxLines = 1) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.weight(1f),
    )
}
