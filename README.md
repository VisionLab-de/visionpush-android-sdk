# VisionPush Android SDK

Official Android SDK for VisionPush — Kotlin-first, coroutine-based, with full Firebase Cloud Messaging support, automatic token rotation handling, and WorkManager-backed event delivery.

## Features

- **Kotlin Coroutines** — all async operations are suspending functions with Java-compatible callback overloads
- **FCM integration** — automatic token registration and rotation with WorkManager retry
- **Rich notifications** — BigPicture, BigText, large icon, up to 3 action buttons, deep-link routing
- **Notification channels** — register custom channels; a default channel is always created automatically
- **Event tracking** — opened, action tapped, and dismissed events delivered via WorkManager with exponential back-off
- **Opt-in / opt-out** — per-device preference persisted locally and synced to the backend
- **User identification** — associate devices with your own external user ID
- **Java-compatible** — every public API is annotated `@JvmStatic` / `@JvmOverloads`
- **Zero reflection** — lightweight JSON serialisation with `org.json` only

---

## Requirements

| Requirement | Minimum |
|---|---|
| Android API level | 21 (Android 5.0 Lollipop) |
| `compileSdk` | 34 |
| Kotlin | 1.9 |
| Firebase BoM | 32.7.4 |
| WorkManager | 2.9.0 |

---

## Installation

### 1. Add the dependency

The SDK is published to the VisionPush Maven repository. Add the repository and the dependency to your project:

**`settings.gradle.kts`** (or project-level `build.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.visionpush.de/releases") }
    }
}
```

**App module `build.gradle.kts`**:

```kotlin
dependencies {
    implementation("de.visionlab:visionpush-android:1.0.0")
}
```

### 2. Firebase Cloud Messaging

VisionPush uses FCM for push delivery. If you haven't set up FCM yet:

1. Create a project in the [Firebase Console](https://console.firebase.google.com/).
2. Register your Android app and download `google-services.json`.
3. Place `google-services.json` in your **app module** directory.
4. Apply the Google Services plugin in your build files:

**Project-level `build.gradle.kts`**:

```kotlin
plugins {
    id("com.google.gms.google-services") version "4.4.1" apply false
}
```

**App module `build.gradle.kts`**:

```kotlin
plugins {
    id("com.google.gms.google-services")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
```

### 3. Permissions

The SDK manifest merges the required permissions automatically:

| Permission | Reason |
|---|---|
| `INTERNET` | API communication |
| `ACCESS_NETWORK_STATE` | WorkManager network constraint |
| `POST_NOTIFICATIONS` | Runtime permission (API 33+); requested via `VisionPush.requestPermission()` |
| `RECEIVE_BOOT_COMPLETED` | WorkManager boot-completed retry |
| `VIBRATE` | Notification vibration |

No manual `AndroidManifest.xml` edits are required for standard usage.

---

## Quick Start

```kotlin
// Application.kt
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VisionPush.initialize(
            context = this,
            appId   = "your-app-uuid",
            apiKey  = "vp_live_xxxxxxxxxxxxxxxxxxxxxxxx"
        )
    }
}

// MainActivity.kt
VisionPush.setNotificationOpenHandler { notification ->
    notification.deepLink?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
}
```

---

## Initialization

Call `VisionPush.initialize()` exactly once in your `Application.onCreate()`. Subsequent calls update the configuration and re-register the device.

```kotlin
VisionPush.initialize(
    context    = this,
    appId      = "your-app-uuid",
    apiKey     = "vp_live_xxxxxxxxxxxxxxxxxxxxxxxx",
    logLevel   = VisionPushConfig.LogLevel.WARN,   // optional; default is WARN
    apiBaseUrl = "https://api.visionpush.io/v1"    // optional; override for staging
)
```

For full control over all options, use the `VisionPushConfig` overload:

```kotlin
val config = VisionPushConfig(
    appId      = "your-app-uuid",
    apiKey     = "vp_live_xxx",
    apiBaseUrl = "https://api.visionpush.io/v1",
    logLevel   = VisionPushConfig.LogLevel.DEBUG
)
VisionPush.initializeWithConfig(context = this, config = config)
```

### `VisionPushConfig`

| Property | Type | Default | Description |
|---|---|---|---|
| `appId` | `String` | **required** | UUID from the VisionPush dashboard |
| `apiKey` | `String` | **required** | API key with prefix `vp_live_` or `vp_test_` |
| `apiBaseUrl` | `String` | `https://api.visionpush.io/v1` | Override for staging / self-hosted |
| `logLevel` | `LogLevel` | `WARN` | Console verbosity: `NONE`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `VERBOSE` |

---

## FCM Token Registration

The SDK includes `VisionPushMessagingService`, which is declared in the SDK manifest and registered automatically. It handles both token refresh events and incoming messages.

If you already extend `FirebaseMessagingService`, subclass `VisionPushMessagingService` instead:

```kotlin
class MyMessagingService : VisionPushMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)        // VisionPush re-registers automatically
        // … your own token handling …
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)   // VisionPush processes vp_payload
        // … additional handling for non-VisionPush messages …
    }
}
```

Then point the manifest `<service>` entry at your subclass:

```xml
<!-- app/src/main/AndroidManifest.xml -->
<service
    android:name=".MyMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

---

## Notification Channels

Android 8.0 (API 26) requires that every notification is posted to a named channel. The SDK creates a default channel (`visionpush_default`) automatically during `initialize()`. You can register additional channels that match the channel IDs configured in the [VisionPush admin panel](http://localhost:4300) under **Notification Channels**:

```kotlin
import android.app.NotificationManager
import de.visionlab.visionpush.VisionPush
import de.visionlab.visionpush.VisionPushChannel

VisionPush.registerNotificationChannels(
    VisionPushChannel(
        channelId   = "promotions",
        name        = "Promotions",
        description = "Special offers and discounts",
        importance  = NotificationManager.IMPORTANCE_DEFAULT
    ),
    VisionPushChannel(
        channelId   = "order_updates",
        name        = "Order Updates",
        description = "Real-time status for your orders",
        importance  = NotificationManager.IMPORTANCE_HIGH,
        enableVibration = true,
        enableLights    = true,
        lightColor      = android.graphics.Color.GREEN
    )
)
```

Call `registerNotificationChannels()` **before** the first notification that references those channel IDs is delivered. A safe place is `Application.onCreate()`, right after `initialize()`.

### `VisionPushChannel`

| Property | Type | Default | Description |
|---|---|---|---|
| `channelId` | `String` | **required** | Must match the channel ID set in the admin panel |
| `name` | `String` | **required** | Human-readable name shown in system settings |
| `description` | `String` | `""` | Optional description in system settings |
| `importance` | `Int` | `IMPORTANCE_DEFAULT` | `NotificationManager` importance constant |
| `enableVibration` | `Boolean` | `true` | Whether to vibrate |
| `enableLights` | `Boolean` | `true` | Whether to use the notification LED |
| `lightColor` | `Int` | `Color.BLUE` | ARGB LED color |

---

## Requesting Notification Permission (Android 13+)

On Android 13 (API 33) and above the `POST_NOTIFICATIONS` permission must be requested at runtime. On older API levels this is a no-op.

### Activity Result API (preferred)

```kotlin
class MainActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        VisionPush.handlePermissionResult(this, granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show permission rationale first, then:
        VisionPush.requestPermissionWithLauncher(permissionLauncher)
    }
}
```

### Legacy `requestPermissions` API

```kotlin
// In your Activity
VisionPush.requestPermission(this)

override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    VisionPush.handlePermissionResult(this, requestCode, permissions, grantResults)
}
```

When permission is granted the SDK automatically forces a device registration sync to ensure the backend records the updated notification-enabled state.

---

## Handling Notifications

### Foreground Messages

VisionPush sends **data-only** FCM messages (no `notification` block). This means `VisionPushMessagingService.onMessageReceived()` is always called regardless of foreground/background state.

When `VisionPushNotification.showSystemNotification` is `true` (the default), the SDK builds and posts a rich system notification automatically. Set it to `false` in your campaign settings to deliver silently and handle display yourself:

```kotlin
VisionPush.setNotificationHandler { notification ->
    // Called on the main thread for every received VisionPush notification
    // Build and show your own in-app UI here
    showInAppBanner(notification.title, notification.body)
}
```

Setting a custom handler does **not** suppress the system notification unless `showSystemNotification` is `false` in the payload.

### Background Messages

Background data messages are handled automatically by `VisionPushMessagingService`. The service is woken by FCM, builds the system notification, and posts it — no additional code is required.

### Notification Tap

```kotlin
VisionPush.setNotificationOpenHandler { notification ->
    // Called on the main thread when the user taps a notification
    // or an action button in the notification shade
    notification.deepLink?.let { uri ->
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    }
    notification.customData["screen"]?.let { screen ->
        navigateTo(screen)
    }
}
```

### `VisionPushNotification` fields

| Property | Type | Description |
|---|---|---|
| `id` | `String` | Unique server-assigned notification UUID |
| `title` | `String` | Primary title line |
| `body` | `String` | Body text |
| `imageUrl` | `String?` | URL of large image (BigPicture style when present) |
| `smallIconName` | `String?` | Drawable resource name for the status-bar icon |
| `largeIconUrl` | `String?` | URL of the round large icon |
| `deepLink` | `String?` | URI opened when the notification or an action is tapped |
| `channelId` | `String` | Notification channel ID (defaults to `visionpush_default`) |
| `actions` | `List<NotificationAction>` | Up to 3 action buttons |
| `customData` | `Map<String, String>` | Arbitrary key-value pairs from the campaign payload |
| `sentAt` | `String?` | ISO 8601 server send timestamp |
| `showSystemNotification` | `Boolean` | `true` to post a system notification (default), `false` for silent delivery |

---

## Identifying Users

Associate the VisionPush device record with a user in your own system:

```swift
// After sign-in
VisionPush.setExternalId("usr_abc123")

