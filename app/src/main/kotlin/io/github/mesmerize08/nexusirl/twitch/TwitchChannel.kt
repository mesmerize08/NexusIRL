// Port of setTwitchStreamTitle / setTwitchStreamCategory in Moblin/Various/Model/ModelTwitch.swift @97e26d2.
package io.github.mesmerize08.nexusirl.twitch

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.BuildConfig
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchApi
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchCategory
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchChannelInfo
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchMissingScopeException
import java.util.UUID

/**
 * The signed-in channel's title and category. Reading and searching are private; [update] changes
 * the public channel at once, so the UI asks first. Callbacks run on the main thread.
 */
object TwitchChannel {
    private val handler = Handler(Looper.getMainLooper())

    /** A failure the user can act on. */
    class Failure(val message: String)

    private fun <T> run(context: Context, streamId: UUID, work: (TwitchApi) -> T, onDone: (T?, Failure?) -> Unit) {
        val appContext = context.applicationContext
        Thread({
            val (result, failure) = try {
                val token = TwitchAccount.accessToken(appContext, streamId) ?: throw IllegalStateException("Sign in to Twitch first")
                work(TwitchApi(BuildConfig.TWITCH_CLIENT_ID, token)) to null
            } catch (e: TwitchMissingScopeException) {
                // Sign-ins from before this feature lack channel:manage:broadcast.
                null to Failure("Sign in to Twitch again to change the title and category")
            } catch (e: Exception) {
                null to Failure(e.message ?: "Twitch did not answer")
            }
            handler.post { onDone(result, failure) }
        }, "twitch-channel").apply { isDaemon = true }.start()
    }

    fun load(context: Context, streamId: UUID, channelId: String, onDone: (TwitchChannelInfo?, Failure?) -> Unit) =
        run(context, streamId, { it.getChannelInformation(channelId) }, onDone)

    fun searchCategories(context: Context, streamId: UUID, query: String, onDone: (List<TwitchCategory>?, Failure?) -> Unit) =
        run(context, streamId, { it.searchCategories(query) }, onDone)

    /** Upstream `setTwitchStreamTitle`/`setTwitchStreamCategory`: public, immediate. */
    fun update(context: Context, streamId: UUID, channelId: String, title: String?, categoryId: String?, onDone: (Failure?) -> Unit) =
        run(context, streamId, { it.modifyChannelInformation(channelId, title, categoryId) }) { _, failure -> onDone(failure) }
}
