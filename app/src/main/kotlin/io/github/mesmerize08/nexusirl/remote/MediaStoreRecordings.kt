package io.github.mesmerize08.nexusirl.remote

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import io.github.mesmerize08.nexusirl.remotecontrol.WebRecording
import io.github.mesmerize08.nexusirl.remotecontrol.WebRecordings
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * This app's recordings in `Movies/NexusIRL` for the web remote control's Recordings page. Without
 * a media permission the media store only shows the app its own files, so nothing else is listed,
 * downloaded or deleted. Names are looked up, never used as paths.
 */
class MediaStoreRecordings(context: Context) : WebRecordings {
    private val resolver = context.applicationContext.contentResolver

    private data class Entry(val uri: Uri, val name: String, val size: Long)

    private fun entries(): List<Entry> = try {
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE),
            "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$RELATIVE_PATH/", "%.mp4"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    add(Entry(uri, cursor.getString(1), cursor.getLong(2)))
                }
            }
        } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun find(name: String) = entries().firstOrNull { it.name == name }

    override fun list() = entries().map { WebRecording(it.name, StatusFormat.bytes(it.size)) }

    @SuppressLint("Recycle") // The caller closes the stream (RemoteControlWeb uses `use`).
    override fun open(name: String): Pair<Long, InputStream>? {
        val entry = find(name) ?: return null
        return try {
            resolver.openInputStream(entry.uri)?.let { entry.size to it }
        } catch (e: Exception) {
            null
        }
    }

    override fun thumbnail(name: String): ByteArray? {
        val entry = find(name) ?: return null
        return try {
            val bitmap = resolver.loadThumbnail(entry.uri, Size(THUMBNAIL_SIDE, THUMBNAIL_SIDE), null)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /** Fails for recordings from before a reinstall: those belong to the earlier installation. */
    override fun delete(name: String): Boolean {
        val entry = find(name) ?: return false
        return try {
            resolver.delete(entry.uri, null, null) > 0
        } catch (e: SecurityException) {
            false
        }
    }

    private companion object {
        const val RELATIVE_PATH = "Movies/NexusIRL"

        /** Upstream: 480 pixels at most. */
        const val THUMBNAIL_SIDE = 480
    }
}
