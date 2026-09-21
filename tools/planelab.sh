#!/bin/bash
# Draw candidate aeroplane silhouettes through the shipped Fb.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KT="${DAWNPATROL_KT:-$HOME/.dawnpatrol-kt}"
O=$(mktemp -d)
"$ROOT/tools/build.sh" "$O" "$ROOT/tools/PlaneLab.kt"
cd "$ROOT"
java -cp "$O:$KT/kotlin-stdlib.jar" com.dawnpatrol.game.PlaneLab out/kt
for c in python3.12 python3.11 python3; do
  command -v "$c" >/dev/null 2>&1 || continue
  "$c" -c "import PIL.Image" >/dev/null 2>&1 && { PY3="$c"; break; }
done
[ -z "$PY3" ] && exit 0
"$PY3" - <<'PY'
from PIL import Image
im = Image.open("out/kt/planes.ppm").convert("RGB")
im.resize((im.width*3, im.height*3), Image.NEAREST).save("out/kt/planes.png")
print("out/kt/planes.png")
PY
