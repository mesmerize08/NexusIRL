package io.github.mesmerize08.nexusirl.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretUnlockTest {
    @Test
    fun withoutScreenLockThereIsNothingToAskFor() {
        assertEquals(UnlockMode.NO_SCREEN_LOCK, SecretUnlock.mode(sdk = 36, deviceSecure = false))
        assertEquals(UnlockMode.NO_SCREEN_LOCK, SecretUnlock.mode(sdk = 29, deviceSecure = false))
    }

    @Test
    fun strongBiometricOrScreenLockFromAndroid11() {
        assertEquals(UnlockMode.STRONG_BIOMETRIC_OR_CREDENTIAL, SecretUnlock.mode(sdk = 30, deviceSecure = true))
        assertEquals(UnlockMode.STRONG_BIOMETRIC_OR_CREDENTIAL, SecretUnlock.mode(sdk = 37, deviceSecure = true))
    }

    @Test
    fun android10UsesTheOlderCredentialFlag() {
        assertEquals(UnlockMode.BIOMETRIC_OR_CREDENTIAL_LEGACY, SecretUnlock.mode(sdk = 29, deviceSecure = true))
    }

    @Test
    fun onlySavedKeysAreLocked() {
        // A new stream's URL is typed by the user: nothing to protect yet.
        assertFalse(SecretUnlock.needsUnlock(savedValue = "", unlocked = false))
        assertTrue(SecretUnlock.needsUnlock(savedValue = "rtmp://live.twitch.tv/app/live_1", unlocked = false))
        assertFalse(SecretUnlock.needsUnlock(savedValue = "rtmp://live.twitch.tv/app/live_1", unlocked = true))
        // Only the server, no key: shown on screen anyway.
        assertFalse(SecretUnlock.needsUnlock(savedValue = "srt://host.example:9000", unlocked = false))
    }
}
