package de.visionlab.visionpush

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// MARK: - Helpers

private fun <T> VisionPushResult<T>.toResult(): Result<T> = when (this) {
    is VisionPushResult.Success -> Result.success(data)
    is VisionPushResult.Failure -> Result.failure(error)
}

private suspend fun awaitUnit(
    block: (callback: (VisionPushResult<Unit>) -> Unit) -> Unit
) = suspendCancellableCoroutine<Unit> { cont ->
    block { result ->
        if (cont.isActive) {
            when (result) {
                is VisionPushResult.Success -> cont.resume(Unit)
                is VisionPushResult.Failure -> cont.resumeWithException(result.error)
            }
        }
    }
}

private suspend fun <T> awaitResult(
    block: (callback: (VisionPushResult<T>) -> Unit) -> Unit
): T = suspendCancellableCoroutine { cont ->
    block { result ->
        if (cont.isActive) {
            when (result) {
                is VisionPushResult.Success -> cont.resume(result.data)
                is VisionPushResult.Failure -> cont.resumeWithException(result.error)
            }
        }
    }
}

// MARK: - Suspend Extensions

/** Suspending version of [VisionPush.updateTags]. Throws [VisionPushError] on failure. */
suspend fun VisionPush.updateTagsAsync(tags: Map<String, String>) =
    awaitUnit { cb -> VisionPush.updateTags(tags, cb) }

/** Suspending version of [VisionPush.removeTags]. Throws [VisionPushError] on failure. */
suspend fun VisionPush.removeTagsAsync(keys: List<String>) =
    awaitUnit { cb -> VisionPush.removeTags(keys, cb) }

/** Suspending version of [VisionPush.setUserProfile]. Throws [VisionPushError] on failure. */
suspend fun VisionPush.setUserProfileAsync(profile: UserProfile) =
    awaitUnit { cb -> VisionPush.setUserProfile(profile, cb) }

/** Suspending version of [VisionPush.subscribeToTopics]. Throws [VisionPushError] on failure. */
suspend fun VisionPush.subscribeToTopicsAsync(topics: List<String>) =
    awaitUnit { cb -> VisionPush.subscribeToTopics(topics, cb) }

/** Suspending version of [VisionPush.unsubscribeFromTopics]. Throws [VisionPushError] on failure. */
suspend fun VisionPush.unsubscribeFromTopicsAsync(topics: List<String>) =
    awaitUnit { cb -> VisionPush.unsubscribeFromTopics(topics, cb) }

/** Suspending version of [VisionPush.sendOutcome]. Throws [VisionPushError] on failure. */
suspend fun VisionPush.sendOutcomeAsync(name: String) =
    awaitUnit { cb -> VisionPush.sendOutcome(name, cb) }

/** Suspending version of [VisionPush.sendOutcomeWithValue]. Throws [VisionPushError] on failure. */
suspend fun VisionPush.sendOutcomeWithValueAsync(name: String, value: Float) =
    awaitUnit { cb -> VisionPush.sendOutcomeWithValue(name, value, cb) }

/** Suspending version of [VisionPush.refreshTags]. Returns the refreshed tag map. Throws [VisionPushError] on failure. */
suspend fun VisionPush.refreshTagsAsync(): Map<String, String> =
    awaitResult { cb -> VisionPush.refreshTags(cb) }

/** Suspending version of [VisionPush.login] (US-15). Returns the resulting userId. Throws [VisionPushError] on failure. */
suspend fun VisionPush.loginAsync(externalId: String, externalIdHash: String? = null): String? =
    awaitResult { cb -> VisionPush.login(externalId, externalIdHash, cb) }

/** Suspending version of [VisionPush.logout] (US-15). Returns the new anonymous userId. Throws [VisionPushError] on failure. */
suspend fun VisionPush.logoutAsync(): String? =
    awaitResult { cb -> VisionPush.logout(cb) }

/** Suspending version of [VisionPush.addAlias] (US-15). Throws [VisionPushError] on failure. */
suspend fun VisionPush.addAliasAsync(label: String, id: String, aliasHash: String) =
    awaitUnit { cb -> VisionPush.addAlias(label, id, aliasHash, cb) }

/** Suspending version of [VisionPush.removeAlias] (US-15). Throws [VisionPushError] on failure. */
suspend fun VisionPush.removeAliasAsync(label: String, id: String, aliasHash: String) =
    awaitUnit { cb -> VisionPush.removeAlias(label, id, aliasHash, cb) }

/** Suspending version of [VisionPush.addEmail] (US-15). Throws [VisionPushError] on failure. */
suspend fun VisionPush.addEmailAsync(email: String) =
    awaitUnit { cb -> VisionPush.addEmail(email, cb) }

/** Suspending version of [VisionPush.addSms] (US-15). Throws [VisionPushError] on failure. */
suspend fun VisionPush.addSmsAsync(phoneE164: String) =
    awaitUnit { cb -> VisionPush.addSms(phoneE164, cb) }

// MARK: - Flow Extensions

/** Cold [Flow] that emits every [VisionPushNotification] received while the flow is collected. */
val VisionPush.notificationFlow: Flow<VisionPushNotification>
    get() = callbackFlow {
        val previous = VisionPush.internalNotificationHandler
        VisionPush.setNotificationHandler { notification ->
            trySend(notification)
            previous?.invoke(notification)
        }
        awaitClose { VisionPush.setNotificationHandler(previous) }
    }

/** Cold [Flow] that emits every [VisionPushNotification] opened by the user. */
val VisionPush.notificationOpenFlow: Flow<VisionPushNotification>
    get() = callbackFlow {
        val previous = VisionPush.internalNotificationOpenCallback
        VisionPush.setNotificationOpenHandler { notification ->
            trySend(notification)
            previous?.invoke(notification)
        }
        awaitClose { VisionPush.setNotificationOpenHandler(previous) }
    }
