package io.github.mesmerize08.nexusirl.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Downloads and caches emote images (first frame only; upstream also shows still images unless
 * animated emotes are turned on). Only https URLs are fetched.
 */
object EmoteImages {
    private const val MAXIMUM_SIZE_BYTES = 1_000_000
    private const val MAXIMUM_DOWNLOAD_BYTES = 2_000_000L

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableVersion = MutableStateFlow(0)

    /** Increases whenever an image arrives, so views can redraw. */
    val version: StateFlow<Int> = mutableVersion

    /** The image if loaded; otherwise starts loading it and returns null. Any thread. */
    fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        synchronized(this) {
            if (!url.startsWith("https://") || url in failed || !loading.add(url)) {
                return null
            }
        }
        executor.execute {
            val bitmap = download(url)
            synchronized(this) {
                loading -= url
                if (bitmap != null) {
                    cache.put(url, bitmap)
                } else {
                    failed += url
                }
            }
            if (bitmap != null) {
                handler.post { mutableVersion.value += 1 }
            }
        }
        return null
    }

    private fun download(url: String): Bitmap? = try {
        ReconnectingWebSocket.SHARED_CLIENT.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null || body.contentLength() > MAXIMUM_DOWNLOAD_BYTES) {
                null
            } else {
                val bytes = body.bytes()
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                // Emotes are small; scale down anything large instead of decoding it whole.
                var sample = 1
                while (options.outWidth / sample * (options.outHeight / sample) * 4 > MAXIMUM_SIZE_BYTES) {
                    sample *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) {
        null
    }
}
