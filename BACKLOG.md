# Dawn Patrol — upgrade backlog

Written against `f37cd14`. Each item is self-contained: hand a CLI session
one ID and it has everything it needs.

## Read this first (the rules every item inherits)

These are the house rules this codebase is built on. An item that breaks one
of them is wrong even if it works.

1. **No image or audio assets.** Sprites are `Array<String>` of `'X'`/`'.'`
   in `Art.kt`; all sound is synthesised in `Sound.kt`. Never add a PNG, WAV
   or OGG to `res/` (the launcher icon is the one exception and it is done).
2. **Nothing that decides behaviour may touch `android.*`.** `Sim`,
   `Renderer`, `Fb`, `World`, `Art`, `Tune`, `Orders`, `Warmup` are pure
   Kotlin so `tools/Audit.kt` can run the shipped code. Only `GameView`,
   `Sound` and `MainActivity` may import `android.*`.
3. **Render and LOOK before you ship art or UI.** `tools/frames.sh` draws
   real frames through the real renderer. `tools/planelab.sh` is the bench
   for aeroplane silhouettes. Never describe a visual you have not viewed.
4. **Every behavioural change gets an audit check.** `tools/audit.sh`,
   currently 92 checks, 0 failures. A check that cannot fail is not a check:
   prove the old behaviour was broken (A15, A21 and A22 all do this).
5. **Measure, do not guess, when setting a number.** `FRONT_PER_LINE`,
   `GRAZE_R` and `PHASE_LEN` were all set from simulation output. Put the
   measurement in the comment.
6. **The canvas is 100x175, 1-bit.** Text is a 3x5 font. If a glyph is
   missing it renders as a silent gap — A18 catches it, but check the font
   in `Art.FONT` before using a new character.
7. Add any new pure file to the list in `tools/build.sh`.

---

## Tier 0 — protect what exists

### T0.1 Continuous integration for the audit
**Why:** 92 checks exist and nothing runs them automatically. One careless
commit silently un-proves the lot.
**Do:** a GitHub Actions workflow that runs `tools/kotlinc-setup.sh` then
`tools/audit.sh` on push and PR, failing the build on a non-zero exit.
Cache `~/.dawnpatrol-kt`.
**Effort:** small.

### T0.2 Actually build the APK in CI
**Why:** `GameView.kt`, `Sound.kt` and `MainActivity.kt` (508 lines) are
**never compiled by any automated step** — the off-device toolchain skips
them deliberately because they import `android.*`. They only get compiled
when you press Build in Android Studio. Every Android-layer change between
your builds is unverified.
**Do:** add `./gradlew assembleDebug` to the CI workflow with
`android-actions/setup-android`. Upload the APK as an artifact so you can
install a build without opening Android Studio.
**Effort:** small. **Highest risk-reduction per line in this document.**

### T0.3 Version the save file
**Why:** `Sim.load()` reads bare keys with no schema version. Any future
change to what is stored can read a stale value as a valid one — e.g. a
`rank` from an older order ladder silently producing impossible targets.
**Do:** write `save_version`; on mismatch, migrate explicitly or reset the
affected keys only (never wipe `best`, `bestScore` or `front`).
**Audit:** load a v1 blob into the current build and assert nothing is
nonsense; assert an unknown future version does not crash.
**Effort:** small.

### T0.4 Backup rules
**Why:** `allowBackup="true"` with no rules means Android may restore a save
from a different device or build. Combined with T0.3 that is a corrupt-save
path.
**Do:** add `android:dataExtractionRules` / `fullBackupContent` that either
excludes the prefs or includes them deliberately once T0.3 lands.
**Effort:** tiny.

---

## Tier 1 — combat depth (the real gap)

The last round added meta-progression. The moment-to-moment game is still
**two verbs and one enemy type**: hold left, hold right, occasionally bomb,
versus one scout that drifts toward your altitude. For "best fighter jet
game" this tier matters more than everything below it combined.

