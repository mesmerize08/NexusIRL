package io.github.mesmerize08.nexusirl

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** An empty activity for UI tests of single composables (debug builds only, see the manifest). */
class TestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A test waits up to tens of seconds; the screen must not time out and stop the activity
        // (the CI emulator then reported "No compose hierarchies found" mid-test).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
