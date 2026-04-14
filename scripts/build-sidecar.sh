#!/usr/bin/env bash
# Build the gameperf-sidecar PyInstaller binary for macOS / Linux.
# Output: sidecar/dist/gameperf-sidecar
#
# Usage:
#   ./scripts/build-sidecar.sh
#
# Requires: Python 3.9+, pip
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIDECAR_DIR="$REPO_ROOT/sidecar"

echo "==> Building gameperf-sidecar (PyInstaller) from $SIDECAR_DIR"

cd "$SIDECAR_DIR"

# Install deps + pyinstaller in isolated venv
python3 -m venv .venv-build
# shellcheck disable=SC1091
source .venv-build/bin/activate

pip install --upgrade pip --quiet
pip install pyinstaller --quiet
pip install -r requirements-lock.txt --quiet

pyinstaller gameperf_sidecar.spec --distpath dist --workpath build/pyinstaller --clean

echo ""
echo "==> Done. Binary at: $SIDECAR_DIR/dist/gameperf-sidecar"
ls -lh "$SIDECAR_DIR/dist/gameperf-sidecar"

deactivate
