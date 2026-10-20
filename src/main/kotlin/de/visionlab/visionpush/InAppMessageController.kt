package de.visionlab.visionpush

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

// MARK: - Models

data class InAppMessage(
    val id: String,
    val type: IAMType,
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    val primaryButton: IAMButton?,
    val secondaryButton: IAMButton?,
    val dismissAfter: Long?,          // milliseconds
    val bannerPosition: IAMBannerPosition,
    val triggers: List<IAMTrigger>,
    val backgroundColor: String?,
    val textColor: String?,
    val campaignId: String?,
    /** `type = "html"` only (US-16 / migration 0056). Raw HTML rendered by
     *  [HtmlInAppMessageRenderer]; `null` for every other type. */
    val htmlBody: String?
) {
    companion object {
        fun fromJson(json: JSONObject): InAppMessage? {
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val type = IAMType.fromString(json.optString("type", "modal"))
            val triggersJson = json.optJSONArray("triggers") ?: JSONArray()
            val triggers = (0 until triggersJson.length())
                .mapNotNull { IAMTrigger.fromJson(triggersJson.optJSONObject(it) ?: return@mapNotNull null) }

            val primaryBtn = json.optJSONObject("primary_button")?.let { IAMButton.fromJson(it) }
            val secondaryBtn = json.optJSONObject("secondary_button")?.let { IAMButton.fromJson(it) }

            return InAppMessage(
                id = id,
                type = type,
                title = json.optString("title").takeIf { it.isNotEmpty() },
                body = json.optString("body").takeIf { it.isNotEmpty() },
                imageUrl = json.optString("image_url").takeIf { it.isNotEmpty() },
                primaryButton = primaryBtn,
                secondaryButton = secondaryBtn,
                dismissAfter = if (json.has("dismiss_after")) (json.optDouble("dismiss_after") * 1000).toLong() else null,
                bannerPosition = IAMBannerPosition.fromString(json.optString("banner_position", "bottom")),
                triggers = triggers,
                backgroundColor = json.optString("background_color").takeIf { it.isNotEmpty() },
                textColor = json.optString("text_color").takeIf { it.isNotEmpty() },
                campaignId = json.optString("campaign_id").takeIf { it.isNotEmpty() },
                htmlBody = json.optString("html_body").takeIf { it.isNotEmpty() }
            )
        }
    }
}

enum class IAMType {
    MODAL, BANNER, FULLSCREEN, HTML;

    companion object {
        fun fromString(s: String) = when (s.lowercase()) {
            "banner" -> BANNER
            "fullscreen" -> FULLSCREEN
            "html" -> HTML
            else -> MODAL
        }
    }
}

enum class IAMBannerPosition {
    TOP, BOTTOM;

    companion object {
        fun fromString(s: String) = if (s.lowercase() == "top") TOP else BOTTOM
    }
}

data class IAMButton(
    val id: String,
    val text: String,
    val action: IAMAction?,
    val backgroundColor: String?,
    val textColor: String?
) {
    companion object {
        fun fromJson(json: JSONObject) = IAMButton(
            id = json.optString("id", ""),
            text = json.optString("text", ""),
            action = json.optJSONObject("action")?.let { IAMAction.fromJson(it) },
            backgroundColor = json.optString("background_color").takeIf { it.isNotEmpty() },
            textColor = json.optString("text_color").takeIf { it.isNotEmpty() }
        )
    }
}

data class IAMAction(
    val type: IAMActionType,
    val url: String?,
    val deepLink: String?,
    val outcomeName: String?
) {
    companion object {
        fun fromJson(json: JSONObject) = IAMAction(
            type = IAMActionType.fromString(json.optString("type", "dismiss")),
            url = json.optString("url").takeIf { it.isNotEmpty() },
            deepLink = json.optString("deep_link").takeIf { it.isNotEmpty() },
            outcomeName = json.optString("outcome_name").takeIf { it.isNotEmpty() }
        )
    }
}

enum class IAMActionType {
    OPEN_URL, DEEP_LINK, DISMISS, OUTCOME;

