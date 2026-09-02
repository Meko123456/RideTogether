# Location, and what Google Play requires for it

**Decision: RideTogether does not request `ACCESS_BACKGROUND_LOCATION`, and should not.**

That single choice removes the restricted-permission declaration form, the demo video Google
usually asks for with it, and the review round-trips that come with both. It is not a shortcut —
it is what the app actually needs.

## Why a foreground service is enough

`ACCESS_BACKGROUND_LOCATION` exists for apps that need a position when they have **no visible
activity and no eligible foreground service** — a geofence that fires while the app is closed, a
tracker that samples overnight. RideTogether is neither. Location is needed from the moment a
rider taps **Start ride** until the ride ends, and for that whole window the app runs a foreground
service of type `location` with a notification the rider can see.

A foreground service with `foregroundServiceType="location"`, started while the app is on screen,
may use location with only the while-in-use grant (`ACCESS_FINE_LOCATION`). The phone can be in a
tank bag with the screen off; the service is what keeps the permission valid, and the persistent
notification is what makes it honest.

Two conditions make this hold, and #9 must implement both:

1. **The service is only ever started from a visible activity.** Tapping "Start ride" is that
   moment. Android 12+ refuses to start a foreground service from the background anyway, and for
   the location type the exemptions are narrow — so this is not merely policy, it is what works.
2. **The service stops when the ride ends.** Not "eventually", not on a timer: `ENDED` stops it,
   and so does the rider leaving the room. Location that outlives its reason is exactly what the
   restricted permission exists to police.

### What would force us back into background location

- Auto-starting a ride from a geofence at the usual meeting point.
- Keeping a rider on the map after they close the app without ending the ride.
- Waking the app to check whether the group has moved.

Each of those is a small convenience bought with a restricted permission, a declaration form, a
demo video, and a permanent obligation to justify overnight location access. **Refuse them.** If
one is ever genuinely wanted, it needs its own decision, not an incremental permission bump.

## What Play still requires

Not requesting the restricted permission does not exempt the app from the location policy. All of
this still applies:

### 1. A prominent in-app disclosure, shown before the permission request

It must appear *before* the system dialog, must name the app, and must say what is collected and
why. The system dialog does not count. Proposed wording, to live in a dialog with **Not now** and
**Continue** and nothing else:

> **RideTogether needs your location while you ride**
>
> While a ride is running, RideTogether shares your position with the other riders in that ride so
> the group can see each other on the map and be warned if someone drops back.
>
> Your location is collected only between tapping **Start ride** and the ride ending, and only
> while the ride notification is showing. It is never collected when no ride is running, and it is
> never used for advertising.
>
> You can stop sharing at any time by ending the ride or leaving the room.

Rules for it: no pre-ticked consent, no "Continue" that also accepts something else, and it must
be shown again if the permission was denied and is being requested a second time.

### 2. The runtime request sequence

- `ACCESS_FINE_LOCATION` only. Never `ACCESS_BACKGROUND_LOCATION`.
- Request it when the rider first starts or joins a ride — not at launch. A permission dialog on
  first open, before the app has explained itself, is the single most common reason for a denial.
- Handle denial without breaking: the rider can still see the room, the chat and the queue; they
  simply do not appear on the map, and the app says so plainly rather than nagging.

### 3. A privacy policy at a public URL

Required in the store listing and inside the app. [`PRIVACY.md`](PRIVACY.md) is the text; publish
it with GitHub Pages from this repo's `docs/` folder so the URL lives with the code that has to
match it.

### 4. Data safety declarations

The form has to match what the app does. What to answer, given the design above:

| Question | Answer |
|---|---|
| Does the app collect or share location? | Yes — **precise location** |
| Collected or shared? | Both. Shared with the other riders in the same ride |
| Purpose | App functionality only |
| Required or optional? | Optional — the app works without it, minus the map |
| Is it processed ephemerally? | No: positions persist for the life of the ride room (24 h) |
| Encrypted in transit? | Yes |
| Can the user request deletion? | Yes — ending the ride deletes the room's positions |
| Sold or shared with third parties? | No |
| Used for advertising or analytics? | No |

The "24 h" answer is a claim about the backend, so #10 has to make it true: room data expires with
the room, and a rider leaving removes their positions.

### 5. Foreground service type declaration

Android 14+ requires a declared type and a stated use case in the console for
`FOREGROUND_SERVICE_LOCATION`. The use case to give:

> RideTogether shares a rider's position with the other members of their group ride while the ride
> is in progress, so the group can see each other on a live map and be alerted when someone falls
> behind. The service runs only between the rider starting or joining a ride and that ride ending,
> and shows a persistent notification throughout.

## The manifest this implies

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Deliberately absent: ACCESS_BACKGROUND_LOCATION. See the top of this document. -->

<service
    android:name=".location.RideLocationService"
    android:exported="false"
    android:foregroundServiceType="location" />
```

## Pre-submission checklist

- [ ] `ACCESS_BACKGROUND_LOCATION` appears nowhere, including in a merged library manifest —
      check the merged manifest, not just this one.
- [ ] Disclosure dialog appears before the first permission request, and again before a re-request.
- [ ] The service starts only from a visible activity, and stops on `ENDED` and on leaving.
- [ ] The notification is present for the entire time location is collected.
- [ ] Denying location leaves a usable app.
- [ ] Privacy policy URL is live and matches the Data safety answers.
- [ ] Foreground service type use case entered in the console.
- [ ] Room data really does expire with the room.
