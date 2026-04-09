"""
Contract tests for the sidecar API.

These test the response shapes and status codes WITHOUT a real iOS device.
pymobiledevice3 is mocked so tests run in CI.
"""

from __future__ import annotations

import json
from unittest.mock import patch, MagicMock

import pytest
from fastapi.testclient import TestClient

from gameperf_sidecar.main import app

client = TestClient(app)


# ===== Health =====

def test_health_returns_ok():
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "version" in data


def test_health_version_is_string():
    resp = client.get("/health")
    data = resp.json()
    assert isinstance(data["version"], str)
    assert len(data["version"]) > 0


# ===== Devices =====

@patch("gameperf_sidecar.devices.list_devices")
def test_devices_empty_when_no_devices(mock_list):
    """No devices connected → empty list, not error."""
    mock_list.return_value = []
    resp = client.get("/devices")
    assert resp.status_code == 200
    data = resp.json()
    assert data["devices"] == []


@patch("gameperf_sidecar.devices._list_connected_devices")
def test_devices_returns_expected_shape(mock_fn):
    """Device list has required fields."""
    mock_fn.return_value = [{
        "id": "00001111-AABBCCDD",
        "model": "iPhone 15 Pro",
        "platform": "IOS",
        "isWifi": False,
        "connectionType": "USB",
    }]
    resp = client.get("/devices")
    assert resp.status_code == 200
    data = resp.json()
    assert len(data["devices"]) == 1
    dev = data["devices"][0]
    assert dev["id"] == "00001111-AABBCCDD"
    assert dev["platform"] == "IOS"
    assert dev["isWifi"] is False
    assert "model" in dev


# ===== Device Info =====

@patch("gameperf_sidecar.devices._get_device_info")
def test_device_info_returns_expected_shape(mock_fn):
    mock_fn.return_value = {
        "model": "iPhone 15 Pro",
        "manufacturer": "Apple",
        "cpu": "arm64e",
        "gpu": "Apple GPU (A17 Pro)",
        "ram": "8 GB",
        "cores": 6,
        "osVersion": "17.4",
        "resolution": "1179x2556",
        "platform": "IOS",
    }
    resp = client.get("/device/test-udid/info")
    assert resp.status_code == 200
    data = resp.json()
    assert data["platform"] == "IOS"
    assert data["manufacturer"] == "Apple"
    assert isinstance(data["cores"], int)
    assert isinstance(data["osVersion"], str)


# ===== Metrics =====

@patch("gameperf_sidecar.metrics._get_or_create_session")
def test_metrics_returns_expected_shape(mock_fn):
    """Metrics snapshot has all required fields with sentinel defaults."""
    mock_session = MagicMock()
    mock_session.snapshot.return_value = {
        "fps": -1,
        "avgFrameTime": -1.0,
        "jankCount": 0,
        "stutterCount": 0,
        "cpuPercent": -1,
        "memoryMb": -1,
        "nativeMb": 0,
        "javaMb": 0,
        "tempCpu": -1.0,
        "tempGpu": -1.0,
        "tempBattery": -1.0,
        "tempSkin": -1.0,
        "batteryLevel": -1,
    }
    mock_fn.return_value = mock_session
    resp = client.get("/device/test-udid/metrics")
    assert resp.status_code == 200
    data = resp.json()

    # All required fields present
    assert "fps" in data
    assert "cpuPercent" in data
    assert "memoryMb" in data
    assert "nativeMb" in data
    assert "javaMb" in data
    assert "tempCpu" in data
    assert "tempGpu" in data
    assert "tempBattery" in data
    assert "tempSkin" in data
    assert "batteryLevel" in data
    assert "avgFrameTime" in data
    assert "jankCount" in data
    assert "stutterCount" in data

    # Sentinel values for unavailable metrics
    assert data["tempSkin"] == -1.0  # NEVER available on iOS


