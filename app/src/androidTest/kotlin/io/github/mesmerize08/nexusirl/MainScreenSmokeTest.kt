package io.github.mesmerize08.nexusirl

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Starts the app as a user would and opens every screen: catches crashes like the ones first
 * found on a real phone (opening the camera before the permission, black preview handling,
 * ending a stream). Runs on an emulator in CI (job "ui-tests"); never run it on a personal phone,
 * it changes the app's settings there.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenSmokeTest {
    /** Grants the runtime permissions before the activity starts, as a user who tapped Allow. */
    private val permissions = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val packageName = instrumentation.targetContext.packageName
                listOfNotNull(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
                ).forEach { instrumentation.uiAutomation.grantRuntimePermission(packageName, it) }
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    private fun waitForMainScreen() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(GO_LIVE)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun everyScreenOpensAndReturns() {
        waitForMainScreen()
        for (entry in listOf(
            "Streams",
            "Scenes",
            "Chat settings",
            "Edit",
            "Import and export settings",
            "Location",
            "Remote control",
            "Moblink",
            "Control another phone",
        )) {
            compose.onNodeWithText(entry).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText(GO_LIVE).assertDoesNotExist()
            back()
            waitForMainScreen()
        }
    }

    @Test
    fun addAStreamThroughTheUi() {
        waitForMainScreen()
        compose.onNodeWithText("Streams").performScrollTo().performClick()
        compose.onNodeWithText("Add stream").performClick()
        compose.onNodeWithText("Name").performTextInput("CI test")
        compose.onNodeWithText("Stream URL").performTextInput("rtmp://127.0.0.1:1935/live/ci-secret-key")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("CI test").assertExists()
        back()
        waitForMainScreen()
        // The summary shows the server, never the key.
        compose.onNodeWithText("rtmp://127.0.0.1:1935", substring = true).assertExists()
        compose.onNodeWithText("ci-secret-key", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val GO_LIVE = "Go live"
    }
}
