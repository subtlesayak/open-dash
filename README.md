# Northstar

**Low-power motorcycle navigation for the Royal Enfield Tripper Dash — with the phone screen off.**

Northstar is an open-source Android app for the **Royal Enfield Himalayan 450**. It projects turn-by-turn navigation onto the bike's round **Tripper TFT dash** without cooking the phone in your tank bag.

> ⚠️ Independent, community project. **Not affiliated with, endorsed by, or supported by Royal Enfield.** The dash protocol is reverse-engineered and unofficial — use at your own risk.

---

## Why

The official Royal Enfield app mirrors Google Maps to the dash by **screen-projection** — it keeps the phone's OLED lit and streams what's on screen. On a long ride in the sun that overheats the phone and drains the battery fast.

Northstar takes a different approach:

> It renders the map **off-screen**, hardware-encodes it to **H.264**, and streams it to the dash over the bike's WiFi — so the phone screen can stay **completely OFF** the entire ride.

That single architectural difference is the whole point of the project.

---

## Features

- **🧭 Navigation** — share a destination from Google Maps, preview the road route, send it to the dash. Real distance + ETA, a glanceable ETA pill on the map, and automatic **off-route rerouting**.
- **🔊 Voice guidance** — off / chime-before-turns / full spoken turn-by-turn (on-device TTS, no cloud).
- **🗺️ Beautiful keyless maps** — MapLibre + [OpenFreeMap](https://openfreemap.org) vector tiles. **No Google Maps API key required.**
- **🔌 Works on any Tripper** — auto-discovers any `RE_*` dash over WiFi and remembers yours; auth handshake + stream in one tap, with auto-reconnect.
- **🏍️ Ride history** — every connect→disconnect session is recorded automatically: distance, duration, avg/max speed, and a track map.
- **🛠️ Garage** — maintenance log (chain, oil, filters, brakes, coolant) with interval tracking + **due reminders**, plus a fuel diary with automatic mileage (km/l), efficiency trends, and cost tracking.
- **☁️ Optional sync** — works 100% offline-local by default; bring your own free Firebase project for cross-device sync if you want it.
- **🔋 Built for endurance** — hardware H.264 encode at low bitrate, frame caching, WiFi/wake locks, and thermal back-off, all so the screen-off ride stays cool.

---

## How it works

```
Google Maps share ─▶ route (OSRM) ─▶ off-screen map render (Canvas)
                                              │
                                   MediaCodec H.264 (hardware)
                                              │
                                     RTP over UDP :5000
                                              ▼
                                    Royal Enfield Tripper Dash
        (K1G control plane over UDP broadcast :2000  ·  RSA-1024 + AES-256 auth)
```

The dash speaks an undocumented binary protocol ("K1G"): a stateful RSA/AES handshake, then it decodes an H.264/RTP stream over UDP. Northstar implements the control plane and auth in Kotlin and feeds the dash a map it renders itself — the dash doesn't care what produces the video.

The control-plane work builds on the excellent reverse-engineering in [**better-dash**](https://github.com/norbertFeron/better-dash) as a reference.

---

## Tech stack

- **Kotlin** + **Jetpack Compose** (native Android)
- **MediaCodec** hardware H.264 encode → custom RTP packetizer → UDP
- **MapLibre** + **OpenFreeMap** for the map (keyless); **OSRM** for road routing
- **SQLite** on-device as the source of truth; **Firebase** (optional) for sync
- **TextToSpeech** for voice; raw `LocationManager` GPS

---

## Download

**📦 Grab the latest APK from [Releases](https://github.com/adityadasika21/NorthStar/releases/latest)** and sideload it (you'll need to allow "install unknown apps"). No account or setup required to try it.

> Heads-up: it's signed with a debug key for now, so Android may show an "unverified app" warning on install — that's expected for a community build.

---

## Building it yourself

```bash
git clone https://github.com/adityadasika21/NorthStar.git
cd NorthStar
./gradlew :app:assembleDebug
```

That's it — **no API keys needed**. The map is keyless (OpenFreeMap) and the app runs fully local.

**Optional — cross-device sync:** create your own [Firebase](https://firebase.google.com) project (Auth + Firestore), download its `google-services.json` into `app/`, and rebuild. Without that file the app simply skips sync and stays local. (Your `google-services.json` is git-ignored — never commit it.)

> **You'll need your own bike.** The dash protocol can only be fully validated against real hardware. This is verified on the author's dash (firmware **11.63**); other firmwares may differ in the handshake. If you try it on yours, please open an issue with what you find.

---

## Status & roadmap

The navigation core — discover, connect, stream, route, reroute, free-roam, voice — works end-to-end against a real Tripper Dash (fw 11.63). Garage, fuel diary, and ride recording are functional and persisted.

**In progress / planned:**
- Stream the **OpenFreeMap** look to the dash too (currently the dash basemap still uses raster tiles)
- Move routing off the public OSRM demo server
- Downloadable offline map regions for no-signal mountain riding
- Per-ABI APK splits (the MapLibre native libs make the debug APK large)
- Media now-playing overlay (pending more dash-protocol reverse engineering)

---

## Contributing & feedback

This started as a personal project and is now open for other Himalayan / Tripper riders. **Issues, ideas, and PRs are very welcome** — especially:
- reports of the handshake working (or not) on **other dash firmwares**,
- packet captures of the official app for the bits still unknown (joystick-in-nav, maneuver glyph codes),
- real-ride battery/thermal numbers with the screen off.

If it helps you keep your phone cool on a ride, that's the win.
