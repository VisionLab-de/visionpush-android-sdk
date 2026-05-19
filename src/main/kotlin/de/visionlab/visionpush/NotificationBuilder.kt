package de.visionlab.visionpush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds and displays rich Android notifications from [VisionPushNotification] payloads.
 *
 * Features supported:
 * - Big-text style for long body copy
 * - Big-picture style when an [VisionPushNotification.imageUrl] is present
 * - Large icon loaded from a remote URL
 * - Up to three [NotificationAction] buttons, each backed by a tracking [PendingIntent]
 * - Notification channel management (creation and update)
 * - Deep-link routing via the notification tap intent
 *
 * All bitmap network operations are performed on [Dispatchers.IO] to keep the
 * main thread free.
 */
internal class NotificationBuilder(private val context: Context) {

    // ── Atomic notification ID counter ──────────────────────────────────────
    // Uses an AtomicInteger so multiple concurrent notifications don't collide.
    private val notificationIdCounter = AtomicInteger(NOTIFICATION_ID_BASE)

    // ── Channel management ──────────────────────────────────────────────────

    /**
     * Ensures the given [channels] exist on the device. On API < 26 this is a
     * no-op because notification channels were introduced in Android O.
     *
     * Calling this method multiple times with the same channel ID is safe – the
     * system silently ignores duplicate registrations.
     */
    fun ensureChannelsExist(channels: List<VisionPushChannel>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channels.forEach { channel ->
            val systemChannel = NotificationChannel(
                channel.channelId,
                channel.name,
                channel.importance
            ).apply {
                description = channel.description
                enableVibration(channel.enableVibration)
                enableLights(channel.enableLights)
                lightColor = channel.lightColor
            }
            manager.createNotificationChannel(systemChannel)
        }
    }

