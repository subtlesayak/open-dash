    # OpenDash

Personal Android companion app for a **Himalayan 450** motorcycle.
Single user (just me), Android-only, targeting a **Nothing Phone 3**. Not a
product to sell — may be open-sourced, but built for my own bike only. No user
personas, no client/enterprise concerns.

## Primary goal

Low-power **navigation projected onto the bike dash** (a small
**round TFT display**) without cooking the phone. The stock companion app overheats
the phone because it screen-*projects* — it keeps the OLED lit and mirrors Google
Maps. OpenDash instead **renders the map off-screen and hardware-encodes H.264**, so
the phone screen can stay **OFF** during the ride. That single architectural
difference is the whole point of the project.

## Dash protocol

- Connect to the bike dash using the **better-dash** protocol as reference:
  https://github.com/norbertFeron/better-dash
- After an auth handshake, the dash decodes an **H.264/RTP stream over UDP port
  5000**. It does not care what produces the video.
- **Unverified against my hardware:** the handshake/connection details vary by
  firmware. My dash runs firmware **11.63** — the control-plane + auth must be
  validated against it before anything else is trusted. This is the make-or-break
  gate; treat it as Phase 1, step 1.

## Core user flow

1. I share a destination from **Google Maps** into OpenDash.
2. OpenDash previews the route, I tap **Send to Dash**.
3. While riding, the dash shows the map; I use the bike's **physical joystick** to
   pan/zoom. The phone screen stays off.

## Tech stack

- **Language:** Kotlin (Android, native).
- **Map rendering:** off-screen render → `MediaCodec` hardware H.264 encode →
  `MediaCodec`/RTP to the dash on UDP/5000.
- **Maps/offline:** offline maps preferred for riding; reuse cached/offline Google
  Maps if feasible, otherwise an OSM stack (MapLibre) as fallback. Decide during
  build — don't over-engineer the map layer up front.
- **Backend:** **Firebase** for email auth + multi-device sync (so installing on a
  second device restores my data). Single user, but sync is wanted.
- **Local persistence:** on-device **SQLite** as the source of truth; Firebase
  syncs it.

## Features

1. **Navigation** (primary) — receive shared location, route preview, send to dash,
   joystick pan/zoom, turn-by-turn.
2. **TTS / voice overlay** — toggle per trip: off / chime-only / full. We own the
   TTS layer, so this is just a setting.
3. **Maintenance log** — chain cleaning + lube tracker, service intervals, due
   reminders.
4. **Fuel diary** — fill-ups, mileage/efficiency calculations, cost tracking.
5. **Telemetry / ride history** — distance, duration, map snapshot per ride.
6. **Media controls** — now-playing overlaid onto our own video frame (not the
   dash's native widget). Note: Android restricts answering/ending calls
   programmatically — calls are realistically display + reject + alert only.

## Build phasing

Sequenced so standalone, useful parts land first and the risky reverse-engineering
is isolated:

1. **Phase 1:** Kotlin control plane + auth ported and validated against firmware
   11.63 (stream a static test video to the dash to prove the protocol). In
   parallel, the standalone features (maintenance log, fuel diary, telemetry) — no
   dash dependency, usable day one.
2. **Phase 2:** off-screen MapLibre/map → MediaCodec → dash with screen OFF. Proves
   the power fix.
3. **Phase 3:** GPS + offline routing + turn-by-turn rendering.
4. **Phase 4:** polish — TTS, day/night, reconnect handling, settings, media.

## Hard constraints / non-goals

- **Android only.** No iOS.
- **One bike** (Himalayan 450), **one dash target** (bike dash). No generic
  multi-bike / multi-dash abstraction.
- No personas, no branding-as-product, no team/lab infrastructure, no server-side
  PostGIS unless a real need appears. Keep it lean.
- The dash-streaming core requires **hardware-in-the-loop validation on my bike** —
  that part can't be verified from code alone.

## Reference docs in this repo

- `@docs/HLD-LLD.md` — full architecture (high- and low-level design).
- `@docs/design/` — UI prototype and screen specs (from Claude Design).
