# Changelog

All notable changes to the VisionPush Android SDK.

## 1.2.0

### Breaking
- Renamed `logout()` (unregister the device, reset the SDK) to `deleteDevice()`,
  and renamed `logoutUser()` (detach the external identity, hand the
  subscription to a new anonymous user) to `logout()` — aligning with the iOS
  SDK and OneSignal v5, where `logout()` means "the user logs out, and the
  subscription moves to a new anonymous user." Acceptable now because 1.2.0 is
  not yet published. `VisionPushKtx.logoutUserAsync()` is renamed to
  `logoutAsync()`. `setExternalId(null)` now delegates to the renamed `logout()`.

### Added
- OneSignal-style user model (US-15): `login(externalId, externalIdHash?)` and
  `logout()` attach/detach the current external identity (the subscription
  moves to a new anonymous user on logout, never silently keeping the old
  one); `getUserId()`/`userId` and `setOnUserChangedListener` observe the
  resulting user id. `addAlias(label, id, aliasHash)`/`removeAlias(label, id,
  aliasHash)` add/remove custom alias namespaces (signed the same way as
  `externalIdHash`); `addEmail(email)`/`addSms(phoneE164)` add email/SMS
  subscriptions, validated locally, with a 409 (another user already owns the
  address/alias) surfaced as a typed `VisionPushError.ApiError` via the new
  `isSubscriptionOwnedByAnotherUser`. `updateTags`/`removeTags` keep their
  signatures but now write to the user (via `PATCH /devices/{id}/user`)
  instead of the device row, so tags survive a `login()` from another
  device. `setExternalId(id, hash?)` is deprecated and delegates to
  `login`/`logout`.
- HTML in-app messages (US-16): `InAppMessage.type == "html"` now renders in a
  hardened `WebView` (`HtmlInAppMessageRenderer`) — `allowFileAccess`,
  `allowContentAccess` and `domStorageEnabled` disabled, geolocation disabled,
  loaded via `loadDataWithBaseURL(null, …)` with no base URL, and every
  navigation blocked (`shouldOverrideUrlLoading` always returns `true`; the
  only way out is the bridge). The impression is counted only after
  `onPageFinished`, not just because the WebView is attached.
- `HtmlIamBridge`: the `window.VisionPush` bridge the rendered HTML gets,
  exposing exactly 4 `@JavascriptInterface` methods — `click`, `close`,
  `addTags`, `removeTags` — all string/JSON-string parameters, strictly
  validated, marshalled onto the main thread. `click` records a click and
  opens an http(s) URL via `ACTION_VIEW` (same scheme rule as
  `UrlSecurity.safeHttpUrl`); `close` records a dismiss; the tag methods
  delegate to the existing `VisionPush.updateTags`/`removeTags`. The bridge is
  removed (`removeJavascriptInterface`) and the `WebView` destroyed when the
  message closes.
- `ApiClient.fetchInAppMessages` now sends `capabilities=html` so the backend
  only ever hands this SDK version a message type it knows how to render;
  added `trackIAMClick`/`trackIAMDismiss` for the bridge's click/dismiss
  events (same backend path the impression tracker already used).
- `consumer-rules.pro`: keeps `HtmlIamBridge`'s `@JavascriptInterface` methods
  so R8 doesn't strip the methods WebView's JS engine calls by name.
- Native in-app-message types (modal/banner/fullscreen) now report click and
  dismiss events to the backend (`trackIAMClick`/`trackIAMDismiss`), same as
  the HTML bridge already did — a native button tap counts a click (never
  also a dismiss) and a native dismiss without a prior tap counts a dismiss,
  each at most once per display (SQ-290).

### Fixed
- `HtmlIamBridge`: `click()`/`close()` tracked a click/dismiss on every call,
  so campaign HTML calling `close()` repeatedly, or `click()` followed by
  `close()`, inflated the stats. Each display now reports at most one
  terminal event (click or dismiss), guarded by an `AtomicBoolean` set before
  the tracking call so two racing bridge calls still record exactly one event;
  every further bridge call (including `addTags`/`removeTags`) is ignored once
  a terminal event has fired (SQ-288, SQ-290).
- `refreshTags()` always returned an empty map: it called `GET /devices/{id}`
  (which a `client` key cannot even read — it needs `devices:read`) and
  decoded a `{"data":{...}}` envelope that route never sent. Now calls the
  device-bound `GET /devices/{id}/user` (SQ-297) and reads `tags` as the
  top-level key the server actually returns (SQ-295).
- Error parsing in `ApiClient.execute()` assumed a top-level `{"message":...}`
  or a bare `{"error":"<code>"}`; the server always answers a non-2xx with the
  nested `{"error":{"code","message"}}` envelope, so `VisionPushError.ApiError
  .errorCode` held the raw JSON text of that object instead of the real code,
  and `isExternalIdVerificationFailed` only passed by an accidental substring
  hit on that dump. `errorCode`/`message` now read `error.code`/`error.message`
  directly; added `isIdentityErased` for the HTTP 410 `identity_erased`
  rejection, which — unlike `external_id_verification_failed` — gets its own
  dedicated code rather than the generic `"forbidden"` (SQ-295).

## 1.1.1

### Fixed
- `jitpack.yml`: the `install` task called
  `publishReleasePublicationToMavenLocal`, which doesn't exist since the
  switch to `com.vanniktech.maven.publish` (the publication is named
  `maven`). JitPack's build of the `1.1.0` tag failed with "Task
  'publishReleasePublicationToMavenLocal' not found" and cached that
  failure, so **`1.1.0` was never resolvable via JitPack** — use `1.1.1`
  instead. The task is now `./gradlew clean publishToMavenLocal -x test`.

## 1.1.0 (not published — JitPack build broken, see 1.1.1)

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
