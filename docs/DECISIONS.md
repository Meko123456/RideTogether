# Decisions

Where the code deliberately differs from [`SPEC.md`](SPEC.md), and why.

The spec is a good one — it picks the right seams and it is honest about the hard part being
detection rather than maps. But a spec written before the code is a hypothesis, and a few of its
rules do not survive contact with a GPS trace. The rule here is that the spec stays as written
and this file records the divergence: a spec quietly contradicted by its own implementation is
worse than no spec, because the next person trusts it.

Each entry says what the spec asked for, what the code does, and what it would cost to go back.

---

## 1. Two modules, not eleven

**Spec §3.2** lists eleven Gradle modules (`:core:model`, `:core:geo`, `:core:alerts`,
`:core:realtime`, …).

**Code:** `:shared` (with `model/`, `room/`, `alerts/` packages) and `:androidApp`.

**Why:** eleven modules buy enforced boundaries and pay for them in build files, publishing
config and cross-module refactor friction. While the seams are still moving, the packages give
the same clarity at none of the cost — and the boundary that actually matters (the alert engine
must not depend on Android, maps or Firebase) is enforced by `:shared` being a Kotlin
Multiplatform module: an Android import there does not compile for the iOS targets. The
constraint is structural, not a convention.

**Cost to reverse:** low, and it is a mechanical move once the package boundaries have stopped
changing. Worth doing when a second app target (Wear, iOS) starts pulling on a subset.

---

## 2. The separation rule is not implemented as written — this is the important one

**Spec §2.3.2:** raise a fallback alert when `gap > 1.5 km` **AND** the gap has been increasing
for 60 s.

**Code:** that rule fires at every long red light, so it is not what the engine does.

**Why:** at 60 km/h a group opens **a kilometre a minute**. A rider caught at a 100-second light
is 1.7 km back with a gap that rose monotonically the entire time — the spec's two conditions are
both satisfied by an entirely normal traffic light. And the deeper problem is not a tuning one:
**a red light and a broken drive chain are indistinguishable from a position trace.** Both show a
stationary rider while the group pulls away. No threshold separates them, because the signal that
would separate them is not in the data.

So the engine stops trying to tell them apart and separates the *responses* by cost instead:

| | Trigger | Who it disturbs |
|---|---|---|
| `FallingBehind` | gap over threshold, growing continuously for the grace period | that rider only — one tap to answer |
| `PossibleIncident` | that prompt unanswered past the timeout, **plus** continuously stationary for longer than any plausible light cycle, **plus** a current GPS fix | the whole group, audibly |

The cheap question is cheap, so a false positive at a light costs one glance. The loud alarm is
expensive, so it needs evidence a light cannot produce. And the light case heals itself: once the
rider is moving again *and* no longer losing ground, the question is withdrawn without anyone
answering it.

Two supporting rules exist for the same reason:

- **Hysteresis.** The alert clears at 75 % of the threshold, not at the threshold, so a gap
  hovering around 1.5 km does not alternate between alerting and clearing.
- **The growth clock is unbroken.** `gapRisingSince` resets the moment a gap stops growing, so
  "rising for 60 s" means *continuously*, up to now. A rider who levels off starts over.

**Cost to reverse:** none, but don't. The KDoc on `AlertEngine` carries the full argument, and
`AlertEngineTest` opens with the red-light trace that the literal rule fails.

---

## 3. Two alert flags, not one

**Spec §2.3.6:** suppress fallback alerts while the ride is paused.

**Code:** `RoomState.separationAlertsActive` (RIDING only) and `RoomState.safetyAlertsActive`
(anywhere location is shared, so PAUSED too).

**Why:** implemented as one flag, pausing for fuel also silenced crash detection and any
unanswered "are you all right?" — the priority exactly inverted. A group that stops for petrol
has not stopped caring, and a rider can come off on the way to the pumps. Pausing should quieten
the nagging, not the safety net. A test asserts `safetyAlertsActive` stays exactly as wide as
`sharesLocation`, since the honest rule is "if we have your position we can watch out for you".

A related bug fell out of the same split: gap growth accumulated *during* a pause used to count,
so resuming would immediately alert on the rider still queueing for the pump. The growth clock now
only runs while separation is actually being watched.

---

## 4. "Sweep" is a flag on `Member`, not a `Role`

**Spec §3.3** models the roles as `LEADER | CO_LEADER | RIDER | SWEEP`.

**Code:** `Role { LEADER, CO_LEADER, RIDER }` plus `Member.isSweep: Boolean`.

