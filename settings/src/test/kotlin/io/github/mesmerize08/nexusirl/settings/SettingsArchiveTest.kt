package io.github.mesmerize08.nexusirl.settings

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsArchiveTest {
    @TempDir
    lateinit var directory: File

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun imagesRoundTrip() {
        val source = File(directory, "source").apply { mkdirs() }
        val id = UUID.randomUUID().toString().uppercase()
        File(source, id).writeBytes(byteArrayOf(1, 2, 3))
        File(source, "not-an-image.txt").writeText("x")
        val archive = SettingsArchive.write("{}", source)
        assertEquals("{}", SettingsArchive.readSettingsJson(archive))

        val target = File(directory, "Images").apply { mkdirs() }
        File(target, "old").writeText("old")
        assertTrue(SettingsArchive.replaceImages(archive, target))
        assertEquals(listOf(id), target.list()!!.toList())
        assertContentEquals(byteArrayOf(1, 2, 3), File(target, id).readBytes())
        assertEquals(listOf("Images"), directory.list()!!.filter { it.startsWith("Images") })
    }

    @Test
    fun onlyImageEntriesAreExtracted() {
        val id = UUID.randomUUID().toString()
        val archive = zip(
            "settings.json" to "{}".toByteArray(),
            "Images/$id" to byteArrayOf(7),
            "Images/../../evil" to byteArrayOf(1),
            "../$id" to byteArrayOf(1),
            "Images/sub/$id" to byteArrayOf(1),
            "Alerts/$id" to byteArrayOf(1),
        )
        val target = File(directory, "files/Images")
        assertTrue(SettingsArchive.replaceImages(archive, target))
        // Lower-case ids from other writers are stored upper-case, as the app reads them.
        assertEquals(listOf(id.uppercase()), target.list()!!.toList())
        assertEquals(listOf("Images"), File(directory, "files").list()!!.toList())
        assertFalse(File(directory, "evil").exists())
    }

    @Test
    fun plainJsonKeepsImagesAndTooLargeKeepsOldOnes() {
        val target = File(directory, "Images").apply { mkdirs() }
        File(target, "kept").writeText("kept")
        assertFalse(SettingsArchive.replaceImages("{}".toByteArray(), target))
        val huge = zip("Images/${UUID.randomUUID()}" to ByteArray(65 * 1024 * 1024))
        assertFailsWith<SettingsArchive.InvalidArchiveException> { SettingsArchive.replaceImages(huge, target) }
        assertEquals(listOf("kept"), target.list()!!.toList())
        assertEquals(listOf("Images"), directory.list()!!.toList())
    }
}
