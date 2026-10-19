// Port of the archive parts of Moblin/Various/Settings/Settings.swift @97e26d2
// (`extractArchive`, `exportToFile`).
package io.github.mesmerize08.nexusirl.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `.moblinSettings` files: a zip archive holding `settings.json` plus media folders (images,
 * alerts, ...). Upstream writes it uncompressed (stored); it reads any zip.
 *
 * v1 reads and writes `settings.json` only; media folders are ignored on import.
 */
object SettingsArchive {
    const val SETTINGS_JSON = "settings.json"
    const val FILE_EXTENSION = "moblinSettings"

    /** Hard limit so a hostile archive cannot exhaust memory (docs/PORT_PLAN.md §9.7). */
    private const val MAX_SETTINGS_BYTES = 16 * 1024 * 1024

    class InvalidArchiveException(message: String) : Exception(message)

    /**
     * Returns the settings JSON from [data], which may be a `.moblinSettings` zip or (like
     * upstream's clipboard import and older exports) plain JSON text.
     */
    fun readSettingsJson(data: ByteArray): String {
        if (data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            return readFromZip(data)
        }
        val text = data.toString(Charsets.UTF_8).trimStart('﻿').trim()
        if (!text.startsWith("{")) {
            throw InvalidArchiveException("Not a settings file")
        }
        return text
    }

    private fun readFromZip(data: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name != SETTINGS_JSON) {
                    continue
                }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) {
                        break
                    }
                    out.write(buffer, 0, count)
                    if (out.size() > MAX_SETTINGS_BYTES) {
                        throw InvalidArchiveException("Settings file is too large")
                    }
                }
                return out.toString(Charsets.UTF_8.name())
            }
        }
        throw InvalidArchiveException("Settings file not found in archive")
    }

    /** Builds a `.moblinSettings` archive (stored, like upstream) containing [settingsJson]. */
    fun write(settingsJson: String): ByteArray {
        val bytes = settingsJson.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry(SETTINGS_JSON).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
