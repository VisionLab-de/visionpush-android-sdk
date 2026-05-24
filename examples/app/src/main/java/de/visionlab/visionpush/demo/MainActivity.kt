package de.visionlab.visionpush.demo

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import de.visionlab.visionpush.VisionPush
import de.visionlab.visionpush.UserProfile
import kotlinx.coroutines.launch

/**
 * Demonstrates every public VisionPush SDK API.
 * Bind to real buttons / events in a production app.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.simple_list_item_1)

        requestPermission()
        setProfile()
        updateTags()
        subscribeTopics()
        triggerCustomEvent()
        manageBadge()
        showHistory()
        runDiagnostics()
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private fun requestPermission() {
        VisionPush.promptForPushPermissions(this, fallbackToSettings = true) { granted ->
            Log.d(TAG, "Permission granted: $granted")
        }
    }

    // ── User Profile ──────────────────────────────────────────────────────────

    private fun setProfile() {
        VisionPush.setUserProfile(UserProfile(
            firstName = "Anna",
            lastName  = "Müller",
            email     = "anna@example.com",
            phone     = "+49123456789",
            gender    = "female",
            birthDate = "1990-06-15"
        ))
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    private fun updateTags() {
        VisionPush.updateTags(mapOf("plan" to "pro", "locale" to "de"))
    }

    private fun removeTags() {
        VisionPush.removeTags(listOf("locale"))
    }

    // ── Topics ────────────────────────────────────────────────────────────────

    private fun subscribeTopics() {
        VisionPush.subscribeToTopics(listOf("news", "sports")) { result ->
            result.onSuccess { Log.d(TAG, "Subscribed") }
            result.onFailure { Log.e(TAG, "Error: ${it.message}") }
        }
    }

    private fun unsubscribeTopics() {
        VisionPush.unsubscribeFromTopics(listOf("sports"))
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    private fun sendOutcome() {
        VisionPush.sendOutcome("tutorial_completed")
        VisionPush.sendOutcomeWithValue("revenue", 9.99)
    }

    // ── In-App Messages ───────────────────────────────────────────────────────

    private fun triggerCustomEvent() {
        VisionPush.triggerCustomEvent("purchase_completed")
    }

    // ── Badge ─────────────────────────────────────────────────────────────────

    private fun manageBadge() {
        VisionPush.setBadge(3)
        VisionPush.incrementBadge()
        Log.d(TAG, "Badge count: ${VisionPush.badgeCount}")
        VisionPush.clearBadge()
    }

    // ── Consent (GDPR) ────────────────────────────────────────────────────────

    private fun grantConsent() {
        VisionPush.setConsentGiven(true)
    }

    // ── Notification History ──────────────────────────────────────────────────

    private fun showHistory() {
        val history = VisionPush.getNotificationHistory()
        Log.d(TAG, "History (${history.size} entries)")
        history.take(5).forEach { Log.d(TAG, "  ${it.title} — opened: ${it.wasOpened}") }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    private fun runDiagnostics() {
        lifecycleScope.launch {
            val report = VisionPush.runDiagnostics()
            Log.d(TAG, report.summary())
        }
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    private fun logout() {
        VisionPush.logout()
    }

    companion object {
        private const val TAG = "VisionPushDemo"
    }
}
