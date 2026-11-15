# Development roadmap

## Implemented

- Firebase project `traceguard-1a8c2`, Email/Password Authentication, Firestore in Johannesburg, Android and Web client registrations, and owner-scoped Firestore rules.
- Kotlin Android onboarding with consent, Firebase sign-in/registration, permission requests, local Room queue, best-effort WorkManager reports, optional visible foreground tracking, and retrying sync.
- React dashboard with sign-in, device state, latest-location map, report history, account settings, and device revoke/delete controls.
- Firestore rules emulator tests covering unauthenticated access, account isolation, valid registration and reports, invalid coordinates, and revoked devices.
- Production web build and Android debug APK build.
- Firestore rules/indexes and the dashboard deployed to Firebase Hosting at <https://traceguard-1a8c2.web.app>; the live sign-in page was verified.

## Remaining before release

- Verify the Android app on the intended phone and Android release, including permission sequencing, background behavior, offline queueing, reboot, force-stop, and battery restrictions.
- Complete owner-account acceptance tests on the deployed dashboard and confirm deletion/revocation behavior against test data.
- Configure production authentication domains and confirm Firebase plan and quota expectations before inviting users.
- Create a release-signed Android package only after device verification. Keep signing material outside the repository.

Do not treat a successful debug build or emulator rules run as proof of reliable lost-device recovery. Android scheduling, permissions, connectivity, and hardware behavior can delay or prevent reports.
