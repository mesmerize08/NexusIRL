package io.github.mesmerize08.nexusirl.core.websocket

import java.security.MessageDigest
import java.util.Base64

/**
 * base64(SHA256(base64(SHA256(password + salt)) + challenge)): obs-websocket 5's authentication
 * string, which upstream's `remoteControlHashPassword` (remote control and Moblink) reuses. Call
 * it with named arguments; the two upstream functions take the three strings in opposite orders.
 */
fun challengePasswordHash(password: String, salt: String, challenge: String): String {
    fun sha256Base64(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    return sha256Base64(sha256Base64(password + salt) + challenge)
}