def test_metrics_sentinels_are_correct_types():
    """Verify sentinel types match what Kotlin expects."""
    # -1 for Int fields, -1.0 for Double fields
    with patch("gameperf_sidecar.metrics._get_or_create_session") as mock_fn:
        mock_session = MagicMock()
        mock_session.snapshot.return_value = {
            "fps": -1, "avgFrameTime": -1.0, "jankCount": 0, "stutterCount": 0,
            "cpuPercent": -1, "memoryMb": -1, "nativeMb": 0, "javaMb": 0,
            "tempCpu": -1.0, "tempGpu": -1.0, "tempBattery": -1.0, "tempSkin": -1.0,
            "batteryLevel": -1,
        }
        mock_fn.return_value = mock_session
        resp = client.get("/device/test-udid/metrics")
        data = resp.json()

        # Int fields
        assert isinstance(data["fps"], int)
        assert isinstance(data["cpuPercent"], int)
        assert isinstance(data["memoryMb"], int)
        assert isinstance(data["batteryLevel"], int)

        # Float fields
        assert isinstance(data["tempCpu"], float)
        assert isinstance(data["tempGpu"], float)
        assert isinstance(data["tempSkin"], float)


# ===== Screen Capture =====

@patch("gameperf_sidecar.screen_capture._take_single_screenshot")
def test_screenshot_returns_png_on_success(mock_fn):
    mock_fn.return_value = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100  # fake PNG header
    resp = client.get("/device/test-udid/screenshot")
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "image/png"


@patch("gameperf_sidecar.screen_capture._take_single_screenshot")
def test_screenshot_returns_500_on_failure(mock_fn):
    mock_fn.return_value = None
    resp = client.get("/device/test-udid/screenshot")
    assert resp.status_code == 500


def test_screen_record_stop_returns_404_for_unknown_session():
    resp = client.post("/device/test-udid/screen-record/stop?capture_id=nonexistent")
    assert resp.status_code == 404


# ===== Error handling =====

def test_device_info_404_for_missing_device():
    """Unknown device returns 404, not 500."""
    with patch("gameperf_sidecar.devices._get_device_info") as mock_fn:
        from fastapi import HTTPException
        mock_fn.side_effect = HTTPException(status_code=404, detail="Device not found")
        resp = client.get("/device/nonexistent-udid/info")
        assert resp.status_code == 404


# ===== Windows-specific behavior =====

@patch("gameperf_sidecar.screen_capture.platform")
def test_screen_record_start_windows_gets_lower_fps(mock_platform):
    """On Windows, target FPS should be 8 (degraded), not 15."""
    mock_platform.system.return_value = "Windows"
    with patch("gameperf_sidecar.screen_capture.CaptureSession") as mock_session_cls:
        mock_instance = MagicMock()
        mock_instance.capture_id = "test-cap"
        mock_instance._frame_count = 0
        mock_session_cls.return_value = mock_instance
        resp = client.post("/device/test-udid/screen-record/start?session_id=test-session")
        assert resp.status_code == 200
        data = resp.json()
        assert data["targetFps"] == 8
        assert data["platform"] == "windows"
        assert "Vista previa" in data.get("qualityLabel", "")


@patch("gameperf_sidecar.screen_capture.platform")
def test_screen_record_start_mac_gets_full_fps(mock_platform):
    """On Mac, target FPS should be 15 (full quality)."""
    mock_platform.system.return_value = "Darwin"
    with patch("gameperf_sidecar.screen_capture.CaptureSession") as mock_session_cls:
        mock_instance = MagicMock()
        mock_instance.capture_id = "test-cap"
        mock_instance._frame_count = 0
        mock_session_cls.return_value = mock_instance
        resp = client.post("/device/test-udid/screen-record/start?session_id=test-session")
        assert resp.status_code == 200
        data = resp.json()
        assert data["targetFps"] == 15
        assert data["platform"] == "mac"
        assert data.get("qualityLabel", "") == "Video"


def test_screen_record_start_response_has_quality_label():
    """Response includes qualityLabel field regardless of platform."""
    with patch("gameperf_sidecar.screen_capture.CaptureSession") as mock_session_cls:
        mock_instance = MagicMock()
        mock_instance.capture_id = "test-cap"
        mock_instance._frame_count = 0
        mock_session_cls.return_value = mock_instance
        resp = client.post("/device/test-udid/screen-record/start?session_id=test-session")
        assert resp.status_code == 200
        data = resp.json()
        assert "qualityLabel" in data
