// Twitch sign-in with the OAuth Device Code flow (docs/PORT_PLAN.md §8.1, §9.5). Replaces upstream's
// Moblin/StreamingPlatforms/Twitch/TwitchAuth.swift, which uses the author's client id and a web
// redirect through the author's site; this needs neither a server nor a client secret.
package io.github.mesmerize08.nexusirl.chat.twitch

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The permissions asked for, only what the app uses (upstream asks for about 30 up front):
 * sending chat, and follow, subscription, cheer and channel point alerts. Raids and viewer counts
 * need none.
 */
val TWITCH_SCOPES = listOf(
    "user:write:chat",
    "moderator:read:followers",
    "channel:read:subscriptions",
    "bits:read",
    "channel:read:redemptions",
)

/** A sign-in in progress: show [userCode] and send the user to [verificationUri]. */
data class TwitchDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/** Tokens from a sign-in. Public-client refresh tokens are single use and last 30 days. */
@Serializable
data class TwitchTokens(val accessToken: String, val refreshToken: String)

/** Who the access token belongs to (`/oauth2/validate`). */
data class TwitchTokenInfo(val login: String, val userId: String, val scopes: List<String>, val expiresInSeconds: Int)

class TwitchAuthException(message: String, val reason: Reason = Reason.OTHER) : IOException(message) {
    enum class Reason { PENDING, SLOW_DOWN, EXPIRED, DENIED, INVALID_TOKEN, OTHER }
}

class TwitchAuth(
    private val clientId: String,
    private val client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    private val baseUrl: String = "https://id.twitch.tv",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun post(path: String, form: Map<String, String>): JsonObject {
        val body = FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build()
        client.newCall(Request.Builder().url("$baseUrl$path").post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val message = parsed?.get("message")?.jsonPrimitive?.content ?: "HTTP ${response.code}"
                throw TwitchAuthException(message, reasonFor(message, response.code))
            }
            return parsed ?: throw TwitchAuthException("Invalid response")
        }
    }

    private fun reasonFor(message: String, code: Int) = when {
        message.contains("authorization_pending") -> TwitchAuthException.Reason.PENDING
        message.contains("slow_down") -> TwitchAuthException.Reason.SLOW_DOWN
        message.contains("invalid device code", ignoreCase = true) || message.contains("expired") -> TwitchAuthException.Reason.EXPIRED
        message.contains("denied", ignoreCase = true) -> TwitchAuthException.Reason.DENIED
        code == 400 && message.contains("refresh token", ignoreCase = true) -> TwitchAuthException.Reason.INVALID_TOKEN
        code == 401 -> TwitchAuthException.Reason.INVALID_TOKEN
        else -> TwitchAuthException.Reason.OTHER
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content ?: throw TwitchAuthException("Missing $key")

    private fun JsonObject.int(key: String, default: Int) = this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: default

    /** Step 1: get a code for the user to enter at twitch.tv/activate. */
    fun startDeviceFlow(scopes: List<String> = TWITCH_SCOPES): TwitchDeviceCode {
        val response = post("/oauth2/device", mapOf("client_id" to clientId, "scopes" to scopes.joinToString(" ")))
        return TwitchDeviceCode(
            deviceCode = response.string("device_code"),
            userCode = response.string("user_code"),
            verificationUri = response.string("verification_uri"),
            expiresInSeconds = response.int("expires_in", 1800),
            intervalSeconds = response.int("interval", 5),
        )
    }

    /**
     * Step 2, repeated every [TwitchDeviceCode.intervalSeconds]: the tokens once the user has
     * approved; throws with reason PENDING until then.
     */
    fun pollToken(code: TwitchDeviceCode, scopes: List<String> = TWITCH_SCOPES): TwitchTokens {
        val response = post(
            "/oauth2/token",
            mapOf(
                "client_id" to clientId,
                "scopes" to scopes.joinToString(" "),
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        return TwitchTokens(response.string("access_token"), response.string("refresh_token"))
    }

    /** New tokens from a refresh token (each refresh token works once). */
    fun refresh(tokens: TwitchTokens): TwitchTokens {
        val response = post(
            "/oauth2/token",
            mapOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to tokens.refreshToken),
        )
        return TwitchTokens(response.string("access_token"), response.string("refresh_token"))
    }

    /** Upstream `validateToken`: whose token it is; throws INVALID_TOKEN when expired or revoked. */
    fun validate(accessToken: String): TwitchTokenInfo {
        val request = Request.Builder().url("$baseUrl/oauth2/validate").header("Authorization", "OAuth $accessToken").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw TwitchAuthException("Token expired or revoked", TwitchAuthException.Reason.INVALID_TOKEN)
            }
            if (!response.isSuccessful) {
                throw TwitchAuthException("HTTP ${response.code}")
            }
            val body = json.parseToJsonElement(response.body!!.string()) as JsonObject
            return TwitchTokenInfo(
                login = body.string("login"),
                userId = body.string("user_id"),
                scopes = (body["scopes"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                expiresInSeconds = body.int("expires_in", 0),
            )
        }
    }

    /** Signing out revokes the token at Twitch (docs/PORT_PLAN.md §9.5). Errors are ignored. */
    fun revoke(accessToken: String) {
        try {
            post("/oauth2/revoke", mapOf("client_id" to clientId, "token" to accessToken))
        } catch (e: IOException) {
            // Already invalid, or offline: the local tokens are deleted anyway.
        }
    }

    /** Signs in: runs the device flow until approved, denied or expired. [onCode] shows the code. Blocking. */
    fun signIn(onCode: (TwitchDeviceCode) -> Unit, sleep: (Long) -> Unit = Thread::sleep): TwitchTokens {
        val code = startDeviceFlow()
        onCode(code)
        var intervalMillis = code.intervalSeconds.coerceAtLeast(1) * 1000L
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            sleep(intervalMillis)
            try {
                return pollToken(code)
            } catch (e: TwitchAuthException) {
                when (e.reason) {
                    TwitchAuthException.Reason.PENDING -> Unit
                    TwitchAuthException.Reason.SLOW_DOWN -> intervalMillis += 5000
                    else -> throw e
                }
            }
        }
        throw TwitchAuthException("The code expired", TwitchAuthException.Reason.EXPIRED)
    }
}
