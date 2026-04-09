"""
Screen capture endpoints.

Mac: Uses pymobiledevice3 screenshot service for frame-by-frame capture,
     stitched into video via ffmpeg. Target: 15-20fps.
Windows: Same approach but lower fps (5-10fps). Labeled "Vista previa".

Endpoints:
  GET  /device/{udid}/screenshot              → single PNG frame
  POST /device/{udid}/screen-record/start      → start capture session
  POST /device/{udid}/screen-record/stop       → stop and return video path
"""

from __future__ import annotations

import logging
import os
import platform
import shutil
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

logger = logging.getLogger("gameperf_sidecar.screen_capture")

router = APIRouter()

# Active capture sessions
_capture_sessions: dict[str, "CaptureSession"] = {}
_sessions_lock = threading.Lock()


class CaptureSession:
    """Captures screenshots in a loop and stitches them into a video."""

    def __init__(self, udid: str, session_id: str, output_dir: Path, target_fps: int = 15):
        self.udid = udid
        self.session_id = session_id
        self.output_dir = output_dir
        self.target_fps = target_fps
        self.capture_id = str(uuid.uuid4())[:8]
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._frame_count = 0
        self._frames_dir = output_dir / f"frames_{self.capture_id}"
        self._frames_dir.mkdir(parents=True, exist_ok=True)

    def start(self):
        """Start screenshot capture loop in background thread."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._capture_loop, daemon=True)
        self._thread.start()

    def stop(self) -> Optional[str]:
        """Stop capture and stitch frames into video. Returns video path or None."""
        self._running = False
        if self._thread:
            self._thread.join(timeout=10)

        if self._frame_count == 0:
            logger.warning(f"No frames captured for session {self.session_id}")
            return None

        # Stitch frames into video with ffmpeg
        video_path = self._stitch_video()
        # Cleanup frame images ONLY if stitch succeeded
        if video_path is not None:
            try:
                shutil.rmtree(self._frames_dir)
            except Exception:
                pass
        else:
            logger.warning(f"Keeping frame images at {self._frames_dir} because stitch failed")
        return video_path

    def _capture_loop(self):
        """Capture screenshots at target FPS."""
        import asyncio

        async def _async_capture():
            from pymobiledevice3.lockdown import create_using_usbmux
            from pymobiledevice3.services.screenshot import ScreenshotService

            lockdown = await create_using_usbmux(serial=self.udid)
            async with ScreenshotService(lockdown=lockdown) as screenshot_service:
                interval = 1.0 / self.target_fps

                while self._running:
                    start_time = time.time()
                    try:
                        png_data = await screenshot_service.take_screenshot()
                        if png_data:
                            frame_path = self._frames_dir / f"frame_{self._frame_count:06d}.png"
                            frame_path.write_bytes(png_data)
                            self._frame_count += 1
                    except Exception as e:
                        logger.debug(f"Screenshot error: {e}")

                    elapsed = time.time() - start_time
                    sleep_time = max(0, interval - elapsed)
                    if sleep_time > 0:
                        await asyncio.sleep(sleep_time)

        try:
            asyncio.run(_async_capture())
        except Exception as e:
            logger.error(f"Capture loop error for {self.udid}: {e}")
        finally:
            self._running = False

    def _stitch_video(self) -> Optional[str]:
        """Stitch PNG frames into MP4 using ffmpeg."""
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            logger.error("ffmpeg not found — cannot stitch video")
            return None

        output_path = self.output_dir / f"ios_video_{self.session_id}.mp4"

        # Use ffmpeg to encode PNGs into H.264 video
        cmd = [
            ffmpeg,
            "-y",
            "-framerate", str(self.target_fps),
            "-i", str(self._frames_dir / "frame_%06d.png"),
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-preset", "fast",
            "-crf", "23",
            str(output_path),
        ]

        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                timeout=120,
            )
            if result.returncode == 0 and output_path.exists():
                logger.info(f"Video stitched: {output_path} ({self._frame_count} frames)")
                return str(output_path)
            else:
                logger.error(f"ffmpeg failed: {result.stderr.decode()[:500]}")
                return None
        except subprocess.TimeoutExpired:
            logger.error("ffmpeg timed out during stitching")
            return None
        except Exception as e:
            logger.error(f"ffmpeg error: {e}")
            return None


async def _take_single_screenshot(udid: str) -> Optional[bytes]:
    """Take a single screenshot and return PNG bytes."""
    try:
        from pymobiledevice3.lockdown import create_using_usbmux
        from pymobiledevice3.services.screenshot import ScreenshotService

        lockdown = await create_using_usbmux(serial=udid)
        async with ScreenshotService(lockdown=lockdown) as screenshot_service:
            return await screenshot_service.take_screenshot()
    except Exception as e:
        logger.error(f"Screenshot error for {udid}: {e}")
        return None


@router.get("/device/{udid}/screenshot")
async def screenshot(udid: str):
    """Take a single screenshot and return as PNG."""
    png_data = await _take_single_screenshot(udid)
    if png_data is None:
        raise HTTPException(status_code=500, detail="Failed to capture screenshot")
    return Response(content=png_data, media_type="image/png")


@router.post("/device/{udid}/screen-record/start")
async def start_screen_record(udid: str, session_id: str = ""):
    """Start a screen capture session.

    Returns a capture_id that must be passed to the stop endpoint.
    """
    if not session_id:
        session_id = str(uuid.uuid4())[:12]

    # Determine FPS based on platform
    is_mac = platform.system() == "Darwin"
    target_fps = 15 if is_mac else 8  # Mac gets better FPS

    output_dir = Path.home() / "GamePerf Reports"
    output_dir.mkdir(exist_ok=True)

    session = CaptureSession(
        udid=udid,
        session_id=session_id,
        output_dir=output_dir,
        target_fps=target_fps,
    )

    with _sessions_lock:
        _capture_sessions[session.capture_id] = session

    session.start()

    # Windows gets a "Vista previa" label to warn users about lower quality
    quality_label = "Video" if is_mac else "Vista previa (baja frecuencia)"

    return {
        "captureId": session.capture_id,
        "sessionId": session_id,
        "targetFps": target_fps,
        "qualityLabel": quality_label,
        "platform": "mac" if is_mac else "windows",
    }


@router.post("/device/{udid}/screen-record/stop")
async def stop_screen_record(udid: str, capture_id: str = ""):
    """Stop a screen capture session and return the video path."""
    with _sessions_lock:
        session = _capture_sessions.pop(capture_id, None)

    if session is None:
        raise HTTPException(status_code=404, detail=f"Capture session {capture_id} not found")

    video_path = session.stop()

    return {
        "videoPath": video_path,
        "frameCount": session._frame_count,
        "captureId": capture_id,
    }
