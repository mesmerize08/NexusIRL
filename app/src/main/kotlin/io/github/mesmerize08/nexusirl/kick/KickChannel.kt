// Port of the Kick title and category calls in Moblin/Various/Model/ModelKick.swift @97e26d2.
package io.github.mesmerize08.nexusirl.kick

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.chat.kick.KickApi
import io.github.mesmerize08.nexusirl.chat.kick.KickCategory
import io.github.mesmerize08.nexusirl.chat.kick.KickStreamInfo
import io.github.mesmerize08.nexusirl.chat.kick.KickUnauthorizedException
import io.github.mesmerize08.nexusirl.stream.StreamController
import java.util.UUID

/**
 * The signed-in channel's title and category. Reading and searching are private; [update] changes
 * the public channel at once, so the UI asks first. Callbacks run on the main thread.
 */
object KickChannel {
    private val handler = Handler(Looper.getMainLooper())

    /** A failure the user can act on. */
    class Failure(val message: String)

    private fun <T> run(context: Context, streamId: UUID, work: (KickApi, String) -> T, onDone: (T?, Failure?) -> Unit) {
        val appContext = context.applicationContext
        Thread({
            val (result, failure) = try {
                val store = StreamController.settings(appContext)
                val stream = store.current.streams.firstOrNull { it.id == streamId }
                val api = stream?.let { KickAccount.api(store, it) } ?: throw IllegalStateException("Sign in to Kick first")
                val slug = stream.kickSlug ?: throw IllegalStateException("Sign in to Kick again")
                work(api, slug) to null
            } catch (e: KickUnauthorizedException) {
                KickAccount.unauthorized(appContext, streamId)
                null to Failure("Sign in to Kick again")
            } catch (e: Exception) {
                null to Failure(e.message ?: "Kick did not answer")
            }
            handler.post { onDone(result, failure) }
        }, "kick-channel").apply { isDaemon = true }.start()
    }

    fun load(context: Context, streamId: UUID, onDone: (KickStreamInfo?, Failure?) -> Unit) =
        run(context, streamId, { api, slug -> api.streamInfo(slug) }, onDone)

    fun searchCategories(context: Context, streamId: UUID, query: String, onDone: (List<KickCategory>?, Failure?) -> Unit) =
        run(context, streamId, { api, _ -> api.searchCategories(query) }, onDone)

    /** Upstream `setKickStreamTitle` and `setKickStreamCategory` (each when given): public at once. */
    fun update(context: Context, streamId: UUID, title: String?, category: KickCategory?, onDone: (Failure?) -> Unit) =
        run(context, streamId, { api, slug ->
            title?.let { api.setTitle(slug, it) }
            category?.let { api.setCategory(slug, it.id) }
        }) { _, failure -> onDone(failure) }
}
