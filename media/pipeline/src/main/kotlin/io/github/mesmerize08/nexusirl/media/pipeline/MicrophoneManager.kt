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
 * Tracks connected microphones and picks one (upstream `ModelAudio` mic selection). With
 * [autoSwitch], the highest-priority connected mic is selected whenever inputs change, like
 * upstream's `autoSwitchMicIfNeededAfterRouteChange`; [priority] maps mic ids to user priorities
 * (higher wins), defaulting to USB > wired headset > Bluetooth > built-in.
 */
class MicrophoneManager(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((List<Microphone>, Microphone?) -> Unit)? = null

    var autoSwitch = true
    var priority: Map<String, Int> = emptyMap()

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
        .filter { it.type in SUPPORTED_TYPES }
        .map { device ->
            val name = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
                else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeName(device.type)
            }
            Microphone("${device.type}:$name", name, device.type, device)
        }
        .distinctBy { it.id }

    /**
     * Manually selects a mic. Automatic switching stops, so plugging something else in later does
     * not take over; if the chosen mic goes away, the best remaining one is used.
     */
    fun select(id: String) {
        val mic = available().firstOrNull { it.id == id } ?: return
        autoSwitch = false
        selected = mic
        listener?.invoke(available(), selected)
    }

    private fun refresh() {
        val mics = available()
        val current = selected
        selected = when {
            autoSwitch -> mics.maxByOrNull { priorityOf(it) }
            current != null && mics.any { it.id == current.id } -> mics.first { it.id == current.id }
            else -> mics.maxByOrNull { priorityOf(it) }
        }
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
