package com.traceguard.antitheft.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import com.traceguard.antitheft.battery.BatteryRepository
import com.traceguard.antitheft.database.LocationEntity
import com.traceguard.antitheft.database.TraceGuardDatabase
import com.traceguard.antitheft.network.NetworkRepository
import kotlinx.coroutines.tasks.await
import java.util.UUID

class TrackingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Keep this worker scheduled after the owner has enabled tracking once. It must
        // continue checking the cloud status while paused so a dashboard resume can
        // take effect without opening the phone.
        if (!preferences.getBoolean(REMOTE_CONTROL_ENABLED, false)) return Result.success()
        val ownerUid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        val deviceId = preferences.getString(DEVICE_ID, null) ?: return Result.success()
        val remoteStatus = try {
            FirebaseFirestore.getInstance().collection("users").document(ownerUid)
                .collection("devices").document(deviceId).get().await()
                .takeIf { it.exists() }?.getString("trackingStatus") ?: "revoked"
        } catch (_: Exception) {
            return Result.retry()
        }
        if (remoteStatus != "active") {
            preferences.edit().putBoolean(TRACKING_ENABLED, false).apply()
            applicationContext.stopService(Intent(applicationContext, TrackingService::class.java))
            if (remoteStatus != "paused") {
                preferences.edit().putBoolean(REMOTE_CONTROL_ENABLED, false).apply()
                TrackingControl.stopPeriodic(applicationContext)
            }
            return Result.success()
        }
        preferences.edit().putBoolean(TRACKING_ENABLED, true).apply()
        val hasLocation = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return Result.success()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return Result.success()

        val report = try { collect(ownerUid) } catch (_: SecurityException) { return Result.success() }
            catch (_: Exception) { return Result.retry() }
            ?: return Result.success()
        val dao = TraceGuardDatabase.get(applicationContext).locationDao()
        dao.insert(report)
        val queued = dao.count()
        if (queued > 500) dao.trimOldest(queued - 500)
        TrackingControl.enqueueSync(applicationContext)
        return try {
            ReportSync.sync(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun collect(ownerUid: String): LocationEntity? {
        val location = LocationSource.getCurrentLocation(applicationContext) ?: return null
        val battery = BatteryRepository(applicationContext).read()
        val network = NetworkRepository(applicationContext).read()
        val deviceId = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DEVICE_ID, null) ?: return null
        return LocationEntity(
            locationId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            ownerUid = ownerUid,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.toDouble().coerceAtMost(100000.0),
            recordedAtMillis = location.time,
            batteryPercentage = battery.percentage,
            charging = battery.charging,
            connectionType = network.connectionType,
            networkOperator = network.networkOperator,
            wifiSsid = network.wifiSsid,
            internetValidated = network.internetValidated,
        )
    }

    companion object {
        const val PREFS = "traceguard"
        const val DEVICE_ID = "device_id"
        const val TRACKING_ENABLED = "tracking_enabled"
        const val REMOTE_CONTROL_ENABLED = "remote_control_enabled"
        const val AUTO_RESUME_LIVE = "auto_resume_live_after_boot"
    }
}
