# Changelog

All notable changes to the VisionPush Android SDK.

## 1.1.0

Additive release: everything in `1.0.0` keeps working; new APIs land alongside it.
The Flutter, React Native, Huawei and Unity plugins require `1.1.0` or later —
`1.0.0` predates geofencing and `adoptInstallationId`, so those plugins fail to
compile against it.

### Added
- Geofencing: `startGeofencing` / `stopGeofencing` and the geofence transport
  wiring (`7bf7877d`), plus a HMS-side hardening fix so a misconfigured geofence
  push could not be forged or wipe an unrelated audience (`949c8a13`).
- `adoptInstallationId` — a public seed API for migrating an existing device's
  installation identity, added for the Unity SDK (SQ-246, `ade0af04`).
- Device binding: a client key now proves which physical device it is
  (`6114950b`).
- Identity verification and Live Activity completion on the shared
  iOS/Android transport contract (`8343197b`).

### Fixed
- `RetryBackoff`: equal-jitter reconnect backoff so retries never overshoot the
  cap, down from a 1.5x drift (`6c36646b`).
- `ImageGuard`: large notification images no longer OOM-crash the app; image
  loading is consent-gated and sockets are closed deterministically (`6b5be9db`).
- `UrlSecurity`: in-app-message action URLs are now scheme-vetted the same way
  notification action URLs already were (`228e0e0b`).
- Realtime channel: every push is acked and display is deduplicated, matching
  the Phase-2 iOS/Android contract (`b6312690`).
- Four transport defects where iOS had fallen behind the Android client's
  behavior (`f2bbec93`).
- `OpenReceiver` relaunch intents now forward `vp_*` extras (`c4ac4dce`).

### Other
- Conformance-corpus test coverage for the native client (`f044a7fc`).
- Default API endpoint corrected from `api.visionpush.io` to `api.visionpush.de`
  (`3036ccf9`).
- LICENSE files added, legal entity corrected to VisionLab GmbH & Co. KG
  (`0ff3047b`).

## 1.0.0

Initial public release — JitPack + Maven Central distribution, mirrored to
`VisionLab-de/visionpush-android-sdk` (tag `1.0.0`, commit `27d92db`).
