# Android app module

Planned application ID: `com.traceguard.antitheft`.

Package boundaries under `app/src/main/java/com/traceguard/antitheft/`:

- `setup/` — owner consent, onboarding, and permission education
- `auth/` — Firebase sign-in and device enrollment
- `location/` — location acquisition and report model
- `tracking/` — WorkManager and optional foreground service
- `battery/`, `network/` — permitted device status collection
- `database/` — Room entities, DAO, and database
- `sync/` — offline queue and upload coordination
- `receiver/` — documented boot recovery entry point, if eligible

This is a Phase 1 module outline; application code and Gradle build files are added in their implementation phases. The app must remain visible in Android Settings and must use required foreground-service notifications and Android privacy indicators.
