// Port of Moblin/Various/Model/ModelSnapshot.swift (saving, takeSnapshotWebRemoteControl,
// tryUploadSnapshotToDiscord) and uploadImage in Moblin/Various/Utils/Utils.swift @97e26d2.
package io.github.mesmerize08.nexusirl.stream

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Snapshot files: saved to the phone, uploaded to Discord, fetched from another phone. Blocking. */
object Snapshots {
    /** Upstream's web remote control fetch timeout. */
    private const val FETCH_TIMEOUT_SECONDS = 15L
    private const val MAX_FETCHED_BYTES = 32 * 1024 * 1024

    /** Saves a JPEG to `Pictures/NexusIRL`; returns whether it was saved. */
    fun save(context: Context, jpeg: ByteArray): Boolean = try {
        val resolver = context.applicationContext.contentResolver
        val name = "NexusIRL_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NexusIRL")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { resolver.openOutputStream(it) }?.use { it.write(jpeg) } != null
    } catch (e: Exception) {
        false
    }

    /**
     * Upstream `uploadImage`: [jpeg] as `snapshot.jpg` and [message] as the post's text, in the
     * same multipart layout. Returns whether the webhook accepted it.
     */
    fun uploadToDiscord(
        webhookUrl: String,
        jpeg: ByteArray,
        message: String?,
        client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    ): Boolean {
        val url = webhookUrl.toHttpUrlOrNull() ?: return false
        val boundary = UUID.randomUUID().toString().uppercase()
        val request = Request.Builder()
            .url(url)
            .post(multipartBody(boundary, jpeg, message).toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Upstream `uploadImage`'s body, byte for byte. */
    fun multipartBody(boundary: String, jpeg: ByteArray, message: String?): ByteArray {
        val body = ByteArrayOutputStream()
        fun text(value: String) = body.write(value.toByteArray(Charsets.UTF_8))
        if (message != null) {
            text("\r\n--$boundary\r\n")
            text("content-disposition: form-data; name=\"content\"\r\n\r\n")
            text(message)
        }
        text("\r\n--$boundary\r\n")
        text("content-disposition: form-data; name=\"snapshot\"; filename=\"snapshot.jpg\"\r\n")
        text("content-type: image/jpeg\r\n\r\n")
        body.write(jpeg)
        text("\r\n--$boundary--\r\n")
        return body.toByteArray()
    }

    /**
     * Upstream `takeSnapshotWebRemoteControl`: `<url>/snapshot?age=<age>` from another phone's web
     * remote control (its address, with its `?t=` token for NexusIRL). Returns the JPEG, or null.
     */
    fun fetchFromWebRemoteControl(baseUrl: String, age: Float, client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT): ByteArray? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        // NexusIRL's `?t=` link is a login link (it redirects, setting a cookie): send the token
        // as that cookie instead.
        val token = base.queryParameter("t")
        val url = base.newBuilder().removeAllQueryParameters(
            "t",
        ).addPathSegment("snapshot").addQueryParameter("age", age.toString()).build()
        val timed = client.newBuilder().callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).apply { token?.let { header("Cookie", "nexusirl_token=$it") } }.build()
        return try {
            timed.newCall(request).execute().use { response ->
                val body = response.body ?: return null
                if (!response.isSuccessful || body.contentLength() > MAX_FETCHED_BYTES) {
                    return null
                }
                body.bytes().takeIf { it.size in 4..MAX_FETCHED_BYTES && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