    /**
     * Registers the single default [VisionPushChannel] so there is always a
     * valid channel for notifications that do not specify one.
     */
    fun ensureDefaultChannelExists() {
        ensureChannelsExist(
            listOf(
                VisionPushChannel(
                    channelId = VisionPushChannel.DEFAULT_CHANNEL_ID,
                    name = VisionPushChannel.DEFAULT_CHANNEL_NAME,
                    importance = NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        )
    }

    // ── Notification display ────────────────────────────────────────────────

    /**
     * Builds and posts a system notification for [notification].
     *
     * The method is `suspend` because it may need to fetch remote bitmaps over
     * the network before constructing the [NotificationCompat.Builder].
     *
     * Returns the system-assigned notification ID used to post the notification,
     * which can later be used to cancel it programmatically.
     */
    suspend fun show(notification: VisionPushNotification): Int {
        val notificationId = notificationIdCounter.incrementAndGet()

        val largeIcon = notification.largeIconUrl?.let { fetchBitmap(it) }
        val bigPicture = notification.imageUrl?.let { fetchBitmap(it) }

        val builder = NotificationCompat.Builder(context, notification.channelId)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSmallIcon(resolveSmallIconRes(notification.smallIconName))
            .setColor(Color.parseColor("#0F52BA")) // VisionPush brand blue

        // Large icon (round badge next to title)
        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        // Rich style: big-picture takes precedence over big-text when image is present
        if (bigPicture != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPicture)
                    .bigLargeIcon(null as Bitmap?) // Hide large icon in expanded state
                    .setSummaryText(notification.body)
            )
        } else if (notification.body.length > BIG_TEXT_THRESHOLD) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notification.body)
            )
        }

        // Content tap intent (opens the app or deep-links into it)
        builder.setContentIntent(buildContentIntent(notification, notificationId))

        // Delete intent for dismiss tracking
        builder.setDeleteIntent(buildDismissIntent(notification, notificationId))

        // Action buttons (max 3, per NotificationCompat contract)
        notification.actions.take(MAX_ACTIONS).forEachIndexed { index, action ->
            builder.addAction(buildAction(notification, action, notificationId, index))
        }

        val builtNotification = builder.build()
        postNotification(notificationId, builtNotification)
        return notificationId
    }

    // ── Intent builders ─────────────────────────────────────────────────────

    /**
     * Builds the [PendingIntent] that fires when the user taps the notification body.
     *
     * The intent first routes through [NotificationTracker.OpenReceiver] so the
     * open event is tracked, then forwards to the deep-link or the host app's
     * launcher activity.
     */
    private fun buildContentIntent(
        notification: VisionPushNotification,
        notificationId: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationTracker.OpenReceiver::class.java).apply {
            action = NotificationTracker.ACTION_NOTIFICATION_OPENED
            putExtra(NotificationTracker.EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(NotificationTracker.EXTRA_SYSTEM_NOTIFICATION_ID, notificationId)
            putExtra(NotificationTracker.EXTRA_EVENT_TYPE, NotificationEvent.EventType.OPENED.apiKey)
            notification.campaignId?.let { putExtra(NotificationTracker.EXTRA_CAMPAIGN_ID, it) }
            notification.deepLink?.let { putExtra(NotificationTracker.EXTRA_DEEP_LINK, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            pendingIntentFlags()
        )
    }

    /**
     * Builds the [PendingIntent] that fires when the user swipes the notification away.
     */
    private fun buildDismissIntent(
        notification: VisionPushNotification,
        notificationId: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationTracker.OpenReceiver::class.java).apply {
            action = NotificationTracker.ACTION_NOTIFICATION_DISMISSED
            putExtra(NotificationTracker.EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(NotificationTracker.EXTRA_SYSTEM_NOTIFICATION_ID, notificationId)
            putExtra(NotificationTracker.EXTRA_EVENT_TYPE, NotificationEvent.EventType.DISMISSED.apiKey)
            notification.campaignId?.let { putExtra(NotificationTracker.EXTRA_CAMPAIGN_ID, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            // Offset by 10000 to avoid colliding with the content intent request code
            notificationId + 10_000,
            intent,
            pendingIntentFlags()
        )
    }

    /**
     * Builds a [NotificationCompat.Action] wired to a tracking [PendingIntent].
     *
     * @param actionIndex 0-based index used to generate a unique PendingIntent request code.
     */
    private fun buildAction(
        notification: VisionPushNotification,
        action: NotificationAction,
        notificationId: Int,
        actionIndex: Int
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationTracker.OpenReceiver::class.java).apply {
            this.action = NotificationTracker.ACTION_ACTION_TAPPED
            putExtra(NotificationTracker.EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(NotificationTracker.EXTRA_SYSTEM_NOTIFICATION_ID, notificationId)
            putExtra(NotificationTracker.EXTRA_EVENT_TYPE, NotificationEvent.EventType.ACTION_TAPPED.apiKey)
            putExtra(NotificationTracker.EXTRA_ACTION_ID, action.actionId)
            notification.campaignId?.let { putExtra(NotificationTracker.EXTRA_CAMPAIGN_ID, it) }
            val deepLink = action.deepLink ?: notification.deepLink
            deepLink?.let { putExtra(NotificationTracker.EXTRA_DEEP_LINK, it) }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 20_000 + actionIndex,
            intent,
            pendingIntentFlags()
        )
        val iconRes = action.iconName?.let { resolveDrawableRes(it) }
            ?: android.R.drawable.ic_menu_send

        return NotificationCompat.Action.Builder(iconRes, action.label, pendingIntent).build()
    }

    // ── Resource helpers ────────────────────────────────────────────────────

    /**
     * Resolves the small icon drawable resource ID.
     *
     * Looks up [iconName] in the host app's drawable resources first. Falls back
     * to the generic `ic_notification` drawable if present, and ultimately falls
     * back to the system's status bar icon to guarantee a valid icon is always set.
     */
    private fun resolveSmallIconRes(iconName: String?): Int {
        if (!iconName.isNullOrBlank()) {
            val id = resolveDrawableRes(iconName)
            if (id != 0) return id
        }
        // Try the conventional SDK default drawable name in the host app
        val defaultId = resolveDrawableRes("ic_visionpush_notification")
        if (defaultId != 0) return defaultId

        // Ultimate fallback
        return android.R.drawable.ic_popup_reminder
    }

    /** Resolves a drawable resource by name from the host application package. */
    private fun resolveDrawableRes(name: String): Int =
        context.resources.getIdentifier(name, "drawable", context.packageName)

    // ── Bitmap loading ──────────────────────────────────────────────────────

    /**
     * Downloads a [Bitmap] from [url] on the IO dispatcher.
     *
     * Returns `null` on any I/O error, connection error, or if the response is
     * not a valid image so that callers can gracefully degrade.
     */
    private suspend fun fetchBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = BITMAP_CONNECT_TIMEOUT_MS
                readTimeout = BITMAP_READ_TIMEOUT_MS
                doInput = true
                connect()
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            BitmapFactory.decodeStream(connection.inputStream)
        } catch (e: IOException) {
            VisionPushLogger.w(TAG, "Failed to fetch bitmap from $url: ${e.message}")
            null
        } catch (e: Exception) {
            VisionPushLogger.w(TAG, "Unexpected error fetching bitmap: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ── Notification posting ────────────────────────────────────────────────

    /** Posts the notification, handling the permission check on API 33+. */
    private fun postNotification(notificationId: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on API 33+
            VisionPushLogger.w(TAG, "Cannot post notification – permission denied: ${e.message}")
        }
    }

    // ── PendingIntent flags ─────────────────────────────────────────────────

    /** Returns the appropriate [PendingIntent] flags for the current API level. */
    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    // ── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VP.NotificationBuilder"

        /** Notification IDs start above the reserved range to avoid collisions. */
        private const val NOTIFICATION_ID_BASE = 10_000

        /** Body text longer than this character count triggers BigTextStyle. */
        private const val BIG_TEXT_THRESHOLD = 60

        /** Maximum number of action buttons rendered by the system. */
        private const val MAX_ACTIONS = 3

        private const val BITMAP_CONNECT_TIMEOUT_MS = 5_000
        private const val BITMAP_READ_TIMEOUT_MS = 10_000
    }
}
