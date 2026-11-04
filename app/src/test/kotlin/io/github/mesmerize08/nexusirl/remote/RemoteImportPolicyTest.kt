package io.github.mesmerize08.nexusirl.remote

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteImportPolicyTest {
    private fun decide(allowed: Boolean = true, fromWeb: Boolean = false, live: Boolean = false, waiting: Boolean = false) =
        RemoteImportPolicy.decide(allowed = allowed, fromWeb = fromWeb, live = live, waiting = waiting)

    @Test
    fun asksTheUserWhenAllowed() {
        assertEquals(RemoteImportDecision.ASK, decide())
    }

    @Test
    fun refusedUnlessTurnedOn() {
        assertEquals(RemoteImportDecision.REFUSE_NOT_ALLOWED, decide(allowed = false))
    }

    @Test
    fun browsersNeverImport() {
        // A web page is any browser on the LAN that has the link; it never imports settings.
        assertEquals(RemoteImportDecision.REFUSE_FROM_WEB, decide(fromWeb = true))
    }

    @Test
    fun refusedWhileLive() {
        assertEquals(RemoteImportDecision.REFUSE_LIVE, decide(live = true))
    }

    @Test
    fun oneQuestionAtATime() {
        assertEquals(RemoteImportDecision.REFUSE_BUSY, decide(waiting = true))
    }

    @Test
    fun timeoutIsThirtySeconds() {
        assertEquals(30_000L, RemoteImportPolicy.ANSWER_TIMEOUT_MILLIS)
    }
}
