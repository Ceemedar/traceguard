package com.traceguard.antitheft.tracking

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import com.traceguard.antitheft.database.LocationEntity
import com.traceguard.antitheft.database.TraceGuardDatabase
import kotlinx.coroutines.tasks.await
import java.util.Date

object ReportSync {
    suspend fun sync(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val dao = TraceGuardDatabase.get(context).locationDao()
        val firestore = FirebaseFirestore.getInstance()
        val deviceId = context.getSharedPreferences(TrackingWorker.PREFS, Context.MODE_PRIVATE)
            .getString(TrackingWorker.DEVICE_ID, null) ?: return
        val deviceReference = firestore.collection("users").document(uid).collection("devices").document(deviceId)
        val deviceSnapshot = deviceReference.get().await()
        // A paused or revoked device must not upload queued reports. Reports gathered
        // before a pause remain on-device and can sync after the owner resumes.
        if (!deviceSnapshot.exists() || deviceSnapshot.getString("trackingStatus") != "active") return
        while (true) {
            val reports = dao.pending(uid)
            if (reports.isEmpty()) break
            for (report in reports) {
                val reference = firestore.collection("users").document(uid)
                    .collection("devices").document(report.deviceId)
                    .collection("locations").document(report.locationId)
                if (!reference.get().await().exists()) reference.set(report.toMap()).await()
                dao.deleteSynced(report.locationId)
            }
        }
    }

    suspend fun registerDevice(context: Context, displayName: String, deviceId: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val reference = FirebaseFirestore.getInstance().collection("users").document(user.uid)
            .collection("devices").document(deviceId)
        if (!reference.get().await().exists()) {
            reference.set(
                mapOf(
                    "deviceId" to deviceId,
                    "displayName" to displayName,
                    "platform" to "android",
                    "registeredAt" to FieldValue.serverTimestamp(),
                    "trackingStatus" to "active",
                ),
            ).await()
        }
    }

    private fun LocationEntity.toMap(): Map<String, Any?> = mapOf(
        "locationId" to locationId,
        "latitude" to latitude,
        "longitude" to longitude,
        "accuracyMeters" to accuracyMeters,
        "recordedAt" to Timestamp(Date(recordedAtMillis)),
        "receivedAt" to FieldValue.serverTimestamp(),
        "batteryPercentage" to batteryPercentage,
        "charging" to charging,
        "connectionType" to connectionType,
        "networkOperator" to networkOperator,
        "wifiSsid" to wifiSsid,
        "internetValidated" to internetValidated,
    )
}