// After sign-out
VisionPush.setExternalId(null)
// or use logout() to fully unregister the device
```

The SDK persists the external ID across process restarts and includes it in every device registration payload. Setting the same value again is idempotent.

```kotlin
// Read back the current value
val userId: String? = VisionPush.externalId
```

---

## Opt-In / Opt-Out

```kotlin
// Suppress future notifications (preference persisted locally and synced to backend)
VisionPush.optOut { result ->
    when (result) {
        is VisionPushResult.Success -> println("Opted out")
        is VisionPushResult.Failure -> println("Error: ${result.error.message}")
    }
}

// Re-enable notifications
VisionPush.optIn()

// Check current state
val optedIn: Boolean = VisionPush.isOptedIn
```

The callbacks are optional. Kotlin coroutine callers can use `VisionPush.optOut()` / `VisionPush.optIn()` without a lambda in a coroutine context where the result is not needed.

---

## User Logout

Call `logout()` when the user signs out to remove the device record from the backend and clear all locally persisted state:

```kotlin
VisionPush.logout { result ->
    result.onSuccess {
        // Handlers are also cleared automatically
        // Call initialize() or setExternalId() for the next user
    }
}
```

After `logout()`, the SDK remains initialized — call `VisionPush.setExternalId()` with the new user's ID or call `VisionPush.syncDevice()` to register a fresh anonymous device.

---

## Device Sync

Force an immediate re-registration with the backend (useful after restoring a backup or on a state inconsistency):

```kotlin
VisionPush.syncDevice { result ->
    result
        .onSuccess { deviceId -> println("Synced: $deviceId") }
        .onFailure { error -> println("Sync failed: ${error.message}") }
}
```

---

## Error Handling

The SDK uses a sealed `VisionPushError` hierarchy and wraps all async results in `VisionPushResult<T>`:

```kotlin
VisionPush.optOut { result ->
    when (result) {
        is VisionPushResult.Success -> { /* ok */ }
        is VisionPushResult.Failure -> when (val err = result.error) {
            is VisionPushError.NotInitialized  -> showSetupError()
            is VisionPushError.NetworkError    -> scheduleRetry(err.cause)
            is VisionPushError.ApiError        -> log("HTTP ${err.httpStatus}: ${err.message}")
            is VisionPushError.PermissionDenied -> promptUser()
            is VisionPushError.TokenUnavailable -> { /* FCM token not yet available; auto-retried */ }
            is VisionPushError.InvalidConfig   -> log(err.message)
            is VisionPushError.Unknown         -> log(err.cause)
        }
    }
}
```

**Error types:**

| Class | Description |
|---|---|
| `VisionPushError.NotInitialized` | Method called before `VisionPush.initialize()` |
| `VisionPushError.NetworkError` | Network-level `IOException` |
| `VisionPushError.ApiError` | Server returned a non-2xx response; exposes `httpStatus: Int` and `message: String` |
| `VisionPushError.PermissionDenied` | `POST_NOTIFICATIONS` denied or disabled in settings |
| `VisionPushError.TokenUnavailable` | FCM token not yet available (transient; auto-retried on next `onNewToken`) |
| `VisionPushError.InvalidConfig` | Blank `appId` or malformed `apiKey` |
| `VisionPushError.Unknown` | Unexpected exception; original cause available via `cause` |

### `VisionPushResult<T>`

| Member | Description |
|---|---|
| `isSuccess: Boolean` | `true` for `Success` |
| `isFailure: Boolean` | `true` for `Failure` |
| `getOrNull(): T?` | Data value or `null` |
| `errorOrNull(): VisionPushError?` | Error or `null` |
| `map { }` | Transform the data value |
| `onSuccess { }` | Execute block on success, return `this` for chaining |
| `onFailure { }` | Execute block on failure, return `this` for chaining |

---

## API Reference

All methods are on the `VisionPush` singleton object.

### Initialization

| Method | Signature | Description |
|---|---|---|
| `initialize` | `(context, appId, apiKey, logLevel?, apiBaseUrl?) Unit` | Initialize the SDK — call once in `Application.onCreate()` |
| `initializeWithConfig` | `(context, config: VisionPushConfig) Unit` | Initialize with a pre-built config object |

### Permission

| Method | Signature | Description |
|---|---|---|
| `requestPermission` | `(activity: Activity) Unit` | Shows the `POST_NOTIFICATIONS` dialog (API 33+); no-op on older versions |
| `requestPermissionWithLauncher` | `(launcher: ActivityResultLauncher<String>) Unit` | Permission request via Activity Result API |
| `handlePermissionResult` | `(context, requestCode, permissions, grantResults) Unit` | Forward from `onRequestPermissionsResult` |
| `handlePermissionResult` | `(context, granted: Boolean) Unit` | Forward from Activity Result API callback |

### User Identification

| Method | Signature | Description |
|---|---|---|
| `setExternalId` | `(externalId: String?) Unit` | Associate device with an external user ID; `null` clears the association |
| `externalId` | `String?` | The current external ID, or `null` if not set |

### Notification Handlers

| Method | Signature | Description |
|---|---|---|
| `setNotificationHandler` | `(handler: ((VisionPushNotification) -> Unit)?) Unit` | Callback invoked on the main thread when a notification is received (foreground and data messages) |
| `setNotificationOpenHandler` | `(handler: ((VisionPushNotification) -> Unit)?) Unit` | Callback invoked on the main thread when the user taps a notification or action button |

### Notification Channels

| Method | Signature | Description |
|---|---|---|
| `registerNotificationChannels` | `(vararg channels: VisionPushChannel) Unit` | Create or update notification channels; must be called before the first notification using that channel |

### Opt-In / Opt-Out

| Method | Signature | Description |
|---|---|---|
| `optOut` | `(callback: ((VisionPushResult<Unit>) -> Unit)? = null) Unit` | Opt the user out of push notifications |
| `optIn` | `(callback: ((VisionPushResult<Unit>) -> Unit)? = null) Unit` | Re-enable push notifications |
| `isOptedIn` | `Boolean` | `true` when the user is opted in to push notifications |

### Device

| Method | Signature | Description |
|---|---|---|
| `syncDevice` | `(callback: ((VisionPushResult<String>) -> Unit)? = null) Unit` | Force an immediate registration sync |
| `logout` | `(callback: ((VisionPushResult<Unit>) -> Unit)? = null) Unit` | Unregister the device and clear all local state |
| `deviceId` | `String?` | The server-assigned device ID, or `null` before first registration |
| `isInitialized` | `Boolean` | `true` after a successful `initialize()` call |

---

## Architecture

```
VisionPush (object)            ← Public singleton facade; all app code goes here
  ├── ApiClient                ← OkHttp wrapper; authentication, logging, retry via WorkManager
  ├── DeviceRegistrar          ← FCM token fetch, registration deduplication, SharedPreferences
  ├── NotificationBuilder      ← Builds rich system notifications; fetches remote bitmaps on IO
  ├── VisionPushMessagingService ← FirebaseMessagingService; token rotation + message dispatch
  └── NotificationTracker      ← BroadcastReceiver + WorkManager worker for event API calls
```

Event delivery (opens, action taps, dismissals) is handled entirely through WorkManager with a `NetworkType.CONNECTED` constraint and exponential back-off starting at 15 seconds. This means events survive process death and are delivered reliably when the device next connects to the internet.

---

## SDK Version

```kotlin
val version: String = VisionPush.SDK_VERSION  // e.g. "1.0.0"
```

---

## License

MIT — Copyright (c) 2025 VisionPush
