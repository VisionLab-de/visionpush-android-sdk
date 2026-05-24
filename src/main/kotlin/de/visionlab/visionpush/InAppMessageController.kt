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
    val campaignId: String?
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
                campaignId = json.optString("campaign_id").takeIf { it.isNotEmpty() }
            )
        }
    }
}

enum class IAMType {
    MODAL, BANNER, FULLSCREEN;

    companion object {
        fun fromString(s: String) = when (s.lowercase()) {
            "banner" -> BANNER
            "fullscreen" -> FULLSCREEN
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

class InAppMessageController(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {

    // MARK: - Callbacks

    var onButtonTapped: ((InAppMessage, IAMButton) -> Unit)? = null
    var onDismissed: ((InAppMessage) -> Unit)? = null
    var onOutcome: ((String) -> Unit)? = null
    var onImpression: ((String) -> Unit)? = null

    // MARK: - State

    private val queue = mutableListOf<InAppMessage>()
    private var isPresenting = false
    private var foregroundActivity: Activity? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val queuedMessageCount: Int get() = queue.size

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    // MARK: - Lifecycle

    override fun onActivityResumed(activity: Activity) { foregroundActivity = activity }
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

    fun dismissCurrent() {
        isPresenting = false
    }

    // MARK: - Presentation

    private fun present(message: InAppMessage) {
        if (isPresenting) {
            queue.add(0, message)
            return
        }
        isPresenting = true
        onImpression?.invoke(message.id)

        mainHandler.post {
            val activity = foregroundActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                isPresenting = false
                queue.add(0, message)
                return@post
            }

            when (message.type) {
                IAMType.MODAL, IAMType.FULLSCREEN -> presentModal(activity, message)
                IAMType.BANNER -> presentBanner(activity, message)
            }
        }
    }

    // MARK: - Modal

    private fun presentModal(activity: Activity, message: InAppMessage) {
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

        dialog.setOnDismissListener {
            isPresenting = false
            onDismissed?.invoke(message)
        }
        dialog.show()

        val delayMs = message.dismissAfter ?: return
        mainHandler.postDelayed({ dialog.dismiss() }, delayMs)
    }

    // MARK: - Banner

    private fun presentBanner(activity: Activity, message: InAppMessage) {
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

        message.primaryButton?.let { iamBtn ->
            val btn = Button(activity).apply {
                text = iamBtn.text
                textSize = 13f
                setTextColor(fgColor)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    onButtonTapped?.invoke(message, iamBtn)
                    handleAction(iamBtn.action)
                    dismissBanner(rootView, banner, message)
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

        val translateFrom = if (isTop) -300f else 300f
        val anim = TranslateAnimation(0f, 0f, translateFrom, 0f).apply {
            duration = 300
            fillAfter = true
        }
        banner.startAnimation(anim)

        val delayMs = message.dismissAfter ?: 4000L
        mainHandler.postDelayed({
            if (banner.parent != null) dismissBanner(rootView, banner, message)
        }, delayMs)
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
                isPresenting = false
                onDismissed?.invoke(message)
            }
        })
        banner.startAnimation(anim)
    }

    // MARK: - Action Handling

    private fun handleAction(action: IAMAction?) {
        val activity = foregroundActivity ?: return
        when (action?.type) {
            IAMActionType.OPEN_URL -> {
                action.url?.let { url ->
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    activity.startActivity(intent)
                }
            }
            IAMActionType.DEEP_LINK -> {
                action.deepLink?.let { dl ->
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(dl))
                    activity.startActivity(intent)
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
}
