# Dawn Patrol

A WW1-era aerial war game in 1-bit pixel art. Side view, portrait, one screen,
no menus. The battlefield below is alive; you are one aircraft passing over it.

**Status: art preview only. No Kotlin yet — awaiting sign-off on the look.**

## The look, in four frames

    out/frame1.png   dawn take-off / start screen
    out/frame2.png   low strafing run, flak up, multiplier up
    out/frame3.png   night pass, searchlight locked
    out/frame4.png   the crash
    out/contact.png  all four side by side
    out/sheet.png    every sprite with its hitbox

## How the art is made (and why)

Nothing is hand-guessed. `mock/` is a Python/PIL renderer that produces full
game frames from the same sprite grids and constants the game will ship, so the
preview and the game cannot disagree.

    mock/artdata.py   sprite grids + tuning constants (source of truth today)
    mock/ktparse.py   once GameView.kt exists, it is parsed with regex and
                      OVERRIDES artdata - the .kt becomes the source of truth
                      and the preview can never drift from shipped code
    mock/render.py    1-bit framebuffer, 8x8 ordered dither, 3x5 pixel font
    mock/world.py     procedural world, seeded from ABSOLUTE world position
    mock/scene.py     layer compositor (sky / far field / earth / atmosphere)
    mock/frames.py    the four frames
    mock/audit.py     numeric regression audit - run this before shipping
    mock/sheet.py     sprite contact sheet
    mock/zoom.py      blow up any sprite by name
    mock/shear.py     design tool: derive pitch attitudes from the level sprite

Run:

    cd mock
    python3 frames.py && python3 sheet.py && python3 audit.py

(Needs Pillow. On this box that is `python3.12`.)

## The value system

A 50% dither at 100x175 is a checkerboard, and a screen full of checkerboard has
no hierarchy. So dither is rationed:

| layer       | treatment                                        |
|-------------|--------------------------------------------------|
| sky         | clean paper, at most a faint stipple gradient     |
| far field   | tall objects (poplars, a ruined spire) at ~60% density — distance reads as haze, not as a low grey landmass |
| earth       | SOLID ink, one unbroken silhouette                |
| features    | solid ink, 1px paper halo so they separate        |
| smoke / flak / searchlight | the only mid-tones on screen — so they pop |

Depth between overlapping silhouettes comes from a 1–2px paper gap punched along
the nearer layer's crest, never from a dither value.

At night the structure inverts: the earth is the paper (black), features are
pale outlines with a faint interior, and an aircraft caught in a searchlight is
drawn as a *hole* punched in a bright pool of light.

## Ported forward from Cat on a Fence

- Fixed logical canvas 400x700, `scale = min(w/LW, h/LH)` with centring offsets.
- `PX = 4` logical units per sprite pixel → a 100 x 175 pixel grid.
- Sprites are `Array<String>` of `X`/`.`, run-length batched into `drawRect`.
- Choreographer loop, `dt = elapsed / 16_666_667f`, clamped to 0.25..2.5.
- No image assets, no audio assets, no XML layouts, no engine.

## Bugs we already paid for

1. **Seed from absolute world position.** `world.cell()` uses floor division and
   `world.frac()` takes the cell index the caller already has, so the index and
   the offset come from the same `floor()` and can never disagree. Audit A1/A2.
2. **Art must not lie about physics.** Every plane attitude fits one 22x9 hitbox
   and no pixel escapes it. Audit A4.
3. **Prove fairness numerically.** `audit.py` is where that lives. Flyability and
   AA dodge-window proofs land there when the flight model does.
4. **Unary minus binds tighter than `%`.** No `%` on anything that can be
   negative; `cell()` uses `floor()`. Audit A3 also shows what naive `int()`
   truncation would have done.
5. Binary files do not go through the GitHub MCP tools — this repo is pushed
   from the local clone.
