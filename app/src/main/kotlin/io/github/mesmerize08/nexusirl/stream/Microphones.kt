package io.github.mesmerize08.nexusirl.stream

import android.content.Context
import io.github.mesmerize08.nexusirl.media.pipeline.Microphone
import io.github.mesmerize08.nexusirl.media.pipeline.MicrophoneManager
import io.github.mesmerize08.nexusirl.settings.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The connected microphones and the one in use (upstream `ModelAudio` mic state), for the whole
 * app: the capture follows it, and the mic picker and remote control show and change it, also
 * while not live. Call from the main thread.
 */
object Microphones {
    private var manager: MicrophoneManager? = null
    private val listeners = mutableListOf<(Microphone?) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutableMics = MutableStateFlow<List<Microphone>>(emptyList())
    val mics: StateFlow<List<Microphone>> = mutableMics

    private val mutableSelected = MutableStateFlow<Microphone?>(null)
    val selected: StateFlow<Microphone?> = mutableSelected

    fun start(context: Context) {
        if (manager != null) {
            return
        }
        val store = StreamController.settings(context)
        manager = MicrophoneManager(context.applicationContext).also { microphones ->
            configure(microphones, store.current)
            microphones.preferred = store.current.mics.defaultMic
            microphones.start { mics, selected ->
                mutableMics.value = mics
                mutableSelected.value = selected
                listeners.forEach { it(selected) }
                // Upstream `defaultMic` follows every selection, also automatic ones.
                if (selected != null && selected.id != store.current.mics.defaultMic) {
                    store.update { it.copy(mics = it.mics.copy(defaultMic = selected.id)) }
                }
            }
        }
        // Follows the Bluetooth output only and auto switch settings.
        scope.launch { store.database.collect { database -> manager?.let { configure(it, database) } } }
    }

    private fun configure(microphones: MicrophoneManager, database: Database) {
        microphones.autoSwitch = database.mics.autoSwitch
        microphones.includeBluetooth = !database.debug.bluetoothOutputOnly
    }

    /** Upstream `manualSelectMicById`: picks [id] and remembers it. */
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
