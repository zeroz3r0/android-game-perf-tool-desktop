# Build the gameperf-sidecar PyInstaller binary for Windows.
# Output: sidecar\dist\gameperf-sidecar.exe
#
# Usage (from repo root):
#   .\scripts\build-sidecar.ps1
#
# Requires: Python 3.9+ on PATH
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$SidecarDir = Join-Path $RepoRoot "sidecar"

Write-Host "==> Building gameperf-sidecar.exe (PyInstaller) from $SidecarDir"

Set-Location $SidecarDir

# Create isolated venv
python -m venv .venv-build
& ".venv-build\Scripts\Activate.ps1"

pip install --upgrade pip --quiet
pip install pyinstaller --quiet
pip install -r requirements-lock.txt --quiet

pyinstaller gameperf_sidecar.spec --distpath dist --workpath build\pyinstaller --clean

Write-Host ""
Write-Host "==> Done. Binary at: $SidecarDir\dist\gameperf-sidecar.exe"
Get-Item "$SidecarDir\dist\gameperf-sidecar.exe" | Select-Object Name, Length

deactivate
