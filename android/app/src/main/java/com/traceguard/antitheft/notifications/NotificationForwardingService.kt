package com.traceguard.antitheft.notifications

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.traceguard.antitheft.tracking.TrackingWorker
import java.security.MessageDigest
import java.util.Date

class NotificationForwardingService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        val preferences = getSharedPreferences(TrackingWorker.PREFS, MODE_PRIVATE)
        preferences.edit().putBoolean(NOTIFICATION_LISTENER_CONNECTED, true).apply()
        if (!preferences.getBoolean(NOTIFICATIONS_ENABLED, false)) return
        try {
            val active = activeNotifications ?: return
            Log.i(TAG, "Listener connected; forwarding ${active.size} currently active notifications")
            active.forEach(::onNotificationPosted)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read currently active notifications (${error.javaClass.simpleName})")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val preferences = getSharedPreferences(TrackingWorker.PREFS, MODE_PRIVATE)
        preferences.edit().putBoolean(NOTIFICATION_LISTENER_CONNECTED, false).apply()
        if (!preferences.getBoolean(NOTIFICATIONS_ENABLED, false)) return
        try {
            requestRebind(ComponentName(this, NotificationForwardingService::class.java))
            Log.w(TAG, "Listener disconnected; requested system rebind")
        } catch (error: Exception) {
            Log.w(TAG, "Could not request listener rebind (${error.javaClass.simpleName})")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val preferences = getSharedPreferences(TrackingWorker.PREFS, MODE_PRIVATE)
        if (!preferences.getBoolean(NOTIFICATIONS_ENABLED, false)) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification
        val extras = notification.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim().orEmpty().take(300)
        val body = notificationBody(extras).take(2_000)
        if (title.isBlank() && body.isBlank()) return

        val appName = applicationName(sbn.packageName).take(100)
        val notificationId = stableNotificationId(sbn)
        val postedAt = Timestamp(Date(sbn.postTime))
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val deviceId = getSharedPreferences(TrackingWorker.PREFS, MODE_PRIVATE)
            .getString(TrackingWorker.DEVICE_ID, null) ?: return
        val reference = FirebaseFirestore.getInstance().collection("users").document(user.uid)
            .collection("devices").document(deviceId)
            .collection("notifications").document(notificationId)
        try {
            // Firestore's Android disk cache queues this write while offline and syncs it
            // when connectivity returns, so don't tie submission to this listener's life.
            reference.set(
                mapOf(
                    "notificationId" to notificationId,
                    "appName" to appName,
                    "packageName" to sbn.packageName.take(200),
                    "title" to title,
                    "body" to body,
                    "postedAt" to postedAt,
                    "receivedAt" to FieldValue.serverTimestamp(),
                ),
            ).addOnSuccessListener { Log.i(TAG, "Forwarded one notification event") }
                .addOnFailureListener { error -> Log.w(TAG, "Notification forwarding failed (${error.javaClass.simpleName})") }
        } catch (error: Exception) {
            Log.w(TAG, "Could not queue notification (${error.javaClass.simpleName})")
        }
    }

    private fun notificationBody(extras: android.os.Bundle): String {
        val longText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        if (longText.isNotBlank()) return longText
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) return text
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?.joinToString("\n")
            .orEmpty()
        if (lines.isNotBlank()) return lines
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.mapNotNull { item -> (item as? Bundle)?.getCharSequence("text")?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?.distinct()
            ?.joinToString("\n")
        if (!messages.isNullOrBlank()) return messages
        return (extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))?.toString()?.trim().orEmpty()
    }

    private fun applicationName(packageName: String): String = runCatching {
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(appInfo).toString()
    }.getOrDefault(packageName)

    private fun stableNotificationId(sbn: StatusBarNotification): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("${sbn.key}|${sbn.postTime}".toByteArray())
        return bytes.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    override fun onDestroy() {
        getSharedPreferences(TrackingWorker.PREFS, MODE_PRIVATE).edit()
            .putBoolean(NOTIFICATION_LISTENER_CONNECTED, false)
            .apply()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TraceGuardNotifRelay"
        const val NOTIFICATIONS_ENABLED = "notification_forwarding_enabled"
        const val NOTIFICATION_LISTENER_CONNECTED = "notification_listener_connected"
        const val NOTIFICATION_CONSENT = "notification_forwarding_consent_v1"
        const val NOTIFICATION_SETUP_PENDING = "notification_access_setup_pending"
    }
}
