package com.traceguard.antitheft.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Uses Google Play location when present and Android's platform providers as a fallback. */
object LocationSource {
    suspend fun getCurrentLocation(context: Context): Location? {
        if (hasGooglePlayServices(context)) {
            try {
                val fused = LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
                    .await()
                if (fused != null) return fused
            } catch (_: Exception) {
                // Fall through to the Android platform location providers.
            }
        }
        return platformCurrentLocation(context)
    }

    fun requestUpdates(
        context: Context,
        intervalMillis: Long,
        minIntervalMillis: Long,
        highAccuracy: Boolean,
        onLocation: (Location) -> Unit,
    ): () -> Unit {
        if (hasGooglePlayServices(context)) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let(onLocation)
                }
            }
            val request = LocationRequest.Builder(
                if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                intervalMillis,
            ).setMinUpdateIntervalMillis(minIntervalMillis).build()
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            return { client.removeLocationUpdates(callback) }
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
        }
        val providers = enabledProviders(manager, highAccuracy)
        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, intervalMillis, 0f, listener, Looper.getMainLooper())
        }
        return { manager.removeUpdates(listener) }
    }

    private suspend fun platformCurrentLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = enabledProviders(manager, highAccuracy = false)
        val cached = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (cached != null && System.currentTimeMillis() - cached.time < 2 * 60_000L) return cached
        if (providers.isEmpty()) return cached

        return withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                lateinit var listener: LocationListener
                val finish: (Location?) -> Unit = { location ->
                    if (continuation.isActive) {
                        runCatching { manager.removeUpdates(listener) }
                        continuation.resume(location)
                    }
                }
                listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = finish(location)
                }
                val timeout = Runnable { finish(cached) }
                continuation.invokeOnCancellation {
                    handler.removeCallbacks(timeout)
                    runCatching { manager.removeUpdates(listener) }
                }
                try {
                    providers.forEach { provider ->
                        manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                    }
                    handler.postDelayed(timeout, 20_000L)
                } catch (_: SecurityException) {
                    finish(cached)
                }
            }
        } ?: cached
    }

    private fun enabledProviders(manager: LocationManager, highAccuracy: Boolean): List<String> {
        val candidates = if (highAccuracy) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.distinct().filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
    }

    private fun hasGooglePlayServices(context: Context): Boolean {
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return hasLocationPermission && GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
}
