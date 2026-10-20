package de.visionlab.visionpush

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Manages Android geofencing via [GeofencingClient].
 *
 * On each periodic location update (every 15 min) it calls PUT /devices/{id}/location
 * to receive the nearest active geofences and registers them with the OS.
 * Geofence transitions are delivered to [GeofenceTransitionReceiver] via PendingIntent,
 * then enqueued to [GeofenceEventWorker] for reliable delivery via WorkManager.
 *
 * Registration is a DIFF against what the OS already holds: only fences that are new
 * (or whose geometry / dwell changed) are added and only dropped ones are removed. The
 * previous implementation removed and re-added ALL fences with `INITIAL_TRIGGER_ENTER`
 * on every 15-minute cycle — the OS then fired ENTER for every fence the device was
 * standing in, every cycle, and each ENTER became an "entry" report and a campaign
 * push. Newly added fences still carry an initial trigger (a user already inside a
 * fence that was just created should get its campaign once); the inside-set in
 * [GeofenceState] is what keeps that from ever repeating.
 *
 * Dwell is evaluated by the OS (`GEOFENCE_TRANSITION_DWELL` + `setLoiteringDelay`),
 * not by a delayed WorkManager job: a Doze-deferred timer used to fire "dwell" without
 * re-checking that the user was still inside.
 *
 * Requires ACCESS_FINE_LOCATION (and ACCESS_BACKGROUND_LOCATION on API 29+ for
 * background geofencing). The host app must request these at runtime.
 */