### T1.1 More than one aeroplane, unlocked by rank
**Why:** you earn eight ranks and fly the identical machine at all of them.
The reward for promotion is a word.
**Do:** `tools/PlaneLab.kt` already holds drawn, fitted candidates —
`TRIPLANE2` and `PARASOL2` — that were rendered, compared and never
installed. Make the airframe a choice: each has different `CLIMB_ACC`,
`VY_MAX_*`, `SPEED_*`, `BOMB_MAX` and hitbox. Unlock at ranks 2/4/6.
**Audit:** every airframe must independently pass A4 (art inside hitbox),
A6 (every shell dodgeable), A8 (balloons clearable) and A9 (reversal under
0.5s). A fast airframe that cannot dodge is a trap, not a reward.
**Effort:** medium. **Best value in this document.**

### T1.2 A second enemy aircraft type
**Why:** one `Enemy` class, one behaviour, one sprite mirrored — so a
head-on pass is two identical smudges (the `VS` column in
`out/kt/planes.png` is that problem, drawn).
**Do:** add a two-seater **bomber** that flies straight and level, ignores
you, and is worth a lot — a target of opportunity rather than a duel; and
make the existing scout visibly a different airframe from the player's.
**Audit:** extend A13/A14 to each type; prove each is both reachable and
avoidable.
**Effort:** medium.

### T1.3 Boss: the enemy ace
**Why:** nothing in the game is an event. Sectors and promotions are
systems; an arcade game needs a fight you remember.
**Do:** at a sector boundary, or every N sectors, spawn a single ace that
actually duels — leads its shots, matches your turns, takes several hits,
and has a tell before it fires. Beating it should take a sector outright.
**Audit:** prove it is beatable from any starting altitude with the control
authority available, and that its shots are dodgeable on the A6 model.
**Effort:** large. **Highest ceiling for "memorable".**

### T1.4 A third weapon
**Why:** guns (flat, short, sag) and bombs (ballistic, ground only) leave an
obvious hole: nothing for a target above or ahead of you at range.
**Do:** rockets — limited, fast, flat, no sag, good against balloons and
scouts, useless against dug-in ground targets. Bind to a right-side
swipe-up or a double-tap so no new on-screen button is needed.
**Audit:** prove rockets do not trivialise balloons (A8's clearance
guarantee must still have a reason to exist).
**Effort:** medium.

### T1.5 Damage states instead of binary lives
**Why:** a hit is currently all-or-nothing. There is no "limping home",
which is the single most characteristic feeling of the genre.
**Do:** a hit that is not fatal damages a system: engine (lower
`SPEED_MAX`), controls (lower `CLIMB_ACC`), or a smoking trail that marks
you for the flak. Repaired on respawn.
**Audit:** prove a fully damaged aeroplane can still clear a balloon and
still dodge a shell — a damage state you cannot survive is just a delayed
death.
**Effort:** medium.

### T1.6 Weather and wind
**Why:** `World.kt` has terrain and features and nothing atmospheric.
`grep` for wind in `Sim.kt` returns nothing. Weather is free variety: it
changes how the same ground plays.
**Do:** per-sector wind (a constant added to `camX` drift, affecting bomb
drift most), and low cloud that hides the ground until you descend into it.
**Audit:** prove the worst wind still leaves every corridor flyable and
every bomb able to reach a depot.
**Effort:** medium.

---

## Tier 2 — feel

### T2.1 Haptics
**Why:** `grep -rn "Vibrat\|HapticFeedback" app/src/` returns **nothing**.
There is no touch feedback at all. On a phone this is the cheapest
perceived-quality win available.
**Do:** `VibrationEffect` in `GameView` only. Short tick on gun fire,
sharper on a hit taken, a double on a sector capture and on a promotion.
Respect the existing mute toggle, or give haptics their own setting.
**Effort:** small. **Best effort-to-impact ratio in this document.**

