"""
Metrics capture endpoints.

Uses pymobiledevice3's DVT instruments to capture:
- FPS via Graphics DVT service
- CPU% via Sysmontap
- Memory via Sysmontap (physFootprint)
- Thermals via DiagnosticsService (battery temperature)
- Battery via DiagnosticsService

Sentinel: -1 / -1.0 for unavailable metrics.

v4.0.0-fix: DVT connections kept OPEN for the lifetime of the session.
v4.1.0-fix: Adapted for pymobiledevice3 v9.9.1 API changes:
  - Sysmontap/Graphics now require a DvtProvider instead of lockdown directly
  - DiagnosticsService uses .get_battery() instead of .ioregistry_entry()
  - DvtProvider is the new DVT channel factory

IMPORTANT: DVT instruments (FPS, CPU, memory) require Developer Mode enabled
on the iOS device. Without it, only battery/temperature via DiagnosticsService
works. The sidecar degrades gracefully — sentinels (-1) are returned for
unavailable metrics.
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
    Manages continuous metrics capture for one iOS device.

    v4.1.0: Uses DvtProvider as DVT channel factory (pymobiledevice3 v9.9.1+).
    Falls back gracefully when Developer Mode is not enabled — battery/temp
    still work via DiagnosticsService.
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
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run_async_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self._thread:
            self._thread.join(timeout=10)

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "fps": self.latest_fps,
                "avgFrameTime": self.latest_avg_frame_time,
                "jankCount": self.latest_jank_count,
                "stutterCount": self.latest_stutter_count,
                "cpuPercent": self.latest_cpu,
                "memoryMb": self.latest_mem_mb,
                "nativeMb": 0,
                "javaMb": 0,
                "tempCpu": self.latest_temp_cpu,
                "tempGpu": self.latest_temp_gpu,
                "tempBattery": self.latest_temp_battery,
                "tempSkin": -1.0,
                "batteryLevel": self.latest_battery_level,
            }

    def _run_async_loop(self):
        try:
            asyncio.run(self._async_monitor_loop())
        except Exception as e:
            logger.error(f"MetricsSession async loop died for {self.udid}: {e}")
        finally:
            self._running = False

    async def _async_monitor_loop(self):
        """
        v4.1.0: Two independent capture paths:
        1. DVT path (FPS, CPU, memory) — requires Developer Mode
        2. Diagnostics path (battery, temperature) — works without Developer Mode

        Both run as concurrent tasks. If DVT fails (no developer mode),
        only diagnostics data is available.
        """
        from pymobiledevice3.lockdown import create_using_usbmux

        lockdown = await create_using_usbmux(serial=self.udid)

        tasks = [
            asyncio.create_task(self._poll_dvt_instruments(lockdown)),
            asyncio.create_task(self._poll_diagnostics(lockdown)),
        ]

        try:
            while self._running:
                await asyncio.sleep(0.5)
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _poll_dvt_instruments(self, lockdown):
        """
        v4.1.0: DVT instruments (Sysmontap + Graphics) via DvtProvider.
        Requires Developer Mode. Fails gracefully if not available.
        """
        try:
            from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
            from pymobiledevice3.services.dvt.instruments.sysmontap import Sysmontap
            from pymobiledevice3.services.dvt.instruments.graphics import Graphics

            async with DvtProvider(lockdown=lockdown) as dvt:
                logger.info(f"DVT Provider connected for {self.udid}")

                # Start Sysmontap and Graphics as concurrent sub-tasks
                sysmontap_task = asyncio.create_task(self._read_sysmontap(dvt))
                graphics_task = asyncio.create_task(self._read_graphics(dvt))

                try:
                    while self._running:
                        await asyncio.sleep(1)
                finally:
                    sysmontap_task.cancel()
                    graphics_task.cancel()
                    await asyncio.gather(sysmontap_task, graphics_task, return_exceptions=True)

        except asyncio.CancelledError:
            pass
        except Exception as e:
            # Most likely: InvalidServiceError (Developer Mode not enabled)
            logger.warning(f"DVT instruments unavailable for {self.udid}: {type(e).__name__}: {e}")
            logger.info("FPS/CPU/memory metrics will show as -1. Enable Developer Mode on the device for full metrics.")

    async def _read_sysmontap(self, dvt):
        """Read CPU + memory from Sysmontap stream."""
        try:
            from pymobiledevice3.services.dvt.instruments.sysmontap import Sysmontap

            sysmontap = Sysmontap.create(dvt, interval=500)
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

    async def _read_graphics(self, dvt):
        """Read FPS from Graphics stream."""
        try:
            from pymobiledevice3.services.dvt.instruments.graphics import Graphics

            graphics = Graphics(dvt)
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

    async def _poll_diagnostics(self, lockdown):
        """
        v4.1.0: Battery + temperature via DiagnosticsService.
        Works WITHOUT Developer Mode. Uses get_battery() API (v9.9.1+).
        """
        try:
            from pymobiledevice3.services.diagnostics import DiagnosticsService

            async with DiagnosticsService(lockdown=lockdown) as diag:
                logger.info(f"DiagnosticsService connected for {self.udid}")
                while self._running:
                    try:
                        battery_data = await diag.get_battery()
                        if battery_data and isinstance(battery_data, dict):
                            with self._lock:
                                # Battery level
                                cap = battery_data.get("CurrentCapacity", -1)
                                self.latest_battery_level = int(cap) if cap is not None and cap >= 0 else -1

                                # Battery temperature (raw is centidegrees)
                                raw_temp = battery_data.get("Temperature", -1)
                                if isinstance(raw_temp, (int, float)) and raw_temp > 0:
                                    self.latest_temp_battery = raw_temp / 100.0
                                else:
                                    self.latest_temp_battery = -1.0

                    except Exception as e:
                        logger.debug(f"Diagnostics poll error: {e}")

                    await asyncio.sleep(2.0)
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.warning(f"DiagnosticsService error for {self.udid}: {e}")


def _get_or_create_session(udid: str) -> MetricsSession:
    with _sessions_lock:
        if udid not in _active_sessions:
            session = MetricsSession(udid)
            session.start()
            _active_sessions[udid] = session
        return _active_sessions[udid]


@router.get("/device/{udid}/metrics")
async def get_metrics(udid: str):
    """Get latest metrics snapshot. Auto-starts monitoring on first call."""
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
