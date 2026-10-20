package de.visionlab.visionpush

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for VisionPush Android SDK.
 *
 * These tests run on the JVM without an Android device or emulator.
 * They cover pure-Kotlin logic: model parsing, badge management,
 * default action buttons, in-app message controller logic, and diagnostics.
 */
class VisionPushSDKTest {

    // ── Setup / teardown ─────────────────────────────────────────────────────

    @Before
    fun resetSdkState() {
        // Reset VisionPush singleton state between tests
        VisionPush.clearBadge()
        VisionPush.clearDefaultActions()
    }

    @After
    fun tearDown() {
        VisionPush.clearBadge()
        VisionPush.clearDefaultActions()
    }

    // ── InAppMessage model ────────────────────────────────────────────────────

    @Test
    fun inAppMessage_parsesRequiredFields() {
        val json = JSONObject().apply {
            put("id", "msg-001")
            put("type", "modal")
            put("title", "Hello")
            put("body", "World")
            put("triggers", org.json.JSONArray())
        }
        val msg = InAppMessage.fromJson(json)
        assertNotNull(msg)
        requireNotNull(msg)
        assertEquals("msg-001", msg.id)
        assertEquals(IAMType.MODAL, msg.type)
        assertEquals("Hello", msg.title)
        assertEquals("World", msg.body)
    }

    @Test
    fun inAppMessage_returnsNullForMissingId() {
        val json = JSONObject().apply { put("type", "modal") }
        assertNull(InAppMessage.fromJson(json))
    }

    @Test
    fun inAppMessage_defaultsToModalForUnknownType() {
        val json = JSONObject().apply {
            put("id", "m1")
            put("type", "unknown_future_type")
            put("triggers", org.json.JSONArray())
        }
        val msg = InAppMessage.fromJson(json)
        assertEquals(IAMType.MODAL, msg?.type)
    }

    @Test
    fun inAppMessage_parsesAllTypes() {
        listOf("modal" to IAMType.MODAL, "banner" to IAMType.BANNER, "fullscreen" to IAMType.FULLSCREEN)
            .forEach { (raw, expected) ->
                val json = JSONObject().apply {
                    put("id", "m"); put("type", raw); put("triggers", org.json.JSONArray())
                }
                assertEquals(expected, InAppMessage.fromJson(json)?.type)
            }
    }

    @Test
    fun inAppMessage_parsesBannerPosition() {
        val json = JSONObject().apply {
            put("id", "b1"); put("type", "banner")
            put("banner_position", "top")
            put("triggers", org.json.JSONArray())
        }
        assertEquals(IAMBannerPosition.TOP, InAppMessage.fromJson(json)?.bannerPosition)
    }

    @Test
    fun inAppMessage_parsesDismissAfterAsMilliseconds() {
        val json = JSONObject().apply {
            put("id", "d1"); put("type", "modal")
            put("dismiss_after", 5.0)   // 5 seconds in backend format
            put("triggers", org.json.JSONArray())
        }
        val msg = InAppMessage.fromJson(json)
        assertEquals(5000L, msg?.dismissAfter)
    }

    @Test
    fun inAppMessage_parsesTriggers() {
        val triggersArr = org.json.JSONArray().apply {
            put(JSONObject().apply { put("type", "on_session") })
            put(JSONObject().apply { put("type", "on_notification_open") })
        }
        val json = JSONObject().apply {
            put("id", "t1"); put("type", "modal"); put("triggers", triggersArr)
        }
        val msg = InAppMessage.fromJson(json)
        assertEquals(2, msg?.triggers?.size)
        assertEquals(IAMTriggerType.ON_SESSION, msg?.triggers?.get(0)?.type)
        assertEquals(IAMTriggerType.ON_NOTIFICATION_OPEN, msg?.triggers?.get(1)?.type)
    }

