// Port of the sign-in and sending parts of Moblin/Various/Model/ModelKick.swift @97e26d2.
package io.github.mesmerize08.nexusirl.kick

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.chat.ChatController
import io.github.mesmerize08.nexusirl.chat.kick.KickApi
import io.github.mesmerize08.nexusirl.chat.kick.KickUnauthorizedException
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.stream.StreamController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Where a Kick sign-in is. */
sealed interface KickSignIn {
    data object Idle : KickSignIn

    /** Looking up the signed-in user and their channel. */
    data object Finishing : KickSignIn

    data class Failed(val message: String) : KickSignIn
}

/**
 * The Kick account of each stream. Kick's official API needs a client secret, so like upstream the
 * app signs in on Kick's own website ([KickLoginActivity]) and uses its `session_token`. The token
 * lives only in the secret store, referenced from the stream's `kickAccessToken`. Signing in sets
 * the stream's Kick channel to the signed-in user, like upstream.
 */
object KickAccount {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableSignIn = MutableStateFlow<KickSignIn>(KickSignIn.Idle)
    val signIn: StateFlow<KickSignIn> = mutableSignIn

    /** Upstream `kickLogin`'s completion: stores [token] and looks up the user and channel. */
    fun signedIn(context: Context, streamId: UUID, token: String) {
        val store = StreamController.settings(context)
        mutableSignIn.value = KickSignIn.Finishing
        Thread({
            try {
                val api = KickApi(token)
                val username = api.username()
                val channel = api.channel(username)
                post {
                    update(store, streamId) { stream ->
                        stream.copy(
                            kickAccessToken = token,
                            kickLoggedIn = true,
                            kickChannelName = username,
                            kickChannelId = channel.chatroomId,
                            kickChatroomChannelId = channel.channelId,
                            kickSlug = channel.slug,
                        )
                    }
                    mutableSignIn.value = KickSignIn.Idle
                }
            } catch (e: KickUnauthorizedException) {
                post { mutableSignIn.value = KickSignIn.Failed("Kick did not accept the sign-in. Try again.") }
            } catch (e: Exception) {
                post { mutableSignIn.value = KickSignIn.Failed("No connection to Kick") }
            }
        }, "kick-sign-in").apply { isDaemon = true }.start()
    }

    fun failed(message: String) {
        mutableSignIn.value = KickSignIn.Failed(message)
    }

    /**
     * Upstream `kickLogout`: forgets the session and the channel's ids. The session stays valid at
     * Kick until it expires or the user signs out on kick.com; nothing here can end it.
     */
    fun signOut(context: Context, streamId: UUID) {
        update(StreamController.settings(context), streamId) { forget(it) }
        mutableSignIn.value = KickSignIn.Idle
    }

    private fun forget(stream: SettingsStream) = stream.copy(kickAccessToken = "", kickLoggedIn = false)

    /** An API for [stream]'s Kick account, or null when it is not signed in. */
    fun api(store: SettingsStore, stream: SettingsStream): KickApi? {
        if (!stream.kickLoggedIn) {
            return null
        }
        return store.kickSecret(stream).ifEmpty { null }?.let { KickApi(it) }
    }

    /**
     * Upstream `kickApiUnauthorized`: Kick no longer accepts the session (signed out on the
     * website, or expired), so the stream is signed out and the user told. Any thread.
     */
    fun unauthorized(context: Context, streamId: UUID) {
        post {
            val store = StreamController.settings(context)
            if (store.current.streams.firstOrNull { it.id == streamId }?.kickLoggedIn == true) {
                update(store, streamId) { forget(it) }
                ChatController.showToast("Not signed in to Kick. Sign in again in the stream's settings.")
            }
        }
    }

    private fun update(store: SettingsStore, streamId: UUID, change: (SettingsStream) -> SettingsStream) {
        store.update { database ->
            database.copy(streams = database.streams.map { if (it.id == streamId) change(it) else it })
        }
    }

    private fun post(block: () -> Unit) = handler.post(block)
}
