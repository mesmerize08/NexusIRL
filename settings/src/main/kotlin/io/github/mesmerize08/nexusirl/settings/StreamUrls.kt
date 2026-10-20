// Port of Common/Various/Validate.swift `isValidUrl` and Common/Various/CommonUtils.swift `cleanUrl`
// @97e26d2, limited to the protocols the Android app supports.
package io.github.mesmerize08.nexusirl.settings

import java.net.URI

object StreamUrls {
    /** Protocols the Android app can stream with today. */
    val SUPPORTED_SCHEMES = setOf("srt", "srtla", "rtmp", "rtmps")

    /** Upstream protocols that are recognised but not supported on Android yet. */
    private val UPSTREAM_ONLY_SCHEMES = setOf("rist", "whip", "whips", "mobcam")

    /** Upstream `cleanUrl`: removes spaces and lower-cases the scheme. */
    fun clean(url: String): String {
        val stripped = url.replace(" ", "")
        val schemeEnd = stripped.indexOf("://")
        return if (schemeEnd > 0) stripped.substring(0, schemeEnd).lowercase() + stripped.substring(schemeEnd) else stripped
    }

    /** Upstream `isValidUrl` for stream URLs: null when valid, otherwise a message for the user. */
    fun validate(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "Malformed URL"
        }
        val scheme = uri.scheme ?: return "Scheme missing"
        if (uri.host.isNullOrEmpty()) {
            return "Host missing"
        }
        if (uri.port != -1 && uri.port !in 1..65535) {
            return "Bad port"
        }
        return when (scheme) {
            "rtmp", "rtmps" -> validateRtmp(uri)
            "srt", "srtla" -> if (uri.port == -1) "SRT(LA) port number missing" else null
            in UPSTREAM_ONLY_SCHEMES -> "${scheme.uppercase()} is not supported on Android yet"
            else -> "Unsupported scheme $scheme"
        }
    }

    /** Upstream `isValidRtmpUrl`: the last path component is the stream key and must be present. */
    private fun validateRtmp(uri: URI): String? {
        val streamKey = uri.rawPath.orEmpty().split('/').lastOrNull { it.isNotEmpty() }
        return if (streamKey == null) "RTMP stream key missing" else null
    }
}
