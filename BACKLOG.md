# Dawn Patrol — upgrade backlog

> **STATUS: ALL 24 ITEMS BUILT.** R1 R2 R3 · T1 T2 T3 T4 ·
> F1 F2 F3 F4 F5 · V1 V2 V3 V4 V5 · S1 S2 S3 S4 S5 · A1 A2 A3 A4 A5.
> Audit: 279 checks, 0 failures.
>
> S5 (the squadron) is built in the safe form its own warning demands: a
> story layer, never a stake. Nothing earned dies with a pilot - rank,
> the front, orders, medals and the logbook all belong to the squadron.
> A40 proves it.
>
> A1 is a tap-to-change row on the ready screen rather than a separate
> settings panel; everything it needed to carry is there.

---

## 0. Rules every item inherits

An item that breaks one of these is wrong even if it works.

1. **No image or audio assets.** Sprites are `Array<String>` of `'X'`/`'.'`
   in `Art.kt`; all sound is synthesised in `Sound.kt`. The launcher icon is
   the one exception and it is finished.
2. **Nothing that decides behaviour may touch `android.*`.** `Sim`,
   `Renderer`, `Fb`, `World`, `Art`, `Tune`, `Orders`, `Warmup` stay pure so
   `tools/Audit.kt` runs the shipped code. Only `GameView`, `Sound` and
   `MainActivity` may import `android.*`.
3. **Render and LOOK before shipping art or UI.** `tools/frames.sh` draws
   real frames through the real renderer; `tools/planelab.sh` is the bench
   for silhouettes. Never describe a visual you have not viewed.
4. **Every behavioural change gets an audit check**, in the style of A1–A25.
   A check that cannot fail is not a check: A15, A21 and A22 each prove the
   old behaviour was broken. Currently 92 checks, 0 failures.
5. **Measure, don't guess, when setting a number.** `FRONT_PER_LINE`,
   `GRAZE_R` and `PHASE_LEN` were all set from simulation output. Put the
   measurement in the comment.
6. **Canvas is 100x175, 1-bit, 3x5 font.** A missing glyph renders as a
   silent gap — A18 catches it, but check `Art.FONT` first.
7. Add any new pure file to the list in `tools/build.sh`.

**Do not rebuild what exists:** greed multiplier, grazes, kill chains,
standing orders + ranks, 3 lives, day/night cycle, pause + count-in,
difficulty ramp, enemy scouts, warm-up, the advancing front, sector
capture, the icon.

**The stated principle, which must survive every item below:** the pull is
*"I was two depots away"*, never *"I will lose my streak"*. No energy
timers, no punishment for not playing, no pay-to-win, nothing online.

---

## Tier R — the run arc (do this first, it is the upgrade)

The game has no shape to a run and nothing to lose. R1–R3 are **one
feature**; building them separately wastes most of their value.

### R1. The sortie needs a return leg
A sortie currently ends only when three lives are gone — no climax, no
arc. Make it **TAKE OFF → reach the objective → RETURN TO BASE**. Crossing
the objective flips the scroll direction; you fly home over ground you
already stripped, damaged, with everything you earned at risk. **Score is
banked only if you land.**

Loss aversion beats accumulation. "Did I make it back?" is a story every
run produces. Reuses existing terrain flown in reverse.

Keep an **ENDLESS** mode alongside for pure score chasers.

*Audit:* RTB always reachable from the objective on worst-case damage and
fuel; landing possible at any front position. Extend A6 — the dodge-window
proof must hold flying right-to-left too.
*Effort:* large. *Touches:* `Sim.kt` (scroll direction, landing state),
`Renderer.kt`, `Tune.kt`.

### R2. Located damage instead of binary lives
A hit damages a **part**, each with a distinct readable consequence:

