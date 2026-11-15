package com.traceguard.antitheft.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.traceguard.antitheft.tracking.TrackingControl
import com.traceguard.antitheft.tracking.TrackingService
import com.traceguard.antitheft.tracking.TrackingWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = context.getSharedPreferences(TrackingWorker.PREFS, Context.MODE_PRIVATE)

        val canReceiveRemoteCommands = preferences.getBoolean("owner_consent_v1", false) &&
            preferences.getBoolean("device_registered", false)
        if (canReceiveRemoteCommands) {
            preferences.edit().putBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, true).apply()
        }
        if (preferences.getBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, false) ||
            preferences.getBoolean(TrackingWorker.TRACKING_ENABLED, false)) {
            TrackingControl.startPeriodic(context)
        }
        if (action != Intent.ACTION_BOOT_COMPLETED) return
        if (!preferences.getBoolean(TrackingWorker.AUTO_RESUME_LIVE, false) ||
            !preferences.getBoolean("owner_consent_v1", false) ||
            !preferences.getBoolean("device_registered", false)) return

        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation || !hasBackgroundLocation) return

        try {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        } catch (_: IllegalStateException) {
            // Android or the device manufacturer can deny automatic service startup.
            // Periodic WorkManager reporting remains scheduled as a fallback.
        } catch (_: SecurityException) {
            // Permission changes between boot and service startup leave periodic work as fallback.
        }
    }
}
