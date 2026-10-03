package com.nc.v380

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import com.nc.v380.camera.CameraStore
import com.nc.v380.ui.CameraApp
import com.nc.v380.ui.theme.NcV380Theme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Before
import org.junit.Test

class CameraNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun useIsolatedTestSettings() {
        val store = CameraStore(compose.activity, "navigation-tests", "ncv380.navigation-tests")
        store.forgetCredentials()
        compose.runOnUiThread {
            compose.activity.setContent { NcV380Theme { androidx.compose.runtime.key("navigation-tests") { CameraApp(store) } } }
        }
    }

    @Test fun cameraOpensLiveViewAndBackReturnsToList() {
        compose.onNodeWithText("Your cameras").assertIsDisplayed()
        compose.onNodeWithText("192.168.0.111").assertIsDisplayed()
        screenshot("landing")
        compose.onNodeWithTag("camera-card").performClick()
        compose.onNodeWithText("Camera password").assertIsDisplayed()
        compose.onNodeWithText("Connect", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithTag("live-screen").assertIsDisplayed()
        screenshot("live-view")
        compose.onNodeWithTag("mute-audio").performClick()
        compose.onNodeWithText("Unmute").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to cameras").performClick()
        compose.onNodeWithText("Your cameras").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val folder = compose.activity.getExternalFilesDir("screenshots")!!
        folder.mkdirs()
        java.io.File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
