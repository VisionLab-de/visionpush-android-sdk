# Module VisionPush Android SDK

Add push notifications to your Android app with a single SDK.

## Overview

The VisionPush Android SDK handles device registration, FCM integration,
in-app messages, and notification lifecycle tracking. All communication uses
the VisionPush REST API.

### Quick start

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VisionPush.configure(this, appId = "YOUR_APP_ID", apiKey = "vp_live_…")
    }
}
```

### Request permission and subscribe

```kotlin
lifecycleScope.launch {
    val result = VisionPush.shared.requestPermission(this@MainActivity)
    if (result.status == PermissionStatus.GRANTED) {
        Log.d("VP", "Subscriber ID: ${VisionPush.shared.subscriberId}")
    }
}
```

# Package de.visionlab.visionpush

Main entry point and public API of the VisionPush Android SDK.
