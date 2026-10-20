package de.visionlab.visionpush

/**
 * Sealed hierarchy of all errors the VisionPush SDK can surface to callers.
 *
 * Using a sealed class instead of a raw [Exception] lets callers exhaustively
 * handle every error case in a `when` expression without a catch-all branch.
 *
 * Example usage (Kotlin):
 * ```kotlin
 * VisionPush.registerDevice { result ->
 *     when (result) {
 *         is VisionPushResult.Success -> { /* happy path */ }
 *         is VisionPushResult.Failure -> when (val err = result.error) {
 *             is VisionPushError.NotInitialized  -> showSetupError()
 *             is VisionPushError.NetworkError    -> scheduleRetry(err.cause)
 *             is VisionPushError.ApiError        -> log(err.message)
 *             is VisionPushError.PermissionDenied -> promptUser()
 *             is VisionPushError.InvalidConfig    -> log(err.message)
 *             is VisionPushError.InvalidArgument  -> log(err.message)
 *             is VisionPushError.TokenUnavailable -> log(err.message)
 *             is VisionPushError.Unknown          -> log(err.cause)
 *         }
 *     }
 * }
 * ```
 */
sealed class VisionPushError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * Thrown when any SDK method is called before [VisionPush.initialize] has
     * successfully completed.
     */
    class NotInitialized : VisionPushError(
        "VisionPush SDK has not been initialized. " +
                "Call VisionPush.initialize(context, appId, apiKey) first."
    )

    /**
     * A network-level failure occurred while communicating with the API.
     * The original [java.io.IOException] is available via [cause].
     *
     * @property cause The underlying IO exception.
     */
    class NetworkError(cause: Throwable) : VisionPushError(
        "Network error: ${cause.message}",
        cause
    )

    /**
     * The VisionPush API returned an error response.
     *
     * @property httpStatus HTTP status code returned by the server, or -1 if unknown.
     * @property message    Human-readable error message from the body's `error.message`
     *                      (e.g. [EXTERNAL_ID_VERIFICATION_FAILED] — some rejections put
     *                      their specific, machine-readable identifier here rather than
     *                      in [errorCode]; see [isExternalIdVerificationFailed]).
     * @property errorCode  The body's `error.code` (e.g. [IDENTITY_ERASED] — but often a
     *                      shared, generic category like `"forbidden"`/`"conflict"`), or
     *                      `null` when absent.
     */
    class ApiError @JvmOverloads constructor(
        val httpStatus: Int,
        override val message: String,
        val errorCode: String? = null,
    ) : VisionPushError("API error $httpStatus: $message") {

        /**
         * `true` for the server's HTTP 403 `external_id_verification_failed` rejection:
         * identity verification is enabled for the app and the request that set
         * `external_id` carried no — or a wrong — `external_id_hash`.
         *
         * The server answers this as `{"error":{"code":"forbidden","message":
         * "external_id_verification_failed"}}` (`ApiError::Forbidden`, a catch-all whose
         * `code` is the generic literal `"forbidden"` — the specific, machine-readable
         * identifier lives in `message`, not `code`). Matching [message] is therefore the
         * real check; [errorCode] is also compared for any future server revision that
         * moves this identifier into its own dedicated code.
         *
         * The rejection is deterministic, so the SDK never retries it. Fix: call
         * [VisionPush.login] with the hash your backend computed (lowercase hex
         * HMAC-SHA256 of the external id, keyed with the app's identity secret).
         */
        val isExternalIdVerificationFailed: Boolean
            get() = httpStatus == 403 &&
                (errorCode == EXTERNAL_ID_VERIFICATION_FAILED || message == EXTERNAL_ID_VERIFICATION_FAILED)

        /**
         * `true` for the server's HTTP 409 on [VisionPush.addEmail] / [VisionPush.addSms] /
         * [VisionPush.addAlias] (US-15): the address or alias already belongs to a
         * DIFFERENT user — never silently taken over. Retrying with the same value
         * will not succeed; surface it to the user instead (e.g. "already in use").
         *
         * The server answers this as `{"error":{"code":"conflict","message":"..."}}`
         * (`ApiError::Conflict`, shared by several unrelated 409s) — `code` is generic
         * here too, so [httpStatus] is the real (and only reliable) signal.
         */
        val isSubscriptionOwnedByAnotherUser: Boolean
            get() = httpStatus == 409

        /**
         * `true` for the server's HTTP 410 `identity_erased` rejection
         * ([VisionPush.login] naming an `externalId` whose person was erased under
         * GDPR Art. 17): the server never recreates an erased identity from a login,
         * only a server-side create (`POST /v1/apps/{app_id}/users`) can.
         *
         * Unlike [isExternalIdVerificationFailed], this rejection DOES get its own
         * dedicated code — `{"error":{"code":"identity_erased","message":"..."}}`
         * (`ApiError::IdentityErased`) — so [errorCode] is the real check.
         *
         * The rejection is deterministic: drop the cached external id locally rather
         * than retrying the same login forever.
         */
        val isIdentityErased: Boolean
            get() = httpStatus == 410 && errorCode == IDENTITY_ERASED

        companion object {
            /** Server error code for a rejected, unverified `external_id` (HTTP 403). */
            const val EXTERNAL_ID_VERIFICATION_FAILED = "external_id_verification_failed"

            /** Server error code for a login naming an erased identity (HTTP 410). */
            const val IDENTITY_ERASED = "identity_erased"
        }
    }

    /**
     * The user denied the `POST_NOTIFICATIONS` runtime permission (API 33+) or
     * notifications are disabled in system settings.
     */
    class PermissionDenied : VisionPushError(
        "Notification permission denied. " +
                "Call VisionPush.requestPermission(activity) to prompt the user."
    )

    /**
     * The SDK configuration is invalid (e.g. blank [VisionPushConfig.appId] or
     * malformed [VisionPushConfig.apiKey]).
     *
     * @property message Description of the specific configuration problem.
     */
    class InvalidConfig(override val message: String) : VisionPushError(message)

    /**
     * The FCM token is not yet available. This is a transient state that
     * resolves automatically; the SDK will retry registration when FCM delivers
     * a new token via [VisionPushMessagingService.onNewToken].
     *
     * @property message Additional context about why the token is unavailable.
     */
    class TokenUnavailable(override val message: String = "FCM token not yet available.") :
        VisionPushError(message)

    /**
     * A method was called with an argument that violates the SDK's documented
     * constraints (e.g. [VisionPush.login] called with a string longer
     * than 1 024 characters).
     *
     * @property message Description of the specific constraint that was violated.
     */
    class InvalidArgument(override val message: String) : VisionPushError(message)

    /**
     * A network operation was blocked because `VisionPushConfig.consentRequired` is `true`
     * and the user has not yet granted consent via [VisionPush.setConsentGiven].
     *
     * This error is thrown (or returned as [VisionPushResult.Failure]) by any method
     * that would send data to the VisionPush backend.
     */
    class ConsentRequired : VisionPushError(
        "User consent is required before data can be collected. " +
                "Call VisionPush.setConsentGiven(true) to activate the SDK."
    )

    /**
     * An unexpected error that does not fit any of the specific categories above.
     *
     * @property cause The underlying throwable for diagnostic purposes.
     */
    class Unknown(cause: Throwable) : VisionPushError(
        "Unexpected error: ${cause.message}",
        cause
    )
}

// ── Result wrapper ──────────────────────────────────────────────────────────────

/**
 * A discriminated union that wraps either a successful value [T] or a
 * [VisionPushError]. Modelled after Kotlin's stdlib [Result] but exposes
 * the concrete error type to callers.
 */
sealed class VisionPushResult<out T> {

    /** Represents a successful operation carrying value [data]. */
    data class Success<T>(val data: T) : VisionPushResult<T>()

    /** Represents a failed operation carrying a typed [error]. */
    data class Failure(val error: VisionPushError) : VisionPushResult<Nothing>()

    // ── Convenience extensions ─────────────────────────────────────────────

    /** Returns `true` when this result is [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** Returns `true` when this result is [Failure]. */
    val isFailure: Boolean get() = this is Failure

    /**
     * Returns the data value if this is [Success], or `null` otherwise.
     * Prefer exhaustive `when` expressions over this method in Kotlin callers.
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Returns the error if this is [Failure], or `null` otherwise.
     */
    fun errorOrNull(): VisionPushError? = (this as? Failure)?.error

    /**
     * Transforms the data value with [transform] if this is [Success],
     * propagating [Failure] unchanged.
     */
    fun <R> map(transform: (T) -> R): VisionPushResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    /**
     * Executes [block] when this is [Success] and returns `this` for chaining.
     */
    fun onSuccess(block: (T) -> Unit): VisionPushResult<T> {
        if (this is Success) block(data)
        return this
    }

    /**
     * Executes [block] when this is [Failure] and returns `this` for chaining.
     */
    fun onFailure(block: (VisionPushError) -> Unit): VisionPushResult<T> {
        if (this is Failure) block(error)
        return this
    }

    companion object {
        /** Wraps [block] in a [VisionPushResult], catching [VisionPushError]s. */
        inline fun <T> runCatching(block: () -> T): VisionPushResult<T> = try {
            Success(block())
        } catch (e: VisionPushError) {
            Failure(e)
        } catch (e: Exception) {
            Failure(VisionPushError.Unknown(e))
        }
    }
}
