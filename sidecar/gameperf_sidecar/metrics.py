"""
Metrics capture endpoints.

Uses pymobiledevice3's DVT (Developer Tools) instruments to capture:
- FPS via Graphics DVT service
- CPU% via Sysmontap
- Memory via Sysmontap (physFootprint)
- Thermals via IOKit diagnostics
- Battery via lockdown

Sentinel: -1 / -1.0 for unavailable metrics.

v4.0.0-fix: DVT connections are kept OPEN for the lifetime of the session.
Previous version opened and closed Sysmontap/Graphics/DiagnosticsService
on every 500ms poll — 6 DVT connections/second, which would overwhelm
the device. Now each service is opened ONCE in the async monitor loop
and polled repeatedly.
"""

from __future__ import annotations

import asyncio
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

    Architecture (v4.0.0-fix):
    - A single background thread runs an asyncio event loop
    - DVT services (Sysmontap, Graphics) are opened ONCE as async context managers
    - The loop polls each service every ~500ms WITHOUT reconnecting
    - All state is protected by self._lock
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
        self._last_update = 0.0

    def start(self):
        """Start background monitoring thread."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run_async_loop, daemon=True)
        self._thread.start()

    def stop(self):
        """Stop background monitoring."""
        self._running = False
        if self._thread:
            self._thread.join(timeout=10)

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

    def _run_async_loop(self):
        """Entry point for the background thread — runs the async monitor loop."""
        try:
            asyncio.run(self._async_monitor_loop())
        except Exception as e:
            logger.error(f"MetricsSession async loop died for {self.udid}: {e}")
        finally:
            self._running = False

    async def _async_monitor_loop(self):
        """
        Main async monitoring loop. Opens DVT connections ONCE and polls repeatedly.

        Architecture:
        - create_using_usbmux → lockdown (async)
        - Battery: polled via lockdown.all_values (cheap, every iteration)
        - CPU/Mem: polled via Sysmontap async iterator (opened once)
        - FPS: polled via Graphics async iterator (opened once)
        - Thermals: polled via DiagnosticsService (opened once)
        """
        from pymobiledevice3.lockdown import create_using_usbmux

        lockdown = await create_using_usbmux(serial=self.udid)

        # Capture battery from lockdown (no DVT needed)
        self._capture_battery_from_lockdown(lockdown)

        # Run instrument captures concurrently as separate tasks
        # Each opens its DVT service ONCE and polls in a loop
        tasks = [
            asyncio.create_task(self._poll_sysmontap(lockdown)),
            asyncio.create_task(self._poll_graphics(lockdown)),
            asyncio.create_task(self._poll_thermals(lockdown)),
            asyncio.create_task(self._poll_battery(lockdown)),
        ]

        try:
            # Wait until _running is False
            while self._running:
                await asyncio.sleep(0.5)
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _poll_sysmontap(self, lockdown):
        """Poll CPU% and memory via Sysmontap — connection kept open."""
        try:
            from pymobiledevice3.services.dvt.instruments.sysmontap import Sysmontap

            async with Sysmontap(lockdown=lockdown) as sysmontap:
                async for proc_snapshot in sysmontap:
                    if not self._running:
                        break
                    if proc_snapshot is None:
                        continue

                    system_attrs = proc_snapshot.get("System", {})
                    with self._lock:
                        cpu_usage = system_attrs.get("SystemCPUUsage", {})
                        total = cpu_usage.get("CPU_TotalLoad", -1)
                        self.latest_cpu = int(total) if total >= 0 else -1

                        processes = proc_snapshot.get("Processes", {})
                        if processes:
                            max_mem = max(
                                (p.get("physFootprint", 0) for p in processes.values()),
                                default=0,
                            )
                            self.latest_mem_mb = int(max_mem / (1024 * 1024))

                    self._last_update = time.time()
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.warning(f"Sysmontap error for {self.udid}: {e}")

    async def _poll_graphics(self, lockdown):
        """Poll FPS via Graphics DVT service — connection kept open."""
        try:
            from pymobiledevice3.services.dvt.instruments.graphics import Graphics

            async with Graphics(lockdown=lockdown) as graphics:
                async for sample in graphics:
                    if not self._running:
                        break
                    if sample is None:
                        continue

                    with self._lock:
                        fps = sample.get("CoreAnimationFramesPerSecond", -1)
                        self.latest_fps = int(fps) if fps >= 0 else -1
                        if fps > 0:
                            self.latest_avg_frame_time = 1000.0 / fps
                            if fps < 55:
                                self.latest_jank_count += 1
                            if fps < 30:
                                self.latest_stutter_count += 1
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.warning(f"Graphics DVT error for {self.udid}: {e}")

    async def _poll_thermals(self, lockdown):
        """Poll thermal data periodically — reuses DiagnosticsService connection."""
        try:
            from pymobiledevice3.services.diagnostics import DiagnosticsService

            async with DiagnosticsService(lockdown=lockdown) as diag:
                while self._running:
                    try:
                        ioregistry = diag.ioregistry_entry("AppleARMSOCDevice", "IOService")
                        with self._lock:
                            if ioregistry and isinstance(ioregistry, dict):
                                self.latest_temp_cpu = _extract_temp(ioregistry, ["SOC Die Temp Sensor0", "die-temp", "Temperature"])
                                self.latest_temp_gpu = _extract_temp(ioregistry, ["GPU Die Temp Sensor", "gpu-temp"])

                        battery_info = diag.ioregistry_entry("AppleSmartBattery", "IOService")
                        if battery_info and isinstance(battery_info, dict):
                            raw_temp = battery_info.get("Temperature", -1)
                            with self._lock:
                                if raw_temp > 0:
                                    self.latest_temp_battery = raw_temp / 100.0
                                else:
                                    self.latest_temp_battery = -1.0
                    except Exception as e:
                        logger.debug(f"Thermal poll error: {e}")

                    await asyncio.sleep(2.0)  # Thermals change slowly — every 2s
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.warning(f"DiagnosticsService error for {self.udid}: {e}")

    async def _poll_battery(self, lockdown):
        """Poll battery level periodically via lockdown."""
        while self._running:
            try:
                self._capture_battery_from_lockdown(lockdown)
            except Exception as e:
                logger.debug(f"Battery poll error: {e}")
            await asyncio.sleep(2.0)  # Battery changes slowly

    def _capture_battery_from_lockdown(self, lockdown):
        """Capture battery level — sync, called from async context."""
        try:
            battery = lockdown.all_values.get("BatteryCurrentCapacity", -1)
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
    background thread with persistent DVT connections.
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
