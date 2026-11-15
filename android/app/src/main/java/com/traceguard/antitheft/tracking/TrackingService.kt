package com.traceguard.antitheft.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.traceguard.antitheft.battery.BatteryRepository
import com.traceguard.antitheft.database.LocationEntity
import com.traceguard.antitheft.database.TraceGuardDatabase
import com.traceguard.antitheft.network.NetworkRepository
import com.traceguard.antitheft.setup.SetupActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class TrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var removeLocationUpdates: (() -> Unit)? = null
    private var statusListener: ListenerRegistration? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        listenForRemotePause()
        if (!hasLocationPermission() || !hasBackgroundLocationPermission()) { stopSelf(); return }
        try {
            removeLocationUpdates = LocationSource.requestUpdates(
                context = this,
                intervalMillis = 60_000L,
                minIntervalMillis = 30_000L,
                highAccuracy = true,
            ) { location -> serviceScope.launch { saveReport(location) } }
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun listenForRemotePause() {
        val preferences = getSharedPreferences(TrackingWorker.PREFS, Context.MODE_PRIVATE)
        val deviceId = preferences.getString(TrackingWorker.DEVICE_ID, null) ?: return
        val ownerUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        statusListener = FirebaseFirestore.getInstance().collection("users").document(ownerUid)
            .collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot == null || !snapshot.exists() || snapshot.getString("trackingStatus") != "active") {
                    preferences.edit().putBoolean(TrackingWorker.TRACKING_ENABLED, false).apply()
                    stopSelf()
                }
            }
    }

    private suspend fun saveReport(location: android.location.Location) {
        val preferences = getSharedPreferences(TrackingWorker.PREFS, Context.MODE_PRIVATE)
        val deviceId = preferences.getString(TrackingWorker.DEVICE_ID, null) ?: return
        val ownerUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val battery = BatteryRepository(this).read()
        val network = NetworkRepository(this).read()
        val report = LocationEntity(
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
        val dao = TraceGuardDatabase.get(this).locationDao()
        dao.insert(report)
        val queued = dao.count()
        if (queued > 500) dao.trimOldest(queued - 500)
        TrackingControl.enqueueSync(this)
        runCatching { ReportSync.sync(this) }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "TraceGuard active tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Required status notification while you have active tracking on"
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TraceGuard tracking is active")
            .setContentText("Location reporting is on. Stop it from the app at any time.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        statusListener?.remove()
        removeLocationUpdates?.invoke()
        serviceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "traceguard-location"
        const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.traceguard.antitheft.STOP_TRACKING"
    }
}
