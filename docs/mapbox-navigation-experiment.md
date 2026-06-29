# Mapbox Navigation Experiment

This branch adds an experimental Mapbox-backed navigation provider behind a feature flag. It does not replace the existing Open Dash routing, dash Wi-Fi/Bluetooth session, RTP projection, or dash packet protocol.

## Tokens

Copy `local.defaults.properties` to your gitignored `local.properties`, then fill in:

```properties
USE_MAPBOX_NAVIGATION_EXPERIMENTAL=true
MAPBOX_DOWNLOADS_TOKEN=your_secret_downloads_token
MAPBOX_ACCESS_TOKEN=your_public_access_token
```

`MAPBOX_DOWNLOADS_TOKEN` is used by Gradle to resolve Mapbox artifacts from the Mapbox Maven repository. `MAPBOX_ACCESS_TOKEN` is exposed to debug builds through `BuildConfig.MAPBOX_ACCESS_TOKEN` and is used by the experimental provider to request routes.

Never commit real tokens.

## Build

Normal Open Dash builds keep the experiment disabled:

```bash
./gradlew assembleLocalDebug
```

To compile with Mapbox dependencies enabled:

```bash
./gradlew assembleLocalDebug -PUSE_MAPBOX_NAVIGATION_EXPERIMENTAL=true -PMAPBOX_DOWNLOADS_TOKEN=... -PMAPBOX_ACCESS_TOKEN=...
```

## What It Does

- Adds `NavigationProvider`, `DashRoute`, `DashManeuver`, and `NavigationProgress` as SDK-neutral Open Dash types.
- Adds `MapboxNavigationProvider` as a separate experimental provider.
- Converts provider progress into the existing dash navigation packet update path through `DashNavigationPacketAdapter`.
- Keeps the existing connection/auth/projection protocol untouched.

## Known Limitations

- The provider is experimental and not wired as the default app navigation path.
- Off-route detection is surfaced in the data model, but the fallback route-progress loop currently reports `false` until the full Mapbox trip-session observer is enabled.
- Dash maneuver icon codes remain conservative: only the existing verified continue glyph is sent to hardware.
- Mapbox dependencies are only included when `USE_MAPBOX_NAVIGATION_EXPERIMENTAL=true`.

## Pricing Warning

Mapbox APIs and SDKs can incur usage-based costs. Review your Mapbox account limits, billing settings, and current Mapbox pricing before enabling this provider on a real ride.

## Safety Warning

This is experimental navigation software. Do not rely on it as the sole source of riding directions. Always follow road signs, traffic laws, and safe riding judgment.
