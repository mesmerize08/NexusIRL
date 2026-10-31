// Port of the signed-in calls NexusIRL uses from Moblin/StreamingPlatforms/Kick/KickApi.swift @97e26d2:
// the user, the channel lookup, sending chat messages and the stream title and category.
package io.github.mesmerize08.nexusirl.chat.kick

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Kick rejected the session (signed out on the website, or it expired): sign in again. */
class KickUnauthorizedException : IOException("Kick rejected the sign-in")

/** A channel's ids (upstream `KickChannel`): the chatroom id is what upstream calls `kickChannelId`. */
data class KickChannelInfo(val slug: String, val chatroomId: String, val channelId: String)

/** The title and category of a channel's stream (upstream `KickStreamInfo`). */
data class KickStreamInfo(val title: String, val categoryName: String?)

/** A stream category from search (upstream `KickCategory`). */
data class KickCategory(val id: Int, val name: String)

@Serializable
private data class KickUserResponse(val username: String)

@Serializable
private data class KickChatroomResponse(val id: Long, val channel_id: Long)

@Serializable
private data class KickChannelResponse(val slug: String, val chatroom: KickChatroomResponse)

@Serializable
private data class KickCategoryDocument(val id: String, val name: String)

@Serializable
private data class KickCategoryHit(val document: KickCategoryDocument)

@Serializable
private data class KickCategorySearchResponse(val hits: List<KickCategoryHit> = emptyList())

/**
 * Kick's website API, authorised with the website's `session_token` as a bearer token, as upstream
 * does (Kick's official API needs a client secret, which an open-source app cannot keep).
 * Blocking: call off the main thread.
 */
class KickApi(
    private val accessToken: String,
    private val client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    private val baseUrl: String = "https://kick.com",
    private val searchUrl: String = "https://search.kick.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(url: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken").header("Accept", "application/json")

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        if (response.code == 401) {
            throw KickUnauthorizedException()
        }
        if (!response.isSuccessful) {
            // Kick's own message (never the session, which only travels in the request) says why.
            val reason = response.body?.string().orEmpty().take(200).replace(Regex("\\s+"), " ").trim()
            throw IOException("Kick HTTP ${response.code}${if (reason.isEmpty()) "" else ": $reason"}")
        }
        response.body?.string().orEmpty()
    }

    private fun v2(method: String, path: String, body: JsonObject? = null): String {
        val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        return execute(request("$baseUrl/api/v2/$path").method(method, requestBody).build())
    }

    /** Upstream `getUser`: the signed-in user's name. */
    fun username(): String = json.decodeFromString(KickUserResponse.serializer(), execute(request("$baseUrl/api/v1/user").build())).username

    /** Upstream `getKickChannelInfo`: tries the name as the slug, then with `_` as `-`. */
    fun channel(channelName: String): KickChannelInfo {
        val slugs = listOf(channelName, channelName.replace("_", "-")).distinct()
        var error: IOException? = null
        for (slug in slugs) {
            try {
                val body = execute(Request.Builder().url("$baseUrl/api/v1/channels/$slug").header("Accept", "application/json").build())
                val channel = json.decodeFromString(KickChannelResponse.serializer(), body)
                return KickChannelInfo(channel.slug, channel.chatroom.id.toString(), channel.chatroom.channel_id.toString())
            } catch (e: IOException) {
                error = e
            }
        }
        throw error ?: IOException("No Kick channel $channelName")
    }

    /** Upstream `sendMessage`: posts publicly in the chatroom as the signed-in user. */
    fun sendMessage(chatroomId: String, message: String) {
        v2(
            "POST",
            "messages/send/$chatroomId",
            buildJsonObject {
                put("type", "message")
                put("content", message)
            },
        )
    }

    /** Upstream `getStreamInfo`. */
    fun streamInfo(slug: String): KickStreamInfo {
        val info = json.parseToJsonElement(v2("GET", "channels/$slug/stream-info")).jsonObject
        val title = info["stream_title"]?.jsonPrimitive?.contentOrNull ?: throw IOException("No stream title from Kick")
        val category = (info["category"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        return KickStreamInfo(title, category)
    }

    /** Upstream `setStreamTitle`. */
    fun setTitle(slug: String, title: String) {
        v2("PATCH", "channels/$slug/stream-info", buildJsonObject { put("stream_title", title) })
    }

    /** Upstream `setStreamCategory`. */
    fun setCategory(slug: String, categoryId: Int) {
        v2("PATCH", "channels/$slug/stream-info", buildJsonObject { put("category_id", categoryId) })
    }

    /** Upstream `searchCategories`: Kick's own category search, with the website's public search key. */
    fun searchCategories(query: String): List<KickCategory> {
        val url = "$searchUrl/collections/subcategory_index/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("query_by", "name")
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Typesense-Api-Key", SEARCH_API_KEY)
            .build()
        val response = json.decodeFromString(KickCategorySearchResponse.serializer(), execute(request))
        return response.hits.mapNotNull { hit -> hit.document.id.toIntOrNull()?.let { KickCategory(it, hit.document.name) } }
    }

    private companion object {
        /** Kick's website search key (public, in upstream and in kick.com's own pages). */
        const val SEARCH_API_KEY = "nXIMW0iEN6sMujFYjFuhdrSwVow3pDQu"
    }
}
