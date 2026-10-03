package com.nc.v380

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.nc.v380.camera.CameraStore
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run explicitly on the owner's phone; does not capture the camera picture or sound. */
class CameraDevicePlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedCameraRendersAndPlaysAudioThroughTheActualLiveScreen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cameraDiagnostic") == "true")
        assumeTrue(CameraStore(InstrumentationRegistry.getInstrumentation().targetContext).credentials() != null)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("camera-card").performClick()
            fun awaitLive() {
                compose.waitUntil(20_000) { compose.onAllNodesWithText("●  LIVE").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Audio on").fetchSemanticsNodes().isNotEmpty() }
            }
            awaitLive()
            compose.onNodeWithTag("mute-audio").performClick()
            compose.onNodeWithText("Muted").assertIsDisplayed()
            compose.onNodeWithTag("mute-audio").performClick()
            compose.onNodeWithText("Audio on").assertIsDisplayed()
            compose.onNodeWithText("Reconnect").performScrollTo().performClick()
            awaitLive()
            compose.onNodeWithContentDescription("Back to cameras").performClick()
            compose.onNodeWithText("Your cameras").assertIsDisplayed()
        }
    }
}
