package com.nc.v380.camera.player

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.nc.v380.camera.protocol.ALaw
import com.nc.v380.camera.protocol.AnnexB
import com.nc.v380.camera.protocol.AudioEncoding
import com.nc.v380.camera.protocol.ImaAdpcm
import com.nc.v380.camera.protocol.ImaWav
import com.nc.v380.camera.protocol.JpegFrame
import com.nc.v380.camera.protocol.StreamInfo
import com.nc.v380.camera.protocol.VideoFrame
import com.nc.v380.camera.protocol.VideoEncoding
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class LivePlayer(
    context: Context,
    private val surface: Surface,
    private val onVideoRendered: () -> Unit,
    private val onAudioStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(true)
    private val waitForKey = AtomicBoolean(true)
    private val videoQueue = ArrayBlockingQueue<VideoFrame>(6)
    private val audioQueue = ArrayBlockingQueue<ShortArray>(8)
    private val adpcm = ImaAdpcm()
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var muted = false
    @Volatile private var info = StreamInfo(1280, 720, 0)
    private val videoThread = Thread(::videoLoop, "camera-video").apply { start() }
    private val audioThread = Thread(::audioLoop, "camera-audio").apply { start() }

    fun setInfo(value: StreamInfo) { info = value }
    fun setMuted(value: Boolean) { muted = value }

    fun video(frame: VideoFrame) {
        if (!running.get()) return
        if (!videoQueue.offer(frame)) {
            videoQueue.clear()
            waitForKey.set(true)
            if (frame.keyframe) videoQueue.offer(frame)
        }
    }

    fun audio(data: ByteArray, encoding: AudioEncoding) {
        if (!running.get()) return
        // Decode every ADPCM packet even while muted so its predictor remains correct.
        val samples = when (encoding) {
            AudioEncoding.ALAW -> ALaw.decode(data)
            AudioEncoding.IMA_ADPCM -> adpcm.decode(data)
            AudioEncoding.IMA_WAV -> ImaWav.decode(data)
        }
        if (samples.isEmpty() || muted) return
        if (!audioQueue.offer(samples)) { audioQueue.clear(); audioQueue.offer(samples) }
    }

    private fun videoLoop() {
        var codec: MediaCodec? = null
        var mime: String? = null
        val parameters = HashMap<Int, ByteArray>()
        val bufferInfo = MediaCodec.BufferInfo()
        var firstPts = 0L
        var lastPts = -1L
        try {
            while (running.get()) {
                val frame = videoQueue.poll(20, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    if (frame.encoding == VideoEncoding.MJPEG) {
                        renderJpeg(frame.data)
                        continue
                    }
                    val units = AnnexB.units(frame.data)
                    if (codec == null) {
                        for (unit in units) {
                            val avc = unit[0].toInt() and 31
                            val hevc = (unit[0].toInt() ushr 1) and 63
                            if (hevc in 32..34 && unit.size > 1) { mime = MediaFormat.MIMETYPE_VIDEO_HEVC; parameters[hevc] = AnnexB.startCoded(unit) }
                            else if ((avc == 7 || avc == 8) && mime != MediaFormat.MIMETYPE_VIDEO_HEVC) {
                                mime = MediaFormat.MIMETYPE_VIDEO_AVC; parameters[avc] = AnnexB.startCoded(unit)
                            }
                        }
                        val avcReady = mime == MediaFormat.MIMETYPE_VIDEO_AVC && parameters.keys.containsAll(listOf(7, 8))
                        val hevcReady = mime == MediaFormat.MIMETYPE_VIDEO_HEVC && parameters.keys.containsAll(listOf(32, 33, 34))
                        if (!(avcReady || hevcReady) || !frame.keyframe) continue
                        val format = MediaFormat.createVideoFormat(mime!!, info.width, info.height).apply {
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                            if (avcReady) {
                                setByteBuffer("csd-0", ByteBuffer.wrap(parameters.getValue(7)))
                                setByteBuffer("csd-1", ByteBuffer.wrap(parameters.getValue(8)))
                            } else {
                                setByteBuffer("csd-0", ByteBuffer.wrap(parameters.getValue(32) + parameters.getValue(33) + parameters.getValue(34)))
                            }
                        }
                        codec = MediaCodec.createDecoderByType(mime!!).apply {
                            configure(format, surface, null, 0)
                            setOnFrameRenderedListener({ _, _, _ -> if (running.get()) onVideoRendered() }, Handler(Looper.getMainLooper()))
                            start()
                        }
                        firstPts = frame.arrivalUs
                    }
                    if (waitForKey.get() && !frame.keyframe) continue
                    if (frame.keyframe) waitForKey.set(false)
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Missing video input buffer")
                        if (input.capacity() < frame.data.size) error("Video frame is too large")
                        val pts = maxOf(frame.arrivalUs - firstPts, lastPts + 1)
                        input.clear()
                        input.put(frame.data)
                        codec.queueInputBuffer(inputIndex, 0, frame.data.size, pts, 0)
                        lastPts = pts
                    } else waitForKey.set(true)
                }
                val decoder = codec ?: continue
                while (running.get()) {
                    val output = decoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (output >= 0) decoder.releaseOutputBuffer(output, true)
                    else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    else break
                }
            }
        } catch (_: InterruptedException) {
            // A normal screen/lifecycle close interrupts the worker.
        } catch (_: Exception) {
            if (running.get()) onError("This phone couldn't decode the camera video. Check Camera settings or try older-camera mode.")
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
        }
    }

    private fun renderJpeg(data: ByteArray) {
        val size = JpegFrame.dimensions(data) ?: error("Invalid JPEG header")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        check(bounds.outWidth == size.first && bounds.outHeight == size.second)
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Invalid JPEG image")
        try {
            if (!running.get() || !surface.isValid) return
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(Color.BLACK)
                val scale = minOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (canvas.width - width) / 2
                val top = (canvas.height - height) / 2
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
            } finally { surface.unlockCanvasAndPost(canvas) }
            if (running.get()) onVideoRendered()
        } finally { bitmap.recycle() }
    }

    private fun audioLoop() {
        var track: AudioTrack? = null
        var writtenSamples = 0L
        var focusHeld = false
        val focusAllowed = AtomicBoolean(true)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            focusAllowed.set(change == AudioManager.AUDIOFOCUS_GAIN)
            if (running.get() && change < 0) onAudioStatus("Audio paused by another app")
        }
        val focusRequest = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper())).build() else null
        var wasMuted = false
        try {
            while (running.get()) {
                val samples = audioQueue.poll(50, TimeUnit.MILLISECONDS)
                val outputMuted = muted || !focusAllowed.get()
                if (outputMuted != wasMuted) {
                    track?.pause()
                    track?.flush()
                    writtenSamples = 0
                    audioQueue.clear()
                    if (!outputMuted) track?.play()
                    wasMuted = outputMuted
                }
                if (samples == null || outputMuted) continue
                if (track == null) {
                    val result = if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.requestAudioFocus(focusRequest)
                    else @Suppress("DEPRECATION") audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (!focusHeld) { focusAllowed.set(false); onAudioStatus("Audio is unavailable while another app holds the speaker"); continue }
                    val minimum = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0)
                    track = AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 4096)).build()
                    check(track.state == AudioTrack.STATE_INITIALIZED)
                    track.play()
                    onAudioStatus("Audio on")
                }
                val played = track.playbackHeadPosition.toLong() and 0xffffffffL
                if (writtenSamples - played > 2000) {
                    track.pause(); track.flush(); track.play()
                    writtenSamples = 0
                    audioQueue.clear()
                }
                var offset = 0
                while (offset < samples.size && running.get() && !muted && focusAllowed.get()) {
                    val count = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    if (count < 0) error("Audio output failed")
                    if (count == 0) break // Keep live audio bounded rather than blocking the network reader.
                    offset += count
                    writtenSamples += count
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            if (running.get()) onAudioStatus("Couldn't play camera audio on this phone")
        } finally {
            try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
            if (focusHeld) {
                if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest)
                else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
            }
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        videoQueue.clear(); audioQueue.clear()
        videoThread.interrupt(); audioThread.interrupt()
    }
}
