# TraceGuard

An Android device-recovery prototype with a React dashboard, built for a device owner's own phone with informed consent and Android permissions.

**[Open the live dashboard](https://traceguard-1a8c2.web.app)**

## Download the project

**[Download the complete source code](traceguard-source.zip)** and extract it before following the setup instructions. The archive contains 54 project files with their original folder structure. Source files are currently distributed as this archive; they are not yet available as individual files in this repository.

## Technology

- Android: Kotlin, Android SDK, Room, WorkManager, Gradle
- Web: React, TypeScript, Vite
- Backend: Firebase Authentication, Cloud Firestore, Firebase Hosting

## Included in the source archive

- `android/`: Android application and Gradle wrapper
- `website/`: Web dashboard, dependencies manifest, and Firestore rules tests
- `firebase/`: Firestore security rules and indexes
- `docs/`: Architecture, permissions, deployment instructions, and roadmap
- `README.md`: Development prerequisites and build instructions
- `.env.example`: Firebase web configuration template

Local credentials, environment configuration, generated builds, APKs, temporary files, and personal documents are excluded. Supply your own Firebase configuration as described in the included documentation.

## Project status

The dashboard is deployed. The Android client is a prototype; validate permission handling, background scheduling, offline behavior, and account deletion on the target device before relying on it for recovery. Reports depend on Android scheduling, connectivity, permissions, and battery policy.
