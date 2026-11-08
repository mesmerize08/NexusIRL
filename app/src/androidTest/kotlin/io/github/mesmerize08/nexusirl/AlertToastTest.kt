package io.github.mesmerize08.nexusirl

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mesmerize08.nexusirl.ui.AlertToastHost
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Twitch alert toasts show one at a time and then go away. */
@RunWith(AndroidJUnit4::class)
class AlertToastTest {
    // Not createComposeRule(): its activity can be recreated by a rotation left from MainActivity.
    @get:Rule
    val compose = createAndroidComposeRule<TestHostActivity>()

    @Test
    fun alertsShowOneAfterAnother() {
        val toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
        compose.setContent { AlertToastHost(toasts = toasts) }
        compose.waitForIdle()
        toasts.tryEmit("Cool_User just followed!")
        toasts.tryEmit("Other_User raided with a party of 12!")
        compose.waitUntil(5_000) { shown("Cool_User just followed!") == true }
        compose.onNodeWithText("Other_User raided with a party of 12!").assertDoesNotExist()
        // The second one follows once the first has had its time.
        compose.waitUntil(10_000) { shown("Other_User raided with a party of 12!") == true }
        compose.waitUntil(10_000) { shown("Other_User raided with a party of 12!") == false }
    }

    /** Null while there is no screen to look at (briefly, on a slow emulator): keep waiting then. */
    private fun shown(text: String): Boolean? = try {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    } catch (e: IllegalStateException) {
        null
    }
}
