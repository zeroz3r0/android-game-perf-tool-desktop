# -*- mode: python ; coding: utf-8 -*-
#
# PyInstaller spec for gameperf-sidecar.
#
# Build on macOS:  pyinstaller sidecar/gameperf_sidecar.spec
# Build on Windows: pyinstaller sidecar\gameperf_sidecar.spec
#
# Output: sidecar/dist/gameperf-sidecar[.exe]

import sys
from pathlib import Path

block_cipher = None

# Entry point is gameperf_sidecar/__main__.py
entry = str(Path('gameperf_sidecar') / '__main__.py')

a = Analysis(
    [entry],
    pathex=['.'],          # run from sidecar/ directory
    binaries=[],
    datas=[],
    hiddenimports=[
        # uvicorn dynamic imports
        'uvicorn.lifespan.on',
        'uvicorn.lifespan.off',
        'uvicorn.logging',
        'uvicorn.loops',
        'uvicorn.loops.auto',
        'uvicorn.loops.asyncio',
        'uvicorn.protocols',
        'uvicorn.protocols.http',
        'uvicorn.protocols.http.auto',
        'uvicorn.protocols.http.h11_impl',
        'uvicorn.protocols.http.httptools_impl',
        'uvicorn.protocols.websockets',
        'uvicorn.protocols.websockets.auto',
        'uvicorn.protocols.websockets.websockets_impl',
        'uvicorn.protocols.websockets.wsproto_impl',
        # fastapi / starlette
        'fastapi',
        'starlette.routing',
        'starlette.responses',
        'starlette.middleware',
        'starlette.middleware.cors',
        # pymobiledevice3 — collected via collect_all but list key ones explicitly
        'pymobiledevice3',
        'pymobiledevice3.lockdown',
        'pymobiledevice3.usbmux',
        'pymobiledevice3.services',
        'pymobiledevice3.services.dvt',
        'pymobiledevice3.services.dvt.instruments',
        'pymobiledevice3.services.diagnostics',
        'pymobiledevice3.services.installation_proxy',
        'pymobiledevice3.services.springboard',
        'pymobiledevice3.services.syslog',
        'pymobiledevice3.tcp_forwarder',
        # async / pydantic
        'anyio',
        'anyio._backends._asyncio',
        'pydantic',
        'pydantic.deprecated',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['tkinter', 'matplotlib', 'numpy', 'scipy', 'PIL'],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
    # Collect ALL of pymobiledevice3 to catch dynamic imports
    collect_all=['pymobiledevice3'],
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='gameperf-sidecar',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,          # console app (no GUI)
    disable_windowed_traceback=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
