"""
Generate the adaptive launcher icon's VectorDrawable XML from
mock/icon_design.py - the layout that was rendered, looked at
(out/icon_design.png), and verified under every mask shape
(out/icon_masks.png, mock/icon_masks.py) before this ever gets run.

Re-run this after any change to icon_design.py's layout constants.
"""
import icon_design as ID

BG_PATH = "/home/user/dawn-patrol/app/src/main/res/drawable/ic_launcher_background.xml"
FG_PATH = "/home/user/dawn-patrol/app/src/main/res/drawable/ic_launcher_foreground.xml"

# paint order: sky bands light-to-dark-warm, then the sun, then the
# ground/buildings on top - matches the layering in icon_design.py
COLOR_ORDER = ["#C97A2E", "#DB9A3C", "#EAB752", "#F6D488", "#FBE7B8", "#1A1310"]


def runs_to_path(rects_xyxy):
    """rects_xyxy: (x0, x1, y0, y1) in dp -> one compact 'd' path string,
    run-length batched the same way GameView batches canvas.drawRect."""
    parts = []
    for (x0, x1, y0, y1) in rects_xyxy:
        w, h = x1 - x0, y1 - y0
        parts.append("M%.2f,%.2fh%.2fv%.2fh-%.2fz" % (x0, y0, w, h, w))
    return "".join(parts)


def write_background():
    layers = ID.build_layers()
    paths = []
    for color, runs in layers.items():
        xyxy = [(gx0 * ID.CELL, gx1 * ID.CELL, gy * ID.CELL, (gy + 1) * ID.CELL)
                for (gx0, gx1, gy) in runs]
        paths.append((color, runs_to_path(xyxy)))
    paths.sort(key=lambda p: COLOR_ORDER.index(p[0]) if p[0] in COLOR_ORDER else 99)

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Dawn sky + aerodrome silhouette, run-length batched from the",
        "     same SPR_HANGAR/SPR_TENT grids GameView draws (see mock/icon_design.py,",
        "     which is also what rendered out/icon_design.png and out/icon_masks.png",
        "     for verification before this file was generated). Background bleeds to",
        "     every edge on purpose - only the foreground plane needs the safe zone.",
        "     Regenerate with mock/gen_icon_xml.py, don't hand-edit. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
    ]
    for color, d in paths:
        lines.append('    <path')
        lines.append('        android:pathData="%s"' % d)
        lines.append('        android:fillColor="%s" />' % color)
    lines.append('</vector>')
    open(BG_PATH, "w").write("\n".join(lines) + "\n")
    return len(paths)


def write_foreground():
    plane_runs = ID.plane_runs_dp()
    worst = ID.worst_corner_radius(plane_runs)
    assert worst <= 35.0, "plane escapes the safe zone: radius %.2f" % worst
    d = runs_to_path(plane_runs)

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- The plane silhouette, run-length batched from the same SPR_PLANE grid",
        "     GameView draws (see mock/icon_design.py - also generates the background",
        "     layer). Verified inside the adaptive-icon safe zone: worst corner at",
        "     radius %.2f of the 35/36 budget (mock/icon_design.py's own audit, plus" % worst,
        "     out/icon_masks.png rendered under circle/squircle/square @ 192/96/48px).",
        "     Regenerate with mock/gen_icon_xml.py, don't hand-edit. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
        '    <path',
        '        android:pathData="%s"' % d,
        '        android:fillColor="#1A160F" />',
        '</vector>',
    ]
    open(FG_PATH, "w").write("\n".join(lines) + "\n")
    return worst


if __name__ == "__main__":
    n = write_background()
    worst = write_foreground()
    print("background: %d fill colors -> %s" % (n, BG_PATH))
    print("foreground: worst corner radius %.2f -> %s" % (worst, FG_PATH))