### T2.2 Screen shake and hit-stop
**Why:** a lethal burst and a scored graze currently look the same in the
frame buffer.
**Do:** a few frames of camera offset on a hit taken, and 2–3 frames of
freeze on a kill. Both belong in `Renderer`/`Sim` as pure state so they are
auditable.
**Audit:** prove shake never moves the plane's drawn position relative to
its hitbox (that is lesson #2 wearing a new hat), and that hit-stop never
drops a physics tick.
**Effort:** small.

### T2.3 More synthesised sound
**Why:** `Sound.kt` has four effects and an engine drone. No sound for a
graze, a chain, a capture, a promotion, the ground rushing up.
**Do:** extend the existing `clip()` synthesis. Add a rising arpeggio for a
chain and a two-note fanfare for a promotion. Still zero audio files.
**Effort:** small.

### T2.4 Stall and spin
**Why:** climbing has no cost but gravity. Real fighters run out of energy.
**Do:** sustained full climb bleeds airspeed; below a threshold the nose
drops and control authority halves until speed recovers. Makes the altitude
bargain two-sided instead of "high is simply safer".
**Audit:** prove a stall is always recoverable above a stated altitude, and
that it cannot be triggered accidentally in the first sortie.
**Effort:** medium. Changes core feel — prototype and fly it before keeping.

---

## Tier 3 — retention

### T3.1 A run history and a stats screen
**Why:** `best` and `bestScore` are the only history kept. There is nowhere
to see a campaign.
**Do:** store the last ~20 sorties (score, rows, sectors, cause) and show
them on a screen reachable from the ready state. Lifetime tallies too —
total depots, total sectors, total rows.
**Effort:** medium.

### T3.2 A ghost of your best run
**Why:** the end card says "180 ROWS SHORT" but you never see the ghost.
**Do:** record the best run's altitude trace (one byte per 8 rows is
plenty), draw it as a faint dotted line. Chasing a line beats chasing a
number.
**Effort:** medium.

### T3.3 Medals
**Why:** ranks are the only award and they are purely cumulative.
**Do:** one-off medals for specific feats — a 6 chain, a sector without
firing a gun, a sortie with no damage, a balloon taken with the last bomb.
Shown on the stats screen.
**Effort:** small, on top of T3.1.

### T3.4 Name the sectors
**Why:** "SECTOR 7" is a number. The front is the game's best idea and it
reads like a spreadsheet cell.
**Do:** generate a name per sector from `World.hash32` and a word list
(VERDUN RIDGE, THE SALIENT, BAPAUME WOOD). Pure, deterministic, free.
**Effort:** tiny. **Punches far above its cost.**

---

## Tier 4 — reach and polish

### T4.1 A settings screen
**Why:** mute is the only option and it is a HUD button. No haptics
toggle, no left-handed mirror, no reduced motion.
**Do:** a simple panel from the ready state. Left/right control swap
matters most — the game is currently unplayable as designed for a
left-handed player who wants the climb under their dominant thumb.
**Effort:** small.

### T4.2 Tablets, foldables and very tall phones
**Why:** `onSizeChanged` does one uniform scale with letterbox bars. On a
tablet that is a small picture in a large black frame.
**Do:** keep the fixed logical canvas, but extend the sky and ground to
fill the bars rather than painting them black.
**Audit:** prove the playfield geometry is unchanged at every aspect ratio
— the extra area must be decoration only, never advantage.
**Effort:** medium.

### T4.3 Reduced-motion and bigger-text options
**Why:** the dither, shake and flashing prompts are a lot; the 3x5 font is
small at 48px-equivalent.
**Do:** a reduced-motion flag that stills the dither and disables shake; a
large-text flag that doubles HUD text scale.
**Effort:** small.

### T4.4 Google Play readiness
**Why:** the 512 icon exists; nothing else does.
**Do:** signed release build config, `targetSdk` bump check, a privacy
statement (the game collects nothing — say so), store screenshots generated
from `tools/frames.sh` rather than hand-captured.
**Effort:** small.

---

## Deliberately not recommended

- **Online anything** — leaderboards, accounts, ads, IAP. The brief is the
  best *offline* game; every one of these adds a permission, a privacy
  policy and a failure mode, and none of them makes the game better.
- **Daily streaks or energy timers.** The pull should be "I was two depots
  away", never "I will lose my streak". `Orders.kt` says this in a comment;
  keep it true.
- **A tutorial sequence.** The briefing card plus the three-state start gate
  already do this without taking control away.
- **An engine or a physics library.** One custom `View`, one `Canvas`, no
  dependencies. That constraint is why the audit can exist.

---

## Suggested order

If you want one sequence rather than a menu:

```
T0.2  build the APK in CI          (stop shipping unverified Android code)
T0.1  run the audit in CI
T2.1  haptics                      (small, transforms feel)
T3.4  name the sectors             (tiny, transforms tone)
T1.1  aircraft unlocked by rank    (makes the rank ladder mean something)
T2.2  screen shake and hit-stop
T1.2  a second enemy type
T0.3  version the save             (do before any more persisted state)
T3.1  run history and stats
T1.3  the enemy ace
```
