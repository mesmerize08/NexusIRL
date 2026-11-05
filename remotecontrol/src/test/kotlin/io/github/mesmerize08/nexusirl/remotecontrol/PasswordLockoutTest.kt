package io.github.mesmerize08.nexusirl.remotecontrol

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordLockoutTest {
    private var now = 1_000L
    private val lockout = PasswordLockout(clock = { now })

    @Test
    fun fiveFailuresLockForAMinute() {
        repeat(PasswordLockout.MAXIMUM_FAILURES - 1) {
            lockout.recordFailure("10.0.0.2")
            assertFalse(lockout.isLockedOut("10.0.0.2"))
        }
        lockout.recordFailure("10.0.0.2")
        assertTrue(lockout.isLockedOut("10.0.0.2"))
        // Per address.
        assertFalse(lockout.isLockedOut("10.0.0.3"))
        now += PasswordLockout.LOCKOUT_MILLIS - 1
        assertTrue(lockout.isLockedOut("10.0.0.2"))
        now += 1
        assertFalse(lockout.isLockedOut("10.0.0.2"))
        // After expiry the count starts over: one more failure does not lock again.
        lockout.recordFailure("10.0.0.2")
        assertFalse(lockout.isLockedOut("10.0.0.2"))
    }

    @Test
    fun successClearsFailures() {
        repeat(PasswordLockout.MAXIMUM_FAILURES) { lockout.recordFailure("10.0.0.2") }
        assertTrue(lockout.isLockedOut("10.0.0.2"))
        lockout.recordSuccess("10.0.0.2")
        assertFalse(lockout.isLockedOut("10.0.0.2"))
        repeat(PasswordLockout.MAXIMUM_FAILURES - 1) { lockout.recordFailure("10.0.0.2") }
        assertFalse(lockout.isLockedOut("10.0.0.2"))
    }

    @Test
    fun refusal() {
        val local = InetAddress.getByName("192.168.1.2")
        val remote = InetAddress.getByName("192.168.1.3")
        assertNull(lockout.refusal(local, remote) { _, _ -> true })
        assertEquals("not the local network", lockout.refusal(local, remote) { _, _ -> false })
        assertEquals("not the local network", lockout.refusal(null, remote) { _, _ -> true })
        repeat(PasswordLockout.MAXIMUM_FAILURES) { lockout.recordFailure("192.168.1.3") }
        assertEquals("too many wrong passwords", lockout.refusal(local, remote) { _, _ -> true })
        assertEquals("not the local network", lockout.refusal(local, remote) { _, _ -> false })
    }

    @Test
    fun challengesAreRandom() {
        val challenge = newChallenge()
        assertEquals(44, challenge.length)
        assertNotEquals(challenge, newChallenge())
    }
}
