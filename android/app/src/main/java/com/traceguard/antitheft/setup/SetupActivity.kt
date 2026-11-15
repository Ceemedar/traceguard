package com.traceguard.antitheft.setup

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.activity.ComponentActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.traceguard.antitheft.BuildConfig
import com.traceguard.antitheft.notifications.NotificationForwardingService
import com.traceguard.antitheft.tracking.TrackingControl
import com.traceguard.antitheft.tracking.TrackingService
import com.traceguard.antitheft.tracking.TrackingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class SetupActivity : ComponentActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var status: TextView
    private var foregroundRequestPending = false

    private val foregroundPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasForegroundLocation()) {
            setStatus("Location permission is ready. Android may still delay background work.")
            if (foregroundRequestPending) requestBackgroundLocation()
        } else {
            foregroundRequestPending = false
            setStatus("Location permission was not granted. Tracking stays off.")
        }
    }

    private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startForegroundTracking() else showRequiredNotificationNotice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(15, 25, 19)
        window.navigationBarColor = android.graphics.Color.rgb(15, 25, 19)
        render()
        val preferences = getPreferences()
        // Migrate previously registered, consented installs into remote-control
        // polling even if they were paused locally before this app update.
        val previouslyEnabled = preferences.getBoolean(TrackingWorker.TRACKING_ENABLED, false)
        val canReceiveRemoteCommands = previouslyEnabled ||
            (preferences.getBoolean(DEVICE_REGISTERED, false) && preferences.getBoolean(CONSENT_KEY, false))
        if (canReceiveRemoteCommands) {
            preferences.edit { putBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, true) }
        }
        if (preferences.getBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, false)) TrackingControl.startPeriodic(this)
    }

    private fun render() {
        val outer = ScrollView(this).apply { setBackgroundColor(rgb(15, 25, 19)) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        outer.addView(content)
        setContentView(outer)

        addRootText("TRACEGUARD", 11f, rgb(124, 205, 149), true, 2)
        addRootText("Your phone. Your security. Your control.", 26f, rgb(242, 247, 242), true, 14)
        addRootText("A private, owner-operated recovery tool. Reports are best-effort and may be delayed by Android, permissions, battery settings, or network availability.", 14f, rgb(164, 181, 168), false, 20)

        if (!getPreferences().getBoolean(CONSENT_KEY, false)) {
            card {
                text("Before you enable tracking", 18f, rgb(238, 244, 239), true, 10f)
                text("TraceGuard collects your phone’s location, accuracy, report time, battery state, and permitted network details. It saves reports on this phone while offline and syncs them to your private Firebase account. Wi-Fi name may be unavailable.", 13f, rgb(178, 193, 181), false, 12f)
                text("You can pause or stop tracking here. Android’s location indicators and required foreground-service notification remain visible. This app cannot guarantee uninterrupted tracking after force-stop, power-off, permission changes, or battery restrictions.", 13f, rgb(178, 193, 181), false, 14f)
                button("I understand and consent", true) {
                    getPreferences().edit { putBoolean(CONSENT_KEY, true) }
                    render()
                    enableRemoteCommandsIfReady()
                    setStatus("Remote background controls are ready. The dashboard can start periodic reports when this phone is online and has all-time location access.")
                }
            }
        }

        val currentUser = auth.currentUser
        if (currentUser == null) renderSignIn() else renderSignedIn(currentUser)
        status = addRootText("", 12f, rgb(176, 193, 180), false, 14)
        status.visibility = if (getPreferences().getBoolean(CONSENT_KEY, false)) View.VISIBLE else View.GONE
        addRootText("TraceGuard remains visible in Android Settings. You can uninstall it or change permissions at any time.", 11f, rgb(122, 142, 128), false, 12)
    }

    private fun renderSignIn() {
        card {
            text("Owner account", 18f, rgb(238, 244, 239), true, 4f)
            text("Use the same email and password on your private dashboard.", 12f, rgb(154, 172, 159), false, 12f)
            emailInput = input("Email address", false)
            passwordInput = input("Password (12+ characters recommended)", true)
            button("Sign in", true) { signIn() }
            button("Create account", false) { createAccount() }
        }
    }

    private fun renderSignedIn(user: FirebaseUser) {
        card {
            text("Signed in as", 11f, rgb(129, 153, 136), true, 4f)
            text(user.email ?: "TraceGuard owner", 16f, rgb(231, 240, 233), true, 7f)
            text("Account ID: …${user.uid.takeLast(4)} · ${if (user.isEmailVerified) "verified" else "email not verified"}", 11f, rgb(169, 187, 175), false, 10f)
            text("Device: ${Build.MODEL}", 12f, rgb(169, 187, 175), false, 10f)
            button("Register or restore this phone", false) { registerPhone(user, explicit = true) }
            button("Enable periodic reports (about every 15 minutes, best-effort)", true) { enablePeriodic() }
            text("Live tracking continues while the screen is locked and is set to resume after reboot when started here. It requires Allow all the time location and keeps Android’s tracking notification visible. Pause and stop disables auto-resume.", 12f, rgb(155, 173, 160), false, 10f)
            button("Start live tracking (resume after reboot)", false) { startActiveTracking() }
            button("Pause and stop tracking", false) { pauseTracking() }
            button("Sign out", false) { signOut() }
            if (getPreferences().getBoolean(DEVICE_REGISTERED, false)) {
                button(if (launcherIconHidden()) "Show launcher icon" else "Hide launcher icon", false) {
                    if (launcherIconHidden()) setLauncherIconHidden(false)
                    else confirmHideLauncherIcon()
                }
            }
        }
        if (getPreferences().getBoolean(DEVICE_REGISTERED, false)) {
            val hasAccess = hasNotificationListenerAccess()
            val forwarding = getPreferences().getBoolean(NotificationForwardingService.NOTIFICATIONS_ENABLED, false) && hasAccess
            card {
                text("Forward notifications to dashboard", 18f, rgb(238, 244, 239), true, 8f)
                text("Optional. Enable Notification Access separately on each phone. TraceGuard forwards notification app names, titles, available text, and times to your private Firebase account, including while offline for sync when internet returns. Some apps or Android privacy settings redact content; work-profile and restricted notifications may not be available.", 12f, rgb(178, 193, 181), false, 12f)
                val listenerConnected = getPreferences().getBoolean(NotificationForwardingService.NOTIFICATION_LISTENER_CONNECTED, false)
                val forwardingStatus = when {
                    !hasAccess -> "Android notification access is not granted."
                    !forwarding -> "Android access is granted; forwarding is paused."
                    listenerConnected -> "Forwarding is on and Android’s listener is connected."
                    else -> "Forwarding is enabled; waiting for Android’s listener to connect."
                }
                text(forwardingStatus, 11f, if (forwarding && listenerConnected) rgb(124, 205, 149) else rgb(176, 193, 180), true, 9f)
                val hasDisclosureConsent = getPreferences().getBoolean(NotificationForwardingService.NOTIFICATION_CONSENT, false)
                button(if (forwarding) "Pause forwarding" else if (hasAccess && hasDisclosureConsent) "Resume forwarding" else "Enable notification forwarding", !forwarding) {
                    if (forwarding) pauseNotificationForwarding()
                    else if (hasAccess && hasDisclosureConsent) resumeNotificationForwarding()
                    else confirmNotificationForwarding()
                }
                button("Open Android notification access settings", false) { openNotificationAccessSettings() }
            }
        }
        if (getPreferences().getBoolean(CONSENT_KEY, false)) {
            addRootText("The dashboard can start periodic background reports after this phone is registered and consented. Android schedules those checks, usually about every 15 minutes. Continuous live tracking must be started here and keeps Android’s persistent tracking notification visible.", 12f, rgb(155, 173, 160), false, 14)
        }
    }

    private fun signIn() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) { setStatus("Enter your email and password."); return }
        setStatus("Signing in…")
        auth.signInWithEmailAndPassword(email, password).addOnSuccessListener {
            if (it.user?.isEmailVerified == false) {
                val accountHint = it.user?.uid?.takeLast(4).orEmpty()
                auth.signOut()
                render()
                setStatus("This unverified account (…$accountHint) is different from the verified dashboard account. Sign in with the dashboard account’s password.")
                return@addOnSuccessListener
            }
            setStatus("Signed in. Registering this phone…")
            registerPhone(it.user)
        }.addOnFailureListener { setStatus(it.localizedMessage ?: "Sign-in failed.") }
    }

    private fun createAccount() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.length < 12) { setStatus("Enter an email and a password with at least 12 characters."); return }
        setStatus("Creating your account…")
        auth.createUserWithEmailAndPassword(email, password).addOnSuccessListener { result ->
            result.user?.sendEmailVerification()
            auth.signOut()
            render()
            setStatus("Account created. Verify it, then sign in with the same verified account you use on the dashboard.")
        }.addOnFailureListener { setStatus(it.localizedMessage ?: "Account creation failed.") }
    }

    private fun registerPhone(user: FirebaseUser?, explicit: Boolean = false) {
        if (user == null) { setStatus("Sign in before registering this phone."); return }
        if (!user.isEmailVerified) {
            setStatus("Verify this email and sign in with the verified dashboard account before registering the phone.")
            return
        }
        val prefs = getPreferences()
        var deviceId = prefs.getString(TrackingWorker.DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(TrackingWorker.DEVICE_ID, it) }
        }
        val previouslyRegistered = prefs.getBoolean(DEVICE_REGISTERED, false)
        screenScope.launch {
            try {
                // Refresh the Firebase ID token before touching owner-scoped Firestore data.
                // This also prevents a restored/stale local auth session from silently failing
                // every device read and background sync with PERMISSION_DENIED.
                user.getIdToken(true).await()
                if (auth.currentUser?.uid != user.uid) {
                    setStatus("Your sign-in changed while registering. Sign in again and retry.")
                    return@launch
                }
                var ref = FirebaseFirestore.getInstance().collection("users").document(user.uid)
                    .collection("devices").document(deviceId)
                var exists = ref.get().await().exists()
                if (!exists && previouslyRegistered && !explicit) {
                    setStatus("This phone was removed from the cloud account. Use ‘Register or restore’ to create a new device record. Unsent reports for the removed device will be cleared.")
                    return@launch
                }
                if (!exists && previouslyRegistered && explicit) {
                    com.traceguard.antitheft.database.TraceGuardDatabase.get(this@SetupActivity).locationDao().deleteDeviceReports(deviceId)
                    deviceId = UUID.randomUUID().toString()
                    prefs.edit { putString(TrackingWorker.DEVICE_ID, deviceId) }
                    ref = FirebaseFirestore.getInstance().collection("users").document(user.uid)
                        .collection("devices").document(deviceId)
                    exists = ref.get().await().exists()
                }
                if (!exists) {
                    ref.set(mapOf(
                        "deviceId" to deviceId,
                        "displayName" to Build.MODEL.take(80),
                        "platform" to "android",
                        "registeredAt" to FieldValue.serverTimestamp(),
                        "trackingStatus" to "paused",
                    )).await()
                }
                prefs.edit { putBoolean(DEVICE_REGISTERED, true) }
                enableRemoteCommandsIfReady()
                TrackingControl.enqueueSync(this@SetupActivity)
                setStatus("This phone is registered to your account.")
                render()
            } catch (error: Exception) {
                setStatus(error.localizedMessage ?: "Could not register this phone. Check your connection and try again.")
            }
        }
    }

    private fun enablePeriodic() {
        if (!getPreferences().getBoolean(CONSENT_KEY, false)) { setStatus("Read and accept the tracking disclosure first."); return }
        if (auth.currentUser == null) { setStatus("Sign in before enabling tracking."); return }
        foregroundRequestPending = true
        if (!hasForegroundLocation()) {
            foregroundPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        } else requestBackgroundLocation()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            activatePeriodic()
            return
        }
        foregroundRequestPending = false
        AlertDialog.Builder(this)
            .setTitle("Allow periodic reports")
            .setMessage("For reports while TraceGuard is not open, Android requires Location access set to ‘Allow all the time’. On the next screen, choose Location, then Allow all the time. Android still controls when background work runs.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open app settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.show()
    }

    override fun onResume() {
        super.onResume()
        val wasPending = foregroundRequestPending
        if (wasPending && hasBackgroundLocation()) activatePeriodic()
        refreshNotificationForwardingState()
        if (::status.isInitialized && auth.currentUser != null) render()
    }

    private fun confirmNotificationForwarding() {
        AlertDialog.Builder(this)
            .setTitle("Forward notification content?")
            .setMessage("This optional feature sends notification app names, titles, text, and times to your private dashboard. Notification previews may contain private messages and one-time codes. You can pause forwarding here or revoke Notification Access in Android settings at any time.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                getPreferences().edit {
                    putBoolean(NotificationForwardingService.NOTIFICATION_CONSENT, true)
                    putBoolean(NotificationForwardingService.NOTIFICATION_SETUP_PENDING, true)
                }
                openNotificationAccessSettings()
            }
            .show()
    }

    private fun openNotificationAccessSettings() {
        val preferences = getPreferences()
        if (preferences.getBoolean(NotificationForwardingService.NOTIFICATION_CONSENT, false) && !hasNotificationListenerAccess()) {
            preferences.edit { putBoolean(NotificationForwardingService.NOTIFICATION_SETUP_PENDING, true) }
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            preferences.edit { putBoolean(NotificationForwardingService.NOTIFICATION_SETUP_PENDING, false) }
            setStatus("Android could not open Notification Access settings. Open Settings and search for ‘Notification access’. ")
        }
    }

    private fun pauseNotificationForwarding() {
        getPreferences().edit { putBoolean(NotificationForwardingService.NOTIFICATIONS_ENABLED, false) }
        render()
        setStatus("Notification forwarding is paused. Android Notification Access remains granted until you revoke it in system settings.")
    }

    private fun resumeNotificationForwarding() {
        if (!hasNotificationListenerAccess()) {
            getPreferences().edit { putBoolean(NotificationForwardingService.NOTIFICATIONS_ENABLED, false) }
            render()
            setStatus("Grant TraceGuard Notification Access in Android settings before resuming.")
            return
        }
        getPreferences().edit { putBoolean(NotificationForwardingService.NOTIFICATIONS_ENABLED, true) }
        render()
        setStatus("Notification forwarding is on.")
    }

    private fun refreshNotificationForwardingState() {
        if (!::status.isInitialized) return
        val preferences = getPreferences()
        val wasEnabled = preferences.getBoolean(NotificationForwardingService.NOTIFICATIONS_ENABLED, false)
        val wasPending = preferences.getBoolean(NotificationForwardingService.NOTIFICATION_SETUP_PENDING, false)
        val hasAccess = hasNotificationListenerAccess()
        if (!hasAccess) {
            preferences.edit {
                putBoolean(NotificationForwardingService.NOTIFICATIONS_ENABLED, false)
                putBoolean(NotificationForwardingService.NOTIFICATION_SETUP_PENDING, false)
            }
            if (wasEnabled || wasPending) {
                render()
                setStatus(if (wasPending) "Notification forwarding was not enabled. Grant Notification Access in Android settings to continue." else "Android Notification Access was removed. Notification forwarding is off.")
            }
        } else if (wasPending && preferences.getBoolean(NotificationForwardingService.NOTIFICATION_CONSENT, false)) {
            preferences.edit {
                putBoolean(NotificationForwardingService.NOTIFICATIONS_ENABLED, true)
                putBoolean(NotificationForwardingService.NOTIFICATION_SETUP_PENDING, false)
            }
            render()
            setStatus("Notification forwarding is on. New notifications will appear on your dashboard.")
        }
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val component = ComponentName(this, NotificationForwardingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        }
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabledListeners.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
    }

    private fun activatePeriodic() {
        foregroundRequestPending = false
        getPreferences().edit {
            putBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, true)
            putBoolean(TrackingWorker.TRACKING_ENABLED, true)
        }
        TrackingControl.startPeriodic(this)
        updateRemoteStatus("active")
        setStatus("Periodic reports are enabled. Android chooses the exact run time.")
    }

    private fun enableRemoteCommandsIfReady() {
        val preferences = getPreferences()
        if (auth.currentUser == null ||
            !preferences.getBoolean(DEVICE_REGISTERED, false) ||
            !preferences.getBoolean(CONSENT_KEY, false)
        ) return
        preferences.edit { putBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, true) }
        TrackingControl.startPeriodic(this)
    }

    private fun startActiveTracking() {
        if (!getPreferences().getBoolean(CONSENT_KEY, false) || auth.currentUser == null) { setStatus("Accept the disclosure and sign in before tracking."); return }
        if (!hasForegroundLocation()) {
            foregroundRequestPending = false
            foregroundPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }
        if (!hasBackgroundLocation()) {
            setStatus("Set TraceGuard Location to ‘Allow all the time’ in Android app settings, then start live tracking again.")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startForegroundTracking()
    }

    private fun startForegroundTracking() {
        getPreferences().edit {
            putBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, true)
            putBoolean(TrackingWorker.TRACKING_ENABLED, true)
            putBoolean(TrackingWorker.AUTO_RESUME_LIVE, true)
        }
        TrackingControl.startPeriodic(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(Intent(this, TrackingService::class.java))
        else startService(Intent(this, TrackingService::class.java))
        updateRemoteStatus("active")
        setStatus("Active tracking is on. Android’s required notification is displayed while it runs.")
    }

    private fun showRequiredNotificationNotice() {
        AlertDialog.Builder(this)
            .setTitle("Android notification required")
            .setMessage("Active tracking must show a persistent system notification. You can continue, but Android may hide it from the notification drawer if permission is denied.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start tracking") { _, _ -> startForegroundTracking() }
            .show()
    }

    private fun pauseTracking() {
        val preferences = getPreferences()
        preferences.edit {
            putBoolean(TrackingWorker.TRACKING_ENABLED, false)
            putBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, preferences.getBoolean(DEVICE_REGISTERED, false) && preferences.getBoolean(CONSENT_KEY, false))
            putBoolean(TrackingWorker.AUTO_RESUME_LIVE, false)
        }
        if (!preferences.getBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, false)) TrackingControl.stopPeriodic(this)
        stopService(Intent(this, TrackingService::class.java))
        updateRemoteStatus("paused")
        setStatus("Tracking is paused. No new reports will be scheduled.")
    }

    private fun launcherIconHidden(): Boolean {
        return when (packageManager.getComponentEnabledSetting(launcherAlias())) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> true
            else -> false
        }
    }

    private fun launcherAlias() = ComponentName(this, "$packageName.setup.LauncherAlias")
    private fun settingsAlias() = ComponentName(this, "$packageName.setup.SettingsAlias")

    private fun confirmHideLauncherIcon() {
        AlertDialog.Builder(this)
            .setTitle("Hide the launcher icon?")
            .setMessage("TraceGuard will remain listed in Android Settings under Apps, but its icon will be removed from the home screen and app drawer. To open it again, use the TraceGuard notification while an active tracking session is running. Android’s tracking notification remains visible while active tracking is on.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Hide icon") { _, _ -> setLauncherIconHidden(true) }
            .show()
    }

    private fun setLauncherIconHidden(hidden: Boolean) {
        packageManager.setComponentEnabledSetting(
            launcherAlias(),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            settingsAlias(),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        setStatus(if (hidden) "Launcher icon hidden. Open TraceGuard from Android Settings → Apps → TraceGuard." else "Launcher icon restored.")
        render()
    }

    private fun updateRemoteStatus(value: String) {
        val user = auth.currentUser ?: return
        val deviceId = getPreferences().getString(TrackingWorker.DEVICE_ID, null) ?: return
        FirebaseFirestore.getInstance().collection("users").document(user.uid).collection("devices").document(deviceId)
            .update("trackingStatus", value)
            .addOnFailureListener { setStatus(it.localizedMessage ?: "Could not sync tracking status.") }
    }

    private fun signOut() {
        pauseTracking()
        getPreferences().edit {
            putBoolean(TrackingWorker.REMOTE_CONTROL_ENABLED, false)
            putBoolean(TrackingWorker.AUTO_RESUME_LIVE, false)
            putBoolean(NotificationForwardingService.NOTIFICATIONS_ENABLED, false)
        }
        TrackingControl.stopPeriodic(this)
        auth.signOut()
        render()
        setStatus("Signed out. Local unsent reports remain queued on this phone.")
    }

    private fun hasForegroundLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun getPreferences() = getSharedPreferences(TrackingWorker.PREFS, MODE_PRIVATE)

    private fun setStatus(message: String) {
        if (::status.isInitialized) { status.text = message; status.visibility = View.VISIBLE }
    }

    private fun card(block: LinearLayout.() -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(rgb(22, 36, 27)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), rgb(47, 66, 51))
            }
        }
        content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(15) })
        box.block()
    }

    private fun addRootText(value: String, size: Float, color: Int, bold: Boolean, bottom: Int): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (bottom > 0) setPadding(0, 0, 0, dp(bottom.toInt()))
        content.addView(this)
    }

    private fun LinearLayout.text(value: String, size: Float, color: Int, bold: Boolean, bottom: Float): TextView = TextView(this@SetupActivity).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (bottom > 0) setPadding(0, 0, 0, dp(bottom.toInt()))
        addView(this)
    }

    private fun LinearLayout.input(hintText: String, password: Boolean): EditText = EditText(this@SetupActivity).apply {
        hint = hintText; textSize = 14f; setTextColor(rgb(238, 245, 239)); setHintTextColor(rgb(119, 139, 125)); setSingleLine(true)
        inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        background = android.graphics.drawable.GradientDrawable().apply { setColor(rgb(16, 27, 20)); cornerRadius = dp(8).toFloat(); setStroke(dp(1), rgb(52, 72, 56)) }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(this, LinearLayout.LayoutParams(-1, dp(49)).apply { bottomMargin = dp(9) })
    }

    private fun LinearLayout.button(label: String, primary: Boolean, action: () -> Unit): Button = Button(this@SetupActivity).apply {
        text = label; textSize = 12f; isAllCaps = false; setTextColor(if (primary) rgb(15, 30, 19) else rgb(219, 234, 222))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (primary) rgb(127, 207, 151) else rgb(36, 57, 42)); cornerRadius = dp(8).toFloat()
            setStroke(dp(1), if (primary) rgb(127, 207, 151) else rgb(60, 86, 64))
        }
        setOnClickListener { action() }
        addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rgb(red: Int, green: Int, blue: Int) = android.graphics.Color.rgb(red, green, blue)

    override fun onDestroy() {
        screenScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CONSENT_KEY = "owner_consent_v1"
        private const val DEVICE_REGISTERED = "device_registered"
    }
}
