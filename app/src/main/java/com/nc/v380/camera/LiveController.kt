package com.nc.v380.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nc.v380.camera.player.LivePlayer
import com.nc.v380.camera.protocol.AudioEncoding
import com.nc.v380.camera.protocol.CameraStreamListener
import com.nc.v380.camera.protocol.StreamInfo
import com.nc.v380.camera.protocol.V380Connection
import com.nc.v380.camera.protocol.VideoFrame
import java.io.Closeable

data class LiveState(
    val message: String = "Connect to start watching",
    val videoPlaying: Boolean = false,
    val connecting: Boolean = false,
    val error: Boolean = false,
    val streamAccepted: Boolean = false,
    val audioMessage: String = "Waiting for camera audio",
    val aspectRatio: Float = 16f / 9f,
)

/** All UI state belongs to the main thread. A generation prevents late callbacks after leaving. */
class LiveController(private val context: Context, private val store: CameraStore) : Closeable {
    var state by mutableStateOf(LiveState())
        private set
    var muted by mutableStateOf(false)
        private set
    var hasLogin by mutableStateOf(store.credentials() != null)
        private set
    private var credentials = store.credentials()
    private val main = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var active = false
    private var networkAllowed = false
    private var wantsConnection = hasLogin
    private var generation = 0
    private var connection: V380Connection? = null
    private var player: LivePlayer? = null
    private var watchdog: Runnable? = null

    fun attachSurface(value: Surface) { surface = value; maybeStart() }
    fun detachSurface() { surface = null; stop("Live view paused") }
    fun setActive(value: Boolean) {
        active = value
        if (value) maybeStart() else stop("Live view paused")
    }
    fun setNetworkAllowed(value: Boolean) {
        networkAllowed = value
        if (value) maybeStart() else stop("Allow local network access to connect")
    }

    fun connect(login: CameraCredentials, remember: Boolean): String? {
        login.validationError(store.settings().olderCamera)?.let { return it }
        try { store.saveCredentials(login, remember) } catch (_: Exception) {
            return "Couldn't save the camera login. Try turning off Remember on this phone."
        }
        credentials = login
        hasLogin = true
        wantsConnection = true
        stop("Connecting to your camera…")
        maybeStart()
        return null
    }

    fun reconnect() { wantsConnection = true; stop("Connecting to your camera…"); maybeStart() }
    fun toggleMute() { muted = !muted; player?.setMuted(muted) }

    private fun maybeStart() {
        if (!active || !networkAllowed || !wantsConnection || connection != null) return
        val target = surface?.takeIf { it.isValid } ?: return
        val login = credentials ?: return
        val current = ++generation
        state = LiveState(message = "Connecting to your camera…", connecting = true)
        val localPlayer = LivePlayer(context, target,
            onVideoRendered = {
                update(current) {
                    if (!state.videoPlaying) {
                        watchdog?.let { main.removeCallbacks(it) }
                        state = state.copy(videoPlaying = true, connecting = false, message = "Live")
                    }
                }
            },
            onAudioStatus = { message -> update(current) { state = state.copy(audioMessage = message) } },
            onError = { message -> update(current) { fail(message) } },
        )
        localPlayer.setMuted(muted)
        player = localPlayer
        val localConnection = V380Connection(store.settings(), login, object : CameraStreamListener {
            override fun onStatus(message: String) = update(current) {
                if (!state.videoPlaying) state = state.copy(message = message)
            }
            override fun onStream(info: StreamInfo) {
                localPlayer.setInfo(info)
                update(current) { state = state.copy(streamAccepted = true, aspectRatio = (info.width.toFloat() / info.height).coerceIn(0.5f, 3f)) }
            }
            override fun onVideo(frame: VideoFrame) = localPlayer.video(frame)
            override fun onAudio(data: ByteArray, encoding: AudioEncoding) = localPlayer.audio(data, encoding)
            override fun onAudioUnavailable(message: String) = update(current) { state = state.copy(audioMessage = message) }
        })
        connection = localConnection
        watchdog = Runnable {
            if (generation == current && !state.videoPlaying) fail(
                if (state.streamAccepted) "The camera connected, but its video couldn't be displayed. Reconnect or check camera compatibility in Settings."
                else "The camera connection didn't finish. Check Camera login and Settings, then reconnect."
            )
        }.also { main.postDelayed(it, 30_000) }
        Thread({
            try {
                localConnection.run()
                update(current) { fail("The camera disconnected. Reconnect to continue watching.") }
            } catch (error: Exception) {
                update(current) { fail(localConnection.failureMessage(error)) }
            } finally { localConnection.close() }
        }, "camera-network").start()
    }

    private fun update(expected: Int, operation: () -> Unit) { main.post { if (generation == expected) operation() } }
    private fun fail(message: String) {
        wantsConnection = false
        stop(message)
        state = state.copy(error = true)
    }
    private fun stop(message: String) {
        generation++
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        connection?.close(); connection = null
        player?.close(); player = null
        state = LiveState(message = message)
    }

    override fun close() { wantsConnection = false; active = false; stop("Live view closed"); credentials = null }
}
