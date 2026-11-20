package online.rushcounty.aligner.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Fix(
    val lat: Double,
    val lon: Double,
    /** Altitude AMSL (m) when available; otherwise WGS84 ellipsoid height (see [altIsMsl]). */
    val altMeters: Double,
    val altIsMsl: Boolean,
    val horizontalAccuracyM: Float?,
    val verticalAccuracyM: Float?,
    /** Magnetic declination at this fix, degrees, east-positive. true = magnetic + declination. */
    val declinationDeg: Double,
    val timeMillis: Long,
)

class LocationProvider(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val altConverter by lazy { if (Build.VERSION.SDK_INT >= 34) AltitudeConverter() else null }

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::handle)
        }
    }

    @SuppressLint("MissingPermission") // caller checks permission
    fun start() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { loc -> if (loc != null && _fix.value == null) handle(loc) }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    private fun handle(loc: Location) {
        // Ellipsoid -> MSL matters for elevation angles to nearby towers (geoid is ~-33 m in Indiana).
        if (Build.VERSION.SDK_INT >= 34 && !loc.hasMslAltitude() && loc.hasAltitude()) {
            scope.launch {
                runCatching { altConverter?.addMslAltitudeToLocation(context, loc) }
                publish(loc)
            }
        } else {
            publish(loc)
        }
    }

    private fun publish(loc: Location) {
        val msl = Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()
        val alt = when {
            msl -> loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude
            else -> 0.0
        }
        val decl = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(), alt.toFloat(), System.currentTimeMillis()
        ).declination.toDouble()
        _fix.value = Fix(
            lat = loc.latitude,
            lon = loc.longitude,
            altMeters = alt,
            altIsMsl = msl,
            horizontalAccuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            verticalAccuracyM = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else null,
            declinationDeg = decl,
            timeMillis = loc.time,
        )
    }
}
