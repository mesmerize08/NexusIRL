package io.github.mesmerize08.nexusirl.remote

import io.github.mesmerize08.nexusirl.settings.Database

enum class RemoteImportDecision { ASK, REFUSE_NOT_ALLOWED, REFUSE_FROM_WEB, REFUSE_LIVE, REFUSE_BUSY }

/**
 * Whether an assistant's `importSettings` is put to the user (docs/PORT_PLAN.md §9.6, §9.7,
 * threat T6). Upstream applies it at once; here it needs "Allow settings import" turned on and the
 * user's yes on the phone, never while live, and never from a web page.
 */
object RemoteImportPolicy {
    /** Unanswered questions are rejected after this long. */
    const val ANSWER_TIMEOUT_MILLIS = 30_000L

    fun decide(allowed: Boolean, fromWeb: Boolean, live: Boolean, waiting: Boolean): RemoteImportDecision = when {
        fromWeb -> RemoteImportDecision.REFUSE_FROM_WEB
        !allowed -> RemoteImportDecision.REFUSE_NOT_ALLOWED
        live -> RemoteImportDecision.REFUSE_LIVE
        waiting -> RemoteImportDecision.REFUSE_BUSY
        else -> RemoteImportDecision.ASK
    }
}

/**
 * An assistant's settings import waiting for the user. [expiresAtMillis] is on the
 * `SystemClock.elapsedRealtime` clock.
 */
class PendingRemoteImport(
    val data: ByteArray,
    val json: String,
    val database: Database,
    val expiresAtMillis: Long,
    internal val respond: (Boolean) -> Unit,
)