| Part | Consequence |
|---|---|
| ENGINE | `SPEED_MAX` / `CLIMB_ACC` fall; smoke trail lengthens |
| FUEL | leak — puts a clock on the return leg |
| ELEVATOR | control lag between input and `CLIMB_ACC` applying |
| WING | constant `vy` bias you must hold against |
| GUNS | `GUN_PERIOD` doubles, or guns dead |

Three lives become three hits' worth of parts. **Make smoke-trail length
the damage readout** so it is legible without looking at the HUD.

"Got home on a dead engine" is a sentence a player says out loud. "I had
one life left" is not.

*Audit:* every damage **combination** still permits RTB and a landing. A
damage state you cannot survive is just a delayed death.
*Effort:* medium.

### R3. Make the multiplier spendable
`mult` is currently passive — it rises while low, decays while high, and
that is all (`Sim.kt:545`). Let the player **cash it in mid-flight**: spend
accumulated greed to patch one damaged part or rearm bombs. Spending
resets `mult` to 1.

This converts a meter into a dilemma re-offered every few seconds — push
for x9, or cash out and survive. Strongest "one more go" mechanic available
for the least code.

*Audit:* spending can never soft-lock — there is always an affordable path
to RTB.
*Effort:* small. **Best value-to-effort in this document.**

---

## Tier T — protect what exists

Not glamorous. T2 in particular is the only reason the rest is safe to
build.

### T1. Run the audit in CI
92 checks and nothing runs them automatically. GitHub Actions: run
`tools/kotlinc-setup.sh` then `tools/audit.sh` on push and PR, fail on
non-zero exit, cache `~/.dawnpatrol-kt`.
*Effort:* small.

### T2. Build the APK in CI
`GameView.kt` + `Sound.kt` + `MainActivity.kt` — **508 lines that no
automated step ever compiles.** The off-device toolchain skips them
deliberately because they import `android.*`. They are only checked when a
human presses Build in Android Studio. Every Android-layer change between
builds is unverified.

Add `./gradlew assembleDebug` with `android-actions/setup-android`; upload
the APK as an artifact so a build can be installed without opening the IDE.
*Effort:* small. **Highest risk reduction per line here.**

### T3. Version the save file
`Sim.load()` reads bare keys with no schema version. R1–R3 and Tier S all
add persisted state; without this, a stale value reads as a valid one.
Write `save_version`; on mismatch migrate explicitly or reset only the
affected keys — never `best`, `bestScore` or `front`.
*Audit:* load an old blob, assert nothing is nonsense; an unknown future
version must not crash.
*Effort:* small. **Do before any item that persists new state.**

### T4. Backup rules
`allowBackup="true"` with no rules means Android may restore a save from a
different device or build. Add `dataExtractionRules` / `fullBackupContent`.
*Effort:* tiny.

---

## Tier F — feel (cheap, high leverage, do even if Tier R slips)

### F1. Haptics
`grep -rn "Vibrat\|HapticFeedback" app/src/` returns **nothing**. Cat on a
Fence already has this — **port it**: `cat_on_a_fence`
`GameView.kt:355–360` (VibratorManager/Vibrator with an API fallback) and
`:537–538` (`hasVibrator()` guard, `VibrationEffect.createOneShot`).

Short tick on graze, heavier on damage, heaviest on death, double on sector
capture. Respect the existing mute toggle or give haptics their own.
*Effort:* small. **Best effort-to-impact ratio here.**

### F2. Hit-stop
Freeze the sim 2–3 ticks on a kill and on taking damage, scaled by target
value. The cheapest juice that exists.
*Audit:* hit-stop must never drop a physics tick or desync `dt`.
*Effort:* small.

### F3. Death must be legible
`crashCause` and `SO_CLOSE` exist — go further. Freeze-frame the moment of
death, highlight the fatal shell or cable, and **always print a distance**:
`ENGINE FIRE — 400 ROWS FROM BASE`. That number is what makes a thumb tap
again.
*Effort:* small.

