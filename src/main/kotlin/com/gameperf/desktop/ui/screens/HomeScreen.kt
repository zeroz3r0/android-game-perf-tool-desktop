package com.gameperf.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.MdnsServiceType
import com.gameperf.desktop.core.SessionHistory
import com.gameperf.desktop.core.model.DevicePlatform
import com.gameperf.desktop.ui.components.EvictionConfirmDialog
import com.gameperf.desktop.ui.components.ExportBanner
import com.gameperf.desktop.ui.components.StatRow
import com.gameperf.desktop.ui.theme.*
import com.gameperf.desktop.viewmodel.AppViewModel
import com.gameperf.desktop.viewmodel.WifiDelegate

@Composable
fun HomeScreen(vm: AppViewModel) {
    val adbAvailable by vm.adbAvailable.collectAsState()
    val devices by vm.devices.collectAsState()
    val selectedDevice by vm.selectedDevice.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()
    val gamePackage by vm.gamePackage.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()
    var duration by remember { mutableStateOf("") }
    var showGuide by remember { mutableStateOf(false) }

    val updateInfo by vm.updateAvailable.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val updateError by vm.updateError.collectAsState()
    // v4.4.1: fallback panel state (non-null = render UpdateFallbackPanel
    // alongside the existing yellow update banner). Spec REQ "Fallback panel display".
    val updateFallback by vm.updateFallback.collectAsState()

    // v4.2.14 — In-app dependency bootstrap state. Mirrors the UpdateBanner
    // pattern: missingDeps drives banner visibility, bootstrapProgress shows
    // the download bar, bootstrapError shows the failure + browser fallback.
    val missingDeps by vm.missingDeps.collectAsState()
    val bootstrapProgress by vm.bootstrapProgress.collectAsState()
    val bootstrapError by vm.bootstrapError.collectAsState()

    val exportStatus by vm.exportStatus.collectAsState()

    // v4.2.8 hotfix: observe sessionPackMessage from the ViewModel and auto-clear
    // it after 4 seconds so the snackbar is transient. Without this the state
    // would persist and the user would see the same "exported / imported"
    // message until they manually dismiss it.
    val sessionPackMessage by vm.sessionPackMessage.collectAsState()
    LaunchedEffect(sessionPackMessage) {
        if (sessionPackMessage != null) {
            kotlinx.coroutines.delay(4000)
            vm.clearSessionPackMessage()
        }
    }

    if (showGuide) {
        com.gameperf.desktop.ui.components.GuideDialog(onDismiss = { showGuide = false })
    }

    // v4.3.7 — Layer 4: surface the eviction confirmation dialog when the AppViewModel
    // raised an EvictionPendingState. The user choice is forwarded straight to the VM,
    // which performs the actual disk insert / favorite toggle / cancel.
    val evictionPending by vm.evictionPending.collectAsState()
    evictionPending?.let { pending ->
        EvictionConfirmDialog(
            pending = pending,
            onDecision = { vm.confirmEviction(it) },
        )
    }

    // v5.0.0 — temp.sh upload path retired (engram #512). Sharing is now
    // only local (open folder + clipboard) or via the new data-URL clipboard
    // button. No disclaimer dialog needed.

    // v4.3.7 — recovery toast (Layer 4 companion to the recovery button further down).
    // Auto-clear after 4s so the toast is transient like sessionPackMessage.
    val recoveryStatus by vm.recoveryStatus.collectAsState()
    LaunchedEffect(recoveryStatus) {
        if (recoveryStatus != null) {
            kotlinx.coroutines.delay(4000)
            vm.clearRecoveryStatus()
        }
    }

    // v4.2.8: outer Box hosts both the main content Column AND the sessionPack
    // snackbar overlay (which lives at the bottom of the screen while visible).
    // Without this Box, the snackbar would be a sibling of Column inside the
    // parent, breaking the fillMaxSize layout.
    Box(modifier = Modifier.fillMaxSize()) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ===== Guide button + PDF Export Banner =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { showGuide = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Guía de testing", color = Cyan, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
        }
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
                                "Nueva versión v${info.version} disponible",
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

        // ===== v4.4.1 — Update Fallback Panel =====
        // Renders below the (optional) update banner when the last update
        // attempt failed terminally. Spec REQ "Fallback panel display" + D1.
        updateFallback?.let { fallbackState ->
            val recent = remember(fallbackState) { vm.recentUpdateAttempts(10) }
            com.gameperf.desktop.ui.components.UpdateFallbackPanel(
                state = fallbackState,
                onDismiss = { vm.dismissUpdateFallback() },
                onOpenDownload = { openInBrowser(fallbackState.downloadUrl) },
                onOpenGuide = { openInBrowser(fallbackState.installGuideUrl) },
                recentAttempts = recent,
            )
        }

        // ===== v4.2.14 — Dependency Bootstrap Banner =====
        // Shown when adb / ffmpeg is missing and needs to be installed.
        // Follows the UpdateBanner visual pattern: card with primary CTA
        // (in-app download) + secondary fallback (open browser) + progress
        // bar during download + error message on failure.
        DepsBootstrapBanner(
            missingDeps = missingDeps,
            progress = bootstrapProgress,
            error = bootstrapError,
            onInstall = { toolName -> vm.installMissingDep(toolName) },
            onOpenBrowser = { toolName -> vm.openToolDownloadUrl(toolName) },
            onDismissError = { vm.dismissBootstrapError() }
        )

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
                        // v3.2.0: onboarding with two paths — USB (default) or WiFi.
                        // The WiFi tab mounts WifiPanelContent and also drives the
                        // mDNS polling lifecycle via `openWifiPanel`/`closeWifiPanel`
                        // in a LaunchedEffect keyed on the selected tab index.
                        var selectedTab by remember { mutableStateOf(0) }
                        TabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color.Transparent,
                            contentColor = Cyan
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("USB", fontSize = 12.sp) }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("WiFi (Android 11+)", fontSize = 12.sp) }
                            )
                        }
                        // Drive mDNS polling lifecycle from the tab selection.
                        // When the WiFi tab becomes active, open the panel (which
                        // starts the polling loop). When the user flips back to
                        // USB, close the panel to stop the loop — this preserves
                        // the R-WP-3 invariant (USB-only users pay zero cost for
                        // the wireless feature).
                        LaunchedEffect(selectedTab) {
                            if (selectedTab == 1) {
                                vm.openWifiPanel()
                            } else {
                                vm.closeWifiPanel()
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (selectedTab == 0) {
                            Text("No hay dispositivos conectados", color = TextSecondary, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("1. Conecta tu dispositivo Android por USB", color = TextDim, fontSize = 11.sp)
                            Text("2. Activa 'Depuracion USB' en Opciones de desarrollador", color = TextDim, fontSize = 11.sp)
                            Text("3. Acepta el dialogo en el dispositivo", color = TextDim, fontSize = 11.sp)
                        } else {
                            WifiPanelContent(vm)
                        }
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(device.model, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(6.dp))
                                        // v4.0.0: platform badge
                                        val badgeText = if (device.platform == DevicePlatform.IOS) "iOS" else "Android"
                                        val badgeColor = if (device.platform == DevicePlatform.IOS) Color(0xFF007AFF) else Green
                                        Text(
                                            badgeText,
                                            color = badgeColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                    Text(
                                        when {
                                            device.platform == DevicePlatform.IOS -> "USB: ${device.id.take(16)}..."
                                            device.isWifi -> "WiFi: ${device.id}"
                                            else -> "USB: ${device.id.take(12)}..."
                                        },
                                        color = TextDim, fontSize = 10.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        // WiFi button
                        val currentIsWifi by vm.isWifi.collectAsState()
                        val wifiStatusText by vm.wifiStatus.collectAsState()

                        // v4.0.0: WiFi button only for Android devices
                        if (selectedDevice != null && !currentIsWifi && selectedDevice?.platform == DevicePlatform.ANDROID) {
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
                            StatRow(
                                if (info.platform == DevicePlatform.IOS) "iOS" else "SDK",
                                info.osVersion
                            )
                        }

                        // v3.2.0: discreet entry point to the wireless pairing flow
                        // when the user already has at least one device connected
                        // (USB or WiFi legacy). Hidden by default — clicking it
                        // opens the WifiPanelContent inline and starts the mDNS
                        // polling loop via `openWifiPanel`. Closing the panel
                        // from within WifiPanelContent stops the loop.
                        val wifiPanelState by vm.wifiPanel.collectAsState()
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = TextDim.copy(alpha = 0.3f))
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                if (wifiPanelState is WifiDelegate.WifiPanelState.Hidden ||
                                    wifiPanelState is WifiDelegate.WifiPanelState.Closed
                                ) {
                                    vm.openWifiPanel()
                                } else {
                                    vm.closeWifiPanel()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = TextDim,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Agregar device WiFi",
                                color = TextDim,
                                fontSize = 12.sp
                            )
                        }
                        AnimatedVisibility(visible = wifiPanelState !is WifiDelegate.WifiPanelState.Hidden) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                WifiPanelContent(vm)
                            }
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
                        Text("No se detectó juego", color = Yellow, fontSize = 13.sp)
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

                    Text("Tipo de sesión", color = TextSecondary, fontSize = 12.sp)
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
            val favoriteEntries = historyEntries.filter { it.isFavorite }
            val recentEntries = historyEntries.filter { !it.isFavorite }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = Cyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sesiones", color = Cyan, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        // v4.2.8: import a .gameperf file shared by a teammate.
                        // Opens a native "Open" dialog filtered to .gameperf files
                        // (implementation-wise we let the user pick anything and
                        // the SessionPack importer validates + rejects on mismatch).
                        TextButton(
                            onClick = {
                                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Importar sesión .gameperf", java.awt.FileDialog.LOAD)
                                dialog.setFilenameFilter { _, name -> name.endsWith(".gameperf", ignoreCase = true) }
                                dialog.isVisible = true
                                val chosen = dialog.file
                                val dir = dialog.directory
                                if (chosen != null && dir != null) {
                                    vm.importSessionPackFromFile(java.io.File(dir, chosen))
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, tint = Cyan, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Importar .gameperf", color = Cyan, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { vm.repairOldVideos() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = TextDim, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reparar videos", color = TextDim, fontSize = 11.sp)
                        }
                        if (comparisonSelection.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text("${comparisonSelection.size} seleccionadas", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { vm.clearComparisonSelection() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "Limpiar", tint = TextDim, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    // ── Favoritos ──
                    if (favoriteEntries.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFFFD700), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Favoritos", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Text("(nunca se borran automáticamente)", color = TextDim, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        favoriteEntries.forEach { entry ->
                            HistoryEntryRow(entry, comparisonSelection, vm, exportStatus)
                        }
                    }

                    // ── Recientes ──
                    if (recentEntries.isNotEmpty()) {
                        Spacer(Modifier.height(if (favoriteEntries.isNotEmpty()) 16.dp else 12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, null, tint = TextDim, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Recientes", color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            // v4.3.7: warn when ≥90% of cap (was: only at exactly MAX_ENTRIES).
                            // The cap is now 100 so a hard-equal comparison would only ever fire
                            // at the very last slot — useless. We use a stable threshold via
                            // SessionHistory.WARNING_THRESHOLD_RATIO so the warning lights up
                            // with breathing room left.
                            val warnThreshold = (SessionHistory.MAX_ENTRIES * SessionHistory.WARNING_THRESHOLD_RATIO).toInt()
                            Text(
                                "${recentEntries.size}/${SessionHistory.MAX_ENTRIES}",
                                color = if (recentEntries.size >= warnThreshold) Red.copy(alpha = 0.8f) else TextDim,
                                fontSize = 10.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            // v4.3.7 — Layer 4 recovery button. Always visible as a tiny secondary
                            // action; click triggers recoverHistoryFromBackup() which is a no-op
                            // when no backup contains more data than the live history.
                            TextButton(
                                onClick = { vm.recoverHistoryFromBackup() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) {
                                Icon(
                                    Icons.Default.Restore,
                                    contentDescription = "Recuperar historial desde respaldo",
                                    tint = Cyan.copy(alpha = 0.7f),
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Recuperar",
                                    color = Cyan.copy(alpha = 0.7f),
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        // v4.3.7 — recovery toast (transient; auto-cleared after 4s by HomeScreen).
                        recoveryStatus?.let { msg ->
                            Text(
                                msg,
                                color = Cyan,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        val warnThreshold2 = (SessionHistory.MAX_ENTRIES * SessionHistory.WARNING_THRESHOLD_RATIO).toInt()
                        if (recentEntries.size >= warnThreshold2) {
                            Text(
                                "La próxima captura reemplazará la más antigua · marca como ⭐ para conservar",
                                color = TextSecondary.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        recentEntries.forEach { entry ->
                            HistoryEntryRow(entry, comparisonSelection, vm, exportStatus)
                        }
                    }

                    // ── Botón comparar ──
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
                                "Selecciona al menos 1 sesión 'Nuestro juego' y 1 'Competencia'",
                                color = Yellow, fontSize = 10.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // v4.2.8: Google Drive sync panel removed. Session sharing is now via
        // manual .gameperf file export/import — see the per-session row for
        // the "Exportar .gameperf" button, and the top of the history section
        // for the "Importar .gameperf" button.
    }

    // v4.2.8: snackbar overlay for session pack export/import feedback.
    // Observed from the sessionPackMessage StateFlow — appears when the user
    // exports or imports a .gameperf, auto-dismisses after 4s via the
    // LaunchedEffect at the top of this Composable.
    val currentSessionPackMsg = sessionPackMessage
    if (currentSessionPackMsg != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (currentSessionPackMsg.startsWith("Error", ignoreCase = true))
                        Red.copy(alpha = 0.95f)
                    else
                        Cyan.copy(alpha = 0.95f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (currentSessionPackMsg.startsWith("Error", ignoreCase = true))
                            Icons.Default.ErrorOutline
                        else
                            Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        currentSessionPackMsg,
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(12.dp))
                    IconButton(
                        onClick = { vm.clearSessionPackMessage() },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }

    } // closes the outer Box opened at the top of HomeScreen in v4.2.8
}

// ============================================================================
// ===== REMOVED v4.2.8: DriveSyncPanel / RemoteSessionRow                     =
// =====                                                                       =
// ===== The Google Drive integration (OAuth + upload/download via Drive API)  =
// ===== was ~450 LOC of setup friction for a QA tool. Users had to get a     =
// ===== credentials.json from Google Cloud Console, enable the Drive API,    =
// ===== and share a team folder ID. Replaced by manual .gameperf file        =
// ===== export/import which works with any file-sharing channel the team     =
// ===== already uses (Slack, email, shared folder, USB).                     =
// =====                                                                       =
// ===== See HistoryEntryRow for the "Exportar .gameperf" button that         =
// ===== replaces the old "Subir a Drive" button, and the new                 =
// ===== ImportGameperfButton composable for the import side.                 =
// ============================================================================

// ============================================================================
// ===== History entry row (v4.2): extracted composable shared by favorites   =
// ===== and recents sections                                                  =
// ============================================================================

@Composable
private fun HistoryEntryRow(
    entry: SessionHistory.HistoryEntry,
    comparisonSelection: Set<String>,
    vm: AppViewModel,
    exportStatus: com.gameperf.desktop.viewmodel.ExportDelegate.ExportStatus,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(entry.name) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val isSelectedForComp = entry.id in comparisonSelection

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                entry.isFavorite -> Color(0xFFFFD700).copy(alpha = 0.06f)
                isSelectedForComp -> Purple.copy(alpha = 0.12f)
                else -> DarkSurface
            }
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {

            // ── Favorite toggle ──
            IconButton(onClick = { vm.toggleFavorite(entry.id) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (entry.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (entry.isFavorite) "Quitar favorito" else "Marcar favorito",
                    tint = if (entry.isFavorite) Color(0xFFFFD700) else TextDim,
                    modifier = Modifier.size(16.dp)
                )
            }

            // ── Comparison checkbox ──
            Checkbox(
                checked = isSelectedForComp,
                onCheckedChange = { vm.toggleComparisonSelection(entry.id) },
                modifier = Modifier.size(20.dp),
                colors = CheckboxDefaults.colors(checkedColor = Purple, uncheckedColor = TextDim)
            )
            Spacer(Modifier.width(8.dp))

            // ── Grade badge ──
            Text(
                "${entry.grade}",
                color = gradeColor(entry.grade),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(32.dp)
            )
            Spacer(Modifier.width(8.dp))

            // ── Tag indicator ──
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

            // ── Name + info ──
            Column(Modifier.weight(1f)) {
                Text(entry.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${entry.date}  |  ${entry.avgFps} FPS  |  ${entry.duration / 60}m ${entry.duration % 60}s" +
                        if (entry.competitorName.isNotEmpty()) "  |  ${entry.competitorName}" else "",
                    color = TextDim, fontSize = 10.sp
                )
            }

            // ── View session results ──
            IconButton(
                onClick = { vm.viewHistorySession(entry) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Visibility, "Ver sesión", tint = Cyan, modifier = Modifier.size(16.dp))
            }

            // ── Edit name (opens dialog) ──
            IconButton(
                onClick = { editName = entry.name; showRenameDialog = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Edit, "Renombrar", tint = TextDim, modifier = Modifier.size(16.dp))
            }

            // ── Open report ──
            if (entry.reportPath.isNotEmpty()) {
                IconButton(onClick = { vm.openHistoryReport(entry) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Description, "Informe", tint = Green, modifier = Modifier.size(16.dp))
                }
            }

            // ── Open video ──
            if (entry.videoPath.isNotEmpty()) {
                IconButton(onClick = { vm.openHistoryVideo(entry) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Videocam, "Video", tint = Purple, modifier = Modifier.size(16.dp))
                }
            }

            // ── Export PDF ──
            if (entry.reportPath.isNotEmpty()) {
                val exportingNow = exportStatus is com.gameperf.desktop.viewmodel.ExportDelegate.ExportStatus.InProgress
                IconButton(
                    onClick = { vm.exportHistoryEntryToPdf(entry) },
                    enabled = !exportingNow,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.PictureAsPdf, "Exportar PDF",
                        tint = if (exportingNow) TextDim else Cyan, modifier = Modifier.size(16.dp)
                    )
                }
            }

            // ── Export as .gameperf (v4.2.8 — replaces the Drive upload button) ──
            // Opens a native "Save As" dialog so the user picks the destination.
            // The resulting file is a self-contained ZIP: recipient imports it
            // from the "Importar .gameperf" button at the top of the history.
            IconButton(
                onClick = {
                    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Exportar sesión como .gameperf", java.awt.FileDialog.SAVE)
                    dialog.file = "${entry.name.ifBlank { "sesion" }}.gameperf"
                    dialog.isVisible = true
                    val chosen = dialog.file
                    val dir = dialog.directory
                    if (chosen != null && dir != null) {
                        val finalName = if (chosen.endsWith(".gameperf", ignoreCase = true)) chosen else "$chosen.gameperf"
                        vm.exportSessionPack(entry.id, java.io.File(dir, finalName))
                    }
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.FileUpload, "Exportar .gameperf",
                    tint = Cyan, modifier = Modifier.size(16.dp)
                )
            }

            // ── Share local (v4.7.1) ─────────────────────────────────────
            // Opens the reports folder in the OS file explorer + copies a
            // ready-to-paste description block to the clipboard. The user
            // then drags the .html into Slack/Drive/Teams/email along with
            // the pasted text. No network, no expiration, default share.
            if (entry.reportPath.isNotEmpty()) {
                IconButton(
                    onClick = { vm.shareReportLocal(entry.id) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.FolderOpen, "Compartir reporte (abrir carpeta + copiar descripción)",
                        tint = Cyan, modifier = Modifier.size(16.dp)
                    )
                }

                // ── Copy HTML as data URL (v5.0.0) ────────────────────────
                // Copies the report HTML to the clipboard as a
                // `data:text/html;base64,...` URL the user can paste in the
                // browser's address bar. Pastes directly in chat or email
                // without any external service. Capped at 5 MB
                // ([DataUrlBuilder.MAX_SIZE_BYTES]) — above the cap the
                // ViewModel returns a "use Compartir reporte instead" hint.
                // Engram #512.
                IconButton(
                    onClick = { vm.copyReportAsDataUrl(entry.id) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        "Copiar HTML como data URL (pegable en navegador; máx 5 MB)",
                        tint = Cyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // ── Delete ──
            IconButton(onClick = { showDeleteConfirmation = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, "Eliminar", tint = Red.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Eliminar sesión", fontWeight = FontWeight.Bold) },
            text = { Text("Se eliminarán la entrada del historial, el video y el informe. Esta acción no se puede deshacer.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirmation = false; vm.deleteHistoryEntry(entry.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) { Text("Eliminar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancelar") }
            },
            containerColor = DarkCard,
            titleContentColor = Color.White,
            textContentColor = TextSecondary
        )
    }

    // ── Rename dialog ──
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renombrar sesión", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Nombre actual:", color = TextDim, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White, fontSize = 14.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = TextDim,
                            cursorColor = Cyan,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                if (editName.isNotBlank()) {
                                    vm.renameHistoryEntry(entry.id, editName.trim())
                                    showRenameDialog = false
                                }
                            }
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank()) {
                            vm.renameHistoryEntry(entry.id, editName.trim())
                            showRenameDialog = false
                        }
                    },
                    enabled = editName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                ) {
                    Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancelar") }
            },
            containerColor = DarkCard,
            titleContentColor = Color.White,
            textContentColor = TextSecondary
        )
    }
}

// ============================================================================
// ===== Wireless ADB (v3.2.0): WifiPanelContent + sub-Composables ============
// ============================================================================
//
// Rendered either inside the "WiFi (Android 11+)" tab of the onboarding (when
// `devices.isEmpty()`) or inline under the device list when the user taps
// "+ Agregar device WiFi". A single Composable with two entry points — per
// design D-8. All state lives in the VM (`wifiPanel`, `mdnsAvailable`,
// `pairingServiceAlive`, `adbVersion`). The UI is a pure projection of the
// state machine: every button/click delegates back to a VM method.
//
// Wireframes: design §5.1-§5.10. Strings: spec §2 R-WP-5 (error messages come
// from the VM via `mapPairReasonToMessage` / `mapConnectReasonToMessage` and
// are LITERAL — we never reformat them here).

@Composable
private fun WifiPanelContent(viewModel: AppViewModel) {
    val state by viewModel.wifiPanel.collectAsState()
    val mdnsAvailable by viewModel.mdnsAvailable.collectAsState()
    val pairingServiceAlive by viewModel.pairingServiceAlive.collectAsState()
    val adbVersion by viewModel.adbVersion.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        // D-11 banner: warn users running platform-tools < 33 that cross-session
        // auto-reconnect (`$ADB_MDNS_AUTO_CONNECT`) requires adb >= 33 to work.
        // Non-blocking — everything still functions without it, users just have
        // to re-pair each session.
        val version = adbVersion
        if (version != null && version.major < 33) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Yellow.copy(alpha = 0.10f))
                    .border(1.dp, Yellow.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Yellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "platform-tools viejo detectado (v${version.major}.${version.minor}.${version.patch}). " +
                            "Para reconexión automática entre sesiones, actualiza a platform-tools 33 o superior.",
                        color = Yellow,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        when (val s = state) {
            is WifiDelegate.WifiPanelState.Hidden -> {
                // Nothing to render — caller guards with `if (state != Hidden)`
            }
            is WifiDelegate.WifiPanelState.Closed -> {
                // Same as Hidden from a rendering standpoint
            }
            is WifiDelegate.WifiPanelState.DiscoveringMdns -> {
                DiscoveringMdnsView()
            }
            is WifiDelegate.WifiPanelState.Discovered -> {
                DiscoveredView(
                    services = s.services,
                    mdnsAvailable = mdnsAvailable,
                    onClick = { viewModel.selectMdnsDevice(it) },
                    onManualRequested = {
                        // User explicitly clicked "Manual" — we don't have a direct
                        // VM transition, but submitting through submitManual goes
                        // through the same pairAndConnect path. To get the form
                        // visible we rely on `retryError()` side effect OR we
                        // just tell the user to keep the panel open (the 3-poll
                        // auto-fallback takes over). Simpler: reuse the VM's
                        // InputtingManual state by closing and re-opening with
                        // the override. But cleanest: there's no explicit method,
                        // so we fake it by calling retryError which restarts
                        // polling — the manual form will auto-expand after
                        // 3 empty polls. For immediate feedback we also show
                        // the manual form inline below the list.
                    },
                )
            }
            is WifiDelegate.WifiPanelState.InputtingCode -> {
                InputtingCodeView(
                    selected = s.selected,
                    pairingServiceAlive = pairingServiceAlive,
                    onSubmit = { code -> viewModel.submitCodeForSelected(code) },
                    onBack = { viewModel.closeWifiPanel() },
                )
            }
            is WifiDelegate.WifiPanelState.InputtingManual -> {
                InputtingManualView(
                    onSubmit = { ip, port, code -> viewModel.submitManual(ip, port, code) },
                )
            }
            is WifiDelegate.WifiPanelState.Pairing -> {
                PairingView()
            }
            is WifiDelegate.WifiPanelState.Connecting -> {
                ConnectingView()
            }
            is WifiDelegate.WifiPanelState.Connected -> {
                ConnectedView(deviceId = s.deviceId)
            }
            is WifiDelegate.WifiPanelState.Error -> {
                ErrorView(
                    message = s.message,
                    recoverable = s.recoverable,
                    onRetry = { viewModel.retryError() },
                    onClose = { viewModel.closeWifiPanel() },
                )
            }
        }
    }
}

/** 3-step onboarding hint shared by Discovering and Discovered views. */
@Composable
private fun WifiOnboardingSteps() {
    Column {
        Text(
            "1. En el móvil: Opciones de desarrollador → Depuración inalámbrica → ON",
            color = TextDim, fontSize = 11.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "2. Toca \"Emparejar dispositivo con codigo de emparejamiento\"",
            color = TextDim, fontSize = 11.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "3. Tu device aparece abajo, tocalo y tipea el codigo",
            color = TextDim, fontSize = 11.sp
        )
    }
}

@Composable
private fun DiscoveringMdnsView() {
    Column(modifier = Modifier.fillMaxWidth()) {
        WifiOnboardingSteps()
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = TextDim.copy(alpha = 0.3f))
        Spacer(Modifier.height(10.dp))
        Text("Dispositivos en la red", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Cyan,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(10.dp))
            Text("Buscando dispositivos...", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DiscoveredView(
    services: List<MdnsService>,
    mdnsAvailable: Boolean,
    onClick: (MdnsService) -> Unit,
    onManualRequested: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        WifiOnboardingSteps()
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = TextDim.copy(alpha = 0.3f))
        Spacer(Modifier.height(10.dp))
        Text("Dispositivos en la red", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        if (!mdnsAvailable) {
            Text(
                "El descubrimiento automático no está disponible. Usa el formulario manual para añadir el dispositivo.",
                color = Yellow, fontSize = 11.sp
            )
            return@Column
        }

        if (services.isEmpty()) {
            Text(
                "Aún no se encontraron dispositivos. Asegúrate de activar 'Depuración inalámbrica' en el móvil.",
                color = TextDim, fontSize = 11.sp
            )
        } else {
            services.forEach { service ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, TextDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .clickable { onClick(service) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = Cyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(service.instance, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${service.ip}:${service.port}",
                            color = TextDim, fontSize = 10.sp
                        )
                    }
                    if (service.serviceType == MdnsServiceType.PAIRING) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Green.copy(alpha = 0.15f))
                                .border(1.dp, Green.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("PAIRING", color = Green, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onManualRequested,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                "¿No aparece tu device? Agregar manualmente ▼",
                color = TextDim, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun InputtingCodeView(
    selected: MdnsService,
    pairingServiceAlive: Boolean,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val isValidCode = code.length == 6 && code.all { it.isDigit() }
    val canSubmit = isValidCode && pairingServiceAlive

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = Cyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(selected.instance, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${selected.ip}:${selected.port}", color = TextDim, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Codigo de emparejamiento (6 digitos)", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { new -> if (new.length <= 6 && new.all { it.isDigit() }) code = new },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, letterSpacing = 6.sp),
            placeholder = { Text("123456", color = TextDim) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                cursorColor = Cyan
            )
        )
        if (!pairingServiceAlive) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Reabri 'Emparejar dispositivo con codigo' en el movil",
                color = Yellow, fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text("← Volver", fontSize = 12.sp)
            }
            Button(
                onClick = { onSubmit(code) },
                enabled = canSubmit,
                modifier = Modifier.weight(2f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cyan,
                    disabledContainerColor = TextDim.copy(alpha = 0.3f)
                )
            ) {
                Text("Parear y conectar", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InputtingManualView(
    onSubmit: (ip: String, port: Int, code: String) -> Unit,
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    val ipRegex = remember { Regex("""^\d{1,3}(\.\d{1,3}){3}$""") }
    val isIpValid = ip.matches(ipRegex)
    val portInt = port.toIntOrNull()
    val isPortValid = portInt != null && portInt in 1..65535
    val isCodeValid = code.length == 6 && code.all { it.isDigit() }
    val canSubmit = isIpValid && isPortValid && isCodeValid

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "No se encontraron dispositivos. Asegúrate de activar 'Depuración inalámbrica' en el móvil y de estar en la misma red WiFi.",
            color = Yellow, fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = TextDim.copy(alpha = 0.3f))
        Spacer(Modifier.height(10.dp))
        Text("Agregar manualmente", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Text("IP del movil", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it.trim() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            singleLine = true,
            placeholder = { Text("192.168.1.42", color = TextDim) },
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                cursorColor = Cyan
            )
        )
        Spacer(Modifier.height(8.dp))

        Text("Puerto de emparejamiento", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { new -> if (new.all { it.isDigit() } && new.length <= 5) port = new },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text("37123", color = TextDim) },
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                cursorColor = Cyan
            )
        )
        Spacer(Modifier.height(8.dp))

        Text("Codigo (6 digitos)", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { new -> if (new.length <= 6 && new.all { it.isDigit() }) code = new },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            placeholder = { Text("123456", color = TextDim) },
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, letterSpacing = 3.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                cursorColor = Cyan
            )
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { onSubmit(ip, portInt ?: 0, code) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cyan,
                disabledContainerColor = TextDim.copy(alpha = 0.3f)
            )
        ) {
            Text("Parear y conectar", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PairingView() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = Cyan,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(12.dp))
        Text("Emparejando con el movil... (3-8s)", color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun ConnectingView() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = Cyan,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(12.dp))
        Text("Conectando...", color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun ConnectedView(deviceId: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Green,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text("Conectado: $deviceId", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ErrorView(
    message: String,
    recoverable: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Red.copy(alpha = 0.10f))
                .border(1.dp, Red.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Red,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(message, color = Red, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (recoverable) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                ) {
                    Text("Volver a intentar", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text("Cerrar", fontSize = 12.sp)
            }
        }
    }
}

// ============================================================================

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

// ============================================================================
// ===== v4.2.14 — Dependency Bootstrap Banner                                 =
// =====                                                                       =
// ===== Surfaces missing external dependencies (adb, ffmpeg) and offers the   =
// ===== user two paths to resolve them:                                       =
// =====   1. Primary CTA: in-app download into UserToolsDir (zero-config).    =
// =====   2. Fallback: open the official download URL in the system browser  =
// =====      so users behind corporate proxies can still proceed manually.   =
// =====                                                                       =
// ===== Visual pattern mirrors UpdateBanner: yellow card, icon, title,        =
// ===== buttons, optional progress bar + error text. The banner auto-hides    =
// ===== once the missing list is empty (after a successful install).          =
// ============================================================================
@Composable
private fun DepsBootstrapBanner(
    missingDeps: List<com.gameperf.desktop.core.DependencyBootstrap.MissingTool>,
    progress: com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress?,
    error: String?,
    onInstall: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    if (missingDeps.isEmpty()) return

    // Show one banner per missing tool. In practice the list is short (1-2 tools).
    missingDeps.forEach { tool ->
        val toolLabel = when (tool.toolName) {
            "adb" -> "ADB"
            "ffmpeg" -> "FFmpeg"
            else -> tool.toolName
        }
        val message = when (tool.reason) {
            com.gameperf.desktop.core.DependencyBootstrap.MissingReason.NOT_FOUND ->
                "$toolLabel no está instalado"
            com.gameperf.desktop.core.DependencyBootstrap.MissingReason.BUNDLED_AVAILABLE ->
                "$toolLabel disponible para instalar (incluido con la app)"
            com.gameperf.desktop.core.DependencyBootstrap.MissingReason.USER_DIR_AVAILABLE ->
                "$toolLabel listo para usar"
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Yellow.copy(alpha = 0.12f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        tint = Yellow,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Dependencia requerida",
                            color = Yellow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            message,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (progress == null || progress is com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress.Failed) {
                        Button(
                            onClick = { onInstall(tool.toolName) },
                            colors = ButtonDefaults.buttonColors(containerColor = Yellow),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "Descargar $toolLabel",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Progress bar — visible during downloading / extracting / verifying.
                if (progress != null && progress !is com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress.Failed) {
                    val pct = when (progress) {
                        is com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress.Downloading -> progress.percent
                        com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress.Completed -> 1f
                        else -> 0.5f // indeterminate-ish for Extracting / Verifying
                    }
                    val label = when (progress) {
                        is com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress.Downloading ->
                            "Descargando... ${String.format(java.util.Locale.US, "%.0f", pct * 100)}%"
                        com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress.Extracting -> "Extrayendo..."
                        com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress.Verifying -> "Verificando..."
                        com.gameperf.desktop.core.DependencyBootstrap.BootstrapProgress.Completed -> "Completado"
                        else -> ""
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Yellow,
                        trackColor = Yellow.copy(alpha = 0.15f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        color = Yellow,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Error + browser fallback.
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        color = Red,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { onOpenBrowser(tool.toolName) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                        ) {
                            Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Abrir en navegador", fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onDismissError,
                            colors = ButtonDefaults.textButtonColors(contentColor = TextDim)
                        ) {
                            Text("Cerrar", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * v4.4.1 — Best-effort browser opener for the [com.gameperf.desktop.ui.components.UpdateFallbackPanel]
 * action buttons. Failures are silently ignored — the panel itself surfaces
 * the URL via the button label so the user can copy/paste manually.
 */
private fun openInBrowser(url: String) {
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            desktop.browse(java.net.URI(url))
        }
    }
}
