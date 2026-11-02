package io.github.mesmerize08.nexusirl.media.pipeline

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** One selectable input (upstream `SettingsMicsMic`). */
data class Microphone(
    /** Stable across reconnects: type plus product name (Android device ids change on replug). */
    val id: String,
    val name: String,
    val type: Int,
    val device: AudioDeviceInfo,
) {
    val isExternal: Boolean
        get() = type != AudioDeviceInfo.TYPE_BUILTIN_MIC

    override fun toString(): String = name
}

/**
 * Upstream's mic choice after a route change (`ModelAudio` `setupAudio`,
 * `autoSwitchMicIfNeededAfterRouteChange`, `manualSwitchMicIfNeededAfterRouteChange`): at start
 * the [preferred] mic (upstream `mics.defaultMic`) if connected; with [autoSwitch], a mic that
 * was just connected and ranks above the current one takes over; otherwise the current mic stays
 * while connected, and when it goes, the highest-ranked connected mic is used.
 */
internal object MicrophoneChoice {
    fun <T> choose(
        mics: List<T>,
        id: (T) -> String,
        priority: (T) -> Int,
        current: String?,
        previous: Set<String>,
        preferred: String?,
        autoSwitch: Boolean,
    ): T? {
        val connected = mics.firstOrNull { id(it) == current }
        if (current == null) {
            return mics.firstOrNull { id(it) == preferred } ?: mics.maxByOrNull(priority)
        }
        if (autoSwitch) {
            val added = mics.filter { id(it) !in previous }.maxByOrNull(priority)
            if (added != null && (connected == null || priority(added) > priority(connected))) {
                return added
            }
        }
        return connected ?: mics.maxByOrNull(priority)
    }
}

/**
 * Tracks connected microphones and picks one (upstream `ModelAudio` mic selection, see
 * [MicrophoneChoice]); [priority] maps mic ids to user priorities (higher wins), defaulting to
 * USB > wired headset > Bluetooth > built-in.
 *
 * Unless [includeBluetooth], Bluetooth mics are not offered, as with upstream's "Bluetooth output
 * only" (on by default): a car or headset then stays an audio output instead of being switched
 * to its low-quality call mode for its mic.
 */
class MicrophoneManager(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((List<Microphone>, Microphone?) -> Unit)? = null

    var autoSwitch = true
    var priority: Map<String, Int> = emptyMap()

    /** Upstream `mics.defaultMic`: chosen at start when connected. */
    var preferred: String? = null

    var includeBluetooth = true
        set(value) {
            if (field != value) {
                field = value
                if (listener != null) {
                    refresh()
                }
            }
        }
    private var previous = emptySet<String>()

    /** Selected mic, or null for the system default route. */
    @Volatile
    var selected: Microphone? = null
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start(onChange: (mics: List<Microphone>, selected: Microphone?) -> Unit) {
        listener = onChange
        audioManager.registerAudioDeviceCallback(callback, handler)
        refresh()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
        listener = null
    }

    fun available(): List<Microphone> = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { it.type in SUPPORTED_TYPES && (includeBluetooth || it.type !in BLUETOOTH_TYPES) }
        .map { device ->
            val name = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
                else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeName(device.type)
            }
            Microphone("${device.type}:$name", name, device.type, device)
        }
        .distinctBy { it.id }

    /**
     * Upstream `manualSelectMicById`: selects a mic, which stays until it goes away or, with
     * [autoSwitch], a higher-ranked mic is connected.
     */
    fun select(id: String) {
        val mics = available()
        selected = mics.firstOrNull { it.id == id } ?: return
        listener?.invoke(mics, selected)
    }

    private fun refresh() {
        val mics = available()
        selected = MicrophoneChoice.choose(mics, { it.id }, ::priorityOf, selected?.id, previous, preferred, autoSwitch)
        previous = mics.map { it.id }.toSet()
        listener?.invoke(mics, selected)
    }

    private fun priorityOf(mic: Microphone): Int = priority[mic.id] ?: when (mic.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 40
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 30
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> 20
        else -> 10
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
        else -> "Microphone"
    }

    private companion object {
        val BLUETOOTH_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        val SUPPORTED_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