internal class GeofenceManager(
    private val context: Context,
    private val apiClient: ApiClient
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, t ->
            // A failed location sync must never crash the host app.
            VisionPushLogger.e(TAG, "Unhandled error in geofence sync: ${t.message}", t)
        }
    )
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var deviceId: String? = null
    /** The fence set we believe the OS currently holds (persisted across process death). */
    private var cachedGeofences: List<GeofenceData> = loadCachedGeofences()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { syncGeofences(it.latitude, it.longitude) }
        }
    }

    companion object {
        private const val TAG          = "VP.GeofenceManager"
        private const val PREFS_NAME   = "vp_geofences"
        private const val KEY_GEOFENCES = "geofences"
        private const val MAX_GEOFENCES = 98  // Android limit is 100; keep 2 buffer
        private const val LOCATION_INTERVAL_MS    = 15 * 60 * 1000L
        private const val LOCATION_MIN_INTERVAL_MS = 5 * 60 * 1000L
        private const val REQUEST_CODE_GEOFENCE = 0x4765
        internal const val PREFS_DWELL = "vp_dwell_times"

        /** Returns the configured dwell-time (seconds) for a geofence, or null. */
        internal fun getDwellTimeSecs(context: Context, geofenceId: String): Int? {
            val v = context.getSharedPreferences(PREFS_DWELL, Context.MODE_PRIVATE)
                .getInt(geofenceId, -1)
            return if (v >= 0) v else null
        }

        /**
         * What has to change at the OS to go from [previous] to [next]: fences that are
         * new or whose definition changed are added; fences that were dropped or changed
         * are removed (a changed fence appears in both — remove, then re-add). Pure, so it
         * is unit-testable without Play Services.
         */
        internal fun computeDiff(previous: List<GeofenceData>, next: List<GeofenceData>): GeofenceDiff {
            val prevById = previous.associateBy { it.id }
            val nextById = next.associateBy { it.id }
            val toAdd = next.filter { prevById[it.id] != it }
            val toRemove = previous.filter { nextById[it.id] != it }.map { it.id }
            return GeofenceDiff(toAdd = toAdd, toRemoveIds = toRemove)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun setDeviceId(id: String) {
        deviceId = id
    }

    @SuppressLint("MissingPermission")
    fun start() {
        // Cold start: the OS drops geofences on reboot, so re-add everything we hold.
        // The initial trigger sweep (ENTER|EXIT) is harmless — the receiver only reports
        // state CHANGES, and an EXIT for a fence the user has meanwhile left clears the
        // stale "inside" entry.
        if (cachedGeofences.isNotEmpty()) {
            applyRegistration(toRemoveIds = emptyList(), toAdd = cachedGeofences, next = cachedGeofences)
        }
        if (!hasLocationPermission()) return

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_MIN_INTERVAL_MS)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        geofencingClient.removeGeofences(buildTransitionIntent())
        GeofenceState.clear(context)
    }

    // ── Location Sync ──────────────────────────────────────────────────────────

    private fun syncGeofences(latitude: Double, longitude: Double) {
        val id = deviceId ?: return
        // GDPR/CCPA: the location upload is the most sensitive call the SDK makes.
        if (!VisionPush.hasConsent) return
        scope.launch(Dispatchers.IO) {
            val result = apiClient.updateDeviceLocation(id, latitude, longitude)
            if (result is VisionPushResult.Success) {
                val fences = result.data.take(MAX_GEOFENCES)
                val diff = computeDiff(cachedGeofences, fences)
                if (diff.toAdd.isEmpty() && diff.toRemoveIds.isEmpty()) {
                    VisionPushLogger.d(TAG, "Geofence set unchanged (${fences.size}); nothing to re-register.")
                    return@launch
                }
                applyRegistration(diff.toRemoveIds, diff.toAdd, next = fences)
            }
        }
    }

    // ── Geofence Registration ──────────────────────────────────────────────────

    /**
     * Remove [toRemoveIds], then add [toAdd]; on success the cache becomes [next].
     * If the add fails the cache keeps only what is still registered, so the next
     * sync retries exactly the fences that are missing.
     */
    @SuppressLint("MissingPermission")
    private fun applyRegistration(toRemoveIds: List<String>, toAdd: List<GeofenceData>, next: List<GeofenceData>) {
        if (!hasLocationPermission()) return

        val addedIds = toAdd.map { it.id }.toSet()
        val survivors = cachedGeofences.filter { it.id !in toRemoveIds && it.id !in addedIds }

        val commit: (List<GeofenceData>) -> Unit = { registered ->
            cachedGeofences = registered
            persistCachedGeofences(registered)
            // Forget inside/dwell state for fences the OS no longer watches.
            GeofenceState.retainOnly(context, registered.map { it.id }.toSet())
        }

        val addAll = {
            if (toAdd.isEmpty()) {
                commit(survivors)
            } else {
                geofencingClient.addGeofences(buildRequest(toAdd), buildTransitionIntent())
                    .addOnSuccessListener {
                        VisionPushLogger.d(TAG, "Registered ${toAdd.size} geofences (${toRemoveIds.size} removed).")
                        commit(next)
                    }
                    .addOnFailureListener { e ->
                        VisionPushLogger.w(TAG, "Geofence registration failed: ${e.message}")
                        commit(survivors)
                    }
            }
        }

        if (toRemoveIds.isEmpty()) {
            addAll()
        } else {
            geofencingClient.removeGeofences(toRemoveIds.map { "vp_$it" }).addOnCompleteListener { addAll() }
        }
    }

    private fun buildRequest(fences: List<GeofenceData>): GeofencingRequest {
        val geofences = fences.map { fence ->
            val dwellMs = fence.dwellTimeSecs?.takeIf { it > 0 }?.toLong()?.times(1000L)
            val transitions = if (dwellMs != null) {
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL
            } else {
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            }
            Geofence.Builder()
                .setRequestId("vp_${fence.id}")
                .setCircularRegion(fence.latitude, fence.longitude, fence.radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(transitions)
                .apply {
                    if (dwellMs != null) {
                        // The OS evaluates presence for the whole loitering period.
                        setLoiteringDelay(dwellMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                }
                .build()
        }

        return GeofencingRequest.Builder()
            // Only NEW fences reach this request, so this cannot re-fire for fences that
            // are merely re-synced; the receiver's inside-set gates the rest.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(geofences)
            .build()
    }

    private fun buildTransitionIntent(): PendingIntent {
        val intent = Intent(context, GeofenceTransitionReceiver::class.java).apply {
            action = "de.visionlab.visionpush.GEOFENCE_TRANSITION"
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_GEOFENCE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ── Cache (SharedPreferences) ──────────────────────────────────────────────

    private fun loadCachedGeofences(): List<GeofenceData> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GEOFENCES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { GeofenceData.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    private fun persistCachedGeofences(fences: List<GeofenceData>) {
        val arr = JSONArray()
        fences.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEOFENCES, arr.toString()).apply()

        // Persist dwell times separately so GeofenceTransitionReceiver can read them
        val dwellEdit = context.getSharedPreferences(PREFS_DWELL, Context.MODE_PRIVATE).edit()
        fences.forEach { fence ->
            if (fence.dwellTimeSecs != null) dwellEdit.putInt(fence.id, fence.dwellTimeSecs)
            else dwellEdit.remove(fence.id)
        }
        dwellEdit.apply()
    }
}

/** The registration change between two fence sets — see [GeofenceManager.computeDiff]. */
internal data class GeofenceDiff(
    val toAdd: List<GeofenceData>,
    val toRemoveIds: List<String>
)

// ── GeofenceState ─────────────────────────────────────────────────────────────────

/**
 * Which fences the device is currently inside (and which of those already produced a
 * dwell report), persisted in SharedPreferences so the state survives process death and
 * is shared between [GeofenceManager] and the manifest-registered
 * [GeofenceTransitionReceiver]. Writes use `commit()`: a BroadcastReceiver's process may
 * be torn down right after `onReceive` returns.
 */
internal object GeofenceState {
    private const val PREFS = "vp_geofence_state"
    private const val KEY_INSIDE = "inside"
    private const val KEY_DWELLED = "dwelled"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun inside(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_INSIDE, null)?.toSet() ?: emptySet()

    private fun dwelled(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DWELLED, null)?.toSet() ?: emptySet()

    internal fun isInside(context: Context, geofenceId: String): Boolean = geofenceId in inside(context)

    /** Marks the fence as entered. Returns `true` only when the device was NOT already inside. */
    internal fun markInside(context: Context, geofenceId: String): Boolean {
        val current = inside(context)
        if (geofenceId in current) return false
        prefs(context).edit().putStringSet(KEY_INSIDE, current + geofenceId).commit()
        return true
    }

    /** Marks the fence as left (and forgets its dwell). Returns `true` only when the device WAS inside. */
    internal fun markOutside(context: Context, geofenceId: String): Boolean {
        val current = inside(context)
        if (geofenceId !in current) return false
        prefs(context).edit()
            .putStringSet(KEY_INSIDE, current - geofenceId)
            .putStringSet(KEY_DWELLED, dwelled(context) - geofenceId)
            .commit()
        return true
    }

    /** Records that the dwell for this visit was reported. Returns `true` the first time per visit. */
    internal fun markDwelled(context: Context, geofenceId: String): Boolean {
        val current = dwelled(context)
        if (geofenceId in current) return false
        prefs(context).edit().putStringSet(KEY_DWELLED, current + geofenceId).commit()
        return true
    }

    /** Forget state for fences that are no longer monitored. */
    internal fun retainOnly(context: Context, geofenceIds: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_INSIDE, inside(context).intersect(geofenceIds))
            .putStringSet(KEY_DWELLED, dwelled(context).intersect(geofenceIds))
            .commit()
    }

    internal fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}

// ── GeofenceData ─────────────────────────────────────────────────────────────────

/** Lightweight geofence record used for local caching and OS registration. */
internal data class GeofenceData(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val dwellTimeSecs: Int? = null
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("latitude", latitude)
        put("longitude", longitude)
        put("radius_meters", radiusMeters)
        dwellTimeSecs?.let { put("dwell_time_secs", it) }
    }

    companion object {
        fun fromJson(obj: org.json.JSONObject) = GeofenceData(
            id           = obj.getString("id"),
            latitude     = obj.getDouble("latitude"),
            longitude    = obj.getDouble("longitude"),
            radiusMeters = obj.getInt("radius_meters"),
            dwellTimeSecs = obj.optInt("dwell_time_secs", -1).takeIf { it > 0 }
        )
    }
}
