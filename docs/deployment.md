# Setup, verification, and deployment

## Firebase project

The configured Firebase project is `traceguard-1a8c2`. It uses the Spark plan, Firestore in Johannesburg (`africa-south1`), and Email/Password Authentication. The Firebase Android app uses package `com.traceguard.antitheft`; the web app is named TraceGuard Dashboard.

The dashboard is live at <https://traceguard-1a8c2.web.app>. Firestore rules and indexes are deployed to the project's default database.

The Android app requires `android/app/google-services.json` downloaded from Firebase Project settings. The web app reads Firebase configuration from `website/.env.local`; use `.env.example` as the template. Both files contain public client configuration, are environment-specific, and are ignored by Git. Never put service-account credentials in the client or repository.

## Local verification

```powershell
cd website
npm ci
npm run build
npm run test:rules

cd ..\android
.\gradlew.bat :app:assembleDebug
```

Install the Firebase CLI and Java 17+ before `npm run test:rules`; Firebase CLI runs the Firestore Emulator locally. The Android debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Before installation, confirm `google-services.json` belongs to project `traceguard-1a8c2` and package `com.traceguard.antitheft`.

Run Firestore Rules tests against the Firebase Emulator Suite before a production release. Test unauthenticated access, cross-account reads and writes, owner device registration, valid and invalid locations, and writes after device revocation. Never use production location records as test data.

## Firebase Hosting

1. Build the dashboard with `npm run build` in `website/`.
2. Confirm the CLI is targeting `traceguard-1a8c2` using `firebase use`.
3. Deploy Firestore rules and indexes first: `firebase deploy --only firestore:rules,firestore:indexes`.
4. Deploy the dashboard: `firebase deploy --only hosting`.
5. Open the Hosting URL and verify account creation, sign-in, password reset, owner-only device visibility, and data deletion.

The Hosting config serves `website/dist` and rewrites client routes to `index.html`. Keep Authentication's authorized domains limited to the Firebase Hosting domain and any explicitly approved custom domain.

## Android release verification

Test on the actual target phone and document its Android version and model. Confirm consent, location permission, background permission behavior, notification display, pause and sign-out behavior, offline queue limits, synchronization, Android force-stop and reboot handling, and removal of cloud data. Background location and periodic work are controlled by Android and may be delayed or stopped by the operating system.

Create a release-signed APK or Play release only after the target-device checks pass. Keep the signing key outside the repository and back it up securely.
