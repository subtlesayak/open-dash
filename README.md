# OpenDash

OpenDash is an open-source Android companion for a compatible bike dash: navigation, ride history, garage tracking, expenses, wallpapers, and media/call cards in one lightweight app.

It renders its own dash view off-screen, encodes it as H.264, and streams it over Wi-Fi so the phone screen can stay off during a ride.

> Independent community project. The dash protocol is unofficial and reverse-engineered. Use with care.

## ✨ Features

- 🧭 Bike dash navigation with OSRM routing, MapLibre/OpenFreeMap preview, ETA, remaining distance, GPS status, and off-route recalculation.
- 📲 Share destinations or `geo:` links directly into OpenDash.
- 🏍️ Vehicle profiles with active-vehicle selection, odometer, PUC/insurance dates, and service details.
- 🧰 Garage with spare-part intervals, service history, odometer editing, and mileage from fuel fill-ups.
- ⛽ Expenses for fuel, repairs, accessories, riding gear, food, stays, transport, and other categories.
- 📊 Monthly/all-time expense filtering and export through the Android share sheet.
- 🖼️ Idle dash wallpapers with up to five local media items and crop/fit controls.
- 🎵 Media and caller cards projected to the dash while streaming.
- 🎨 Material 3 Expressive UI with app-wide motorcycle-inspired themes.
- 🔒 Local-first storage with encrypted dash Wi-Fi credentials.

## 📦 Install

1. Open the [OpenDash Releases page](https://github.com/subtlesayak/open-dash/releases).
2. Download the latest APK for your phone.
3. Allow installation from your browser or file manager.
4. Install or update OpenDash.

Android 10 or newer is recommended for the Wi-Fi connection flow.

## 🚀 First Use

1. Open OpenDash and grant precise location plus Wi-Fi/nearby-device permissions.
2. Turn on the motorcycle and wait for the dash to start.
3. Tap **Connect to dash**.
4. Confirm the discovered dash SSID when OpenDash asks.
5. Share a destination or `geo:` link, preview it, then tap **Start navigation**.

The paired dash SSID and password are stored with AndroidX encrypted preferences. Use **Forget Dash** in More/Settings when pairing again.

## 🧭 Main Tabs

| Tab | What it does |
| --- | --- |
| Home | Connect, start navigation, saved destinations, recent rides |
| Vehicles | Add/edit vehicles and choose the active vehicle |
| Expenses | Add, filter, review, and export expenses |
| Garage | Odometer, mileage, spare parts, service logging |
| More | Connection, themes, wallpaper, media/calls, voice, units, help |

## 🛠️ Build From Source

```bash
git clone https://github.com/subtlesayak/open-dash.git
cd open-dash
./gradlew :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APKs are created in:

```text
app/build/outputs/apk/debug/
```

Run tests:

```bash
./gradlew :app:testDebugUnitTest
```

Release signing uses your own keystore through Gradle properties or CI secrets. Never commit keys, APKs, logs, `local.properties`, `google-services.json`, or other private files.

## 🔐 Privacy

- App data is local-first.
- Android cloud/device-transfer backup is disabled.
- Dash credentials are encrypted.
- Wallpaper media stays in app-private storage.
- Expense exports are created locally and shared only when you choose to share them.
- Firebase/Google sync is optional and bring-your-own-project.
- Release builds avoid logging full URLs, coordinates, media titles, or caller names.

Google sign-in and Firestore sync setup is documented in [`docs/google-cloud-sync.md`](docs/google-cloud-sync.md).

## ⚠️ Notes

- Real dash behavior depends on firmware and needs hardware testing.
- Public OSRM routing and online map tiles need internet access.
- Media/call behavior can vary by Android version, dialer, and media app.
- Album-art packet fragmentation stays disabled until fully verified.

Protocol-critical behavior is documented in [`docs/PROTOCOL_FREEZE.md`](docs/PROTOCOL_FREEZE.md).

## 🤝 Contributing

Issues and pull requests are welcome. Please remove personal data from logs and screenshots before sharing: coordinates, SSIDs, caller names, account IDs, tokens, and device identifiers.

## License

OpenDash is distributed under the terms in [`LICENSE`](LICENSE).

## References

- [norbertFeron/better-dash](https://github.com/norbertFeron/better-dash) - Motivation
- [adityadasika21/NorthStar](https://github.com/adityadasika21/NorthStar) - App base
