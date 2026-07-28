#!/usr/bin/env python3
"""Generate 128x80 Minecraft PNG flags from overlay SVG sources."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = Path(__file__).resolve().parent


def main() -> None:
    node_modules = TOOLS / "node_modules"
    if not node_modules.exists():
        print("Installing flag rasterization dependencies (npm install in tools/)...")
        subprocess.check_call(["npm", "install"], cwd=TOOLS)

    subprocess.check_call(["node", str(TOOLS / "rasterize_flags.mjs")], cwd=TOOLS)
    print("Flag generation complete.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as exc:
        sys.exit(exc.returncode)
