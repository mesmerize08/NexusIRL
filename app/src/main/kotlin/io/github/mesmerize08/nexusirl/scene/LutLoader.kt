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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object LutLoader {
    private val cache = ConcurrentHashMap<String, VideoPipeline.LutStrip>()

    private fun file(context: Context, id: UUID): File =
        File(File(context.filesDir, SceneEngine.IMAGES_DIRECTORY), id.toString().uppercase())

    /** Forgets every loaded LUT, for example after an import replaced the files. */
    fun clearCache() = cache.clear()

    /** Loads (or takes from the cache) a LUT as a texture strip; null when it cannot be read. */
    fun load(context: Context, lut: SettingsColorLut): VideoPipeline.LutStrip? {
        val key = lut.id.toString()
        cache[key]?.let { return it }
        val limited = cube(context, lut) ?: return null
        return VideoPipeline.LutStrip(key, limited.dimension, Luts.toStrip(limited)).also { cache[key] = it }
    }

    /** A LUT's cube, limited to upstream's size, for applying on the CPU; null when it cannot be read. */
    fun cube(context: Context, lut: SettingsColorLut): LutCube? = try {
        val cube = when (lut.type) {
            "bundled" -> decodeImage { options ->
                context.assets.open("luts/${lut.name}.png").use { BitmapFactory.decodeStream(it, null, options) }
            }
            "diskCube" -> file(context, lut.id).bufferedReader().use { Luts.parseCube(it) }
            else -> decodeImage { options -> BitmapFactory.decodeFile(file(context, lut.id).path, options) }
        }
        Luts.limit(cube)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        // A LUT from a settings import is only checked here; skip it rather than crash every launch.
        null
    }

    /** Checks the image's size from its header before decoding it, so a huge image is never decoded. */
    private fun decodeImage(decode: (BitmapFactory.Options) -> Bitmap?): LutCube {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw InvalidLutException("Not an image")
        }
        Luts.imageDimension(bounds.outWidth, bounds.outHeight)
        val bitmap = decode(BitmapFactory.Options()) ?: throw InvalidLutException("Not an image")
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
            // Reads one byte past the limit to tell a too large file without reading all of it.
            val data = input.readAtMost(MAX_FILE_SIZE + 1)
            if (data.size > MAX_FILE_SIZE) throw InvalidLutException("LUT file is too large")
            data
        } ?: throw InvalidLutException("Cannot read the file")
        val isCube = name.endsWith(".cube", ignoreCase = true)
        // Validate before storing.
        try {
            if (isCube) {
                Luts.parseCube(bytes.inputStream().reader())
            } else {
                decodeImage { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            }
        } catch (e: OutOfMemoryError) {
            throw InvalidLutException("LUT is too large")
        }
        val lut = SettingsColorLut(type = if (isCube) "diskCube" else "disk", name = name.substringBeforeLast('.'), enabled = true)
        file(context, lut.id).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        return lut to isCube
    }

    private const val MAX_FILE_SIZE = 64 * 1024 * 1024

    // InputStream.readNBytes needs API 33.
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun delete(context: Context, lut: SettingsColorLut) {
        cache.remove(lut.id.toString())
        file(context, lut.id).delete()
    }
}
