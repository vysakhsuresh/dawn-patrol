"""
Keep the preview honest.

The rule from Cat on a Fence: a mock renderer that holds its own copy of the
art will drift from the shipped game within a day.  So the moment a Kotlin
source exists, THAT becomes the source of truth and this module rewrites
artdata's values from it.  Until then artdata's own literals are used, and
`status()` says which.

Kotlin shapes recognised:
    private val SPR_PLANE = arrayOf(
        "....XXXX....",
        ...
    )
    val SPR_BLAST = arrayOf(          // nested: an array of frames
        arrayOf("...", "XXX", ...),
        arrayOf(...),
    )
    const val HORIZON = 126
    private const val CLIMB_ACC = 0.055f

Parsing is done with BALANCED-PAREN scanning, not a single regex over the
whole body: an earlier version used `arrayOf\\((.*?)\\)` non-greedy, which
stops at the first ')' it meets - correct for a flat sprite, silently wrong
for a nested one like SPR_BLAST (it truncated a 4-frame animation down to
"whatever's before the first frame's closing paren"). Verified against a
fixture with real nested arrayOf(arrayOf(...), ...) before trusting it here.
"""
import os
import re
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

VAL_HEAD_RE = re.compile(
    r'\bval\s+([A-Z][A-Z0-9_]*)\s*(?::[^=]+)?=\s*arrayOf\s*\(')
ROW_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
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


def _matching_paren(src, open_idx):
    """Index of the ')' that balances the '(' at open_idx, honouring
    nested parens and skipping over parens that appear inside "..." """
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == '\\' else 1
            i += 1
            continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("unbalanced parens from index %d" % open_idx)


def _parse_body(body):
    """A comma-separated arrayOf(...) body: either quoted-string rows (a
    single sprite frame) or nested arrayOf(...) calls (an animation - a
    list of frames, each a list of rows)."""
    stripped = body.strip()
    if stripped.startswith("arrayOf"):
        frames = []
        i = 0
        while True:
            m = re.compile(r'arrayOf\s*\(').search(stripped, i)
            if not m:
                break
            close = _matching_paren(stripped, m.end() - 1)
            frames.append(ROW_RE.findall(stripped[m.end():close]))
            i = close + 1
        return frames
    return ROW_RE.findall(stripped)


def parse(paths=None):
    """Return (sprites, consts) lifted straight out of the Kotlin source.
    `sprites[name]` is a list of rows for a flat sprite, or a list of
    lists of rows for a nested (multi-frame) one."""
    sprites, consts = {}, {}
    for path in (paths if paths is not None else sources()):
        with open(path) as fh:
            src = fh.read()
        for m in VAL_HEAD_RE.finditer(src):
            name = m.group(1)
            if not name.startswith("SPR_"):
                continue
            open_idx = m.end() - 1
            close_idx = _matching_paren(src, open_idx)
            parsed = _parse_body(src[open_idx + 1:close_idx])
            if parsed:
                sprites[name] = parsed
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
        v = s[k]
        if v and isinstance(v[0], list):
            print("  sprite %-22s %d frames" % (k, len(v)))
        else:
            print("  sprite %-22s %2d x %2d" % (k, max(len(r) for r in v), len(v)))
    for k in sorted(c):
        print("  const  %-22s %s" % (k, c[k]))
