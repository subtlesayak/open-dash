# Google Sign-In And Cloud Sync

OpenDash cloud sync is optional and bring-your-own-Firebase-project. Without Firebase
configuration, the app stays local-only and the APK remains buildable.

## What Syncs

- Google sign-in state through Firebase Authentication.
- Vehicles and active vehicle selection.
- Rides, expenses, fuel fill-ups, maintenance items, odometer state, and saved destinations.
- Vehicle-specific dash Wi-Fi credentials.
- Dash wallpaper metadata: active slot, crop, fit, kind, and RPM-arc preservation.
- Crashlytics user association by Firebase UID when Crashlytics is enabled.

Wallpaper image/GIF/video files are not uploaded yet. The current implementation syncs
wallpaper settings only; media backup needs Firebase Storage or another object store.

## Firebase Console Setup

1. Create a Firebase project.
2. Add an Android app for the exact package you want to test.
   - `mapboxTestDebug`: `com.opendash.mapboxtest.mui3`
   - `localDebug`: `com.opendash.app.mui3`
   - Play release: `com.subtlesayak.opendash`
3. Add the SHA-1 and SHA-256 fingerprints for the signing key used by that variant.
4. Download `google-services.json` and place it at:

```text
app/google-services.json
```

5. In Firebase Authentication, enable Google as a sign-in provider.
6. Copy the Web client ID from Google Cloud Console or Firebase Authentication settings.
7. Add it to `local.properties`:

```properties
GOOGLE_WEB_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
```

8. Enable Cloud Firestore.
9. Optional: enable Crashlytics for builds where `CRASHLYTICS_ENABLED` is true.

## Firestore Shape

OpenDash writes per-user data under:

```text
users/{uid}/fuel/{sid}
users/{uid}/maintenance/{sid}
users/{uid}/expenses/{sid}
users/{uid}/saved/{sid}
users/{uid}/rides/{sid}
users/{uid}/state/bike-{vehicleId}
users/{uid}/settings/vehicles
users/{uid}/settings/dash
users/{uid}/settings/wallpaper
```

Suggested starter Firestore rules:

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

## Build

Mapbox test APK:

```powershell
.\gradlew.bat assembleMapboxTestDebug
```

Play release bundle:

```powershell
.\gradlew.bat bundlePlayRelease
```

The Google Services plugin is applied only when `google-services.json` contains a client
matching the package being built. If the file is missing or for another package, OpenDash
builds local-only.

## Warnings

- Firebase/Firestore can incur costs. Keep test usage small and review Firebase quotas.
- Dash Wi-Fi credentials are stored in Firestore under the signed-in user's document.
  Firestore rules must restrict access to the authenticated user.
- Do not commit `google-services.json`, `local.properties`, API keys, tokens, or release keys.
- Sync is last-write-wins. Avoid editing the same vehicle or expense from two phones at
  exactly the same time.

Official setup references:

- Firebase Android setup: https://firebase.google.com/docs/android/setup
- Firebase Google sign-in: https://firebase.google.com/docs/auth/android/google-signin
- Android Credential Manager with Google ID: https://developer.android.com/identity/sign-in/credential-manager-siwg
