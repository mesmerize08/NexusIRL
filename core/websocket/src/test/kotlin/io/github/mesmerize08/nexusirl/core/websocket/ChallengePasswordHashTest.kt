package io.github.mesmerize08.nexusirl.core.websocket

import kotlin.test.Test
import kotlin.test.assertEquals

class ChallengePasswordHashTest {
    @Test
    fun knownVector() {
        // The vector the OBS and remote control tests also assert, so all three stay in step.
        assertEquals(
            "zTM5ki6L2vVvBQiTG9ckH1Lh64AbnCf6XZ226UmnkIA=",
            challengePasswordHash(password = "password", salt = "salt", challenge = "challenge"),
        )
    }
}