### F4. Audio is the thinnest system in the build
`Sound.kt` has gun / bomb / thud / hit plus engine pitch. Add, all
synthesised: flak burst with **distinct near vs far timbre** (the near one
is the graze cue), a whoosh on a graze, Doppler on a passing scout, a sting
on sector capture, and the **engine note dropping when the engine is hit**
— audio as damage readout, pairing with R2.
*Effort:* small–medium.

### F5. Screen shake
A few frames of camera offset on damage taken.
*Audit:* shake must never move the plane's drawn position relative to its
hitbox — that is lesson #2 in a new hat.
*Effort:* small.

---

## Tier V — variety

### V1. Authored set pieces, sequenced procedurally
`World.SLOT_TABLE` is 16 entries shuffled — texture, not encounters, so
every sortie has the same rhythm. Hand-author ~12 chunks and let the
generator choose and tune the **sequence**: flak gauntlet, zeppelin, low
pass under a bridge span, searchlight alley, balloon line, ace intercept,
artillery barrage crossing the screen, a friendly bomber to escort, a
railway with a moving train.

Pure procedural goes samey; pure handmade is finite. Twelve chunks shuffled
gives hundreds of distinct sorties.
*Audit:* extend A1/A5 across chunk boundaries; every chunk survivable from
any entry altitude.
*Effort:* large.

### V2. More than one aircraft, unlocked by rank
You earn eight ranks and fly the same machine at all of them — the reward
for promotion is a word. Not stat bumps, **different verbs**:

- **SCOUT** — nimble, fast climb, 2 bombs (dodge-heavy)
- **BOMBER** — heavy, poor climb, 12 bombs (commit to a line)
- **TRIPLANE** — slow forward, superb climb (vertical game)

`tools/PlaneLab.kt` already holds `TRIPLANE2` and `PARASOL2` — drawn,
fitted to the hitbox, compared against the current plane, never installed.
The art is done.
*Audit:* re-run A4, A6, A8, A9 and A14 **per aircraft**. A poor climber
must still clear every balloon and dodge every legal shell.
*Effort:* medium. **Multiplies replay value more than any content item,
because it makes the player re-learn a game they already know.**

### V3. A named recurring rival
Scouts are anonymous. Add **one** named enemy ace persisted in `Store`:
own silhouette, a tally of *your* downed machines painted on it, escalating
aggression each appearance. Kill them and a new ace inherits the grudge;
tally resets.

A personal grudge is the cheapest strong emotion in game design. Roughly
150 lines: persistent state plus a smarter tracker than `ENEMY_*`.
*Audit:* always escapable (extend A14) and always killable.
*Effort:* medium.

### V4. Weather, chosen per sortie and shown at the briefing
Clear / headwind / crosswind / fog / storm.
`headwind` lowers `SPEED_*` (longer sortie, more fuel) · `crosswind`
drifts bombs laterally · `fog` makes flak fire blind (huge `AIM_ERR`) but
hides targets · `storm` adds vertical gusts perturbing `vy`.

Free replay variety that changes **how** you fly, not just the numbers.
*Audit:* every weather state still permits a clearable sortie.
*Effort:* medium.

### V5. A stated objective per sortie
Orders are passive tallies of what you happened to do. Give the **sortie
itself** an objective, shown on the briefing: destroy the railhead /
photograph a sector (fly level and low over a span, no firing) / escort the
bomber home / night raid with no searchlight locks. Pairs with R1.
*Effort:* medium.

---

## Tier S — stories and self-competition

### S1. Ghost of your best run
A faint silhouette flying your best sortie's recorded path. One byte per 8
rows is plenty. Creates competition with yourself on every run, fully
offline.
*Effort:* medium.

### S2. Daily seeded sortie — with no timer
Seed the world from the **date**. Same sortie for everyone that day, local
best kept. This is *not* the thing the rules forbid: no streak, no
punishment, nothing lost by skipping a day. Pure upside, fully offline.
*Effort:* small.

