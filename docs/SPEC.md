# RideTogether — Group Ride Coordinator App
## Full Product & Technical Specification (v1.0)

**Target:** Android first, iOS second. Kotlin Multiplatform + Compose Multiplatform.
**Team:** Solo developer (Android background) + AI coding agent.
**Users:** Small motorcycle groups (2–10 riders).

---

## 1. Core Concept

A mobile app where a group of riders joins a "Ride Room," sees each other live on a map, and gets smart alerts when someone falls behind, stops unexpectedly, or may have crashed. Built for phone-in-pocket use: critical info is delivered via audio/TTS and notifications, not just the screen.

---

## 2. Feature Specification

### 2.1 Ride Rooms
- **Create room:** creator becomes Ride Leader. Sets: room name, max riders (2–10), visibility (invite-only / public), planned destination (optional), meeting point (optional).
- **Invite-only rooms:** 6-character alphanumeric join code + shareable deep link (`ridetogether://join/ABC123`). QR code display for parking-lot joins.
- **Public rooms:** discoverable in a "Nearby rides" list (within X km, starting within Y hours). Joiners still require leader approval (toggle: auto-accept ON/OFF).
- **Roles:**
  - **Leader:** start/pause/end ride, kick riders, promote co-leader, change settings.
  - **Sweep (optional role):** last rider; fallback alerts behave differently for sweep (they're supposed to be last).
  - **Rider:** default.
- **Room lifecycle states:** `LOBBY → RIDING → PAUSED (fuel/food stop) → RIDING → ENDED`. Location sharing only active in RIDING/PAUSED. Room auto-expires 24h after creation or 1h after ENDED.

### 2.2 Live Map
- Full pannable/zoomable map (MapLibre GL + OpenStreetMap tiles).
- Each rider = colored avatar marker with heading arrow, updated in near-real-time.
- Marker tap → rider card: name, speed, distance behind leader, last-update timestamp, battery level (opt-in), "call rider" button.
- **Group overview button:** auto-zoom to fit all riders.
- **Follow modes:** follow-me / follow-group / free pan.
- Stale locations (no update > 45s) shown greyed with "last seen" time — never silently frozen.
- Planned route polyline (if leader set a destination) + meeting point pin.
- Offline map tile caching for the planned route corridor (download on ride start over WiFi/cellular).

### 2.3 Fallback / Separation Detection (the smart part)
Do NOT alert on raw distance alone. Algorithm:

1. Compute each rider's **gap** = along-route distance to the rider ahead (fallback to straight-line if no route set).
2. Trigger `FALLING_BEHIND` when gap > threshold (default 1.5 km, leader-configurable) **AND** gap is increasing for > 60s (grace period handles red lights).
3. Rider who fell behind gets a local prompt: **"You've dropped back — all good?"** → one-tap responses: `I'm fine` / `Mechanical issue` / `Need help`. Response is broadcast to the room.
4. If no response in 90s **AND** rider is stationary → escalate to `POSSIBLE_INCIDENT`: loud alert to the whole group with the rider's pin.
5. If rider's phone loses signal, show `SIGNAL LOST` (grey) state — explicitly different from `POSSIBLE_INCIDENT`. Never conflate no-data with emergency.
6. Suppress all fallback alerts while room state is `PAUSED`.

### 2.4 Alerts & Communication
- **Event feed** per room (rider joined, fell behind, responded "fine," paused, resumed, etc.).
- **Quick messages (canned, one-tap):** "Fuel stop needed" / "Slow down" / "Pull over next safe spot" / "All good." Broadcast + TTS-announced on other riders' phones ("Alex needs fuel").
- **Audio-first design:** all critical alerts read aloud via TTS through helmet Bluetooth/headset. Assume the screen is never looked at while moving.
- Optional push-to-talk / voice notes: **OUT OF SCOPE for v1** (note in backlog — huge complexity).

### 2.5 Crash Detection Lite (v1.5, design for it now)
- Phone sensors: sudden deceleration spike + orientation change + stationary afterwards → local 30s countdown with loud alarm → if not cancelled, broadcast `POSSIBLE_CRASH` to room with exact coordinates + optional SMS to emergency contact.
- Keep the detection module isolated (pluggable) — tuning false positives will take iterations.

### 2.6 Ride Summary
- On `ENDED`: distance, duration, avg/max speed per rider, route trace map, stops count. Shareable image card. Ride history list (local + cloud).

### 2.7 Settings / Profile
- Display name, avatar color, motorcycle name/model (fun, also useful: "the red Ducati is Alex").
- Emergency contact (for crash detection).
- Units (km/mi), TTS on/off, battery saver mode toggle.

---

## 3. Technical Architecture

### 3.1 Stack
| Layer | Choice | Rationale |
|---|---|---|
| Shared logic | Kotlin Multiplatform (KMP) | Dev is Kotlin-native; share domain + data layers |
| UI | Compose Multiplatform (Android first; iOS UI in later phase, optionally SwiftUI if CMP-iOS maps underperform) | |
| Map | MapLibre GL Native (Android + iOS bindings) + OSM/OpenFreeMap tiles | Free, no vendor lock-in, offline tiles |
| Realtime backend | **Option A (recommended for v1):** Firebase Realtime Database + Cloud Functions. **Option B:** self-hosted Ktor server + WebSockets + Redis + PostGIS | A = ship fast; B = full control later. Abstract behind repository interface so swap is possible |
| Auth | Firebase Auth (anonymous + Google Sign-In) | Frictionless: friends shouldn't need accounts to join a ride |
| Push | Firebase Cloud Messaging (+ APNs for iOS) | |
| Local DB | SQLDelight (KMP-native) | Ride history, cached rooms, offline queue |
| DI | Koin (KMP support) | |
| Serialization | kotlinx.serialization | |
| Location (Android) | Foreground Service + FusedLocationProvider | |
| Location (iOS) | CLLocationManager, `allowsBackgroundLocationUpdates`, significant-change fallback | |

### 3.2 Clean Architecture — Module Layout
```
:core:common          — result wrappers, dispatchers, logging
:core:model           — Rider, Room, LocationPoint, RideEvent, RoomState (pure Kotlin)
:core:database        — SQLDelight, DAOs
:core:network         — Firebase/WS abstraction behind RealtimeClient interface
:core:location        — expect/actual location providers, adaptive interval engine
:core:alerts          — fallback detection engine, crash detection (pure logic, 100% unit-testable)
:feature:auth         — sign-in, profile
:feature:room         — create/join/lobby, invites, QR
:feature:ride         — live map, markers, event feed, quick messages
:feature:summary      — ride stats, history
:app:android          — Android entry, foreground service, notifications
:app:ios              — iOS entry (phase 2)
```
Each feature = `domain` (use cases, interfaces) / `data` (repos impl) / `presentation` (ViewModel/StateHolder + Compose UI). Dependencies point inward only. **The alert engine (`:core:alerts`) must be pure Kotlin with zero platform deps** — feed it location streams in tests, assert emitted alerts.

### 3.3 Data Models (sketch)
```kotlin
data class Room(
  val id: String, val code: String, val name: String,
  val visibility: Visibility, val maxRiders: Int,
  val state: RoomState, val leaderId: String,
  val route: List<LatLng>?, val createdAt: Instant
)
data class RiderPresence(
  val riderId: String, val displayName: String, val color: Int,
  val location: LatLng?, val heading: Float?, val speedMps: Float?,
  val batteryPct: Int?, val updatedAt: Instant,
  val status: RiderStatus // ACTIVE, FALLING_BEHIND, STOPPED, SIGNAL_LOST, POSSIBLE_INCIDENT, PAUSED
)
sealed interface RideEvent { /* Joined, Left, FellBehind, Responded, QuickMessage, StateChanged, PossibleIncident ... */ }
```

### 3.4 Location Pipeline & Battery Strategy (critical)
- **Adaptive intervals:** moving > 20 km/h → update every 3–5s; stopped → every 30s; battery < 20% → every 15s + notify room "battery saver."
- Upload **batched deltas**, not every point. Interpolate marker movement client-side for smoothness.
- Android: Foreground Service with persistent notification (required for background GPS, and required by Play Store policy — declare `FOREGROUND_SERVICE_LOCATION`).
- iOS: background location entitlement, blue status bar indicator; test aggressively — iOS will suspend the app if misconfigured.
- Kill-switch: leaving room / ride ENDED must reliably stop all location collection. Privacy = trust.

### 3.5 Realtime Sync Design (Firebase v1)
```
/rooms/{roomId}/meta        — room config + state (listeners: all members)
/rooms/{roomId}/presence/{riderId} — location payload (write: self only; read: members)
/rooms/{roomId}/events      — append-only event log
```
- Security rules: only members read presence; only self writes own presence; only leader writes meta.
- Cloud Function: room expiry cleanup, join-approval, push notifications for `POSSIBLE_INCIDENT`.
- Firebase free tier easily handles 10 riders × 1 update/4s. Cost estimate at scale in README.

### 3.6 Offline / Degraded Behavior
- Outgoing location updates queue locally when offline; flush on reconnect (send only latest + trace summary, not full backlog).
- UI always shows per-rider data freshness. No signal ≠ emergency (see 2.3.5).
- Map tiles pre-cached along route corridor.

---

## 4. Build Plan (Phases)

**Phase 0 — Skeleton (week 1)**
KMP project setup, module structure, CI (GitHub Actions: build + unit tests), Firebase project, anonymous auth, Koin wiring.

**Phase 1 — Rooms (week 2)**
Create/join via code + deep link + QR. Lobby screen with member list. Room lifecycle states. Leader controls. Security rules + tests.

**Phase 2 — Live Map (weeks 3–4)**
MapLibre integration, own location foreground service, presence sync, rider markers with heading + interpolation, group-fit zoom, stale/greyed states, rider detail card.

**Phase 3 — Alert Engine (week 5)**
`:core:alerts` pure-logic module + full unit test suite (simulated GPS traces: red light, fuel stop, genuine fallback, signal loss). Fallback prompts, quick responses, event feed, TTS announcements, push notifications.

**Phase 4 — Polish & Beta (week 6)**
Battery optimization pass, offline queue, ride summary, settings, Play Store internal testing track → friends beta.

**Phase 5 — iOS port**
iOS app module, CLLocation actuals, MapLibre iOS, background-mode battle. Only after Android beta feedback is folded in.

**Backlog (post-v1):** crash detection tuning, public room discovery, voice notes/PTT, route planning with curvy-road scoring, Wear OS glances, group ride stats/leaderboards.

---

## 5. Non-Functional Requirements
- Location update end-to-end latency < 5s under normal LTE.
- App must survive 4-hour ride with < 40% battery consumption (mid-range Android, screen mostly off).
- All alert logic unit-tested with recorded/synthetic GPS traces before field testing.
- GDPR-minded: location kept only for active ride + summaries; explicit consent screen; full data delete.
- Play Store background-location policy compliance: prominent disclosure dialog before requesting permission.

## 6. Testing Strategy
- Unit: alert engine, room state machine, adaptive interval logic (highest value).
- Integration: Firebase rules tests (emulator suite), repository tests.
- Field protocol: 2-phone car test first (cheap), then real group ride with logging enabled; collect false-positive/negative alert stats.

## 7. Key Risks
| Risk | Mitigation |
|---|---|
| iOS background location suspension | Android-first; budget dedicated time; significant-change API fallback |
| Fallback alert false positives annoy users | Grace periods, PAUSED state, tunable thresholds, beta telemetry |
| Battery drain kills adoption | Adaptive intervals from day one; battery test in CI field protocol |
| Firebase costs if app grows | RealtimeClient abstraction → swap to Ktor/WebSockets |
| Play Store rejection (background location) | Foreground service + disclosure flow per policy, video for review |
