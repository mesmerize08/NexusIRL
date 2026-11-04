package io.github.mesmerize08.nexusirl.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveClipboardTest {
    @Test
    fun clearsOnlyOurOwnClip() {
        assertEquals(ClipAction.CLEAR, SensitiveClipboard.action(ClipState.Readable("ours"), "ours"))
        // The user copied something else since: leave it alone.
        assertEquals(ClipAction.KEEP, SensitiveClipboard.action(ClipState.Readable("someone else's"), "ours"))
        assertEquals(ClipAction.KEEP, SensitiveClipboard.action(ClipState.Readable(null), "ours"))
    }

    @Test
    fun waitsWhileTheClipboardCannotBeRead() {
        // Android 10+ hides the clipboard from apps in the background: check again once visible.
        assertEquals(ClipAction.RETRY_WHEN_VISIBLE, SensitiveClipboard.action(ClipState.Unreadable, "ours"))
    }

    @Test
    fun dueAfterSixtySeconds() {
        assertFalse(SensitiveClipboard.isDue(copiedAtMillis = 1_000, nowMillis = 60_999))
        assertTrue(SensitiveClipboard.isDue(copiedAtMillis = 1_000, nowMillis = 61_000))
    }
}
