package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.AdbBridgeApi
import com.gameperf.desktop.core.AdbVersion
import com.gameperf.desktop.core.ConnectFailureReason
import com.gameperf.desktop.core.ConnectResult
import com.gameperf.desktop.core.MdnsService
import com.gameperf.desktop.core.MdnsServiceType
import com.gameperf.desktop.core.PairFailureReason
import com.gameperf.desktop.core.PairResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.1.0 — Manages the WiFi ADB pairing panel state machine.
 *
 * Extracted from AppViewModel. Owns the sealed [WifiPanelState], mDNS polling
 * loop, and pair/connect orchestration. The delegate receives the AdbBridgeApi,
 * a CoroutineScope, and a callback for refreshDevices (the only cross-delegate
 * dependency — after a successful connect, the device list needs refreshing).
 *
 * Design reference: sdd/wireless-adb/design (D-1..D-11).
 * Spec reference: sdd/wireless-adb/spec (WP-1..WP-11).
 */
class WifiDelegate(
    private val adb: AdbBridgeApi,
    private val scope: CoroutineScope,
    private val onRefreshDevices: () -> Unit,
) {

    private val _wifiPanel = MutableStateFlow<WifiPanelState>(WifiPanelState.Hidden)
    val wifiPanel: StateFlow<WifiPanelState> = _wifiPanel

    private val _mdnsAvailable = MutableStateFlow(true)
    val mdnsAvailable: StateFlow<Boolean> = _mdnsAvailable

    private val _pairingServiceAlive = MutableStateFlow(false)
    val pairingServiceAlive: StateFlow<Boolean> = _pairingServiceAlive

    private val _adbVersion = MutableStateFlow<AdbVersion?>(null)
    val adbVersion: StateFlow<AdbVersion?> = _adbVersion

    private var mdnsPollingJob: Job? = null

    init {
        // Check adb version once at startup (needed for "mDNS unavailable" warning).
        scope.launch {
            _adbVersion.value = withContext(Dispatchers.IO) { adb.getAdbVersion() }
        }
    }

    /**
     * State of the "Agregar device WiFi" panel.
     */
    sealed class WifiPanelState {
        object Hidden : WifiPanelState()
        object Closed : WifiPanelState()
        object DiscoveringMdns : WifiPanelState()
        data class Discovered(val services: List<MdnsService>) : WifiPanelState()
        data class InputtingCode(val selected: MdnsService) : WifiPanelState()
        object InputtingManual : WifiPanelState()
        object Pairing : WifiPanelState()
        object Connecting : WifiPanelState()
        data class Connected(val deviceId: String) : WifiPanelState()
        data class Error(val message: String, val recoverable: Boolean) : WifiPanelState()
    }

    private fun mapPairReasonToMessage(reason: PairFailureReason): String = when (reason) {
        PairFailureReason.INVALID_CODE, PairFailureReason.EXPIRED_CODE ->
            "Codigo incorrecto. Abri nuevamente 'Emparejar dispositivo con codigo' en el movil para generar un codigo nuevo."
        PairFailureReason.CONNECTION_REFUSED ->
            "No se puede conectar a esa direccion. Verifica que la IP sea la que muestra el movil y que esten en la misma WiFi."
        PairFailureReason.TIMEOUT ->
            "El movil no respondio. Asegurate de que 'Depuracion inalambrica' este activa en el movil."
        PairFailureReason.UNKNOWN ->
            "No se pudo emparejar el dispositivo. Volve a abrir el menu de emparejamiento en el movil y probá de nuevo."
    }

    private fun mapConnectReasonToMessage(reason: ConnectFailureReason): String = when (reason) {
        ConnectFailureReason.NO_ROUTE ->
            "El movil no esta visible en la red. Verifica que tenga WiFi activa y este en la misma red que esta computadora."
        ConnectFailureReason.REFUSED ->
            "El movil rechazo la conexion. Abri de nuevo 'Depuracion inalambrica' en el movil y probá de nuevo."
        ConnectFailureReason.TIMEOUT ->
            "El movil no respondio al conectar. Verifica que siga en la misma WiFi."
        ConnectFailureReason.UNKNOWN ->
            "No se pudo conectar al dispositivo. Volve a parearlo desde el menu del movil."
    }

    private fun startMdnsPolling() {
        mdnsPollingJob?.cancel()
        if (!_mdnsAvailable.value) {
            _wifiPanel.value = WifiPanelState.InputtingManual
            return
        }
        mdnsPollingJob = scope.launch {
            var consecutiveEmpty = 0
            while (isActive) {
                val current = _wifiPanel.value
                if (current is WifiPanelState.Hidden ||
                    current is WifiPanelState.Closed ||
                    current is WifiPanelState.Connected
                ) break

                val services = withContext(Dispatchers.IO) {
                    try { adb.mdnsServices() } catch (_: Throwable) { emptyList() }
                }
                val pairingServices = services.filter { it.serviceType == MdnsServiceType.PAIRING }
                _pairingServiceAlive.value = pairingServices.isNotEmpty()

                when (val s = _wifiPanel.value) {
                    is WifiPanelState.DiscoveringMdns, is WifiPanelState.Discovered -> {
                        _wifiPanel.value = WifiPanelState.Discovered(pairingServices)
                        if (pairingServices.isEmpty()) consecutiveEmpty++
                        else consecutiveEmpty = 0
                        if (consecutiveEmpty >= 3 && _mdnsAvailable.value) {
                            _wifiPanel.value = WifiPanelState.InputtingManual
                        }
                    }
                    is WifiPanelState.InputtingCode -> {
                        @Suppress("UNUSED_EXPRESSION") s
                    }
                    else -> { /* keep sensor updating */ }
                }
                delay(2500)
            }
        }
    }

    private fun stopMdnsPolling() {
        mdnsPollingJob?.cancel()
        mdnsPollingJob = null
    }

    private suspend fun findConnectServiceForInstance(
        instance: String, retries: Int = 1,
    ): MdnsService? {
        repeat(retries + 1) { attempt ->
            val snap = withContext(Dispatchers.IO) {
                try { adb.mdnsServices() } catch (_: Throwable) { emptyList() }
            }
            val match = snap.firstOrNull {
                it.serviceType == MdnsServiceType.CONNECT && it.instance == instance
            }
            if (match != null) return match
            if (attempt < retries) delay(500)
        }
        return null
    }

    private suspend fun pairAndConnect(
        service: MdnsService?, ip: String, pairPort: Int, code: String,
    ) {
        _wifiPanel.value = WifiPanelState.Pairing
        val pairResult = withContext(Dispatchers.IO) { adb.pair(ip, pairPort, code) }
        if (pairResult is PairResult.Failure) {
            _wifiPanel.value = WifiPanelState.Error(
                message = mapPairReasonToMessage(pairResult.reason), recoverable = true,
            )
            return
        }

        val connectIp: String
        val connectPort: Int
        if (service != null) {
            val cs = findConnectServiceForInstance(service.instance, retries = 1)
            if (cs == null) {
                _wifiPanel.value = WifiPanelState.Error(
                    message = "No se pudo encontrar el puerto de conexion del dispositivo. Volve a parear desde el menu del movil.",
                    recoverable = true,
                )
                return
            }
            connectIp = cs.ip
            connectPort = cs.port
        } else {
            val snap = withContext(Dispatchers.IO) {
                try { adb.mdnsServices() } catch (_: Throwable) { emptyList() }
            }
            val byIp = snap.firstOrNull {
                it.serviceType == MdnsServiceType.CONNECT && it.ip == ip
            }
            if (byIp != null) { connectIp = byIp.ip; connectPort = byIp.port }
            else { connectIp = ip; connectPort = pairPort }
        }
        _wifiPanel.value = WifiPanelState.Connecting
        val connectResult = withContext(Dispatchers.IO) { adb.connectWireless(connectIp, connectPort) }
        when (connectResult) {
            is ConnectResult.Success -> {
                _wifiPanel.value = WifiPanelState.Connected(connectResult.deviceId)
                onRefreshDevices()
                delay(1000)
                _wifiPanel.value = WifiPanelState.Hidden
                stopMdnsPolling()
            }
            is ConnectResult.Failure -> {
                _wifiPanel.value = WifiPanelState.Error(
                    message = mapConnectReasonToMessage(connectResult.reason), recoverable = true,
                )
            }
        }
    }

    // ===== Public API =====

    internal fun setMdnsAvailableForTest(available: Boolean) {
        _mdnsAvailable.value = available
    }

    fun openWifiPanel() {
        if (_wifiPanel.value !is WifiPanelState.Hidden &&
            _wifiPanel.value !is WifiPanelState.Closed
        ) return
        _wifiPanel.value = WifiPanelState.DiscoveringMdns
        startMdnsPolling()
    }

    fun closeWifiPanel() {
        _wifiPanel.value = WifiPanelState.Hidden
        stopMdnsPolling()
    }

    fun selectMdnsDevice(service: MdnsService) {
        _wifiPanel.value = WifiPanelState.InputtingCode(service)
    }

    fun submitCodeForSelected(code: String) {
        val state = _wifiPanel.value
        if (state !is WifiPanelState.InputtingCode) return
        val service = state.selected
        scope.launch {
            pairAndConnect(service = service, ip = service.ip, pairPort = service.port, code = code)
        }
    }

    fun submitManual(ip: String, pairPort: Int, code: String) {
        scope.launch {
            pairAndConnect(service = null, ip = ip, pairPort = pairPort, code = code)
        }
    }

    fun retryError() {
        _wifiPanel.value = WifiPanelState.DiscoveringMdns
        startMdnsPolling()
    }
}
