"""
Metrics capture endpoints.

Uses pymobiledevice3's DVT (Developer Tools) instruments to capture:
- FPS via Graphics DVT service
- CPU% via Sysmontap
- Memory via Sysmontap (physFootprint)
- Thermals via IOKit diagnostics
- Battery via lockdown

Sentinel: -1 / -1.0 for unavailable metrics.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Optional

from fastapi import APIRouter, HTTPException

logger = logging.getLogger("gameperf_sidecar.metrics")

router = APIRouter()

# Active monitoring sessions per device
_active_sessions: dict[str, "MetricsSession"] = {}
_sessions_lock = threading.Lock()


class MetricsSession:
    """
    Manages a pymobiledevice3 DVT instruments session for continuous metrics capture.

    Keeps the latest snapshot in memory so the Kotlin app can poll via GET /device/{udid}/metrics
    without the overhead of re-establishing the DVT connection each time.
    """

    def __init__(self, udid: str):
        self.udid = udid
        self.latest_fps: int = -1
        self.latest_cpu: int = -1
        self.latest_mem_mb: int = -1
        self.latest_temp_cpu: float = -1.0
        self.latest_temp_gpu: float = -1.0
        self.latest_temp_battery: float = -1.0
        self.latest_battery_level: int = -1
        self.latest_avg_frame_time: float = -1.0
        self.latest_jank_count: int = 0
        self.latest_stutter_count: int = 0
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._lockdown = None
        self._last_update = 0.0

    def start(self):
        """Start background monitoring thread."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self._thread.start()

    def stop(self):
        """Stop background monitoring."""
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)

    def snapshot(self) -> dict:
        """Return latest metrics snapshot."""
        with self._lock:
            return {
                "fps": self.latest_fps,
                "avgFrameTime": self.latest_avg_frame_time,
                "jankCount": self.latest_jank_count,
                "stutterCount": self.latest_stutter_count,
                "cpuPercent": self.latest_cpu,
                "memoryMb": self.latest_mem_mb,
                "nativeMb": 0,   # iOS doesn't separate native/java
                "javaMb": 0,     # iOS doesn't separate native/java
                "tempCpu": self.latest_temp_cpu,
                "tempGpu": self.latest_temp_gpu,
                "tempBattery": self.latest_temp_battery,
                "tempSkin": -1.0,  # NEVER available on iOS
                "batteryLevel": self.latest_battery_level,
            }

    def _monitor_loop(self):
        """Background loop that polls DVT instruments."""
        try:
            from pymobiledevice3.lockdown import create_using_usbmux
            self._lockdown = create_using_usbmux(serial=self.udid)
        except Exception as e:
            logger.error(f"Cannot connect to device {self.udid}: {e}")
            self._running = False
            return

        while self._running:
            try:
                self._capture_cpu_and_memory()
                self._capture_fps()
                self._capture_thermals()
                self._capture_battery()
                self._last_update = time.time()
            except Exception as e:
                logger.warning(f"Metrics capture error for {self.udid}: {e}")

            # Poll every ~500ms to match Android cadence
            time.sleep(0.5)

    def _capture_cpu_and_memory(self):
        """Capture CPU% and memory via sysmontap."""
        try:
            from pymobiledevice3.services.dvt.instruments.sysmontap import Sysmontap

            with Sysmontap(lockdown=self._lockdown) as sysmontap:
                # Get a single snapshot
                for proc_snapshot in sysmontap:
                    if proc_snapshot is None:
                        continue
                    system_attrs = proc_snapshot.get("System", {})
                    with self._lock:
                        # System CPU usage (0-100)
                        cpu_usage = system_attrs.get("SystemCPUUsage", {})
                        total = cpu_usage.get("CPU_TotalLoad", -1)
                        self.latest_cpu = int(total) if total >= 0 else -1

                        # Memory: physFootprint of the frontmost app
                        processes = proc_snapshot.get("Processes", {})
                        # Sum or find the largest consumer — for now just total
                        if processes:
                            max_mem = max(
                                (p.get("physFootprint", 0) for p in processes.values()),
                                default=0,
                            )
                            self.latest_mem_mb = int(max_mem / (1024 * 1024))
                    break  # Single snapshot
        except Exception as e:
            logger.debug(f"Sysmontap error: {e}")

    def _capture_fps(self):
        """Capture FPS via Graphics DVT service."""
        try:
            from pymobiledevice3.services.dvt.instruments.graphics import Graphics

            with Graphics(lockdown=self._lockdown) as graphics:
                for sample in graphics:
                    if sample is None:
                        continue
                    with self._lock:
                        fps = sample.get("CoreAnimationFramesPerSecond", -1)
                        self.latest_fps = int(fps) if fps >= 0 else -1
                        if fps > 0:
                            self.latest_avg_frame_time = 1000.0 / fps
                            # Jank: frame time > 16.67ms (below 60fps target)
                            # This is a rough estimate — Android uses compositor data
                            if fps < 55:
                                self.latest_jank_count += 1
                            if fps < 30:
                                self.latest_stutter_count += 1
                    break
        except Exception as e:
            logger.debug(f"Graphics DVT error: {e}")

    def _capture_thermals(self):
        """Capture thermal data via diagnostics service."""
        try:
            from pymobiledevice3.services.diagnostics import DiagnosticsService

            with DiagnosticsService(lockdown=self._lockdown) as diag:
                ioregistry = diag.ioregistry_entry("AppleARMSOCDevice", "IOService")
                with self._lock:
                    if ioregistry and isinstance(ioregistry, dict):
                        # CPU temp — varies by device model
                        self.latest_temp_cpu = _extract_temp(ioregistry, ["SOC Die Temp Sensor0", "die-temp", "Temperature"])
                        # GPU temp — rarely exposed separately
                        self.latest_temp_gpu = _extract_temp(ioregistry, ["GPU Die Temp Sensor", "gpu-temp"])

                # Battery temp via different IORegistry path
                battery_info = diag.ioregistry_entry("AppleSmartBattery", "IOService")
                if battery_info and isinstance(battery_info, dict):
                    raw_temp = battery_info.get("Temperature", -1)
                    with self._lock:
                        if raw_temp > 0:
                            # Apple reports in centidegrees
                            self.latest_temp_battery = raw_temp / 100.0
                        else:
                            self.latest_temp_battery = -1.0
        except Exception as e:
            logger.debug(f"Diagnostics error: {e}")

    def _capture_battery(self):
        """Capture battery level via lockdown."""
        try:
            if self._lockdown:
                battery = self._lockdown.all_values.get("BatteryCurrentCapacity", -1)
                with self._lock:
                    self.latest_battery_level = int(battery) if battery >= 0 else -1
        except Exception as e:
            logger.debug(f"Battery error: {e}")


def _extract_temp(registry: dict, keys: list[str]) -> float:
    """Try multiple IORegistry keys to find a temperature value."""
    for key in keys:
        val = registry.get(key)
        if val is not None and isinstance(val, (int, float)) and val > 0:
            return float(val)
    return -1.0


def _get_or_create_session(udid: str) -> MetricsSession:
    """Get existing session or create new one."""
    with _sessions_lock:
        if udid not in _active_sessions:
            session = MetricsSession(udid)
            session.start()
            _active_sessions[udid] = session
        return _active_sessions[udid]


@router.get("/device/{udid}/metrics")
async def get_metrics(udid: str):
    """Get latest metrics snapshot for a device.

    Auto-starts a monitoring session on first call. The session runs in a
    background thread polling DVT instruments every ~500ms.
    """
    session = _get_or_create_session(udid)
    return session.snapshot()


@router.delete("/device/{udid}/metrics")
async def stop_metrics(udid: str):
    """Stop metrics monitoring for a device."""
    with _sessions_lock:
        session = _active_sessions.pop(udid, None)
    if session:
        session.stop()
        return {"status": "stopped"}
    return {"status": "not_found"}
