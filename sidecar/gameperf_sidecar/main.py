"""
GamePerf iOS Sidecar — main FastAPI application.

Run with: python -m gameperf_sidecar --port 8765
Or:       uvicorn gameperf_sidecar.main:app --port 8765

Endpoints:
  GET  /health                         → {"status":"ok","version":"1.0.0"}
  POST /shutdown                       → graceful shutdown
  GET  /devices                        → list connected iOS devices
  GET  /device/{udid}/info             → device hardware info
  GET  /device/{udid}/metrics          → FPS, CPU%, memory, thermals, battery
  GET  /device/{udid}/screenshot       → PNG screenshot
  POST /device/{udid}/screen-record/start → start screen capture session
  POST /device/{udid}/screen-record/stop  → stop and return video path
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal
import sys

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from . import __version__

logger = logging.getLogger("gameperf_sidecar")

app = FastAPI(
    title="GamePerf iOS Sidecar",
    version=__version__,
    docs_url=None,
    redoc_url=None,
)


# ===== Health & Lifecycle =====

@app.get("/health")
async def health():
    """Health check for SidecarLifecycle polling."""
    return {"status": "ok", "version": __version__}


@app.post("/shutdown")
async def shutdown():
    """Graceful shutdown requested by the Kotlin app."""
    logger.info("Shutdown requested via /shutdown endpoint")
    # Schedule shutdown after response is sent
    asyncio.get_running_loop().call_later(0.5, _force_exit)
    return {"status": "shutting_down"}


def _force_exit():
    """Force exit the process."""
    os.kill(os.getpid(), signal.SIGTERM)


# ===== Device endpoints (imported from submodules) =====

from .devices import router as devices_router  # noqa: E402
from .metrics import router as metrics_router  # noqa: E402
from .screen_capture import router as screen_capture_router  # noqa: E402

app.include_router(devices_router)
app.include_router(metrics_router)
app.include_router(screen_capture_router)


# ===== Error handling =====

@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    logger.error(f"Unhandled error: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"error": str(exc)},
    )


# ===== CLI entry point =====

def main():
    import argparse
    import uvicorn

    parser = argparse.ArgumentParser(description="GamePerf iOS Sidecar")
    parser.add_argument("--port", type=int, default=8765, help="Port to listen on")
    parser.add_argument("--host", default="127.0.0.1", help="Host to bind to")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    )
    logger.info(f"Starting GamePerf iOS Sidecar v{__version__} on {args.host}:{args.port}")

    uvicorn.run(
        "gameperf_sidecar.main:app",
        host=args.host,
        port=args.port,
        log_level="info",
    )
