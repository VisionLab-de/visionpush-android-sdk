package de.visionlab.visionpush

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that performs a device registration sync in the background.
 *
 * This worker is enqueued in two scenarios:
 * 1. After [VisionPush.initialize] when the network is not immediately available.
 * 2. After [VisionPushMessagingService.onNewToken] fails due to a network error.
 *
 * WorkManager ensures the sync eventually completes when the network returns,
 * even if the process is killed in the meantime. Exponential back-off prevents
 * hammering a server that may be temporarily unavailable.
 *
 * The work is named with [UNIQUE_WORK_NAME] and uses [ExistingWorkPolicy.REPLACE]
 * so that enqueuing a new sync always cancels a pending one, avoiding
 * duplicate registrations.
 */
internal class RegistrationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        VisionPushLogger.i(TAG, "Registration sync worker starting.")

        val registrar = VisionPush.internalDeviceRegistrar
        if (registrar == null) {
            VisionPushLogger.e(TAG, "SDK not initialized – cannot sync registration.")
            return Result.failure()
        }

        val result = registrar.register(forceRefresh = false)

        return when {
            result.isSuccess -> {
                VisionPushLogger.i(TAG, "Registration sync completed successfully.")
                Result.success()
            }
            result.errorOrNull() is VisionPushError.NetworkError -> {
                VisionPushLogger.w(TAG, "Registration sync failed due to network error – scheduling retry.")
                Result.retry()
            }
            else -> {
                VisionPushLogger.e(TAG, "Registration sync failed permanently: ${result.errorOrNull()?.message}")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "VP.SyncWorker"

        /** Unique name ensures only one registration sync runs at a time. */
        const val UNIQUE_WORK_NAME = "visionpush_registration_sync"

        private const val INITIAL_BACKOFF_SECONDS = 30L

        /**
         * Enqueues a registration sync, replacing any pending sync that is
         * already scheduled. Requires a connected network.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<RegistrationSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INITIAL_BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                .addTag(UNIQUE_WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)

            VisionPushLogger.d(TAG, "Registration sync enqueued.")
        }
    }
}
