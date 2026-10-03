package com.nc.v380

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nc.v380.camera.CameraCredentials
import com.nc.v380.camera.CameraStore
import com.nc.v380.camera.player.LivePlayer
import com.nc.v380.camera.protocol.*
import com.nc.v380.ui.CameraApp
import com.nc.v380.ui.theme.NcV380Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CameraPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var testStore: CameraStore

    @Before fun useIsolatedTestSettings() {
        testStore = CameraStore(compose.activity, "playback-tests", "ncv380.playback-tests")
        testStore.forgetCredentials()
        compose.runOnUiThread {
            compose.activity.setContent { NcV380Theme { androidx.compose.runtime.key("playback-tests") { CameraApp(testStore) } } }
        }
    }

    @Test fun nativeDecoderRendersVideoAndAudioOutputStarts() {
        compose.onNodeWithTag("camera-card").performClick()
        compose.onNodeWithText("Later").performClick()
        fun findSurface(view: View): SurfaceView? = when (view) {
            is SurfaceView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findSurface(view.getChildAt(it)) }
            else -> null
        }
        lateinit var videoSurface: SurfaceView
        compose.runOnIdle { videoSurface = findSurface(compose.activity.window.decorView)!! }
        val rendered = CountDownLatch(1)
        val audioStarted = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val player = LivePlayer(compose.activity, videoSurface.holder.surface,
            onVideoRendered = { rendered.countDown() },
            onAudioStatus = { if (it == "Audio on") audioStarted.countDown() else failure.set(it) },
            onError = { failure.set(it); rendered.countDown() },
        )
        try {
            player.setInfo(StreamInfo(64, 64, 2))
            val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("decoder-test.h264").use { it.readBytes() }
            val frames = ArrayList<ByteArray>()
            var current = ByteArrayOutputStream()
            for (unit in AnnexB.units(fixture)) {
                if (unit[0].toInt() and 31 == 9 && current.size() > 0) {
                    frames.add(current.toByteArray()); current = ByteArrayOutputStream()
                }
                current.write(AnnexB.startCoded(unit))
            }
            if (current.size() > 0) frames.add(current.toByteArray())
            val now = System.nanoTime() / 1000
            frames.forEachIndexed { index, data -> player.video(VideoFrame(data, true, now + index * 125_000)) }
            player.audio(ByteArray(800) { 0xd5.toByte() }, AudioEncoding.ALAW)
            assertTrue("No frame rendered: ${failure.get()}", rendered.await(10, TimeUnit.SECONDS))
            assertTrue("Audio output did not start: ${failure.get()}", audioStarted.await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
            player.setMuted(true)
        } finally { player.close() }
    }

    @Test fun rememberedPasswordIsEncryptedAndCanBeForgotten() {
        val store = testStore
        try {
            store.saveCredentials(CameraCredentials("test-owner", "local-test-only"), true)
            val sealed = compose.activity.getSharedPreferences("playback-tests", 0).getString("password", null)!!
            assertFalse(sealed.contains("local-test-only"))
            assertEquals("local-test-only", store.credentials()?.password)
            store.forgetCredentials()
            assertNull(store.credentials())
        } finally { store.forgetCredentials() }
    }

    @Test fun jpegVideoRendersAndBlockBasedImaAudioStarts() {
        compose.onNodeWithTag("camera-card").performClick()
        compose.onNodeWithText("Later").performClick()
        fun findSurface(view: View): SurfaceView? = when (view) {
            is SurfaceView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findSurface(view.getChildAt(it)) }
            else -> null
        }
        lateinit var videoSurface: SurfaceView
        compose.runOnIdle { videoSurface = findSurface(compose.activity.window.decorView)!! }
        val rendered = CountDownLatch(1)
        val audioStarted = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val player = LivePlayer(compose.activity, videoSurface.holder.surface,
            onVideoRendered = { rendered.countDown() },
            onAudioStatus = { if (it == "Audio on") audioStarted.countDown() else failure.set(it) },
            onError = { failure.set(it); rendered.countDown() },
        )
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0, 107, 100)) }
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            bitmap.recycle()
            assertEquals(64 to 64, JpegFrame.dimensions(jpeg))
            player.video(VideoFrame(jpeg, true, System.nanoTime() / 1000, VideoEncoding.MJPEG))
            player.audio(ByteArray(256), AudioEncoding.IMA_WAV)
            assertTrue("JPEG wasn't rendered: ${failure.get()}", rendered.await(10, TimeUnit.SECONDS))
            assertTrue("IMA audio output didn't start: ${failure.get()}", audioStarted.await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally { player.close() }
    }
}