    companion object {
        fun fromString(s: String) = when (s.lowercase()) {
            "open_url" -> OPEN_URL
            "deep_link" -> DEEP_LINK
            "outcome" -> OUTCOME
            else -> DISMISS
        }
    }
}

data class IAMTrigger(
    val type: IAMTriggerType,
    val value: String?
) {
    companion object {
        fun fromJson(json: JSONObject) = IAMTrigger(
            type = IAMTriggerType.fromString(json.optString("type", "manual")),
            value = json.optString("value").takeIf { it.isNotEmpty() }
        )
    }
}

enum class IAMTriggerType {
    ON_SESSION, ON_NOTIFICATION_OPEN, CUSTOM_EVENT, MANUAL;

    companion object {
        fun fromString(s: String) = when (s.lowercase()) {
            "on_session" -> ON_SESSION
            "on_notification_open" -> ON_NOTIFICATION_OPEN
            "custom_event" -> CUSTOM_EVENT
            else -> MANUAL
        }
    }
}

// MARK: - Controller

/**
 * Queues in-app messages and presents them one at a time on the foreground activity.
 *
 * Two queues:
 * - the trigger queue (`queue`): messages waiting for THEIR trigger (session start,
 *   notification open, a custom event, or a manual `presentMessage`);
 * - the deferred queue (`deferred`): messages whose trigger already fired but that could
 *   not be shown yet — another message was on screen, or no usable foreground activity
 *   existed. They are drained, in order, whenever a slot frees up: after the current
 *   message is dismissed, and when an activity resumes.
 *
 * `onImpression` fires only once a message is actually on screen (dialog shown / banner
 * attached) — never for an attempt that could not present, so a message that is
 * deferred or fails to show is not billed as an impression.
 */
