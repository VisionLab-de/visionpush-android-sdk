# VisionPush Android SDK – Consumer ProGuard / R8 rules
#
# These rules are automatically applied to any app that depends on the SDK via
# the 'consumerProguardFiles' directive in the library's build.gradle.kts.
# The host app does NOT need to copy these rules manually.

# ── Keep all public SDK classes ──────────────────────────────────────────────
-keep public class de.visionlab.visionpush.VisionPush { *; }
-keep public class de.visionlab.visionpush.VisionPushConfig { *; }
-keep public class de.visionlab.visionpush.VisionPushConfig$LogLevel { *; }
-keep public class de.visionlab.visionpush.VisionPushError { *; }
-keep public class de.visionlab.visionpush.VisionPushResult { *; }
-keep public class de.visionlab.visionpush.VisionPushNotification { *; }
-keep public class de.visionlab.visionpush.NotificationAction { *; }
-keep public class de.visionlab.visionpush.VisionPushChannel { *; }

# ── Keep the FCM Service (must be accessible by the system) ──────────────────
-keep public class de.visionlab.visionpush.VisionPushMessagingService { *; }
-keep public class * extends de.visionlab.visionpush.VisionPushMessagingService { *; }

# ── Keep broadcast receiver (referenced in manifest by class name) ───────────
-keep public class de.visionlab.visionpush.NotificationTracker$OpenReceiver { *; }

# ── Keep WorkManager workers (instantiated by class name at runtime) ─────────
-keep public class de.visionlab.visionpush.NotificationTracker$TrackEventWorker { *; }
-keep public class de.visionlab.visionpush.RegistrationSyncWorker { *; }

# ── Keep Kotlin metadata (required for Kotlin reflection and coroutines) ─────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ── OkHttp (suppress warnings about missing optional dependencies) ───────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Coroutines (suppress debug agent warning) ────────────────────────────────
-dontwarn kotlinx.coroutines.debug.*