    @Test
    fun inAppMessage_parsesPrimaryAndSecondaryButton() {
        val primaryBtn = JSONObject().apply {
            put("id", "p"); put("text", "Buy")
            put("action", JSONObject().apply { put("type", "open_url"); put("url", "https://example.com") })
            put("background_color", "#007AFF"); put("text_color", "#FFF")
        }
        val secondaryBtn = JSONObject().apply {
            put("id", "s"); put("text", "Later")
            put("action", JSONObject().apply { put("type", "dismiss") })
        }
        val json = JSONObject().apply {
            put("id", "btn-test"); put("type", "modal")
            put("primary_button", primaryBtn)
            put("secondary_button", secondaryBtn)
            put("triggers", org.json.JSONArray())
        }
        val msg = InAppMessage.fromJson(json)
        assertNotNull(msg?.primaryButton)
        assertEquals("Buy", msg?.primaryButton?.text)
        assertEquals(IAMActionType.OPEN_URL, msg?.primaryButton?.action?.type)
        assertEquals("https://example.com", msg?.primaryButton?.action?.url)
        assertNotNull(msg?.secondaryButton)
        assertEquals(IAMActionType.DISMISS, msg?.secondaryButton?.action?.type)
    }

    // ── IAMTriggerType ────────────────────────────────────────────────────────

    @Test
    fun iamTriggerType_parsesAllValues() {
        assertEquals(IAMTriggerType.ON_SESSION, IAMTriggerType.fromString("on_session"))
        assertEquals(IAMTriggerType.ON_NOTIFICATION_OPEN, IAMTriggerType.fromString("on_notification_open"))
        assertEquals(IAMTriggerType.CUSTOM_EVENT, IAMTriggerType.fromString("custom_event"))
        assertEquals(IAMTriggerType.MANUAL, IAMTriggerType.fromString("manual"))
        assertEquals(IAMTriggerType.MANUAL, IAMTriggerType.fromString("unknown"))
    }

    @Test
    fun inAppMessage_parsesCustomEventTrigger() {
        val triggersArr = org.json.JSONArray().apply {
            put(JSONObject().apply { put("type", "custom_event"); put("value", "purchase_completed") })
        }
        val json = JSONObject().apply {
            put("id", "ce1"); put("type", "modal"); put("triggers", triggersArr)
        }
        val msg = InAppMessage.fromJson(json)
        assertEquals(1, msg?.triggers?.size)
        assertEquals(IAMTriggerType.CUSTOM_EVENT, msg?.triggers?.get(0)?.type)
        assertEquals("purchase_completed", msg?.triggers?.get(0)?.value)
    }

    @Test
    fun incrementBadge_incrementsByOne() {
        VisionPush.setBadge(3)
        VisionPush.incrementBadge()
        assertEquals(4, VisionPush.badgeCount)
    }

    // ── IAMActionType ─────────────────────────────────────────────────────────

    @Test
    fun iamActionType_parsesAllValues() {
        assertEquals(IAMActionType.OPEN_URL, IAMActionType.fromString("open_url"))
        assertEquals(IAMActionType.DEEP_LINK, IAMActionType.fromString("deep_link"))
        assertEquals(IAMActionType.OUTCOME, IAMActionType.fromString("outcome"))
        assertEquals(IAMActionType.DISMISS, IAMActionType.fromString("dismiss"))
        assertEquals(IAMActionType.DISMISS, IAMActionType.fromString("unknown_action"))
    }

    // ── VisionPushNotificationRecord ──────────────────────────────────────────

    @Test
    fun notificationRecord_roundtripsJson() {
        val original = VisionPushNotificationRecord(
            id = "n-abc-123",
            title = "Hello",
            body = "World",
            campaignId = "camp-1",
            deepLink = "myapp://home",
            receivedAt = "2026-01-01T00:00:00.000Z",
            wasOpened = false
        )
        val json = VisionPushNotificationRecord.toJson(original)
        val decoded = VisionPushNotificationRecord.fromJson(json)

        assertEquals(original.id, decoded.id)
        assertEquals(original.title, decoded.title)
        assertEquals(original.body, decoded.body)
        assertEquals(original.campaignId, decoded.campaignId)
        assertEquals(original.deepLink, decoded.deepLink)
        assertEquals(original.receivedAt, decoded.receivedAt)
        assertEquals(original.wasOpened, decoded.wasOpened)
    }

    @Test
    fun notificationRecord_wasOpenedDefaultsFalse() {
        val json = JSONObject().apply {
            put("id", "x"); put("title", "T"); put("body", "B")
            put("received_at", "2026-01-01T00:00:00.000Z")
        }
        val record = VisionPushNotificationRecord.fromJson(json)
        assertFalse(record.wasOpened)
    }

