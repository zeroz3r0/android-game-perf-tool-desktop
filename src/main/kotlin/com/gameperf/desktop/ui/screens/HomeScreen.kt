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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.core.AdbBridge
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            "Game Performance Tool",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Cyan
        )
        Text("v1.0.0", color = TextDim, fontSize = 12.sp)
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
                            Divider(color = TextDim.copy(alpha = 0.3f))
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
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) Cyan.copy(alpha = 0.2f) else Color.Transparent)
                                    .border(1.dp, if (selected) Cyan else TextDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .clickable { duration = value }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (selected) Cyan else TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Start button
                    Button(
                        onClick = { vm.startCapture(duration.toIntOrNull() ?: 0) },
                        enabled = gamePackage != null && selectedDevice != null,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Cyan,
                            disabledContainerColor = TextDim.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Iniciar prueba", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
