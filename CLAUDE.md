# OpenDash contributor guidance

OpenDash is an independent Android motorcycle companion focused exclusively on
app-only features: route preview, vehicle profiles, garage and maintenance,
expenses, ride history, themes, downloadable phone wallpapers, and optional
bring-your-own cloud sync.

## Hard boundary

Do not add, restore, research, document, or accept contributions for motorcycle
dashboard pairing, authentication, Wi-Fi discovery, projection, video streaming,
hardware control, dashboard wallpaper playback, or reverse-engineered protocols.

Do not collect or store motorcycle-dashboard credentials. Pull requests and
issues proposing those capabilities must be closed as out of scope.

## Engineering direction

- Keep application data local-first.
- Treat Firebase and Google sign-in as optional bring-your-own configuration.
- Use supported map and routing APIs under their published terms.
- Preserve route preview as a phone application feature.
- Keep vehicle and maintenance data isolated per user-created vehicle.
- Never commit credentials, signing material, private logs, or user data.

## Verification

Run the local unit tests and build the affected Android variant before release.
Review release artifacts to ensure prohibited dashboard integration code and
credentials are absent.