    // ── UserProfile ───────────────────────────────────────────────────────────

    @Test
    fun userProfile_toJson_includesSetFields() {
        val profile = UserProfile(
            firstName = "Anna",
            lastName = "Müller",
            email = "anna@example.com",
            phone = "+49123456789",
            gender = "female",
            birthDate = "1990-06-15"
        )
        val json = profile.toJson()
        assertEquals("Anna", json.optString("first_name"))
        assertEquals("Müller", json.optString("last_name"))
        assertEquals("anna@example.com", json.optString("email"))
        assertEquals("+49123456789", json.optString("phone"))
        assertEquals("female", json.optString("gender"))
        assertEquals("1990-06-15", json.optString("birth_date"))
    }

    @Test
    fun userProfile_toJson_omitsNullFields() {
        val profile = UserProfile(email = "only@example.com")
        val json = profile.toJson()
        assertEquals("only@example.com", json.optString("email"))
        assertFalse(json.has("first_name"))
        assertFalse(json.has("last_name"))
        assertFalse(json.has("birth_date"))
    }

    // ── Badge Management ──────────────────────────────────────────────────────

    @Test
    fun setBadge_updatesBadgeCount() {
        VisionPush.setBadge(5)
        assertEquals(5, VisionPush.badgeCount)
    }

    @Test
    fun clearBadge_resetsToZero() {
        VisionPush.setBadge(10)
        VisionPush.clearBadge()
        assertEquals(0, VisionPush.badgeCount)
    }

    @Test
    fun setBadge_clampsNegativeToZero() {
        VisionPush.setBadge(-3)
        assertEquals(0, VisionPush.badgeCount)
    }

    @Test
    fun setBadge_allowsZero() {
        VisionPush.setBadge(7)
        VisionPush.setBadge(0)
        assertEquals(0, VisionPush.badgeCount)
    }

    // ── Default Action Buttons ────────────────────────────────────────────────

    @Test
    fun addDefaultAction_appendsToList() {
        VisionPush.addDefaultAction("Open", "open")
        VisionPush.addDefaultAction("Dismiss", "dismiss")
        val actions = VisionPush.defaultActions
        assertEquals(2, actions.size)
        assertEquals("Open", actions[0].title)
        assertEquals("open", actions[0].id)
        assertEquals("Dismiss", actions[1].title)
    }

    @Test
    fun clearDefaultActions_emptiesList() {
        VisionPush.addDefaultAction("Reply", "reply")
        VisionPush.clearDefaultActions()
        assertTrue(VisionPush.defaultActions.isEmpty())
    }

