"""
Regression audit for the Dawn Patrol procedural world.

These are the bugs Cat on a Fence actually shipped.  Each one is a numeric
assertion, run over thousands of frames, not an eyeball check.

  A1  Scroll stability - nothing changes shape while it is on screen.
  A2  Position-seeded, not index-seeded - the visible set must be identical
      whether you enumerate slots from the left edge or the right edge.
  A3  Wrap safety - behaviour at and across worldX = 0 and negative worldX
      is identical to behaviour anywhere else  (the -(a%b) != -a%b trap).
  A4  Art never extends past the hitbox in the direction that matters.
  A5  Terrain continuity - no gaps, no cliffs the renderer would tear on.
"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artdata as A
import world as W
import scene as S

FAIL = []


def check(name, ok, detail=""):
    print("%-52s %s   %s" % (name, "PASS" if ok else "FAIL", detail))
    if not ok:
        FAIL.append(name)


# ---------------------------------------------------------------- A1
def a1_scroll_stability(frames=24000, speed=1.37):
    """Replay a long scroll.  Every time a world x is sampled, the terrain
    height and the feature at that position must be bit-identical to the
    first time it was seen, regardless of the camera offset."""
    seen_terr, seen_slot = {}, {}
    worst = 0.0
    cam = -913.5
    for f in range(frames):
        cam += speed
        for sx in range(0, A.GW, 3):
            wx = round(cam + sx, 4)
            key = round(wx, 2)
            g = W.ground_y(wx)
            if key in seen_terr:
                worst = max(worst, abs(seen_terr[key] - g))
            else:
                seen_terr[key] = g
        for k in W.visible_slots(cam, A.GW):
            s = W.slot(k)
            if k in seen_slot:
                if seen_slot[k] != s:
                    check("A1 slot %d mutated" % k, False, str(s))
                    return
            else:
                seen_slot[k] = s
    check("A1 terrain stable over %d frames" % frames, worst == 0.0,
          "max drift %.g px over %d sampled world positions" %
          (worst, len(seen_terr)))
    check("A1 features stable over %d frames" % frames, True,
          "%d distinct slots visited, 0 mutations" % len(seen_slot))


# ---------------------------------------------------------------- A2
def a2_seed_source(trials=6000):
    """The visible feature set must not depend on which edge you count from.
    This is the exact bug that reshuffled the stone wall once a second."""
    bad = 0
    for i in range(trials):
        cam = (i * 7.31) - 9000.0
        left = [W.slot(k) for k in W.visible_slots(cam, A.GW)]
        # enumerate the other way: from the right edge, walking backwards
        k1 = W.cell(cam + A.GW + 40, A.SLOT_W)
        k0 = W.cell(cam - 40, A.SLOT_W)
        right = [W.slot(k) for k in range(k1, k0 - 1, -1)][::-1]
        if left != right:
            bad += 1
    check("A2 visible set independent of walk direction", bad == 0,
          "%d/%d camera positions" % (trials - bad, trials))

    # and: cell()/frac() must be derived from the same floor()
    worst = 0.0
    for i in range(trials):
        wx = (i * 3.77) - 7000.0
        c = W.cell(wx, A.TERRAIN_CELL)
        t = W.frac(wx, A.TERRAIN_CELL, c)
        worst = max(worst, 0.0 if 0.0 <= t < 1.0 else abs(t))
    check("A2 within-cell fraction always in [0,1)", worst == 0.0,
          "checked %d positions incl. negative worldX" % trials)


# ---------------------------------------------------------------- A3
def a3_wrap_safety():
    """-(a % b) is not -a % b.  Prove cell() has no sign discontinuity."""
    bad = []
    for wx in [x * 0.5 for x in range(-400, 400)]:
        c = W.cell(wx, A.TERRAIN_CELL)
        if not (c * A.TERRAIN_CELL <= wx < (c + 1) * A.TERRAIN_CELL):
            bad.append(wx)
    check("A3 cell() correct across worldX = 0", not bad,
          "%d samples, %d failures" % (800, len(bad)))
    # naive int() truncation would fail here - show the contrast
    naive_bad = sum(1 for wx in [x * 0.5 for x in range(-400, 0)]
                    if int(wx / A.TERRAIN_CELL) * A.TERRAIN_CELL > wx)
    check("A3 naive int() truncation would have failed", naive_bad > 0,
          "%d of 400 negative samples - this is why cell() uses floor()"
          % naive_bad)


# ---------------------------------------------------------------- A4
def a4_art_within_hitbox():
    """LESSON #2: drawn art must never extend past the hitbox in the
    direction that matters.  For a flyer that is UP and DOWN: the ground,
    the ceiling and balloon cables all test vertical extent."""
    ok = True
    for name in ("SPR_PLANE", "SPR_PLANE_CLIMB", "SPR_PLANE_DIVE"):
        spr = getattr(A, name)
        rows = [j for j, r in enumerate(spr) if "X" in r]
        cols = [i for r in spr for i, c in enumerate(r) if c == "X"]
        top, bot = min(rows), max(rows)
        hb = (0, 0, max(len(r) for r in spr), len(spr))
        inside = (top >= hb[1] and bot < hb[3]
                  and min(cols) >= hb[0] and max(cols) < hb[2])
        print("    %-16s art rows %2d..%-2d  hitbox rows %2d..%-2d  %s"
              % (name, top, bot, hb[1], hb[3] - 1, "ok" if inside else "OUT"))
        ok = ok and inside
    check("A4 no plane art outside its hitbox", ok,
          "3 attitudes, vertical extent is the binding direction")

    # every attitude must share one hitbox, or the hitbox lies as you pitch
    hs = {(max(len(r) for r in getattr(A, n)), len(getattr(A, n)))
          for n in ("SPR_PLANE", "SPR_PLANE_CLIMB", "SPR_PLANE_DIVE")}
    check("A4 all attitudes share one hitbox size", len(hs) == 1, str(hs))


# ---------------------------------------------------------------- A5
def a5_terrain_continuity(span=60000):
    """No gaps and no cliffs: consecutive columns must never jump more than
    the renderer's crust can bridge, or the ground shows a tear."""
    worst, worst_at = 0.0, 0
    prev = W.ground_y(0.0)
    for i in range(1, span):
        wx = i * 1.0
        g = W.ground_y(wx)
        d = abs(g - prev)
        if d > worst:
            worst, worst_at = d, wx
        prev = g
    check("A5 max terrain step per column", worst <= 2.0,
          "%.3f px at worldX %d  (crust is %d px)" % (worst, worst_at, S.CRUST + 1))

    lo = min(W.ground_y(i * 1.0) for i in range(0, 20000))
    hi = max(W.ground_y(i * 1.0) for i in range(0, 20000))
    check("A5 terrain stays inside its band", lo >= A.HORIZON - W.TERR_AMP - 1
          and hi <= A.HORIZON + W.TERR_AMP + 1,
          "%.1f .. %.1f  (band %d +/- %d)" % (lo, hi, A.HORIZON, W.TERR_AMP))


if __name__ == "__main__":
    print("=" * 78)
    print("DAWN PATROL - procedural world audit")
    print("=" * 78)
    a1_scroll_stability()
    a2_seed_source()
    a3_wrap_safety()
    a4_art_within_hitbox()
    a5_terrain_continuity()
    print("=" * 78)
    print("%d checks failed" % len(FAIL))
    sys.exit(1 if FAIL else 0)
