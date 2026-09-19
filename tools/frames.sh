#!/bin/bash
# Render frames of the real game (shipped Sim + shipped Renderer) to
# out/kt/*.ppm, and to PNG if Pillow is available.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KT="${DAWNPATROL_KT:-$HOME/.dawnpatrol-kt}"
O=$(mktemp -d)
"$ROOT/tools/build.sh" "$O" "$ROOT/tools/TestPilot.kt" "$ROOT/tools/FrameDump.kt"
cd "$ROOT"
java -cp "$O:$KT/kotlin-stdlib.jar" com.dawnpatrol.game.FrameDump out/kt
# Pillow here is packaged for a specific CPython ABI; the default `python3`
# on this box is not always it, and the import fails with a bare
# "cannot import name _imaging". Take the first interpreter that can
# actually load it.
PY3=""
for c in python3.12 python3.11 python3; do
  command -v "$c" >/dev/null 2>&1 || continue
  "$c" -c "import PIL.Image" >/dev/null 2>&1 && { PY3="$c"; break; }
done
if [ -z "$PY3" ]; then
  echo "(no interpreter with a working Pillow; PPMs are written regardless)"
  exit 0
fi
"$PY3" - <<'PY'
from PIL import Image, ImageDraw
import glob
ps = sorted(glob.glob("out/kt/f*.ppm"))
ims = []
for p in ps:
    im = Image.open(p).convert("RGB")
    im.resize((im.width*5, im.height*5), Image.NEAREST).save(p[:-4] + ".png")
    ims.append((p.split("/")[-1][:-4], im))
# one contact sheet, so a regression is visible at a glance rather than
# needing ten files opened
S, pad, lab = 3, 8, 12
W = sum(im.width*S + pad for _, im in ims) + pad
H = max(im.height*S for _, im in ims) + pad*2 + lab
sheet = Image.new("RGB", (W, H), (24, 20, 16))
d = ImageDraw.Draw(sheet)
x = pad
for n, im in ims:
    sheet.paste(im.resize((im.width*S, im.height*S), Image.NEAREST), (x, pad+lab))
    d.text((x, 2), n, fill=(230, 220, 200))
    x += im.width*S + pad
sheet.save("out/kt/contact.png")
print("PNGs + contact sheet written to out/kt/")
PY
