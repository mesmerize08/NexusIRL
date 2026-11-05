// Not upstream: docs/PORT_PLAN.md §9.6, shared by the remote control assistant and the Moblink
// streamer (both servers that challenge with upstream's password hash).
package io.github.mesmerize08.nexusirl.remotecontrol

import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64

private val challengeRandom = SecureRandom()

/** A fresh challenge or salt for upstream's hello: 32 random bytes as base64. */
fun newChallenge(): String = ByteArray(32).also(challengeRandom::nextBytes).let { Base64.getEncoder().encodeToString(it) }

/**
 * After [MAXIMUM_FAILURES] wrong passwords an address is refused for [LOCKOUT_MILLIS]; a right one
 * clears its count. Thread safe.
 */
class PasswordLockout(private val clock: () -> Long = System::currentTimeMillis) {
    private val failures = HashMap<String, Pair<Int, Long>>()

    fun isLockedOut(host: String): Boolean = synchronized(this) {
        val (count, lockedUntil) = failures[host] ?: return false
        count >= MAXIMUM_FAILURES && clock() < lockedUntil
    }

    fun recordFailure(host: String) = synchronized(this) {
        val (count, lockedUntil) = failures[host] ?: (0 to 0L)
        val newCount = if (count >= MAXIMUM_FAILURES && clock() >= lockedUntil) 1 else count + 1
        failures[host] = newCount to if (newCount >= MAXIMUM_FAILURES) clock() + LOCKOUT_MILLIS else 0L
    }

    fun recordSuccess(host: String) {
        synchronized(this) { failures.remove(host) }
    }

    /**
     * Why a new connection from [remote] to [local] is refused, or null to accept it: only on
     * addresses [acceptLocalAddress] allows, and not from a locked out address.
     */
    fun refusal(local: InetAddress?, remote: InetAddress?, acceptLocalAddress: (InetAddress, InetAddress?) -> Boolean): String? = when {
        local == null || !acceptLocalAddress(local, remote) -> "not the local network"
        isLockedOut(remote?.hostAddress.orEmpty()) -> "too many wrong passwords"
        else -> null
    }

    companion object {
        const val MAXIMUM_FAILURES = 5
        const val LOCKOUT_MILLIS = 60_000L
    }
}
