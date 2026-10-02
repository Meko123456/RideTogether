# RideTogether 🏍

[![CI](https://github.com/Meko123456/RideTogether/actions/workflows/ci.yml/badge.svg)](https://github.com/Meko123456/RideTogether/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A group-ride coordinator for small motorcycle groups (2–10 riders): everyone joins a **Ride
Room**, sees each other live on a map, and gets a *smart* alert when someone drops back, stops
unexpectedly, or may have come off. Built for phone-in-pocket use — the critical information
arrives as audio through a helmet headset, not as something you have to look at while moving.

**Kotlin Multiplatform**, Android first, iOS second. The full product and technical
specification lives in [`docs/SPEC.md`](docs/SPEC.md).

## The interesting engineering problem

Alerting on raw distance is useless: at 60 km/h a group opens a kilometre a minute, so a rider
at a long traffic light looks exactly like a rider with a puncture. The whole value of the app
is in telling those apart, which means the alert engine is:

- **pure Kotlin with zero platform dependencies** — no Android, no maps, no Firebase — so it can
  be driven by synthetic GPS traces in unit tests;
- fed a **clock the tests control**, never `System.currentTimeMillis()`;
- explicit that **no signal is not an emergency** — a phone going quiet is reported as
  `SIGNAL_LOST`, never escalated as a crash, because an app that cries wolf gets ignored.

## Status

🚧 Early. Building the pure core first, deliberately: the domain model, the room state machine
and the alert engine can all be finished and fully tested before any Firebase project, maps SDK
or location permission exists.

| Area | State |
|---|---|
| Domain model (`LatLng`/`Geo`, `Room`, `RiderPresence`, `RideEvent`, `JoinCode`) | ✅ |
| Room lifecycle + join policy state machines | ✅ |
| Alert engine (separation / incident detection) | ✅ mutation-tested |
| Android app shell (create + join a ride, room controls, invite links) | ✅ |
| Live map (MapLibre + OpenFreeMap vector tiles) | ✅ |
| Foreground-service location + adaptive intervals | ✅ |
| Realtime sync — Firebase Realtime Database over REST and streaming, anonymous sign-in | ✅ Android, run against the emulators |
| Crash detection — sensors, countdown, spoken warning | ✅ |
| Adaptive location intervals + kill switch | ✅ mutation-tested |
| Audio-first announcement policy (what is spoken, and what is not) | ✅ mutation-tested |
| Ride session (engine + announcer + location policy, composed) | ✅ |
| Ride summary + history | ✅ |
| Quick messages, ride log + TTS through a helmet headset | ✅ |
| iOS app — shared framework links and its tests pass; no app yet | 🟡 phase 2 |

229 tests in `:shared`, plus 11 that run the realtime client against the Firebase emulators, and 24 in `:androidApp` — the engine is driven by synthetic GPS traces, so the interesting
logic is covered without a device.

## iOS

There is an iOS app, and it runs the same engine the Android one does.

```sh
cd iosApp && xcodegen generate
open RideTogether.xcodeproj
```

The Xcode project is generated from `project.yml` rather than committed, the same way Barati and
Nishani do it. A pre-build step builds the Kotlin `Shared` framework, so opening and running is the
whole setup.

**What it does today.** Every decision — who has fallen behind, when that is worth saying out loud,
what a gap in metres means — comes from `:shared` and is already tested there. The Swift layer keeps
rider positions, advances a clock and draws what comes back; it owns no rules.

Positions are moved by controls on screen rather than by `CoreLocation`. That is deliberate for this
first version. A simulated ride exercises the alert engine far harder than a phone on a desk, and it
makes visible things that are otherwise invisible: that the engine wants a gap to be **growing
continuously** before it says anything, that a rider parked 2 km back at a steady distance is riding
their own pace rather than in trouble, and that the designated sweep is exempt because being last is
their job. Ride time runs at ten times real time so those grace periods play out in seconds.

Five UI tests drive the app on a simulator in CI, and two of them go the whole way through the
engine: a tap makes a rider lose ground, the gap grows past the threshold, the joining and
separation graces elapse, and "falling behind" comes back out in SwiftUI. Then catching up clears
it, which also proves the hysteresis is reachable from the UI rather than only from a unit test.
`xcodebuild test` runs them, and CI runs `test` rather than `build` for exactly that reason.

`CLLocationManager` actuals, MapLibre and background modes are the rest of
[#15](https://github.com/Meko123456/RideTogether/issues/15), which that issue deliberately sequences
after Android beta feedback.

## Architecture

```
shared/      Kotlin Multiplatform, no platform deps in the core
  model/ LatLng + Geo (haversine, bearing, polyline distances), Room, Member,
             RiderPresence, RideEvent, JoinCode
  room/      room lifecycle + membership state machine
  alerts/    fallback / separation detection engine (pure, trace-tested)
  crash/     crash detection behind an interface, deliberately unable to reach alerts/
  location/  adaptive reporting intervals, and the kill switch as a pure rule
  announce/  what a rider actually hears through a helmet -- mostly, what they do not
  session/   the three above composed, so the platform layer stays thin
  realtime/  the backend boundary, and an in-memory client that doubles as the test double
  summary/   post-ride numbers from a recorded trace (glitch-filtered, moving averages)
androidApp/  Compose UI, foreground-service location, notifications
```

Modules are deliberately fewer than the spec's eleven for now: `:shared` + `:androidApp` keeps
the build fast and honest while the core is in flux, and the spec's `:core:*` split is a
mechanical refactor once the seams have stopped moving.

Where the code deliberately departs from the spec — the alert rule above all — the reasoning is
recorded in [`docs/DECISIONS.md`](docs/DECISIONS.md) rather than left for the next reader to
reverse-engineer.

Location handling is settled in advance rather than discovered at submission time:
[`docs/PLAY_LOCATION_COMPLIANCE.md`](docs/PLAY_LOCATION_COMPLIANCE.md) explains why the app never
requests `ACCESS_BACKGROUND_LOCATION`, and what Play requires regardless.

## Setup

Nothing needs configuring to build and test. The core builds and tests with no accounts or keys:

```sh
./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug
```

The same suite runs on Kotlin/Native, which is how "no platform dependencies" stays true rather
than aspirational (needs macOS + Xcode):

```sh
./gradlew :shared:iosSimulatorArm64Test
```

### Riding through Firebase

With no settings the app keeps rides in memory, so a ride exists only on the phone that made it.
To ride through Firebase, give the build a project. Each setting is read from the environment, or
else from `local.properties`, which git ignores. Nothing project-specific is ever committed, and
there is no `google-services.json`: the app talks to Firebase over REST.

| Setting | What it is |
|---|---|
| `RIDETOGETHER_FIREBASE_DATABASE_URL` | the Realtime Database, `https://<name>.<region>.firebasedatabase.app` |
| `RIDETOGETHER_FIREBASE_API_KEY` | the project's Web API key (Project settings → General) |
| `RIDETOGETHER_FIREBASE_DATABASE_NAMESPACE` | emulators only: the database name |
| `RIDETOGETHER_FIREBASE_AUTH_EMULATOR_HOST` | emulators only: `host:port` of the Auth emulator |

The project needs Anonymous sign-in turned on (Authentication → Sign-in method) and this repo's
rules deployed (`firebase deploy --only database`). The app asks a rider for a name and signs them
in anonymously, and that is all: no account, no email.

#### Against the emulators

No project needed. Start the emulators with the repo's rules:

```sh
(cd tools/rules-tests && npm ci)
tools/rules-tests/node_modules/.bin/firebase emulators:start --project demo-ridetogether --only database,auth
```

Then build a debug app that points at them. `10.0.2.2` is the development machine as an Android
emulator sees it, and debug builds may use plain http to it; release builds may not:

```sh
RIDETOGETHER_FIREBASE_DATABASE_URL=http://10.0.2.2:9110 \
RIDETOGETHER_FIREBASE_DATABASE_NAMESPACE=demo-ridetogether-default-rtdb \
RIDETOGETHER_FIREBASE_API_KEY=demo-key \
RIDETOGETHER_FIREBASE_AUTH_EMULATOR_HOST=10.0.2.2:9099 \
./gradlew :androidApp:installDebug
```

From a phone on USB, `adb reverse tcp:9110 tcp:9110 && adb reverse tcp:9099 tcp:9099` and use
`localhost` instead of `10.0.2.2`. The realtime client's own tests run against the same emulators,
as CI does:

```sh
tools/rules-tests/node_modules/.bin/firebase emulators:exec --project demo-ridetogether \
  --only database,auth "./gradlew :shared:testAndroidHostTest --tests '*RtdbEmulatorTest*'"
```

## License

[MIT](LICENSE) © 2026 Merab Kochlamazashvili
