package com.nc.v380

import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.nc.v380.camera.CameraStore
import com.nc.v380.camera.protocol.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit, local hardware check. Normal test runs skip it and never contact a real camera. */
class CameraDeviceDiagnosticTest {
    @Test fun diagnoseSavedCameraConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("cameraDiagnostic") == "true")
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "CAMERA_DIAGNOSTIC: $message\n")
        })
        val store = CameraStore(instrumentation.targetContext)
        val credentials = store.credentials()
        if (credentials == null) {
            report("No camera login saved in the app. Enter it on the phone before this check.")
            return
        }
        val settings = store.settings()
        report("Saved login available; compatibility=${settings.olderCamera}; manual ID=${settings.deviceId.isNotEmpty()}")
        var videoFrames = 0
        var audioFrames = 0
        lateinit var connection: V380Connection
        connection = V380Connection(settings, credentials, object : CameraStreamListener {
            override fun onStatus(message: String) { report(message) }
            override fun onDiagnostic(message: String) { report(message) }
            override fun onStream(info: StreamInfo) {
                report("Stream accepted: version=${info.deviceVersion}, size=${info.width}x${info.height}")
            }
            override fun onVideo(frame: VideoFrame) {
                videoFrames++
                if (videoFrames == 1) {
                    if (frame.encoding == VideoEncoding.MJPEG) {
                        val bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                        report("First JPEG: decoded=${bitmap != null}; size=${bitmap?.width}x${bitmap?.height}")
                        bitmap?.recycle()
                    } else {
                        val types = AnnexB.units(frame.data).take(5).map { it[0].toInt() and 31 }
                        report("First valid video: bytes=${frame.data.size}; AVC NAL types=$types")
                    }
                }
                if (videoFrames >= 3 && audioFrames > 0) connection.close()
            }
            override fun onAudio(data: ByteArray, encoding: AudioEncoding) {
                audioFrames++
                if (audioFrames == 1) report("First audio: encoding=$encoding; bytes=${data.size}")
                if (videoFrames >= 3) connection.close()
            }
            override fun onAudioUnavailable(message: String) { report(message) }
        })
        val timer = Executors.newSingleThreadScheduledExecutor()
        timer.schedule({ connection.close() }, 25, TimeUnit.SECONDS)
        try {
            connection.run()
        } catch (error: Exception) {
            // Protocol exception messages contain no credentials, tickets, keys, or payloads.
            report("Stopped: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            connection.close()
            timer.shutdownNow()
            report("Received video=$videoFrames, audio=$audioFrames")
        }
    }
}
