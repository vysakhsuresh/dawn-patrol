# Dawn Patrol

A WW1-era aerial war game in 1-bit pixel art. Side view, portrait, one screen,
no menus. The battlefield below is alive; you are one aircraft passing over it.

**Status: playable.** Core loop, anti-aircraft fire, bombs and guns, a
persistent front line, barrage balloons, searchlights, and a day/night cycle —
all compiled, audited and rendered from the shipped Kotlin.

## The game

One thumb, no menus.

| input | does |
|---|---|
| **hold left half** | climb |
| **let go** | glide down gently |
| **tap right half** | drop a bomb *and* open up with the guns |
| **hold right half** | keep the guns firing |
| **tap anywhere (crashed)** | scramble a new sortie |

The aircraft flies straight and level until your first touch — the sortie
starts when you're ready, not the instant the app opens. First launch shows a
briefing card with the controls and what everything is worth.

| target | points |
|---|---|
| depot | 280 |
| balloon | 170 |
| tank | 140 |
| searchlight | 130 |
| AA gun | 110 |

All multiplied by your greed multiplier, up to ×9.

**The tension is altitude.** Low is where the bombs land true, where the guns
reach, and where the multiplier climbs — and it is exactly where the flak is
accurate. High is safe and useless: above the batteries' reach nothing can
touch you, and nothing you do counts. The multiplier only ticks up while you
are low, so climbing to safety is what spends it. You choose your own greed.

**The AA guns solve a real intercept.** They fire time-fuzed shells at where
you *would* be if you held your line, and the shell bursts on that point. So
holding a steady line is what kills you; changing vertical velocity is what
saves you. Every shell is provably dodgeable — see A6 below.

**The line is the score.** Destroying a depot, gun, tank or balloon pushes the
front line your way and it stays pushed — wrecks keep burning on the next pass.
Flying past live targets lets the line creep back. It persists across sessions.

## Frames

`out/kt/contact.png` — dawn, a day run, dusk, a night pass caught in a
searchlight, and the crash card.

These are **not mockups**. `Fb`, `Sim` and `Renderer` contain no `android.*`,
so `tools/frames.sh` runs the shipped simulation through the shipped renderer
and writes its framebuffer straight to disk. If a frame looks wrong here, the
game looks wrong.

## Verifying it without a device

    tools/kotlinc-setup.sh     # one-time: fetches a standalone Kotlin compiler
    tools/audit.sh             # the fairness + regression proof
    tools/frames.sh            # render real frames to out/kt/

The audit compiles against the same classes that go in the APK. A result there
is a statement about the game, not about a model of it.

    A1  terrain + features stable over 24000 frames   0 drift, 0 mutations
    A2  seeding independent of walk direction         6000/6000 camera positions
    A3  cell() correct across worldX = 0              vs naive toInt(), which fails 375/400
    A4  no plane art outside its hitbox               all 3 attitudes, box == drawn extent
    A5  terrain continuity                            max step 1.836 rows
    A6  every legal shell is dodgeable                648/648 reachable states, worst margin 14.16 vs 8.70 needed
    A7  never 2 lethal bursts at once                 max concurrent 1
    A8  balloons clearable from any altitude          144/144, worst margin 22.3 rows
    A9  control response                              full reversal 0.34s, gentle 0.34s glide to terminal sink
    A10 soak, 40 sorties                              no NaN, nobody dies on the runway
    A11 warm-up really is quiet                       260 rows before the guns wake

## Architecture

    Art.kt       sprites, font, dither table, canvas constants
    Tune.kt      every number that decides how it FEELS, in one place
    World.kt     procedural world, seeded from absolute world position
    Sim.kt       the entire game, pure and deterministic
    Fb.kt        1-bit software framebuffer + drawing primitives
    Renderer.kt  draws a Sim into an Fb
    GameView.kt  Android: input, frame clock, one bitmap blit
    Sound.kt     synthesized audio, zero audio files
    MainActivity.kt

The scene is composed into a 100x175 1-bit buffer in pure Kotlin and blitted
once as a nearest-neighbour `Bitmap`. Drawing ~17k scaled `drawRect` calls
instead leaves sub-pixel seams at most scale factors; a bitmap blit cannot, and
it is what lets the offline tools render identical pixels.

Ported forward from Cat on a Fence: fixed 400x700 logical canvas with
`scale = min(w/LW, h/LH)` and centring offsets; `PX = 4`; sprites as
`Array<String>` of `X`/`.`; Choreographer loop with `dt = elapsed / 16_666_667f`
clamped to 0.25..2.5; pause on window **focus** loss, not just `onPause`; no
image assets, no audio assets, no XML layouts, no engine.

## The value system

A 50% dither at 100x175 is a checkerboard, and a screen of checkerboard has no
hierarchy. So dither is rationed:

| layer | treatment |
|---|---|
| sky | clean paper, at most a faint **noise** stipple |
| far field | tall hazed objects — distance is haze, never a grey landmass |
| earth | SOLID ink, one unbroken silhouette |
| features | solid ink, 1px paper halo so they separate from it |
| smoke / flak / beams | the only mid-tones on screen, so they pop |

Depth between overlapping silhouettes is a 1–2px paper gap punched along the
nearer layer's crest, never a dither value.

Ordered (Bayer) dither at very low density lights the same cells of every 8x8
tile, which reads as a regular dotted lattice — wallpaper, not atmosphere. The
sky gradient therefore uses hashed **noise** dither; the ordered table is still
the right tool for smoke, flak and beams, where structure reads as texture.

At night the structure inverts: the earth becomes the paper, features are pale
outlines, and an aircraft caught in a beam is a **hole** punched in the light.
Colours crossfade between five keyframes; the structural flip happens at the
deep-dusk keyframe, where both sides are near-black, so nothing visibly jumps.

## Bugs we already paid for

1. **Seed from absolute world position.** `World.cell()` uses floor division
   and `frac()` takes the cell index the caller already has, so index and
   offset come from the same `floor()`. Audit A1/A2.
2. **Art must not lie about physics.** The hitbox is the exact drawn extent —
   no forgiving shrink, which is the same lie wearing a hat. Audit A4.
3. **Prove fairness numerically.** A6/A7/A8 are that proof, run against
   shipped code.
4. **Unary minus binds tighter than `%`.** No `%` on anything that can be
   negative. `World.hash32` uses `UInt` so its shifts stay logical — a plain
   Kotlin `shr` sign-extends and silently diverges. Audit A3.
5. Binary files don't go through the GitHub MCP tools — this repo is pushed
   from the local clone.
6. **Adaptive icons:** VectorDrawable only (`<path>`/`<group>`/`<clip-path>`,
   no `<circle>`), content inside the 66% safe circle, verified under circle,
   squircle and square masks. `mock/icon_masks.py`, `out/icon_masks.png`.

## About `mock/`

It now holds only the launcher-icon tooling, and that reads sprites straight
out of `Art.kt` via `mock/ktparse.py`.

The Python renderer, world generator and audit that used to live here have been
**deleted**. They were a second implementation of things the Kotlin now does,
and a second implementation is precisely the thing this project keeps getting
burned by — it agrees with the game right up until it quietly doesn't. One
source of truth, and the tools run *it*.
