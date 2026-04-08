package com.gameperf.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.core.AdbBridge
import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.ui.components.ExportBanner
import com.gameperf.desktop.ui.components.StatRow
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.viewmodel.AppViewModel

@Composable
fun HomeScreen(vm: AppViewModel) {
    val adbAvailable by vm.adbAvailable.collectAsState()
    val devices by vm.devices.collectAsState()
    val selectedDevice by vm.selectedDevice.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()
    val gamePackage by vm.gamePackage.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()
    var duration by remember { mutableStateOf("") }

    val updateInfo by vm.updateAvailable.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val updateError by vm.updateError.collectAsState()

    val exportStatus by vm.exportStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ===== PDF Export Banner =====
        ExportBanner(
            status = exportStatus,
            onDismiss = { vm.resetExportStatus() },
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // ===== Update Banner =====
        if (updateInfo != null) {
            val info = updateInfo!!
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Yellow.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = Yellow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Nueva version v${info.version} disponible",
                                color = Yellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                info.name,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        if (updateProgress == null) {
                            TextButton(
                                onClick = { vm.dismissUpdate() },
                                colors = ButtonDefaults.textButtonColors(contentColor = TextDim)
                            ) {
                                Text("Despues", fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = { vm.downloadAndApplyUpdate() },
                                colors = ButtonDefaults.buttonColors(containerColor = Yellow),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    "Actualizar",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // ===== Mini changelog =====
                    val miniChangelog = remember(info.body) { summarizeReleaseBody(info.body) }
                    if (miniChangelog.isNotEmpty() && updateProgress == null) {
                        Spacer(Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.25f))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "QUE HAY DE NUEVO",
                                color = Yellow.copy(alpha = 0.9f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            miniChangelog.forEachIndexed { index, line ->
                                // Single Text with the bullet prefixed guarantees the bullet
                                // and the first line share the same baseline. Hanging-indent
                                // via textIndent equivalent: we use a double-space indent on
                                // wrapped lines by prefixing the bullet inline. Since Compose
                                // Text doesn't expose hanging indent natively, we render the
                                // bullet and text as one string — wrapped lines will align to
                                // the left of the bullet, which is acceptable at this width.
                                Text(
                                    text = "•  $line",
                                    color = Color.White.copy(alpha = 0.82f),
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                                if (index < miniChangelog.lastIndex) {
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }

                    // Download progress
                    if (updateProgress != null) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { updateProgress!! },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Yellow,
                            trackColor = Yellow.copy(alpha = 0.15f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (updateProgress!! >= 1f) "Reiniciando..."
                            else "Descargando... ${String.format(java.util.Locale.US, "%.0f", updateProgress!! * 100)}%",
                            color = Yellow,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Error
                    if (updateError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            updateError!!,
                            color = Red,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Header
        Text(
            "Game Performance Tool",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Cyan
        )
        Text("v${AppVersion.NAME}", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(statusMessage, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))

        if (!adbAvailable) {
            // ADB not found
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, null, tint = Red, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("ADB no encontrado", color = Red, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Instala Android SDK Platform-Tools:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("macOS:  brew install android-platform-tools", color = Cyan, fontSize = 12.sp)
                    Text("Windows: developer.android.com/studio", color = Cyan, fontSize = 12.sp)
                }
            }
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: Device panel
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = Cyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Dispositivo", color = Cyan, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.refreshDevices() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, "Refrescar", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (devices.isEmpty()) {
                        Text("No hay dispositivos conectados", color = TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("1. Conecta tu dispositivo Android por USB", color = TextDim, fontSize = 11.sp)
                        Text("2. Activa 'Depuracion USB' en Opciones de desarrollador", color = TextDim, fontSize = 11.sp)
                        Text("3. Acepta el dialogo en el dispositivo", color = TextDim, fontSize = 11.sp)
                    } else {
                        devices.forEach { device ->
                            val isSelected = device.id == selectedDevice?.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Cyan.copy(alpha = 0.15f) else Color.Transparent)
                                    .border(
                                        width = if (isSelected) 1.dp else 0.dp,
                                        color = if (isSelected) Cyan.copy(alpha = 0.5f) else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { vm.selectDevice(device) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape).background(Green)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(device.model, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (device.isWifi) "WiFi: ${device.id}" else "USB: ${device.id.take(12)}...",
                                        color = TextDim, fontSize = 10.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        // WiFi button
                        val currentIsWifi by vm.isWifi.collectAsState()
                        val wifiStatusText by vm.wifiStatus.collectAsState()

                        if (selectedDevice != null && !currentIsWifi) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { vm.switchToWifi() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Yellow)
                            ) {
                                Icon(Icons.Default.Wifi, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Cambiar a WiFi (medir bateria real)", fontSize = 11.sp)
                            }
                        }
                        if (currentIsWifi) {
                            Spacer(Modifier.height(4.dp))
                            Text("WiFi activo - desconecta el cable USB", color = Green, fontSize = 11.sp)
                        }
                        if (wifiStatusText.isNotEmpty()) {
                            Text(wifiStatusText, color = TextDim, fontSize = 10.sp)
                        }

                        // Device specs
                        if (deviceInfo != null) {
                            val info = deviceInfo!!
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = TextDim.copy(alpha = 0.3f))
                            Spacer(Modifier.height(8.dp))
                            StatRow("CPU", info.cpu)
                            StatRow("GPU", info.gpu.take(40))
                            StatRow("RAM", info.ram)
                            StatRow("Cores", "${info.cores}")
                            StatRow("SDK", "${info.sdk}")
                        }
                    }
                }
            }

            // Right: Game + Capture panel
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SportsEsports, null, tint = Purple, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Juego", color = Purple, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.refreshGame() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, "Refrescar", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (gamePackage != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(8.dp).clip(CircleShape).background(Green)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(gamePackage!!, color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text("No se detecto juego", color = Yellow, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Abre un juego en el dispositivo y pulsa Refrescar", color = TextDim, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(24.dp))

                    // Duration selector
                    Text("Duracion de la prueba", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    val durations = listOf(
                        "" to "Libre", "30" to "30s", "60" to "1m", "120" to "2m",
                        "300" to "5m", "600" to "10m", "3600" to "1h"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        durations.forEach { (value, label) ->
                            val selected = duration == value
                            // v3.1.14: the selected button now uses a SOLID Cyan fill with
                            // a dark-background text color so the active choice is obvious
                            // from across the room. Previously both selected and unselected
                            // states used the same near-transparent `Cyan.copy(alpha=0.2f)`
                            // background, which left users second-guessing which option
                            // was actually active. Unselected buttons are unchanged.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) Cyan else Color.Transparent)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) Cyan else TextDim.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { duration = value }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (selected) DarkBg else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Session tag selector
                    val currentTag by vm.sessionTag.collectAsState()
                    val currentCompetitor by vm.competitorName.collectAsState()

                    Text("Tipo de sesion", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isOurs = currentTag == SessionHistory.SessionTag.OUR_GAME
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isOurs) Cyan.copy(alpha = 0.2f) else Color.Transparent)
                                .border(1.dp, if (isOurs) Cyan else TextDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable { vm.setSessionTag(SessionHistory.SessionTag.OUR_GAME) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Nuestro juego", color = if (isOurs) Cyan else TextSecondary, fontSize = 12.sp, fontWeight = if (isOurs) FontWeight.Bold else FontWeight.Normal)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isOurs) Orange.copy(alpha = 0.2f) else Color.Transparent)
                                .border(1.dp, if (!isOurs) Orange else TextDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable { vm.setSessionTag(SessionHistory.SessionTag.COMPETITION) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Competencia", color = if (!isOurs) Orange else TextSecondary, fontSize = 12.sp, fontWeight = if (!isOurs) FontWeight.Bold else FontWeight.Normal)
                        }
                    }

                    // Competitor name field (only when tag is COMPETITION)
                    if (currentTag == SessionHistory.SessionTag.COMPETITION) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = currentCompetitor,
                            onValueChange = { vm.setCompetitorName(it) },
                            label = { Text("Nombre del juego competidor", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Orange,
                                unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                                focusedLabelColor = Orange,
                                unfocusedLabelColor = TextDim,
                                cursorColor = Orange
                            )
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Start button
                    Button(
                        onClick = { vm.startCapture(duration.toIntOrNull() ?: 0) },
                        enabled = gamePackage != null && selectedDevice != null,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentTag == SessionHistory.SessionTag.COMPETITION) Orange else Cyan,
                            disabledContainerColor = TextDim.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (currentTag == SessionHistory.SessionTag.COMPETITION) "Capturar competencia" else "Iniciar prueba",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Recent tests history
        val historyEntries by vm.history.collectAsState()
        val comparisonSelection by vm.selectedForComparison.collectAsState()

        if (historyEntries.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = Cyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pruebas recientes", color = Cyan, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        // v3.1.13: discreet button to manually re-run the legacy video
                        // repair logic. Useful after a power loss / crash that left
                        // segments un-concatenated. The same logic runs once on app
                        // startup automatically, this is just the on-demand version.
                        // Kept small + secondary so it doesn't compete with the main
                        // capture flow.
                        TextButton(
                            onClick = { vm.repairOldVideos() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = TextDim,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Reparar videos",
                                color = TextDim,
                                fontSize = 11.sp
                            )
                        }
                        if (comparisonSelection.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${comparisonSelection.size} seleccionadas",
                                color = Purple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { vm.clearComparisonSelection() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, "Limpiar", tint = TextDim, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    // Passive capacity hint: appears only when the history is at the
                    // hard 5/5 retention limit. Tells the user that the next capture
                    // will silently replace the oldest entry, so they can choose to
                    // export to PDF first if they care about persisting it.
                    if (historyEntries.size == SessionHistory.MAX_ENTRIES) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Historial: 5/5 - la próxima captura reemplazará la más antigua",
                            color = TextSecondary.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    historyEntries.forEach { entry ->
                        var isEditing by remember { mutableStateOf(false) }
                        var editName by remember { mutableStateOf(entry.name) }
                        var showDeleteConfirmation by remember { mutableStateOf(false) }
                        val isSelectedForComp = entry.id in comparisonSelection

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelectedForComp) Purple.copy(alpha = 0.12f) else DarkSurface
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Comparison checkbox
                                Checkbox(
                                    checked = isSelectedForComp,
                                    onCheckedChange = { vm.toggleComparisonSelection(entry.id) },
                                    modifier = Modifier.size(20.dp),
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Purple,
                                        uncheckedColor = TextDim
                                    )
                                )
                                Spacer(Modifier.width(8.dp))

                                // Grade badge
                                Text(
                                    "${entry.grade}",
                                    color = gradeColor(entry.grade),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(32.dp)
                                )
                                Spacer(Modifier.width(8.dp))

                                // Tag indicator
                                val tagColor = if (entry.tag == SessionHistory.SessionTag.COMPETITION) Orange else Cyan
                                val tagLabel = if (entry.tag == SessionHistory.SessionTag.COMPETITION) "COMP" else "NUESTRO"
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(tagColor.copy(alpha = 0.15f))
                                        .border(1.dp, tagColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(tagLabel, color = tagColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(8.dp))

                                // Name + info
                                Column(Modifier.weight(1f)) {
                                    if (isEditing) {
                                        TextField(
                                            value = editName,
                                            onValueChange = { editName = it },
                                            modifier = Modifier.fillMaxWidth().height(40.dp),
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                color = Color.White, fontSize = 13.sp
                                            ),
                                            singleLine = true,
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent,
                                                focusedIndicatorColor = Cyan,
                                                cursorColor = Cyan
                                            )
                                        )
                                    } else {
                                        Text(entry.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text(
                                        "${entry.date}  |  ${entry.avgFps} FPS  |  ${entry.duration / 60}m ${entry.duration % 60}s" +
                                            if (entry.competitorName.isNotEmpty()) "  |  ${entry.competitorName}" else "",
                                        color = TextDim, fontSize = 10.sp
                                    )
                                }

                                // Edit/Save name
                                IconButton(
                                    onClick = {
                                        if (isEditing) {
                                            vm.renameHistoryEntry(entry.id, editName)
                                            isEditing = false
                                        } else {
                                            editName = entry.name
                                            isEditing = true
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                                        null, tint = if (isEditing) Green else TextDim,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Open report
                                if (entry.reportPath.isNotEmpty()) {
                                    IconButton(
                                        onClick = { vm.openHistoryReport(entry) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Description, "Informe", tint = Green, modifier = Modifier.size(16.dp))
                                    }
                                }

                                // Open video
                                if (entry.videoPath.isNotEmpty()) {
                                    IconButton(
                                        onClick = { vm.openHistoryVideo(entry) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Videocam, "Video", tint = Purple, modifier = Modifier.size(16.dp))
                                    }
                                }

                                // Export to PDF — only enabled when the source HTML still exists
                                // and there is no PDF export already running.
                                if (entry.reportPath.isNotEmpty()) {
                                    val exportingNow = exportStatus is AppViewModel.ExportStatus.InProgress
                                    IconButton(
                                        onClick = { vm.exportHistoryEntryToPdf(entry) },
                                        enabled = !exportingNow,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PictureAsPdf,
                                            "Exportar PDF",
                                            tint = if (exportingNow) TextDim else Cyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                // Delete entry
                                IconButton(
                                    onClick = { showDeleteConfirmation = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "Eliminar", tint = Red.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        // Delete confirmation dialog
                        if (showDeleteConfirmation) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirmation = false },
                                title = { Text("Eliminar sesión", fontWeight = FontWeight.Bold) },
                                text = { Text("Se eliminarán la entrada del historial, el video y el informe. Esta acción no se puede deshacer.") },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showDeleteConfirmation = false
                                            vm.deleteHistoryEntry(entry.id)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Red)
                                    ) {
                                        Text("Eliminar", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteConfirmation = false }) {
                                        Text("Cancelar")
                                    }
                                },
                                containerColor = DarkCard,
                                titleContentColor = Color.White,
                                textContentColor = TextSecondary
                            )
                        }
                    }

                    // Comparison button
                    if (comparisonSelection.size >= 2) {
                        Spacer(Modifier.height(16.dp))
                        val canCompare = vm.canCompare()
                        Button(
                            onClick = { vm.goToComparison() },
                            enabled = canCompare,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Purple,
                                disabledContainerColor = TextDim.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Comparar sesiones", fontWeight = FontWeight.Bold)
                        }
                        if (!canCompare) {
                            Text(
                                "Selecciona al menos 1 sesion 'Nuestro juego' y 1 'Competencia'",
                                color = Yellow, fontSize = 10.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Extract the most relevant bullet points from a GitHub release body (markdown).
 *
 * Section priority (highest first):
 *   1. "Que hay de nuevo" / "Novedades" / "Highlights" / "What's new"  — human-friendly summary
 *      written specifically for end users. Always shown first if present.
 *   2. "Added" / "Nuevo" / "Nuevas" / "New features"                    — new features
 *   3. "Fixed" / "Arreglado" / "Arreglos" / "Correcciones"              — bug fixes
 *   4. "Changed" / "Cambios" / "Cambiado"                               — changes
 *   5. "Critical"                                                         — critical notices
 *
 * Any top-level bullet that is not under a recognized section gets the lowest priority.
 *
 * Empty list means nothing useful was extracted (very short body, no bullets, etc.) —
 * the caller should hide the changelog block in that case.
 *
 * Authors writing release notes should put a "## Que hay de nuevo" section at the top with
 * plain-language bullets aimed at non-technical users. Technical details can live in the
 * lower sections without polluting the in-app banner.
 */
private fun summarizeReleaseBody(body: String?): List<String> {
    if (body.isNullOrBlank()) return emptyList()

    // Section priority (lower index = higher priority = appears first in the banner).
    // Each entry is a list of substrings that match the section header (lowercased).
    val prioritySections: List<List<String>> = listOf(
        // 0: user-friendly summary, highest priority
        listOf("que hay de nuevo", "novedades", "highlights", "what's new", "whats new", "resumen"),
        // 1: new features
        listOf("added", "nuevo", "nuevas", "new features", "features"),
        // 2: bug fixes
        listOf("fixed", "arreglado", "arreglos", "correcciones", "bug fix"),
        // 3: changes
        listOf("changed", "cambios", "cambiado"),
        // 4: critical / important
        listOf("critical", "importante", "aviso")
    )
    val lowestPriority = prioritySections.size

    fun matchPriority(header: String): Int {
        prioritySections.forEachIndexed { idx, keywords ->
            if (keywords.any { header.contains(it) }) return idx
        }
        return lowestPriority
    }

    data class Bullet(val priority: Int, val order: Int, val text: String)
    val bullets = mutableListOf<Bullet>()
    var currentPriority = lowestPriority
    var order = 0

    body.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()

        // Section header detection: #, ##, ### ... or **Bold**: style
        val headerMatch = Regex("""^#{1,6}\s+(.+)$""").find(line)
            ?: Regex("""^\*\*(.+?)\*\*\s*:?$""").find(line)
        if (headerMatch != null) {
            val name = headerMatch.groupValues[1].lowercase()
            currentPriority = matchPriority(name)
            return@forEach
        }

        // Bullet detection: -, *, +, or numbered
        val bulletMatch = Regex("""^[-*+]\s+(.+)$""").find(line)
            ?: Regex("""^\d+\.\s+(.+)$""").find(line)
            ?: return@forEach

        var text = bulletMatch.groupValues[1]
        // Strip markdown noise (bold, italic, code, links)
        text = text.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""\*(.+?)\*"""), "$1")
            .replace(Regex("""`(.+?)`"""), "$1")
            .replace(Regex("""\[(.+?)\]\((.+?)\)"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (text.length < 3) return@forEach
        // Truncate very long lines so the banner stays compact
        val truncated = if (text.length > 140) text.substring(0, 137).trimEnd() + "..." else text
        bullets.add(Bullet(currentPriority, order++, truncated))
    }

    // Sort by priority (ascending — highest priority first) then by original order (preserve
    // the author's intended reading order within a section).
    return bullets
        .sortedWith(compareBy({ it.priority }, { it.order }))
        .take(5)
        .map { it.text }
}
