> [!WARNING]
> OpenDash no longer contains or accepts motorcycle-dashboard connection, projection, reverse-engineered protocol, hardware-control, or dashboard-wallpaper playback implementations. The project is an independent phone application focused on route preview, vehicle management, maintenance, garage, expenses, ride history, themes, and downloadable phone wallpapers.

# OpenDash

OpenDash is an open-source Android app for motorcycle ownership, trip prep, and ride-adjacent tools. The project is moving forward without dash connection, projection, reverse-engineered protocol, or proprietary integration code.

The new direction is simple: keep the useful rider tools, make the app clean and independent, and rebuild only around original app-only features.

## Current Focus

- Route preview from shared map links or `geo:` links.
- Vehicle profiles with odometer, PUC, insurance, and service details.
- Garage and maintenance tracking for parts, service intervals, and service history.
- Expense tracking for fuel, repairs, accessories, riding gear, food, stays, transport, and other ownership costs.
- Downloadable wallpaper pack in Settings.
- Material 3 UI themes.
- Local-first storage, with optional bring-your-own Firebase/Google sync where configured.

## Removed Direction

OpenDash does not include or accept contributions for:

- Dash pairing or connection flows.
- Dash projection, video streaming, media/call cards, or hardware control.
- Reverse-engineered dash protocol/session/auth code.
- Dash wallpaper upload or playback features.
- Documentation or troubleshooting for connection behavior in older builds.

## Install

1. Open the [OpenDash Releases page](https://github.com/subtlesayak/open-dash/releases).
2. Download the latest APK for your device. The universal APK works on most phones; ABI-specific APKs are smaller if you know your device architecture.
3. Allow installation from your browser or file manager.
4. Install or update OpenDash.

## First Use

1. Open OpenDash.
2. Add your motorcycle in **Vehicles**.
3. Add odometer, PUC, insurance, and service details.
4. Log fuel, maintenance, and ownership costs in **Garage** and **Expenses**.
5. Share a destination or `geo:` link into OpenDash to preview a route.
6. Use **More** for account, sync, appearance, map provider, and wallpaper downloads.

## Main Tabs

| Tab | What it does |
| --- | --- |
| Vehicles | Add/edit vehicles and choose the active vehicle |
| Expenses | Add, filter, review, and export expenses |
| Garage | Odometer, mileage, spare parts, and service logging |
| More | Account, sync, themes, navigation provider, units, help, and wallpaper downloads |

Route preview opens from shared destinations and saved locations instead of being a permanent bottom tab.

## Build From Source

```bash
git clone https://github.com/subtlesayak/open-dash.git
cd open-dash
./gradlew :app:assembleLocalDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleLocalDebug
```

Run local unit tests:

```bash
./gradlew :app:testLocalDebugUnitTest
```

Release signing uses your own keystore through Gradle properties or CI secrets. Never commit keys, APKs, logs, `local.properties`, `key.properties`, `google-services.json`, keystores, tokens, or other private files.

## Release Variants

- `localRelease` builds APKs for GitHub releases with application id `com.opendash.app`.
- `playRelease` builds the Google Play app bundle with application id `com.subtlesayak.opendash`.
- Mapbox routing is the primary route-preview provider when configured for release builds.

## Privacy

- App data is local-first.
- Expense exports are created locally and shared only when you choose to share them.
- Firebase/Google sync is optional and bring-your-own-project.
- Release builds should avoid logging full URLs, coordinates, account IDs, or device identifiers.
- The app should not collect dash credentials or connect to motorcycle dash hardware going forward.

## Contributing

Issues and pull requests are welcome for the app-only direction: route preview, vehicles, garage, maintenance, expenses, sync, themes, and downloadable wallpapers.

Please remove personal data from logs and screenshots before sharing: coordinates, SSIDs, account IDs, tokens, and device identifiers.

## License

OpenDash is distributed under the terms in [`LICENSE`](LICENSE).

Historical source attribution required by applicable open-source licenses is
preserved in [`NOTICE`](NOTICE).