class InAppMessageController(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {

    // MARK: - Callbacks

    var onButtonTapped: ((InAppMessage, IAMButton) -> Unit)? = null
    var onDismissed: ((InAppMessage) -> Unit)? = null
    var onOutcome: ((String) -> Unit)? = null
    var onImpression: ((String) -> Unit)? = null

    /** US-16: fired by the HTML bridge's `click(actionId, …)` — `buttonId` is the
     *  action id the HTML content passed. Distinct from [onButtonTapped], which is
     *  for the native modal/banner's admin-defined buttons, not free-form HTML. */
    var onHtmlClick: ((InAppMessage, buttonId: String) -> Unit)? = null

    /** US-16: fired only by the HTML bridge's `close()` — a click is never also
     *  counted as a dismiss. [onDismissed] still fires for every closed message
     *  (including an HTML click-then-close), same as it always has. */
    var onHtmlDismiss: ((InAppMessage) -> Unit)? = null

    /** SQ-290: fired when a native (modal/banner/fullscreen) message's admin-defined
     *  button is tapped — `buttonId` is [IAMButton.id]. Backend click tracking,
     *  distinct from the public [onButtonTapped], which an app can freely overwrite.
     *  Fires at most once per display, same as [onHtmlClick] already does for HTML. */
    var onNativeClick: ((InAppMessage, buttonId: String) -> Unit)? = null

    /** SQ-290: fired when a native (modal/banner/fullscreen) message is dismissed
     *  WITHOUT a preceding button tap — a click is never also counted as a dismiss.
     *  Backend dismiss tracking, distinct from the public [onDismissed]. Fires at
     *  most once per display, same as [onHtmlDismiss] already does for HTML. */
    var onNativeDismiss: ((InAppMessage) -> Unit)? = null

    // MARK: - State

    /** Messages waiting for their trigger. */
    private val queue = mutableListOf<InAppMessage>()

    /** Messages whose trigger fired but that are waiting for a free slot / an activity. */
    private val deferred = ArrayDeque<InAppMessage>()

    private var isPresenting = false
    private var foregroundActivity: Activity? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Guards [onNativeClick]/[onNativeDismiss] so a native display reports at most one
     * terminal event, same idempotency the HTML bridge enforces for itself (SQ-288).
     * Reset per display in [present] right before it actually shows (never while
     * another presentation is in flight — [isPresenting] keeps displays serialized).
     * Not consulted for `type = "html"`: that path tracks entirely inside
     * [HtmlIamBridge], whose own guard already makes this one redundant there.
     */
    private val nativeTerminalEventFired = AtomicBoolean(false)

    /** Dismisses whatever is currently on screen (set once a presentation succeeded). */
    private var currentDismissal: (() -> Unit)? = null

    /** Messages in either queue (waiting for a trigger, or deferred behind another message). */
    val queuedMessageCount: Int get() = queue.size + deferred.size

    /** Messages whose trigger already fired but that are waiting to be shown. */
    val deferredMessageCount: Int get() = deferred.size

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    // MARK: - Lifecycle

    override fun onActivityResumed(activity: Activity) {
        foregroundActivity = activity
        // A message deferred for lack of an activity gets its chance now.
        drainDeferred()
    }
    override fun onActivityPaused(activity: Activity) {
        if (foregroundActivity === activity) foregroundActivity = null
    }
    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit

    // MARK: - Public Interface

    fun ingest(messages: List<InAppMessage>) {
        queue.addAll(messages)
    }

    fun triggerSession() {
        val eligible = queue.filter { msg -> msg.triggers.any { it.type == IAMTriggerType.ON_SESSION } }
        for (msg in eligible) {
            queue.remove(msg)
            present(msg)
        }
    }

    fun triggerNotificationOpen() {
        val eligible = queue.filter { msg -> msg.triggers.any { it.type == IAMTriggerType.ON_NOTIFICATION_OPEN } }
        for (msg in eligible) {
            queue.remove(msg)
            present(msg)
        }
    }

    fun triggerCustomEvent(eventName: String) {
        val eligible = queue.filter { msg ->
            msg.triggers.any { it.type == IAMTriggerType.CUSTOM_EVENT && it.value == eventName }
        }
        for (msg in eligible) {
            queue.remove(msg)
            present(msg)
        }
    }

    fun presentMessage(id: String) {
        val msg = queue.find { it.id == id } ?: return
        queue.remove(msg)
        present(msg)
    }

    /**
     * Dismisses the message currently on screen (its dismiss callback then drains the
     * next deferred message). With nothing on screen, just frees the slot and drains.
     */
    fun dismissCurrent() {
        val dismiss = currentDismissal
        if (dismiss != null) {
            dismiss()
        } else {
            isPresenting = false
            drainDeferred()
        }
    }

    // MARK: - Presentation

    private fun present(message: InAppMessage) {
        if (isPresenting) {
            // One at a time: park it behind the current message, in trigger order.
            deferred.addLast(message)
            return
        }
        isPresenting = true

        mainHandler.post {
            val activity = foregroundActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                // No usable activity right now — park it at the head; onActivityResumed
                // drains it. No impression: nothing was shown.
                isPresenting = false
                deferred.addFirst(message)
                return@post
            }

            // New display: this message hasn't reported a click or dismiss yet.
            nativeTerminalEventFired.set(false)

            val shown = try {
                when (message.type) {
                    IAMType.MODAL, IAMType.FULLSCREEN -> presentModal(activity, message)
                    IAMType.BANNER -> presentBanner(activity, message)
                    IAMType.HTML -> presentHtml(activity, message)
                }
            } catch (e: Exception) {
                // e.g. WindowManager.BadTokenException when the activity is going away.
                VisionPushLogger.w(TAG, "Presenting in-app message ${message.id} failed: ${e.message}")
                false
            }

            if (shown) {
                // HTML counts its impression later, from the WebView's onPageFinished
                // (presentHtml wires that itself) — firing it here too would double-count.
                if (message.type != IAMType.HTML) {
                    onImpression?.invoke(message.id)
                }
            } else {
                // Not shown: free the slot and drop the message rather than looping on a
                // message that deterministically fails to present.
                currentDismissal = null
                isPresenting = false
                drainDeferred()
            }
        }
    }

    /** Shared end-of-presentation path for every dismiss route (tap, timer, dismissCurrent). */
    private fun onPresentationEnded(message: InAppMessage) {
        currentDismissal = null
        isPresenting = false
        // HTML already tracked its own click/dismiss through the bridge (SQ-288) by the
        // time this runs — tracking it again here would double-count. A native display
        // that already reported a click via onNativeClick skips the dismiss too.
        if (message.type != IAMType.HTML && nativeTerminalEventFired.compareAndSet(false, true)) {
            onNativeDismiss?.invoke(message)
        }
        onDismissed?.invoke(message)
        drainDeferred()
    }

    /** Presents the next deferred message, if any and if nothing is on screen. */
    private fun drainDeferred() {
        if (isPresenting) return
        val next = deferred.removeFirstOrNull() ?: return
        present(next)
    }

    // MARK: - Modal

    /** Returns `true` once the dialog is showing. Throws if the window cannot be attached. */
    private fun presentModal(activity: Activity, message: InAppMessage): Boolean {
        val scrollView = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val bgColor = parseColor(message.backgroundColor, Color.WHITE)
        val fgColor = parseColor(message.textColor, Color.BLACK)

        val bgDrawable = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = if (message.type == IAMType.FULLSCREEN) 0f else 32f
        }

        message.title?.let {
            container.addView(TextView(activity).apply {
                text = it
                textSize = 20f
                setTextColor(fgColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = 16
                layoutParams = lp
            })
        }

        message.body?.let {
            container.addView(TextView(activity).apply {
                text = it
                textSize = 15f
                setTextColor(fgColor)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = 24
                layoutParams = lp
            })
        }

        val buttons = listOfNotNull(message.primaryButton, message.secondaryButton)
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = buttons.size.toFloat()
        }
        for (iamBtn in buttons) {
            val btn = Button(activity).apply {
                text = iamBtn.text
                val btnBg = GradientDrawable().apply {
                    setColor(parseColor(iamBtn.backgroundColor, Color.parseColor("#007AFF")))
                    cornerRadius = 16f
                }
                background = btnBg
                setTextColor(parseColor(iamBtn.textColor, Color.WHITE))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(8, 0, 8, 0)
                layoutParams = lp
            }
            btn.setOnClickListener {
                onButtonTapped?.invoke(message, iamBtn)
                handleAction(iamBtn.action)
                if (nativeTerminalEventFired.compareAndSet(false, true)) {
                    onNativeClick?.invoke(message, iamBtn.id)
                }
            }
            btnRow.addView(btn)
        }
        container.addView(btnRow)
        scrollView.addView(container)

        val dialog = AlertDialog.Builder(activity)
            .setView(scrollView)
            .create()

        dialog.window?.setBackgroundDrawable(bgDrawable)
        if (message.type == IAMType.FULLSCREEN) {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        dialog.setOnDismissListener { onPresentationEnded(message) }
        dialog.show() // may throw (BadTokenException) → caller treats as "not shown"
        currentDismissal = { if (dialog.isShowing) dialog.dismiss() }

        message.dismissAfter?.let { delayMs ->
            mainHandler.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, delayMs)
        }
        return true
    }

    // MARK: - Banner

    /** Returns `true` once the banner view is attached to the activity's content view. */
    private fun presentBanner(activity: Activity, message: InAppMessage): Boolean {
        val rootView = activity.window.decorView.findViewById<FrameLayout>(android.R.id.content)
        val bgColor = parseColor(message.backgroundColor, Color.parseColor("#007AFF"))
        val fgColor = parseColor(message.textColor, Color.WHITE)
        val isTop = message.bannerPosition == IAMBannerPosition.TOP

        val banner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgColor)
            setPadding(32, 20, 32, 20)
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(activity).apply {
            text = message.title ?: message.body ?: ""
            textSize = 14f
            setTextColor(fgColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        banner.addView(label)

        // Guard against the tap + auto-dismiss timer both dismissing the same banner.
        var dismissing = false
        val dismissOnce: () -> Unit = {
            if (!dismissing && banner.parent != null) {
                dismissing = true
                dismissBanner(rootView, banner, message)
            }
        }

        message.primaryButton?.let { iamBtn ->
            val btn = Button(activity).apply {
                text = iamBtn.text
                textSize = 13f
                setTextColor(fgColor)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    onButtonTapped?.invoke(message, iamBtn)
                    handleAction(iamBtn.action)
                    if (nativeTerminalEventFired.compareAndSet(false, true)) {
                        onNativeClick?.invoke(message, iamBtn.id)
                    }
                    dismissOnce()
                }
            }
            banner.addView(btn)
        }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isTop) Gravity.TOP else Gravity.BOTTOM
        }
        banner.layoutParams = lp
        rootView.addView(banner)
        currentDismissal = dismissOnce

        val translateFrom = if (isTop) -300f else 300f
        val anim = TranslateAnimation(0f, 0f, translateFrom, 0f).apply {
            duration = 300
            fillAfter = true
        }
        banner.startAnimation(anim)

        val delayMs = message.dismissAfter ?: 4000L
        mainHandler.postDelayed({ dismissOnce() }, delayMs)
        return true
    }

    private fun dismissBanner(rootView: FrameLayout, banner: LinearLayout, message: InAppMessage) {
        val isTop = (banner.layoutParams as? FrameLayout.LayoutParams)?.gravity == Gravity.TOP
        val translateTo = if (isTop) -300f else 300f
        val anim = TranslateAnimation(0f, 0f, 0f, translateTo).apply {
            duration = 300
            fillAfter = true
        }
        anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation?) = Unit
            override fun onAnimationRepeat(a: android.view.animation.Animation?) = Unit
            override fun onAnimationEnd(a: android.view.animation.Animation?) {
                rootView.removeView(banner)
                onPresentationEnded(message)
            }
        })
        banner.startAnimation(anim)
    }

    // MARK: - HTML

    /**
     * Returns `true` once the hardened [HtmlInAppMessageRenderer] has a dialog on
     * screen. Unlike [presentModal]/[presentBanner], the impression is NOT fired
     * here — [HtmlInAppMessageRenderer] counts it once the WebView's `onPageFinished`
     * actually runs (US-16), not just because the dialog is attached.
     */
    private fun presentHtml(activity: Activity, message: InAppMessage): Boolean {
        if (message.htmlBody == null) return false

        val renderer = HtmlInAppMessageRenderer(
            activity = activity,
            message = message,
            onImpression = { onImpression?.invoke(message.id) },
            onClick = { buttonId -> onHtmlClick?.invoke(message, buttonId) },
            onDismiss = { onHtmlDismiss?.invoke(message) },
            onClosed = { onPresentationEnded(message) }
        )
        val shown = renderer.show()
        if (shown) currentDismissal = { renderer.close() }
        return shown
    }

    // MARK: - Action Handling

    private fun handleAction(action: IAMAction?) {
        val activity = foregroundActivity ?: return
        when (action?.type) {
            IAMActionType.OPEN_URL -> {
                // Vet the server-supplied URL's scheme — the SAME allowlist the
                // notification deep-link path uses (UrlSecurity) — so an IAM action
                // can't ACTION_VIEW a content://, file://, or javascript: URL.
                action.url?.let { url ->
                    UrlSecurity.safeUri(url)?.let { uri ->
                        activity.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        )
                    }
                }
            }
            IAMActionType.DEEP_LINK -> {
                action.deepLink?.let { dl ->
                    UrlSecurity.safeUri(dl)?.let { uri ->
                        activity.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        )
                    }
                }
            }
            IAMActionType.OUTCOME -> {
                action.outcomeName?.let { name -> onOutcome?.invoke(name) }
            }
            IAMActionType.DISMISS, null -> Unit
        }
    }

    // MARK: - Helpers

    private fun parseColor(hex: String?, fallback: Int): Int {
        if (hex.isNullOrBlank()) return fallback
        return try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (e: IllegalArgumentException) {
            fallback
        }
    }

    private companion object {
        const val TAG = "VP.InAppMessages"
    }
}
