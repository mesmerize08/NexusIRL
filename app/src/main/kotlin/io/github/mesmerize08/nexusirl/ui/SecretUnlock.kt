package io.github.mesmerize08.nexusirl.ui

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.widget.Toast
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets

/** How [SecretUnlock] asks. */
enum class UnlockMode {
    /** No PIN, pattern or password on the phone: nothing to check against. */
    NO_SCREEN_LOCK,

    /** Android 11+: a strong (class 3) biometric or the screen lock. */
    STRONG_BIOMETRIC_OR_CREDENTIAL,

    /** Android 10 only knows "biometric, or the screen lock instead". */
    BIOMETRIC_OR_CREDENTIAL_LEGACY,
}

/**
 * Asks for the fingerprint, face or screen lock before a saved stream key is shown or changed
 * (docs/PORT_PLAN.md §9.3, threat T2). Uses the system BiometricPrompt, which needs neither
 * Google Play services nor a FragmentActivity. Streaming never asks: only the editor does.
 *
 * Always on: it asks only when the phone has a screen lock, and once per editor visit. A switch
 * to turn it off would be one more thing someone holding the unlocked phone could flip.
 */
object SecretUnlock {
    fun mode(sdk: Int, deviceSecure: Boolean): UnlockMode = when {
        !deviceSecure -> UnlockMode.NO_SCREEN_LOCK
        sdk >= Build.VERSION_CODES.R -> UnlockMode.STRONG_BIOMETRIC_OR_CREDENTIAL
        else -> UnlockMode.BIOMETRIC_OR_CREDENTIAL_LEGACY
    }

    /** Whether a field still holding [savedValue] (the saved, real value) must be unlocked first. */
    fun needsUnlock(savedValue: String, unlocked: Boolean): Boolean = !unlocked && StreamUrlSecrets.split(savedValue).second.isNotEmpty()

    /** Calls [onUnlocked] on the main thread once the user has proven it is them. */
    fun authenticate(context: Context, onUnlocked: () -> Unit) {
        val deviceSecure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        val mode = mode(Build.VERSION.SDK_INT, deviceSecure)
        if (mode == UnlockMode.NO_SCREEN_LOCK) {
            Log.i(TAG, "No screen lock: stream keys are shown without asking")
            Toast.makeText(context, R.string.secret_unlock_no_screen_lock, Toast.LENGTH_LONG).show()
            onUnlocked()
            return
        }
        val builder = BiometricPrompt.Builder(context)
            .setTitle(context.getString(R.string.secret_unlock_title))
            .setSubtitle(context.getString(R.string.secret_unlock_subtitle))
        if (mode == UnlockMode.STRONG_BIOMETRIC_OR_CREDENTIAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelling is the user's choice, not an error to report.
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) {
                        Toast.makeText(context, errString, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    private const val TAG = "SecretUnlock"
}
