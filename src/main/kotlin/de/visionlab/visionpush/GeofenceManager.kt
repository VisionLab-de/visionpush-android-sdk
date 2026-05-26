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
 * Requires ACCESS_FINE_LOCATION (and ACCESS_BACKGROUND_LOCATION on API 29+ for
 * background geofencing). The host app must request these at runtime.
 */
internal class GeofenceManager(
    private val context: Context,
    private val apiClient: ApiClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var deviceId: String? = null
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
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun setDeviceId(id: String) {
        deviceId = id
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (cachedGeofences.isNotEmpty()) {
            registerGeofences(cachedGeofences)
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
    }

    // ── Location Sync ──────────────────────────────────────────────────────────

    private fun syncGeofences(latitude: Double, longitude: Double) {
        val id = deviceId ?: return
        scope.launch(Dispatchers.IO) {
            val result = apiClient.updateDeviceLocation(id, latitude, longitude)
            if (result is VisionPushResult.Success) {
                val fences = result.data.take(MAX_GEOFENCES)
                cachedGeofences = fences
                persistCachedGeofences(fences)
                registerGeofences(fences)
            }
        }
    }

    // ── Geofence Registration ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun registerGeofences(fences: List<GeofenceData>) {
        if (!hasLocationPermission() || fences.isEmpty()) return

        val geofences = fences.map { fence ->
            Geofence.Builder()
                .setRequestId("vp_${fence.id}")
                .setCircularRegion(fence.latitude, fence.longitude, fence.radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        geofencingClient.removeGeofences(buildTransitionIntent()).addOnCompleteListener {
            geofencingClient.addGeofences(request, buildTransitionIntent())
                .addOnSuccessListener { VisionPushLogger.d(TAG, "Registered ${geofences.size} geofences.") }
                .addOnFailureListener { e -> VisionPushLogger.w(TAG, "Geofence registration failed: ${e.message}") }
        }
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