    @Test
    fun addDefaultAction_setsNullDeepLinkAndIconByDefault() {
        VisionPush.addDefaultAction("View", "view")
        val action = VisionPush.defaultActions.first()
        assertNull(action.deepLink)
        assertNull(action.iconName)
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    @Test
    fun diagnosticReport_summaryContainsAllRequiredSections() {
        val report = DiagnosticReport(
            sdkVersion = "1.0.0",
            isInitialized = true,
            deviceId = "dev-123",
            pushPermissionStatus = "granted",
            hasConsent = true,
            consentRequired = false,
            apiKeyPrefix = "vp_live_",
            apiBaseUrl = "https://api.visionpush.de/v1",
            networkReachable = true,
            subscribedTopics = listOf("news", "sports"),
            cachedTagCount = 3,
            notificationHistoryCount = 7,
            iamMessagesQueued = 0,
            timestamp = 1_000_000L
        )
        val summary = report.summary()
        assertTrue(summary.contains("VisionPush Diagnostic Report"))
        assertTrue(summary.contains("sdkVersion"))
        assertTrue(summary.contains("isInitialized"))
        assertTrue(summary.contains("1.0.0"))
        assertTrue(summary.contains("dev-123"))
    }

    @Test
    fun diagnosticReport_uninitializedState() {
        val report = DiagnosticReport(
            sdkVersion = "1.0.0",
            isInitialized = false,
            deviceId = null,
            pushPermissionStatus = "notDetermined",
            hasConsent = false,
            consentRequired = true,
            apiKeyPrefix = "vp_test_",
            apiBaseUrl = "https://api.visionpush.de/v1",
            networkReachable = false,
            subscribedTopics = emptyList(),
            cachedTagCount = 0,
            notificationHistoryCount = 0,
            iamMessagesQueued = 0,
            timestamp = 0L
        )
        assertFalse(report.isInitialized)
        assertNull(report.deviceId)
        assertFalse(report.hasConsent)
        assertTrue(report.consentRequired)
        val summary = report.summary()
        assertTrue(summary.contains("false"))
    }

    // ── VisionPushError ───────────────────────────────────────────────────────

    @Test
    fun notInitializedError_hasMessage() {
        val error = VisionPushError.NotInitialized()
        val msg = requireNotNull(error.message) { "message must not be null" }
        assertTrue(msg.isNotEmpty())
    }

    @Test
    fun invalidConfigError_preservesMessage() {
        val error = VisionPushError.InvalidConfig("appId must not be blank.")
        val msg = requireNotNull(error.message) { "message must not be null" }
        assertTrue(msg.contains("appId"))
    }

    @Test
    fun apiError_preservesStatusAndMessage() {
        val error = VisionPushError.ApiError(404, "Not found")
        assertEquals(404, error.httpStatus)
        assertNotNull(error.message)
    }

    @Test
    fun networkError_wrapsUnderlyingException() {
        val cause = java.io.IOException("Connection refused")
        val error = VisionPushError.NetworkError(cause)
        assertNotNull(error.cause)
    }

    // ── ApiResponse parsing ───────────────────────────────────────────────────

    @Test
    fun apiResponse_parsesDeviceId() {
        val json = JSONObject().apply { put("device_id", "dev-uuid-123") }
        val response = ApiResponse.fromJson(json)
        assertEquals("dev-uuid-123", response.deviceId)
    }

    @Test
    fun apiResponse_parsesRawData() {
        val data = JSONObject().apply { put("tags", JSONObject().apply { put("plan", "pro") }) }
        val json = JSONObject().apply { put("data", data) }
        val response = ApiResponse.fromJson(json)
        val rawData = requireNotNull(response.rawData) { "rawData must not be null" }
        assertEquals("pro", rawData.optJSONObject("tags")?.optString("plan"))
    }

    @Test
    fun apiResponse_parsesDataArray() {
        val arr = org.json.JSONArray().apply {
            put(JSONObject().apply { put("id", "msg-1") })
            put(JSONObject().apply { put("id", "msg-2") })
        }
        val json = JSONObject().apply { put("data", arr) }
        val response = ApiResponse.fromJson(json)
        val dataArray = requireNotNull(response.rawDataArray) { "rawDataArray must not be null" }
        assertEquals(2, dataArray.length())
    }

    // ── VisionPushConfig ──────────────────────────────────────────────────────

    @Test
    fun config_consentRequiredDefaultsFalse() {
        val config = VisionPushConfig(appId = "app", apiKey = "vp_live_key")
        assertFalse(config.consentRequired)
    }

    @Test
    fun config_consentRequiredCanBeEnabled() {
        val config = VisionPushConfig(appId = "app", apiKey = "vp_live_key", consentRequired = true)
        assertTrue(config.consentRequired)
    }

    @Test
    fun config_logLevelDefaultsToWarn() {
        val config = VisionPushConfig(appId = "app", apiKey = "key")
        assertEquals(VisionPushConfig.LogLevel.WARN, config.logLevel)
    }

    @Test
    fun config_customLogLevelIsPreserved() {
        val config = VisionPushConfig(appId = "app", apiKey = "key", logLevel = VisionPushConfig.LogLevel.DEBUG)
        assertEquals(VisionPushConfig.LogLevel.DEBUG, config.logLevel)
    }

    // ── VisionPushNotification ────────────────────────────────────────────────

    @Test
    fun visionPushNotification_parsesMinimalPayload() {
        val data = mapOf("vp_payload" to """{"id":"n1","title":"Hello"}""")
        val n = VisionPushNotification.fromFcmData(data)
        assertNotNull(n)
        requireNotNull(n)
        assertEquals("n1", n.id)
        assertEquals("Hello", n.title)
        assertEquals("", n.body)
    }

    @Test
    fun visionPushNotification_parsesFullPayload() {
        val payload = """
            {"id":"n2","campaign_id":"c1","title":"T","body":"B",
             "sent_at":"2026-01-01T00:00:00.000Z","show_system_notification":false}
        """.trimIndent()
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))
        assertNotNull(n)
        requireNotNull(n)
        assertEquals("c1", n.campaignId)
        assertEquals("2026-01-01T00:00:00.000Z", n.sentAt)
        assertFalse(n.showSystemNotification)
    }

    @Test
    fun visionPushNotification_returnsNullForNonVisionPushFcmPayload() {
        val n = VisionPushNotification.fromFcmData(mapOf("other_key" to "value"))
        assertNull(n)
    }

    @Test
    fun visionPushNotification_returnsNullForMalformedJson() {
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to "{bad json}"))
        assertNull(n)
    }

    // NOTE: URL-scheme blocking (javascript:/data:/file: deep links + image URLs) is NOT
    // tested here on purpose. `android.net.Uri` is a stub on the plain JVM, so every URL
    // parses to null and an assertNull would pass with the blocklist deleted. Those
    // tests live in NotificationUrlSecurityTest (Robolectric) with positive controls.

    @Test
    fun visionPushNotification_truncatesOversizedId() {
        val longId = "x".repeat(600)
        val payload = """{"id":"$longId","title":"T"}"""
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))
        requireNotNull(n)
        assertEquals(512, n.id.length)
    }

    @Test
    fun visionPushNotification_enforcesMaxThreeActions() {
        val actions = (1..5).joinToString(",") {
            """{"action_id":"a$it","title":"Btn $it"}"""
        }
        val payload = """{"id":"n7","title":"T","actions":[$actions]}"""
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))
        requireNotNull(n)
        assertEquals(3, n.actions.size)
    }

    // ── Input Validation ──────────────────────────────────────────────────────

    @Test
    fun setExternalId_throwsInvalidArgumentWhenTooLong() {
        try {
            VisionPush.setExternalId("a".repeat(1025))
            fail("Expected VisionPushError.InvalidArgument")
        } catch (e: VisionPushError.InvalidArgument) {
            assertTrue(requireNotNull(e.message).isNotEmpty())
        }
    }

    @Test
    fun setExternalId_exactly1024CharsThrowsNotInitialized() {
        // 1024 chars is valid — validation passes and reaches checkInitialized()
        try {
            VisionPush.setExternalId("a".repeat(1024))
            fail("Expected an exception")
        } catch (e: VisionPushError.NotInitialized) {
            // correct: validation passed, SDK not initialized
        } catch (e: VisionPushError.InvalidArgument) {
            fail("Should not throw InvalidArgument for exactly 1024 chars")
        }
    }

    // ── Identity verification: externalIdHash ────────────────────────────────

    @Test
    fun setExternalId_rejectsAMalformedHashBeforeAnythingElse() {
        try {
            VisionPush.setExternalId("user", "not-a-hash")
            fail("Expected VisionPushError.InvalidArgument")
        } catch (e: VisionPushError.InvalidArgument) {
            assertTrue("the message must say what the hash is", requireNotNull(e.message).contains("HMAC-SHA256"))
        }
    }

    @Test
    fun setExternalId_validHashPassesValidationThenThrowsNotInitialized() {
        try {
            VisionPush.setExternalId("user", "0F".repeat(32))
            fail("Expected an exception")
        } catch (e: VisionPushError.NotInitialized) {
            // correct: validation passed (upper-case hex is normalised, not rejected)
        } catch (e: VisionPushError.InvalidArgument) {
            fail("a 64-hex-char hash must be accepted")
        }
    }

    @Test
    fun setExternalId_nullIdIgnoresTheHash() {
        // Clearing never needs a hash — a stray one must not turn a logout into an error.
        try {
            VisionPush.setExternalId(null, "garbage")
            fail("Expected an exception")
        } catch (e: VisionPushError.NotInitialized) {
            // correct: the hash was ignored, not validated
        }
    }

    @Test
    fun normalizeExternalIdHash_trimsLowercasesAndRejectsNonHmacInput() {
        val hash = "ab".repeat(32)
        assertNull(VisionPush.normalizeExternalIdHash(null))
        assertNull("blank means none", VisionPush.normalizeExternalIdHash("   "))
        assertEquals(hash, VisionPush.normalizeExternalIdHash(" ${hash.uppercase()}\n"))
        for (bad in listOf("abc", "z".repeat(64), hash + "0", "the-identity-secret-itself")) {
            try {
                VisionPush.normalizeExternalIdHash(bad)
                fail("expected InvalidArgument for '$bad'")
            } catch (_: VisionPushError.InvalidArgument) {
            }
        }
    }

    @Test
    fun apiError_detectsTheExternalIdVerificationRejection() {
        val code = VisionPushError.ApiError.EXTERNAL_ID_VERIFICATION_FAILED
        assertTrue(VisionPushError.ApiError(403, code, code).isExternalIdVerificationFailed)
        assertTrue("message-only bodies still match", VisionPushError.ApiError(403, code).isExternalIdVerificationFailed)
        assertFalse(VisionPushError.ApiError(403, "forbidden", "forbidden").isExternalIdVerificationFailed)
        assertFalse("only a 403", VisionPushError.ApiError(400, code, code).isExternalIdVerificationFailed)
        assertNull("the two-arg constructor stays source-compatible", VisionPushError.ApiError(404, "Not found").errorCode)
    }

    @Test
    fun updateTags_throwsInvalidArgumentWhenTooManyPairs() {
        val tags = (1..101).associate { "key$it" to "val" }
        try {
            VisionPush.updateTags(tags)
            fail("Expected VisionPushError.InvalidArgument")
        } catch (e: VisionPushError.InvalidArgument) {
            assertTrue(requireNotNull(e.message).contains("100"))
        }
    }

    @Test
    fun updateTags_throwsInvalidArgumentWhenKeyTooLong() {
        try {
            VisionPush.updateTags(mapOf("k".repeat(257) to "value"))
            fail("Expected VisionPushError.InvalidArgument")
        } catch (e: VisionPushError.InvalidArgument) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun updateTags_throwsInvalidArgumentWhenValueTooLong() {
        try {
            VisionPush.updateTags(mapOf("key" to "v".repeat(257)))
            fail("Expected VisionPushError.InvalidArgument")
        } catch (e: VisionPushError.InvalidArgument) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun sendOutcome_throwsInvalidArgumentWhenNameTooLong() {
        try {
            VisionPush.sendOutcome("o".repeat(257))
            fail("Expected VisionPushError.InvalidArgument")
        } catch (e: VisionPushError.InvalidArgument) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun subscribeToTopics_throwsInvalidArgumentWhenTooManyTopics() {
        val topics = (1..51).map { "topic$it" }
        try {
            VisionPush.subscribeToTopics(topics)
            fail("Expected VisionPushError.InvalidArgument")
        } catch (e: VisionPushError.InvalidArgument) {
            assertTrue(requireNotNull(e.message).contains("50"))
        }
    }

    // ── Not-Initialized Checks ────────────────────────────────────────────────

    @Test
    fun updateTags_throwsNotInitializedWhenSDKNotReady() {
        try {
            VisionPush.updateTags(mapOf("key" to "value"))
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun removeTags_throwsWhenNotInitialized() {
        try {
            VisionPush.removeTags(listOf("key"))
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun sendOutcome_throwsWhenNotInitialized() {
        try {
            VisionPush.sendOutcome("purchase")
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun sendOutcomeWithValue_throwsWhenNotInitialized() {
        try {
            VisionPush.sendOutcomeWithValue("revenue", 9.99f)
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun subscribeToTopics_throwsNotInitializedWhenSDKNotReady() {
        try {
            VisionPush.subscribeToTopics(listOf("news"))
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun unsubscribeFromTopics_throwsNotInitializedWhenSDKNotReady() {
        try {
            VisionPush.unsubscribeFromTopics(listOf("news"))
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun optIn_throwsWhenNotInitialized() {
        try {
            VisionPush.optIn()
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun optOut_throwsWhenNotInitialized() {
        try {
            VisionPush.optOut()
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun syncDevice_throwsWhenNotInitialized() {
        try {
            VisionPush.syncDevice()
            fail("Expected VisionPushError.NotInitialized")
        } catch (e: VisionPushError.NotInitialized) {
            assertNotNull(e.message)
        }
    }
}
