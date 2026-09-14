"""
Keep the preview honest.

The rule from Cat on a Fence: a mock renderer that holds its own copy of the
art will drift from the shipped game within a day.  So the moment a Kotlin
source exists, THAT becomes the source of truth and this module rewrites
artdata's values from it with regex.  Until then artdata's own literals are
used, and `status()` says which.

Kotlin shapes recognised:
    private val SPR_PLANE = arrayOf(
        "....XXXX....",
        ...
    )
    const val HORIZON = 126
    private const val CLIMB_ACC = 0.055f
"""
import os
import re
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SPRITE_RE = re.compile(
    r'\bval\s+(SPR_[A-Z0-9_]+)\s*(?::[^=]+)?=\s*arrayOf\s*\((.*?)\)',
    re.S)
ROW_RE = re.compile(r'"([.X]+)"')
CONST_RE = re.compile(
    r'\b(?:const\s+)?val\s+([A-Z][A-Z0-9_]{1,30})\s*(?::\s*\w+\s*)?=\s*'
    r'(-?\d+(?:\.\d+)?)[fFL]?\s*(?:$|//|\n)', re.M)


def sources():
    pats = ("app/src/main/java/**/*.kt", "app/src/main/kotlin/**/*.kt",
            "**/*.kt")
    out = []
    for p in pats:
        out += glob.glob(os.path.join(ROOT, p), recursive=True)
        if out:
            break
    return sorted(set(out))


def parse(paths=None):
    """Return (sprites, consts) lifted straight out of the Kotlin source."""
    sprites, consts = {}, {}
    for path in (paths if paths is not None else sources()):
        with open(path) as fh:
            src = fh.read()
        for name, body in SPRITE_RE.findall(src):
            rows = ROW_RE.findall(body)
            if rows:
                sprites[name] = rows
        for name, val in CONST_RE.findall(src):
            consts[name] = float(val) if "." in val else int(val)
    return sprites, consts


def apply_to(module, paths=None):
    """Overwrite module attributes from Kotlin.  Returns a report."""
    sprites, consts = parse(paths)
    changed, added = [], []
    for name, rows in sprites.items():
        if hasattr(module, name):
            if getattr(module, name) != rows:
                changed.append(name)
        else:
            added.append(name)
        setattr(module, name, rows)
    for name, val in consts.items():
        if hasattr(module, name) and getattr(module, name) != val:
            changed.append(name)
        setattr(module, name, val)
    return {"files": len(paths if paths is not None else sources()),
            "sprites": len(sprites), "consts": len(consts),
            "changed": changed, "added": added}


def status():
    n = len(sources())
    if n == 0:
        return "no .kt yet - previews are driven by mock/artdata.py literals"
    s, c = parse()
    return ("parsed %d .kt file(s): %d sprites, %d constants lifted from "
            "shipped source" % (n, len(s), len(c)))


if __name__ == "__main__":
    print(status())
    s, c = parse()
    for k in sorted(s):
        print("  sprite %-22s %2d x %2d" % (k, max(len(r) for r in s[k]), len(s[k])))
    for k in sorted(c):
        print("  const  %-22s %s" % (k, c[k]))