**Why:** sweep is a *duty*, not a permission. It changes what the alert engine believes about
being last (that it is their job, so they are never flagged for it), not what the rider is
allowed to do. As a role value it makes a legitimate combination — a co-leader who is also
sweeping — unrepresentable, and forces every permission check to remember that `SWEEP` implies
`RIDER`. Orthogonal facts belong in orthogonal fields.

---

## 5. `RiderStatus.PAUSED` deleted

**Spec §3.3** lists `PAUSED` among the per-rider statuses.

**Code:** `RiderStatus { ACTIVE, FALLING_BEHIND, STOPPED, SIGNAL_LOST, POSSIBLE_INCIDENT }`.

**Why:** it duplicated `RoomState.PAUSED`. A pause is a property of the *ride*, not of one rider —
one rider cannot be paused while the others ride. Two sources of truth for one fact is a
divergence waiting to happen, and the one that lives on the room is the one the state machine
already guards.

---

## 6. Join codes keep the full alphabet and normalise the input instead

**Spec §2.1:** a 6-character code, avoiding look-alike characters.

**Code:** full Crockford Base32 (`0-9A-Z` minus `I`, `L`, `O`, `U`) with input normalisation —
typed `O` → `0`, `I`/`L` → `1`, separators and spaces dropped.

**Why:** shrinking the alphabet to dodge confusion gives up entropy *and still rejects the typo*.
Normalising keeps the full 32⁶ ≈ 1.07 billion keyspace and forgives the mistake. Because the
excluded letters are never valid symbols, the mapping can only ever recover the intended code — it
can never silently resolve to a *different* room, which is the failure that would matter.

Length is not the security boundary and the KDoc says so: 30 bits is enumerable by a script, so a
short code protects a ride only alongside server-side rate limiting on code resolution. Codes also
die with their room after 24 h, which bounds the window.

---

## 7. Expiry is computed, and it outranks permission

**Spec:** rooms live 24 h.

**Code:** `RoomStateMachine.isExpired(state, createdAt, endedAt, now)` — derived on every
decision, never stored — and the expiry check runs *before* the permission check.

**Why:** a stored `isExpired` flag needs a writer, and the writer is the thing that will be
missing when a client wakes up after a day in a tank bag. Deriving it means an offline client
reaches the same conclusion as the server with no round trip.

The ordering is deliberate: a leader acting on a dead room should be told the room is dead, not
that they lack permission. Rejection reasons are UI copy — the wrong one sends the rider looking
for the wrong fix.

---

## 8. Membership is checked before "room full", and joining a ride in progress is allowed

**Code:** `JoinPolicy.evaluate` returns `AlreadyMember` before it considers `ROOM_FULL`, and a
`RIDING` room still admits joiners.

**Why:** the first ordering stops a rider being locked out of the ride they are already in when
the room fills up behind them — a reconnect after a tunnel must not be treated as a new join. The
second is the real-world case: someone leaves late and catches the group up. Refusing them is a
rule with no purpose behind it.

---

## 9. `kotlinx.datetime.Instant` with the clock injected

**Spec §3.3** is not specific about time.

**Code:** the engine takes `now` on every tick and never reads a system clock.

**Why:** the alert engine reasons entirely about elapsed time — grace periods, response timeouts,
staleness, how long someone has been stationary. Tests have to be able to run a two-minute
scenario instantly and deterministically. Reading the clock inside the engine would make every one
of those tests either slow or flaky, which is how safety logic ends up untested.

---

## 10. Along-route gaps, with an off-route escape hatch

**Spec §2.3:** measure the gap to the group.

**Code:** when the leader has set a route, each rider is projected onto the polyline and gaps are
compared along it; riders further than 250 m from the route are excluded from that comparison and
fall back to straight-line distance to the leader.

**Why:** straight-line distance lies on a mountain road. Two riders 400 m apart across a hairpin
are 2 km apart along the road, and a rider who has just rounded a bend looks *closer* than they
were. Projection fixes that. The tolerance exists because a rider on the other carriageway, or who
took a different turn, is not "behind" in any sense the projection can express — and pretending
otherwise produced the worst alerts in early traces.

---

## 11. Staleness scales with the rider's own reporting interval

**Spec §3.4** describes adaptive reporting intervals for battery.

**Code:** `staleAfter` is a floor; the actual window is `reportingInterval × 2.5` when that is
larger.

**Why:** the battery saver and the staleness detector are the same mechanism seen from two sides.
A rider whose phone has dropped to a 30-second interval to save battery is not lost, but a fixed
45-second window would grey them out constantly. Scaling the window with what the rider promised
is the only way both features can be on at once.

---

## 12. Invite links prefill the code — they do not auto-join

**Spec §2.1:** deep links for invites.

