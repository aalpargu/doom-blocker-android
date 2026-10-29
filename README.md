# Doom Blocker

An Android application I use to put a deliberate limit on distracting apps. Each protected app can have a daily allowance, a session limit and a mandatory break. Focus blocks and scheduled rules add stricter boundaries when needed.

[Türkçe](README.tr.md)

The app runs locally without an account. Optional Supabase synchronization shares daily allowances and active breaks between a phone and tablet.

## How it behaves

- Counts active use of a protected app; time spent on the lock screen or in another app does not consume its session allowance.
- Offers an optional intention screen with a short usage window before opening an app.
- Shows a live break countdown after the selected session ends.
- Supports scheduled blocks, shared limits for groups of apps and a focus mode that can be extended.
- Applies stricter rules immediately and delays changes that would weaken a limit.
- Keeps usage history, local notifications, a home-screen widget and Quick Settings shortcuts.
- Preserves counters and active breaks across supported process/restart recovery paths.

Android Usage Access provides the usage events. An accessibility service observes app transitions and performs the blocking action. These permissions must be enabled by the user, and device battery management can affect the service. This is a personal self-management tool, not a device-administration system.

## Code structure

`core/` contains the Kotlin rule engine: daily/session limits, planned access, focus rules and restore behavior. It can be tested independently of Android UI code.

`app/` contains the Jetpack Compose interface, accessibility service, usage-history reader, persistence, notifications and device synchronization. `supabase/` contains the SQL migration and a separate synchronization test harness.

The local store uses atomic writes and recovery copies. Synced usage is tracked per device/day so repeated updates do not add the same time twice. Network delay still permits a small timing difference between simultaneously used devices.

## Build and test

Requirements: JDK 17, Android SDK Platform 36 and Build Tools. The included Gradle wrapper uses the project's pinned Android/Kotlin versions. Minimum Android version is 14 (API 34).

```bash
./gradlew :core:test
./gradlew :app:testProductionDebugUnitTest :app:lintProductionDebug
./gradlew :app:assembleProductionDebug
```

On Windows, use `gradlew.bat`. The `production` flavor uses real minutes; `lab` is a separate application with shorter time units for development.

An APK built with a different signing key cannot update an existing installation. Keep personal signing keys outside the repository. The build supports `DOOM_BLOCKER_KEYSTORE`, `DOOM_BLOCKER_STORE_PASSWORD`, `DOOM_BLOCKER_KEY_ALIAS` and `DOOM_BLOCKER_KEY_PASSWORD` when an existing key is needed.

## Optional device synchronization

Copy `sync.properties.example` to the ignored `sync.properties`, then supply your own Supabase URL and publishable key. Apply the migration under `supabase/migrations/` in your own project. Administrative keys do not belong in the Android build.

Daily totals and active breaks can be shared; schedules, local history and several rule preferences remain device-specific. Actual two-device behavior, permission revocation and vendor battery restrictions need device testing in addition to unit tests.

## Status

This source snapshot is the personal 3.1.3 application. It includes ongoing maintenance and device-specific limitations. The automated tests in the repository cover rule and recovery behavior; they do not establish uninterrupted enforcement on every Android device.
