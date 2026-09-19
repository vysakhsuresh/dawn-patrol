#!/bin/bash
# Render frames of the real game (shipped Sim + shipped Renderer) to
# out/kt/*.ppm, and to PNG if Pillow is available.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KT="${DAWNPATROL_KT:-$HOME/.dawnpatrol-kt}"
O=$(mktemp -d)
"$ROOT/tools/build.sh" "$O" "$ROOT/tools/FrameDump.kt"
cd "$ROOT"
java -cp "$O:$KT/kotlin-stdlib.jar" com.dawnpatrol.game.FrameDump out/kt
python3 - <<'PY' || echo "(install Pillow for PNGs; PPMs are written regardless)"
from PIL import Image
import glob, os
for p in sorted(glob.glob("out/kt/*.ppm")):
    im = Image.open(p).convert("RGB")
    im.resize((im.width*5, im.height*5), Image.NEAREST).save(p[:-4] + ".png")
print("PNGs written to out/kt/")
PY