**Code:** `ridetogether://join/<CODE>` opens the app with the code filled in; the rider still taps
Join.

**Why:** a tapped link should never put someone into a group that can see their location without
showing them what they are joining first. The extra tap is the consent.

---

## 13. Rooms live in memory until the realtime layer lands

**Code:** `RideViewModel` holds a map of rooms; a code resolves only against rides created on this
device, and there is a visible "add a rider (demo)" button.

**Why:** it keeps the UI honest and exercises the real `JoinPolicy` path while issue #10 is open,
rather than mocking the domain. Both go away with Firebase behind `RealtimeClient` — the interface
exists so that swap does not reach the engine.

---

## 14. Crash detection is a separate signal hierarchy, not an `Alert`

**Spec §2.5** asks for crash detection kept isolated and pluggable.

**Code:** `crash/` has its own `CrashSignal` hierarchy and its own `CrashDetector` interface, and
nothing in `alerts/` can reach it.

**Why:** "the detector must never be able to fire from the alert engine's path" is only a comment
if the two share a type. Because `CrashSignal` and `Alert` are unrelated sealed hierarchies, the
alert engine literally cannot construct a crash alarm and the detector cannot construct a
separation alert — the compiler enforces the isolation the spec asks for.

`DisabledCrashDetector` exists for the same reason: "detection off" has to be a real object that a
one-line substitution installs, not a boolean threaded through every call site. A detector that
fires wrongly is worse than no detector, so switching it off must be trivial.

The detector requires an impact spike, **then** a large orientation change, **then** stillness —
in that order, within seconds, and only while the rider was actually riding. Each signal alone has
a boring explanation (pothole, phone out of a pocket, red light), and the same three signals in
the *wrong* order are a fuel stop. The impact threshold is set at ~4 g rather than what a crash
really measures, because phone accelerometers commonly clip around 8 g: a threshold set to the
physical truth would never be reached on the hardware in the rider's pocket.

And the countdown is the actual safety valve. The detector is *allowed* to be wrong, because being
wrong costs one tap — which is what makes it acceptable to arm at all.

---

## 15. The ride summary reports moving averages, and says what it threw away

**Spec §2.6** asks for distance, duration, average and max speed per rider, and a stop count.

**Code:** average speed is over *moving* time only, implausible fixes are discarded **and
counted**, max speed is median-filtered, and moving-versus-stopped is decided by displacement
rather than by the provider's reported speed.

**Why:** each of these is the difference between a number a rider believes and one that makes them
distrust the whole app.

- **Wall-clock average is useless.** A two-hour ride with a lunch stop averages ~40 km/h, which
  reads like a measurement error. The moving average matches the ride they remember, and stopped
  time is reported next to it so nothing is hidden to flatter the number.
- **One bad fix is worth kilometres.** A GPS glitch teleports a few hundred metres and back;
  believed literally it inflates distance and reports a top speed of 300 km/h — and the top speed
  is exactly the number a rider screenshots. Segments implying more than ~270 km/h are dropped, as
  are segments spanning a coverage gap over two minutes, because a straight line through a tunnel
  is a ride nobody took. The discard count is part of the output: a trace with dozens of them has
  no business presenting itself as precise.
- **Max speed is the maximum of a three-point median.** A genuine fast stretch spans several
  fixes and survives; a lone spike is outvoted by its neighbours.
- **Displacement decides moving versus stopped.** The reported speed disagrees in a case that
  matters: a rider parked with reporting slowed to once a minute pulls away, and their first fix
  says 20 m/s with the bike not having moved. Trusting it books that whole stationary minute as
  riding, and the lunch stop leaks back into the moving average. Displacement cannot lie about
  having stayed put. The reported speed is still preferred for the max-speed series, where an
  instantaneous figure is what is wanted.
- **A stop is a stop, not a traffic light.** Halts under a minute count towards stopped time but
  not towards the stop count. "You stopped 34 times" is true and worthless.

The ride's own distance is the furthest any single rider rode, not an average across riders: a
rider who joined halfway has their own smaller number, and both are true, whereas the mean
describes a ride nobody took.

---

## Smaller notes

- **`mipmap-anydpi-v26` keeps its qualifier** even though `minSdk` is 26 and lint calls it
  obsolete: AAPT2 refuses to resolve an adaptive icon from a plain `-anydpi` folder. Verified by
  breaking the build, not assumed.
- **`shrinkResources`/R8 are on for release** from the first commit, so the release build cannot
  quietly rot into one that fails only on submission day.
- **CI runs `lintDebug` as well as the tests**, and the Android leg builds a real APK — an
  `assembleDebug` with no source set is a green tick that means nothing, which is exactly the bug
  issue #5 fixed.
