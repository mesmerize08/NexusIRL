package io.github.mesmerize08.nexusirl.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import java.util.UUID

/** What the clipboard holds when the clear timer runs. */
sealed interface ClipState {
    /** Android 10+ hides the clipboard from apps without focus. */
    data object Unreadable : ClipState

    /** The clip's marker, or null for a clip that is not ours (or no clip). */
    data class Readable(val marker: String?) : ClipState
}

enum class ClipAction { CLEAR, KEEP, RETRY_WHEN_VISIBLE }

/**
 * Copies secrets (links with access tokens, stream URLs) so they do not linger
 * (docs/PORT_PLAN.md §9.4): the clip is marked sensitive, so keyboards and the clipboard preview
 * hide it, and it is cleared after [CLEAR_AFTER_MILLIS] unless the user copied something else
 * since. Main thread.
 */
object SensitiveClipboard {
    const val CLEAR_AFTER_MILLIS = 60_000L

    /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33); older keyboards such as Gboard read it too. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val EXTRA_MARKER = "io.github.mesmerize08.nexusirl.CLIP_MARKER"

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private class Pending(val marker: String, val copiedAtMillis: Long)

    private var pending: Pending? = null
    private var hasFocus = false
    private var appContext: Context? = null

    fun action(state: ClipState, ours: String): ClipAction = when (state) {
        ClipState.Unreadable -> ClipAction.RETRY_WHEN_VISIBLE
        is ClipState.Readable -> if (state.marker == ours) ClipAction.CLEAR else ClipAction.KEEP
    }

    fun isDue(copiedAtMillis: Long, nowMillis: Long): Boolean = nowMillis - copiedAtMillis >= CLEAR_AFTER_MILLIS

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val marker = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
            putString(EXTRA_MARKER, marker)
        }
        clipboard.setPrimaryClip(clip)
        appContext = context.applicationContext
        pending = Pending(marker, SystemClock.elapsedRealtime())
        handler.removeCallbacks(check)
        handler.postDelayed(check, CLEAR_AFTER_MILLIS)
    }

    /** From the activity: a clear that had to wait for focus runs now. */
    fun onWindowFocusChanged(focused: Boolean) {
        hasFocus = focused
        if (focused) {
            check.run()
        }
    }

    private val check = Runnable {
        val waiting = pending ?: return@Runnable
        val context = appContext ?: return@Runnable
        if (!isDue(waiting.copiedAtMillis, SystemClock.elapsedRealtime())) {
            return@Runnable
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@Runnable
        val description = clipboard.primaryClipDescription
        val state = when {
            description != null -> ClipState.Readable(description.extras?.getString(EXTRA_MARKER))
            // Without focus a null only means "not allowed to look".
            hasFocus -> ClipState.Readable(null)
            else -> ClipState.Unreadable
        }
        when (action(state, waiting.marker)) {
            ClipAction.CLEAR -> {
                clipboard.clearPrimaryClip()
                pending = null
            }
            ClipAction.KEEP -> pending = null
            ClipAction.RETRY_WHEN_VISIBLE -> Unit
        }
    }
}
