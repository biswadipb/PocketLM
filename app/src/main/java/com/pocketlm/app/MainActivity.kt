package com.pocketlm.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.IntentCompat
import com.pocketlm.app.data.ShareIntake
import com.pocketlm.app.data.TextAction
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pocketlm.app.ui.LockScreen
import com.pocketlm.app.ui.PocketLmApp
import com.pocketlm.app.ui.PocketLmTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val vm: PocketViewModel by viewModels()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the download notification just stays hidden if refused */ }
    private var stoppedAt = 0L
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        // With the lock on, hide the app in the recent-apps list and block screenshots.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                // An incognito chat is also kept out of the recent-apps list and screenshots.
                combine(vm.lockEnabled, vm.incognito) { lock, incognito -> lock || incognito }.collect { enabled ->
                    if (enabled) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        // Ask once for permission to show the download progress notification.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.downloads.collect { if (it.isNotEmpty()) askForNotificationsOnce() }
            }
        }
        // Replies running in the background also need the notification permission.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.generating.collect { if (it && vm.backgroundReplies.value) askForNotificationsOnce() }
            }
        }

        setContent {
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            val textScale by vm.textScale.collectAsStateWithLifecycle()
            val locked by vm.locked.collectAsStateWithLifecycle()
            LaunchedEffect(locked) { if (locked) promptUnlock() }
            // The app's own text size sits on top of the phone's.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
                PocketLmTheme(mode) {
                    // While locked the app is not composed at all, so nothing underneath can be seen,
                    // focused, read by an accessibility service, or tapped.
                    if (locked) LockScreen(onUnlock = ::promptUnlock) else PocketLmApp(vm)
                }
            }
        }
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop() {
        super.onStop()
        vm.setAppVisible(false)
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        vm.setAppVisible(true)
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > LOCK_AFTER_MS) vm.lockIfEnabled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun promptUnlock() {
        if (promptShowing) return
        promptShowing = true
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptShowing = false
                vm.unlock()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptShowing = false
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock PocketLM")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).authenticate(info)
    }

    /** Text shared from another app, or selected and sent through the text selection menu. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == IntentActions.ASK_SCREEN) {
            vm.receiveScreen(intent.getStringExtra(IntentActions.EXTRA_SCREEN_TEXT), intent.getStringExtra(IntentActions.EXTRA_SCREENSHOT))
            return
        }
        // Files and photos shared in (a PDF from a mail app, a picture from the gallery) go into a fresh chat.
        val files: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (files.isNotEmpty()) {
            vm.receiveFiles(files)
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { selected ->
                // "Explain / Translate / Summarise / Improve with PocketLM" arrive through their own menu entries.
                TextAction.fromComponent(intent.component?.className)?.promptFor(selected) ?: selected
            }
            // A reply typed into a notification that the app could not take on the spot.
            IntentActions.ASK -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) {
            // A shared link is read as a page; anything else goes into the message box.
            when (val plan = if (intent.action == Intent.ACTION_SEND) ShareIntake.classifyText(text) else ShareIntake.TextPlan.Message(text)) {
                is ShareIntake.TextPlan.Page -> vm.receiveLink(plan.url)
                is ShareIntake.TextPlan.Message -> vm.receiveText(plan.text)
            }
        }
        if (intent.action == IntentActions.ASK) vm.requestAsk()
    }

    private companion object {
        /** Ask for unlock again after the app has been in the background this long. */
        const val LOCK_AFTER_MS = 30_000L
    }
}
