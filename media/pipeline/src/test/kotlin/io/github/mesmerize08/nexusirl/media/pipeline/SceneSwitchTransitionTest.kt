package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class SceneSwitchTransitionTest {
    @Test
    fun blurRadiusLikeUpstream() {
        assertEquals(15f, SceneSwitchTransitionKind.BLUR.blurRadius(0.0, 1920))
        assertEquals(30f, SceneSwitchTransitionKind.BLUR.blurRadius(1.0, 1920))
        assertEquals(45f, SceneSwitchTransitionKind.BLUR.blurRadius(10.0, 1920))
        assertEquals(22.5f, SceneSwitchTransitionKind.BLUR.blurRadius(2.0, 960))
        assertEquals(0f, SceneSwitchTransitionKind.BLUR_AND_ZOOM.blurRadius(0.0, 1920))
        assertEquals(12.5f, SceneSwitchTransitionKind.BLUR_AND_ZOOM.blurRadius(2.5, 1920))
        assertEquals(25f, SceneSwitchTransitionKind.BLUR_AND_ZOOM.blurRadius(9.0, 1920))
        assertEquals(0f, SceneSwitchTransitionKind.FREEZE.blurRadius(1.0, 1920))
    }

    @Test
    fun zoomLikeUpstream() {
        assertEquals(1f, SceneSwitchTransitionKind.BLUR_AND_ZOOM.zoom(0.0))
        assertEquals(1 / 0.9f, SceneSwitchTransitionKind.BLUR_AND_ZOOM.zoom(2.0), 1e-6f)
        assertEquals(1 / 0.75f, SceneSwitchTransitionKind.BLUR_AND_ZOOM.zoom(60.0), 1e-6f)
        assertEquals(1f, SceneSwitchTransitionKind.BLUR.zoom(3.0))
        assertEquals(1f, SceneSwitchTransitionKind.FREEZE.zoom(3.0))
    }
}
