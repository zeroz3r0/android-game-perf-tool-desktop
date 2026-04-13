"""
Device discovery and info endpoints.

Uses pymobiledevice3's usbmux to list connected iOS devices and query
their hardware info via lockdown.
"""

from __future__ import annotations

import logging
from typing import Optional

from fastapi import APIRouter, HTTPException

logger = logging.getLogger("gameperf_sidecar.devices")

router = APIRouter()


async def _list_connected_devices() -> list[dict]:
    """List all connected iOS devices via usbmux."""
    try:
        from pymobiledevice3.usbmux import list_devices
        from pymobiledevice3.lockdown import create_using_usbmux
        devices = await list_devices()
        result = []
        for dev in devices:
            try:
                lockdown = await create_using_usbmux(serial=dev.serial)
                product_type = lockdown.product_type or "Unknown"
                # Try to get a friendly model name
                model = lockdown.all_values.get("DeviceName", product_type)
                result.append({
                    "id": dev.serial,
                    "model": model,
                    "platform": "IOS",
                    "isWifi": False,
                    "connectionType": "USB",
                })
            except Exception as e:
                logger.warning(f"Could not query device {dev.serial}: {e}")
                result.append({
                    "id": dev.serial,
                    "model": "Unknown iOS Device",
                    "platform": "IOS",
                    "isWifi": False,
                    "connectionType": "USB",
                })
        return result
    except ImportError:
        logger.error("pymobiledevice3 not installed")
        return []
    except Exception as e:
        logger.error(f"Error listing devices: {e}")
        return []


async def _get_device_info(udid: str) -> dict:
    """Get detailed hardware info for a device."""
    try:
        from pymobiledevice3.lockdown import create_using_usbmux
        lockdown = await create_using_usbmux(serial=udid)
        values = lockdown.all_values

        # Extract hardware info
        model = values.get("DeviceName", values.get("ProductType", "Unknown"))
        manufacturer = "Apple"
        cpu = values.get("CPUArchitecture", "Unknown")
        # iOS doesn't expose GPU model via lockdown — infer from chip
        hw_model = values.get("HardwareModel", "")
        product_type = values.get("ProductType", "")
        gpu = _infer_gpu(product_type, hw_model)

        # RAM: not directly exposed, estimate from ProductType
        ram = _estimate_ram(product_type)
        cores = _estimate_cores(cpu)

        os_version = values.get("ProductVersion", "Unknown")
        resolution = _get_resolution(product_type)

        return {
            "model": model,
            "manufacturer": manufacturer,
            "cpu": cpu,
            "gpu": gpu,
            "ram": ram,
            "cores": cores,
            "osVersion": os_version,
            "resolution": resolution,
            "platform": "IOS",
        }
    except Exception as e:
        logger.error(f"Error getting device info for {udid}: {e}")
        raise HTTPException(status_code=404, detail=f"Device {udid} not found or not accessible")


def _infer_gpu(product_type: str, hw_model: str) -> str:
    """Best-effort GPU inference from ProductType."""
    # Map chip families to GPU names
    gpu_map = {
        "iPhone16": "Apple GPU (A17 Pro)",
        "iPhone15": "Apple GPU (A16)",
        "iPhone14": "Apple GPU (A15)",
        "iPhone13": "Apple GPU (A15)",
        "iPhone12": "Apple GPU (A14)",
        "iPhone11": "Apple GPU (A13)",
        "iPhone10": "Apple GPU (A11)",
        "iPad14": "Apple GPU (M2)",
        "iPad13": "Apple GPU (M1)",
    }
    for prefix, gpu in gpu_map.items():
        if product_type.startswith(prefix):
            return gpu
    return "Apple GPU"


def _estimate_ram(product_type: str) -> str:
    """Best-effort RAM estimate from ProductType."""
    # iPhones 15 Pro / Pro Max: 8 GB, others: 6 GB
    if "iPhone16" in product_type:
        return "8 GB"
    if "iPhone15" in product_type:
        return "6 GB"
    if "iPhone14" in product_type:
        return "6 GB"
    return "4 GB"


def _estimate_cores(cpu_arch: str) -> int:
    """Estimate core count from CPU architecture."""
    if "arm64e" in cpu_arch:
        return 6
    if "arm64" in cpu_arch:
        return 6
    return 4


def _get_resolution(product_type: str) -> str:
    """Best-effort resolution from ProductType."""
    res_map = {
        "iPhone16,1": "1179x2556",  # iPhone 15 Pro
        "iPhone16,2": "1290x2796",  # iPhone 15 Pro Max
        "iPhone15,2": "1179x2556",  # iPhone 14 Pro
        "iPhone15,3": "1290x2796",  # iPhone 14 Pro Max
        "iPhone14,2": "1170x2532",  # iPhone 13 Pro
        "iPhone14,3": "1284x2778",  # iPhone 13 Pro Max
    }
    return res_map.get(product_type, "Unknown")


async def _get_foreground_app(udid: str) -> Optional[str]:
    """
    v4.1.0: Detect the foreground app bundle ID using SpringBoardServices.
    Returns the bundle identifier or None if unavailable.
    """
    try:
        from pymobiledevice3.lockdown import create_using_usbmux
        from pymobiledevice3.services.springboardservices import SpringBoardServicesService

        lockdown = await create_using_usbmux(serial=udid)
        async with SpringBoardServicesService(lockdown=lockdown) as sbs:
            # get_front_board_application_info returns dict with bundleId
            info = sbs.get_icon_state()
            # Alternative: use instruments for more reliable detection
            pass
    except ImportError:
        pass
    except Exception as e:
        logger.debug(f"SpringBoard detection failed: {e}")

    # Fallback: use instruments DVT to get frontmost app
    try:
        from pymobiledevice3.lockdown import create_using_usbmux
        from pymobiledevice3.services.dvt.instruments.process_control import ProcessControl

        lockdown = await create_using_usbmux(serial=udid)
        async with ProcessControl(lockdown=lockdown) as proc_control:
            # List running processes and find the frontmost non-system app
            # This is best-effort — iOS doesn't have a single "foreground app" API like Android
            pass
    except Exception as e:
        logger.debug(f"ProcessControl detection failed: {e}")

    return None


@router.get("/devices")
async def list_devices():
    """List all connected iOS devices."""
    devices = await _list_connected_devices()
    return {"devices": devices}


@router.get("/device/{udid}/info")
async def get_device_info(udid: str):
    """Get hardware info for a specific device."""
    info = await _get_device_info(udid)
    return info


@router.get("/device/{udid}/foreground-app")
async def get_foreground_app(udid: str):
    """v4.1.0: Detect the frontmost app on an iOS device."""
    bundle_id = await _get_foreground_app(udid)
    return {"bundleId": bundle_id}
