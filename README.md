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
| Realtime sync — interface + in-memory implementation | 🟡 Firebase pending |
| Crash detection — sensors, countdown, spoken warning | ✅ |
| Adaptive location intervals + kill switch | ✅ mutation-tested |
| Audio-first announcement policy (what is spoken, and what is not) | ✅ mutation-tested |
| Ride session (engine + announcer + location policy, composed) | ✅ |
| Ride summary + history | ✅ |
| Quick messages, ride log + TTS through a helmet headset | ✅ |
| iOS app — shared framework links and its tests pass; no app yet | 🟡 phase 2 |

197 tests in `:shared`, 14 in `:androidApp` — the engine is driven by synthetic GPS traces, so the interesting
logic is covered without a device.

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

There is nothing to configure yet — the core builds and tests with no accounts or keys:

```sh
./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug
```

The same suite runs on Kotlin/Native, which is how "no platform dependencies" stays true rather
than aspirational (needs macOS + Xcode):

```sh
./gradlew :shared:iosSimulatorArm64Test
```

When realtime sync lands it will need a Firebase project of your own. `google-services.json`
is **git-ignored and must never be committed**; the README will carry the setup steps.

## License

[MIT](LICENSE) © 2026 Merab Kochlamazashvili