### S3. Run history, lifetime stats, medals
Store the last ~20 sorties (score, rows, sectors, cause) plus lifetime
tallies, on a screen reachable from the ready state. One-off medals for
specific feats — a 6-chain, a sector without firing a gun, a sortie with no
damage, a balloon taken with the last bomb.
*Effort:* medium.

### S4. Name the sectors
"SECTOR 7" reads like a spreadsheet cell. Generate a name per sector from
`World.hash32` and a word list — VERDUN RIDGE, THE SALIENT, BAPAUME WOOD.
Deterministic, pure, free.
*Effort:* tiny. **Punches far above its cost.**

### S5. Named pilots with permadeath and a memorial — *decide before building*
A squadron roster in `Store`: generated name, sortie count, a trait earned
by surviving N sorties. GAME OVER kills that pilot permanently; the name
goes on a scrollable memorial.

**Read this before starting it.** The argument for it is "losing a pilot on
their 11th sortie hurts, and hurt is what brings people back". That is one
word away from the pattern this project explicitly rules out. Attachment is
real and good; *punishment for having played* is the thing `Orders.kt`
promises not to do. If it is built, the next pilot must already be waiting
one tap away and nothing earned may be lost with the dead one — it is a
**story layer, not a stake**.
*Effort:* medium. **Do not build before R1–R3.** Meta-progression wrapped
around a thin core is just a menu.

---

## Tier A — reach and polish

### A1. Settings screen
Mute is the only option and it is a HUD button. Add a panel from the ready
state. **Left/right control swap matters most** — the game is currently
unplayable as designed for a left-handed player who wants climb under their
dominant thumb.
*Effort:* small.

### A2. Teach by flying, not by reading
The briefing card is **11 lines of text on a 100x175 screen** — a wall.
*Resolution of a genuine conflict between the two source lists:* do **not**
build a gated tutorial that takes control away; the three-state start gate
already solves the panic problem. Instead, split the briefing into
contextual prompts during a first sortie with no flak — "HOLD LEFT" as the
ground approaches, "TAP RIGHT" as the first depot scrolls on — and keep the
full card available from the settings panel.
*Effort:* medium.

### A3. Tablets, foldables, very tall phones
`onSizeChanged` does one uniform scale with black letterbox bars. Extend
sky and ground into the bars instead.
*Audit:* playfield geometry unchanged at every aspect ratio — extra area is
decoration only, never advantage.
*Effort:* medium.

### A4. Reduced motion and larger text
A flag that stills the dither and disables shake; a flag that doubles HUD
text scale.
*Effort:* small.

### A5. Play Store readiness
Signed release config, `targetSdk` check, a privacy statement (the game
collects nothing — say so), screenshots generated from `tools/frames.sh`.
*Effort:* small.

---

## Deliberately not recommended

- **Anything online** — leaderboards, accounts, ads, IAP. Each adds a
  permission, a privacy policy and a failure mode; none makes the game
  better. The brief is the best *offline* game.
- **Energy timers, daily streaks, login rewards.** See S2 for how to get
  the good part of a daily without the bad part.
- **An engine or a physics library.** One custom `View`, one `Canvas`, no
  dependencies. That constraint is why the audit can exist at all.

---

## Order of work

```
R1 + R2 + R3   together — one feature, and it is the upgrade
T2             build the APK in CI (before the codebase grows)
T1             audit in CI
F1 F2 F3 F4    a weekend; the game feels twice as good
T3             version the save, before any more persisted state
S4             name the sectors (one afternoon, changes the tone)
V2             aircraft by rank — makes the existing ladder mean something
V3 V1          the rival, then set pieces
V4 V5          weather, sortie objectives
S1 S2 S3       ghost, daily seed, stats and medals
A1 A2 A3 A4 A5 reach and store
S5             only if the warning in it is resolved first
```

**Every item needs its own audit entry in `tools/Audit.kt`, run against the
shipped classes, in the style of A1–A25. A change that cannot be proven
fair does not ship.**
