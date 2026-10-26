// Port of the recordings part of RemoteControlWebDelegate (Moblin/RemoteControl/RemoteControlWeb.swift,
// Moblin/Various/Model/ModelRemoteControl.swift) @97e26d2.
package io.github.mesmerize08.nexusirl.remotecontrol

import java.io.InputStream

/** One recording as the web remote control's Recordings page lists it. */
data class WebRecording(val name: String, val size: String)

/**
 * The app's own recordings for the web remote control (upstream `remoteControlWebGetRecordings`
 * and friends). Names come from [list]; implementations look a name up among their recordings and
 * never use it as a path, so a crafted name reaches nothing else.
 */
interface WebRecordings {
    /** Newest first. */
    fun list(): List<WebRecording>

    /** The recording's length in bytes and its contents, or null when there is no such recording. */
    fun open(name: String): Pair<Long, InputStream>?

    /** A JPEG of the first frame, or null. */
    fun thumbnail(name: String): ByteArray?

    /** Whether the recording was deleted. */
    fun delete(name: String): Boolean
}
