package de.visionlab.visionpush.demo

import android.app.Application
import de.visionlab.visionpush.VisionPush
import de.visionlab.visionpush.VisionPushConfig

class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        VisionPush.initialize(
            context = this,
            config  = VisionPushConfig(
                appId   = "YOUR_APP_ID",
                apiKey  = "vp_live_YOUR_KEY",
                logLevel = VisionPushConfig.LogLevel.DEBUG
            )
        )

        VisionPush.setNotificationHandler { notification ->
            android.util.Log.d("VisionPush", "Received: ${notification.title}")
        }

        VisionPush.setNotificationOpenHandler { notification ->
            android.util.Log.d("VisionPush", "Opened: ${notification.deepLink}")
        }

        VisionPush.onError = { error ->
            android.util.Log.e("VisionPush", "Error: ${error.message}")
        }
    }
}
