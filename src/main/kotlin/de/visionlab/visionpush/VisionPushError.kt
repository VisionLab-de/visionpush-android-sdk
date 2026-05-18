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
 *             is VisionPushError.InvalidConfig   -> log(err.message)
 *             is VisionPushError.TokenUnavailable -> log(err.message)
 *             is VisionPushError.Unknown         -> log(err.cause)
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
     * @property message    Human-readable error message from the server response body.
     */
    class ApiError(val httpStatus: Int, override val message: String) : VisionPushError(
        "API error $httpStatus: $message"
    )

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
