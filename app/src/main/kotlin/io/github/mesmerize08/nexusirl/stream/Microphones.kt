package io.github.mesmerize08.nexusirl.stream

import android.content.Context
import io.github.mesmerize08.nexusirl.media.pipeline.Microphone
import io.github.mesmerize08.nexusirl.media.pipeline.MicrophoneManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The connected microphones and the one in use (upstream `ModelAudio` mic state), for the whole
 * app: the capture follows it, and the mic picker and remote control show and change it, also
 * while not live. Call from the main thread.
 */
object Microphones {
    private var manager: MicrophoneManager? = null
    private val listeners = mutableListOf<(Microphone?) -> Unit>()

    private val mutableMics = MutableStateFlow<List<Microphone>>(emptyList())
    val mics: StateFlow<List<Microphone>> = mutableMics

    private val mutableSelected = MutableStateFlow<Microphone?>(null)
    val selected: StateFlow<Microphone?> = mutableSelected

    fun start(context: Context) {
        if (manager != null) {
            return
        }
        manager = MicrophoneManager(context.applicationContext).also { microphones ->
            microphones.start { mics, selected ->
                mutableMics.value = mics
                mutableSelected.value = selected
                listeners.forEach { it(selected) }
            }
        }
    }

    /** Upstream `manualSelectMicById`: picks [id] and stops switching automatically. */
    fun select(id: String) {
        manager?.select(id)
    }

    fun addListener(listener: (Microphone?) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Microphone?) -> Unit) {
        listeners -= listener
    }
}
