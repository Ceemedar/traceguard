# Validation notes

Date: 15 November 2026

## Owner validation

The owner reports that the apps are working and have been fully tested. No additional device tests were requested after that confirmation.

## Checks performed during portfolio preparation

- Dashboard: `npm run build` passed (TypeScript compilation and Vite production build).
- Live dashboard: loaded successfully and displayed registered devices and recent reports. No production device settings were changed.
- Local sign-in screen: loaded successfully; the portfolio screenshot contains no account or location data.
- Firestore emulator: could not complete because the available Java runtime was below the current CLI's Java 21 requirement. This is not a passing rules-test result.
- Android build: started, then stopped after the owner's instruction that the apps were already tested. No fresh APK build result is claimed.
- Physical-device checks: no Android device was attached to ADB. Location permissions, reboot behavior and offline synchronization were not independently repeated.

The existing Firestore tests remain in `website/tests/firestore-rules.test.mjs`. Run them using `npm run test:rules` from `website/` with Firebase CLI and Java 21+ installed.
