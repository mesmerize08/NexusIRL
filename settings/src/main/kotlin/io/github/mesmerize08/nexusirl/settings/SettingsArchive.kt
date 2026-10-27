// Port of the archive parts of Moblin/Various/Settings/Settings.swift @97e26d2
// (`extractArchive`, `exportToFile`).
package io.github.mesmerize08.nexusirl.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `.moblinSettings` files: a zip archive holding `settings.json` plus media folders (images,
 * alerts, ...). Upstream writes it uncompressed (stored); it reads any zip.
 *
 * Of the media folders, NexusIRL uses `Images` (image widgets and LUTs, one file per UUID); the
 * others (alerts, PNGTuber, VTuber, media player, replay transitions) are ignored on import.
 */
object SettingsArchive {
    const val SETTINGS_JSON = "settings.json"
    const val FILE_EXTENSION = "moblinSettings"

    /** Upstream `imagesStorageDirectory`, in the archive and in the app's files. */
    const val IMAGES_DIRECTORY = "Images"

    /** Hard limits so a hostile archive cannot exhaust memory or storage (docs/PORT_PLAN.md §9.7). */
    private const val MAX_SETTINGS_BYTES = 16L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    private const val MAX_IMAGES_BYTES = 256L * 1024 * 1024

    /** The only media entries extracted: `Images/<UUID>`, nothing that could leave the folder. */
    private val IMAGE_ENTRY = Regex("$IMAGES_DIRECTORY/([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})")

    class InvalidArchiveException(message: String) : Exception(message)

    /**
     * Returns the settings JSON from [data], which may be a `.moblinSettings` zip or (like
     * upstream's clipboard import and older exports) plain JSON text.
     */
    fun readSettingsJson(data: ByteArray): String {
        if (isZip(data)) {
            return readFromZip(data)
        }
        val text = data.toString(Charsets.UTF_8).trimStart('﻿').trim()
        if (!text.startsWith("{")) {
            throw InvalidArchiveException("Not a settings file")
        }
        return text
    }

    private fun isZip(data: ByteArray) = data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()

    private fun readFromZip(data: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name != SETTINGS_JSON) {
                    continue
                }
                val out = ByteArrayOutputStream()
                copy(zip, out, MAX_SETTINGS_BYTES, "Settings file is too large")
                return out.toString(Charsets.UTF_8.name())
            }
        }
        throw InvalidArchiveException("Settings file not found in archive")
    }

    /** Copies at most [limit] bytes and returns the count. */
    private fun copy(input: InputStream, output: OutputStream, limit: Long, tooLarge: String): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                return total
            }
            total += count
            if (total > limit) {
                throw InvalidArchiveException(tooLarge)
            }
            output.write(buffer, 0, count)
        }
    }

    /**
     * Upstream `extractArchive` for images: when [data] is an archive, [directory] is replaced by
     * the archive's `Images` (emptied when it has none, as upstream recreates its media folders).
     * The old images stay until all new ones are written. Plain JSON leaves [directory] alone.
     * Returns whether the images were replaced.
     */
    fun replaceImages(data: ByteArray, directory: File): Boolean {
        if (!isZip(data)) {
            return false
        }
        val parent = directory.absoluteFile.parentFile
        val staging = File(parent, "${directory.name}.importing")
        val old = File(parent, "${directory.name}.old")
        staging.deleteRecursively()
        old.deleteRecursively()
        try {
            if (!staging.mkdirs()) {
                throw InvalidArchiveException("Cannot write images")
            }
            var total = 0L
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val id = IMAGE_ENTRY.matchEntire(entry.name)?.groupValues?.get(1) ?: continue
                    File(staging, id.uppercase()).outputStream().use { out ->
                        total += copy(zip, out, MAX_IMAGE_BYTES, "An image is too large")
                    }
                    if (total > MAX_IMAGES_BYTES) {
                        throw InvalidArchiveException("The images are too large")
                    }
                }
            }
            if (directory.exists() && !directory.renameTo(old)) {
                throw InvalidArchiveException("Cannot replace images")
            }
            if (!staging.renameTo(directory)) {
                old.renameTo(directory)
                throw InvalidArchiveException("Cannot replace images")
            }
            old.deleteRecursively()
            return true
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Builds a `.moblinSettings` archive (stored, like upstream) containing [settingsJson] and the
     * images in [imagesDirectory], if given.
     */
    fun write(settingsJson: String, imagesDirectory: File? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun stored(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            stored(SETTINGS_JSON, settingsJson.toByteArray(Charsets.UTF_8))
            val images = imagesDirectory?.listFiles().orEmpty().filter {
                it.isFile && IMAGE_ENTRY.matches("$IMAGES_DIRECTORY/${it.name}")
            }
            for (image in images.sortedBy { it.name }) {
                stored("$IMAGES_DIRECTORY/${image.name}", image.readBytes())
            }
        }
        return out.toByteArray()
    }
}
