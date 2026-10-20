package io.github.mesmerize08.nexusirl.twitch

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.BuildConfig
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchAuth
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchAuthException
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchDeviceCode
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchTokens
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.stream.StreamController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

/** Where a sign-in is. */
sealed interface TwitchSignIn {
    data object Idle : TwitchSignIn

    /** Waiting for the user to enter [code] at twitch.tv/activate. */
    data class Waiting(val streamId: UUID, val code: TwitchDeviceCode) : TwitchSignIn

    data class Done(val login: String) : TwitchSignIn

    data class Failed(val message: String) : TwitchSignIn
}

/**
 * The Twitch account of each stream (upstream keeps the token per stream too). Tokens live only in
 * the secret store, as JSON, referenced from the stream's `twitchAccessToken`. Signing in sets the
 * stream's channel to the signed-in user, like upstream.
 */
object TwitchAccount {
    private val handler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableSignIn = MutableStateFlow<TwitchSignIn>(TwitchSignIn.Idle)
    val signIn: StateFlow<TwitchSignIn> = mutableSignIn

    @Volatile
    private var signInThread: Thread? = null

    val isAvailable: Boolean
        get() = BuildConfig.TWITCH_CLIENT_ID.isNotEmpty()

    private fun auth() = TwitchAuth(BuildConfig.TWITCH_CLIENT_ID)

    /** Starts the Device Code flow for [streamId]; progress is in [signIn]. */
    fun startSignIn(context: Context, streamId: UUID) {
        cancelSignIn()
        val store = StreamController.settings(context)
        val thread = Thread({
            try {
                val tokens = auth().signIn(onCode = { code -> post { mutableSignIn.value = TwitchSignIn.Waiting(streamId, code) } })
                val info = auth().validate(tokens.accessToken)
                post {
                    save(store, streamId, tokens, login = info.login, userId = info.userId)
                    mutableSignIn.value = TwitchSignIn.Done(info.login)
                }
            } catch (e: InterruptedException) {
                post { mutableSignIn.value = TwitchSignIn.Idle }
            } catch (e: TwitchAuthException) {
                val message = when (e.reason) {
                    TwitchAuthException.Reason.EXPIRED -> "The code expired. Try again."
                    TwitchAuthException.Reason.DENIED -> "Sign-in was declined."
                    else -> e.message ?: "Sign-in failed"
                }
                post { mutableSignIn.value = TwitchSignIn.Failed(message) }
            } catch (e: Exception) {
                post { mutableSignIn.value = TwitchSignIn.Failed("No connection to Twitch") }
            }
        }, "twitch-sign-in")
        thread.isDaemon = true
        signInThread = thread
        mutableSignIn.value = TwitchSignIn.Idle
        thread.start()
    }

    fun cancelSignIn() {
        signInThread?.interrupt()
        signInThread = null
        mutableSignIn.value = TwitchSignIn.Idle
    }

    private fun post(block: () -> Unit) = handler.post(block)

    private fun save(store: SettingsStore, streamId: UUID, tokens: TwitchTokens?, login: String? = null, userId: String? = null) {
        store.update { database ->
            database.copy(
                streams = database.streams.map { stream ->
                    if (stream.id != streamId) {
                        stream
                    } else {
                        var updated = stream.copy(
                            twitchAccessToken = tokens?.let { json.encodeToString(TwitchTokens.serializer(), it) }.orEmpty(),
                            twitchLoggedIn = tokens != null,
                        )
                        if (login != null && userId != null) {
                            updated = updated.copy(twitchChannelName = login, twitchChannelId = userId)
                        }
                        updated
                    }
                },
            )
        }
    }

    private fun tokens(store: SettingsStore, streamId: UUID): TwitchTokens? {
        val stream = store.current.streams.firstOrNull { it.id == streamId } ?: return null
        val secret = store.twitchSecret(stream).ifEmpty { return null }
        // An iOS import has a bare access token without a refresh token.
        return runCatching { json.decodeFromString(TwitchTokens.serializer(), secret) }.getOrElse { TwitchTokens(secret, "") }
    }

    /** Signs out: revokes the token at Twitch and deletes it here (docs/PORT_PLAN.md §9.5). */
    fun signOut(context: Context, streamId: UUID) {
        val store = StreamController.settings(context)
        val tokens = tokens(store, streamId)
        save(store, streamId, null)
        mutableSignIn.value = TwitchSignIn.Idle
        if (tokens != null) {
            Thread({ auth().revoke(tokens.accessToken) }, "twitch-revoke").apply { isDaemon = true }.start()
        }
    }

    /**
     * A working access token for [streamId], refreshing it when Twitch says it expired; null when
     * signed out or the sign-in is no longer valid. Blocking: not on the main thread.
     */
    @Synchronized
    fun accessToken(context: Context, streamId: UUID): String? {
        val store = StreamController.settings(context)
        val tokens = tokens(store, streamId) ?: return null
        try {
            auth().validate(tokens.accessToken)
            return tokens.accessToken
        } catch (e: TwitchAuthException) {
            if (e.reason != TwitchAuthException.Reason.INVALID_TOKEN) {
                return tokens.accessToken
            }
        }
        // Saved at once (SettingsStore is thread safe): each refresh token works only once.
        if (tokens.refreshToken.isEmpty()) {
            save(store, streamId, null)
            return null
        }
        return try {
            val refreshed = auth().refresh(tokens)
            save(store, streamId, refreshed)
            refreshed.accessToken
        } catch (e: TwitchAuthException) {
            // Refresh tokens expire after 30 days unused: sign in again.
            save(store, streamId, null)
            null
        }
    }
}
