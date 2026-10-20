// Port of the Helix calls NexusIRL uses from Moblin/StreamingPlatforms/Twitch/TwitchApi.swift @97e26d2.
package io.github.mesmerize08.nexusirl.chat.twitch

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The token was rejected (expired or revoked): sign in again or refresh. */
class TwitchUnauthorizedException : IOException("Twitch rejected the sign-in")

/** A live stream (upstream `TwitchApiStreamData`). */
data class TwitchStreamInfo(val viewerCount: Int, val title: String, val gameName: String)

class TwitchApi(
    private val clientId: String,
    private val accessToken: String,
    private val client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    private val baseUrl: String = "https://api.twitch.tv/helix",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(path: String) =
        Request.Builder().url("$baseUrl/$path").header("Client-Id", clientId).header("Authorization", "Bearer $accessToken")

    private fun execute(request: Request): JsonObject? = client.newCall(request).execute().use { response ->
        if (response.code == 401) {
            throw TwitchUnauthorizedException()
        }
        if (!response.isSuccessful) {
            throw IOException("Twitch HTTP ${response.code}")
        }
        val body = response.body?.string().orEmpty()
        if (body.isEmpty()) null else json.parseToJsonElement(body) as? JsonObject
    }

    private fun postJson(path: String, body: String): JsonObject? =
        execute(request(path).post(body.toRequestBody("application/json".toMediaType())).build())

    /** Upstream `createEventSubSubscription`. */
    fun createEventSubSubscription(type: String, version: Int, condition: Map<String, String>, sessionId: String) {
        val body = buildJsonObject {
            put("type", type)
            put("version", version.toString())
            put("condition", buildJsonObject { condition.forEach { (key, value) -> put(key, value) } })
            put(
                "transport",
                buildJsonObject {
                    put("method", "websocket")
                    put("session_id", sessionId)
                },
            )
        }
        postJson("eventsub/subscriptions", body.toString())
    }

    /** Upstream `getStream`: null when the channel is offline. */
    fun getStream(userId: String): TwitchStreamInfo? {
        val data = execute(request("streams?user_id=$userId").get().build())?.get("data") as? JsonArray ?: return null
        val stream = data.firstOrNull() as? JsonObject ?: return null
        return TwitchStreamInfo(
            viewerCount = stream["viewer_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            title = stream["title"]?.jsonPrimitive?.content.orEmpty(),
            gameName = stream["game_name"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /** Upstream `sendChatMessage`: posts publicly in the channel's chat. */
    fun sendChatMessage(broadcasterId: String, senderId: String, message: String) {
        val body = buildJsonObject {
            put("broadcaster_id", broadcasterId)
            put("sender_id", senderId)
            put("message", message)
        }
        val result = postJson("chat/messages", body.toString())
        val sent = ((result?.get("data") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("is_sent")?.jsonPrimitive?.content
        if (sent == "false") {
            throw IOException("Twitch did not send the message")
        }
    }
}
