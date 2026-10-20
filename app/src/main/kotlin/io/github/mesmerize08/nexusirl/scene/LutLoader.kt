// LUT files (upstream ModelCamera.swift LUT import and LutEffect.setLut @97e26d2).
package io.github.mesmerize08.nexusirl.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import io.github.mesmerize08.nexusirl.effects.InvalidLutException
import io.github.mesmerize08.nexusirl.effects.LutCube
import io.github.mesmerize08.nexusirl.effects.Luts
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import io.github.mesmerize08.nexusirl.settings.SettingsColorLut
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object LutLoader {
    private val cache = ConcurrentHashMap<String, VideoPipeline.LutStrip>()

    private fun file(context: Context, id: UUID): File =
        File(File(context.filesDir, SceneEngine.IMAGES_DIRECTORY), id.toString().uppercase())

    /** Loads (or takes from the cache) a LUT as a texture strip; null when it cannot be read. */
    fun load(context: Context, lut: SettingsColorLut): VideoPipeline.LutStrip? {
        val key = lut.id.toString()
        cache[key]?.let { return it }
        val cube = try {
            when (lut.type) {
                "bundled" -> context.assets.open("luts/${lut.name}.png").use { decodeImage(BitmapFactory.decodeStream(it)) }
                "diskCube" -> Luts.parseCube(file(context, lut.id).readText())
                else -> decodeImage(BitmapFactory.decodeFile(file(context, lut.id).path))
            }
        } catch (e: Exception) {
            null
        } ?: return null
        val limited = Luts.limit(cube)
        return VideoPipeline.LutStrip(key, limited.dimension, Luts.toStrip(limited)).also { cache[key] = it }
    }

    private fun decodeImage(bitmap: Bitmap?): LutCube {
        bitmap ?: throw InvalidLutException("Not an image")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return Luts.fromImage(bitmap.width, bitmap.height, pixels)
    }

    /**
     * Imports a `.cube` file or a LUT image (upstream "Add LUT"). Returns the new settings entry,
     * or throws [InvalidLutException] with a message for the user.
     */
    fun import(context: Context, uri: Uri): Pair<SettingsColorLut, Boolean> {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "LUT"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val data = input.readBytes()
            if (data.size > 64 * 1024 * 1024) throw InvalidLutException("LUT file is too large")
            data
        } ?: throw InvalidLutException("Cannot read the file")
        val isCube = name.endsWith(".cube", ignoreCase = true)
        // Validate before storing.
        if (isCube) {
            Luts.parseCube(bytes.toString(Charsets.UTF_8))
        } else {
            decodeImage(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        val lut = SettingsColorLut(type = if (isCube) "diskCube" else "disk", name = name.substringBeforeLast('.'), enabled = true)
        file(context, lut.id).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        return lut to isCube
    }

    fun delete(context: Context, lut: SettingsColorLut) {
        cache.remove(lut.id.toString())
        file(context, lut.id).delete()
    }
}
