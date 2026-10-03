package com.nc.v380

import com.nc.v380.camera.CameraCredentials
import com.nc.v380.camera.CameraSettings
import com.nc.v380.camera.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class CameraConnectionTest {
    private fun fields(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun DataInputStream.bytes(count: Int) = ByteArray(count).also { readFully(it) }

    @Test fun cameraSessionHandlesSplitRepliesAndDeliversBothMediaTracks() {
        ServerSocket(0).use { server ->
            val peerFailure = AtomicReference<Throwable?>()
            val peerDone = CountDownLatch(1)
            val peer = Thread {
                try {
                    server.accept().use { auth ->
                        auth.soTimeout = 3000
                        val request = DataInputStream(auth.getInputStream()).bytes(520)
                        assertEquals(1167, fields(request).getInt(0))
                        val reply = ByteArray(256).also {
                            fields(it).putInt(0, 1168).putInt(4, 1001).putInt(13, 77)
                            it[12] = 2
                        }
                        auth.getOutputStream().write(reply, 0, 9)
                        Thread.sleep(10)
                        auth.getOutputStream().write(reply, 9, 247)
                    }
                    server.accept().use { stream ->
                        stream.soTimeout = 3000
                        val input = DataInputStream(stream.getInputStream())
                        val request = input.bytes(256)
                        assertEquals(301, fields(request).getInt(0))
                        assertEquals(4097, fields(request).getInt(22))
                        val reply = ByteArray(412).also { fields(it).putInt(0, 401).putInt(4, 1001).putShort(8, 20).putInt(10, 640).putInt(14, 360) }
                        stream.getOutputStream().write(reply, 0, 16)
                        Thread.sleep(10)
                        stream.getOutputStream().write(reply, 16, reply.size - 16)
                        assertEquals(303, fields(input.bytes(256)).getInt(0))
                        fun send(type: Int, data: ByteArray) {
                            val header = ByteArray(12).also {
                                it[0] = 0x7f; it[1] = type.toByte()
                                fields(it).putShort(3, 1).putShort(5, 0).putShort(7, data.size.toShort())
                            }
                            stream.getOutputStream().write(header + data)
                        }
                        send(0, ByteArray(16) + byteArrayOf(0, 0, 0, 1, 0x67, 1))
                        send(0x1a, ByteArray(16) + byteArrayOf(0xd5.toByte(), 0x55))
                        peerDone.await(2, TimeUnit.SECONDS)
                    }
                } catch (error: Throwable) { peerFailure.set(error) }
            }.apply { start() }
            val gotVideo = AtomicReference<VideoFrame?>()
            val gotAudio = AtomicReference<ByteArray?>()
            lateinit var connection: V380Connection
            connection = V380Connection(CameraSettings(address = "127.0.0.1", deviceId = "123"), CameraCredentials("owner", "test-only"),
                object : CameraStreamListener {
                    override fun onStatus(message: String) = Unit
                    override fun onStream(info: StreamInfo) { assertEquals(640, info.width) }
                    override fun onVideo(frame: VideoFrame) { gotVideo.set(frame) }
                    override fun onAudio(data: ByteArray, encoding: AudioEncoding) {
                        gotAudio.set(data); peerDone.countDown(); connection.close()
                    }
                    override fun onAudioUnavailable(message: String) { fail(message) }
                }, server.localPort)
            connection.run()
            peer.join(3000)
            peerFailure.get()?.let { throw AssertionError("Test camera failed", it) }
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1), gotVideo.get()?.data)
            assertArrayEquals(byteArrayOf(0xd5.toByte(), 0x55), gotAudio.get())
        }
    }

    @Test fun modernCameraDeliversEncryptedJpegAndSeededImaAudio() {
        val jpeg = cameraJpegFixture()
        val paddedJpeg = jpeg + ByteArray(512 - jpeg.size)
        val audioBlock = ByteArray(256).also {
            fields(it).putShort(0, 1000); it[2] = 10; it[4] = 0x17
        }
        val key = V380Wire.mediaKey(77)
        fun encrypt(clear: ByteArray, sparse: Boolean): ByteArray {
            val result = clear.copyOf()
            val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            }
            if (sparse) {
                var offset = 0
                while (offset + 64 <= result.size) {
                    cipher.doFinal(clear, offset, 64).copyInto(result, offset)
                    offset += 80
                }
            } else cipher.doFinal(clear, 0, clear.size / 16 * 16).copyInto(result)
            return result
        }
        ServerSocket(0).use { server ->
            val peerFailure = AtomicReference<Throwable?>()
            val peerDone = CountDownLatch(1)
            val peer = Thread {
                try {
                    server.accept().use { auth ->
                        auth.soTimeout = 3000
                        DataInputStream(auth.getInputStream()).bytes(520)
                        auth.getOutputStream().write(ByteArray(256).also {
                            fields(it).putInt(0, 1168).putInt(4, 1001).putInt(13, 77)
                            it[12] = 31
                        })
                    }
                    server.accept().use { stream ->
                        stream.soTimeout = 3000
                        val input = DataInputStream(stream.getInputStream())
                        input.bytes(256)
                        // This firmware sends a short 32-byte stream reply, not a fixed 412-byte reply.
                        stream.getOutputStream().write(ByteArray(32).also {
                            fields(it).putInt(0, 401).putInt(4, 1001).putShort(8, 20).putInt(10, 64).putInt(14, 64)
                        })
                        assertEquals(0x3001, fields(input.bytes(256)).getInt(4))
                        fun send(type: Int, payload: ByteArray, total: Int = 1, index: Int = 0) {
                            val header = ByteArray(12).also {
                                it[0] = 0x7f; it[1] = type.toByte()
                                fields(it).putShort(3, total.toShort()).putShort(5, index.toShort()).putShort(7, payload.size.toShort())
                            }
                            stream.getOutputStream().write(header + payload)
                        }
                        val video = ByteArray(16) + encrypt(paddedJpeg, sparse = true)
                        send(9, video.copyOfRange(0, 300), 2, 0)
                        send(9, video.copyOfRange(300, video.size), 2, 1)
                        send(0x16, ByteArray(16) + encrypt(audioBlock, sparse = false))
                        peerDone.await(2, TimeUnit.SECONDS)
                    }
                } catch (error: Throwable) { peerFailure.set(error) }
            }.apply { start() }
            val gotVideo = AtomicReference<VideoFrame?>()
            val gotAudio = AtomicReference<ByteArray?>()
            lateinit var connection: V380Connection
            connection = V380Connection(CameraSettings(address = "127.0.0.1", deviceId = "123"), CameraCredentials("owner", "test-only"),
                object : CameraStreamListener {
                    override fun onStatus(message: String) = Unit
                    override fun onStream(info: StreamInfo) { assertEquals(31, info.deviceVersion) }
                    override fun onVideo(frame: VideoFrame) { gotVideo.set(frame) }
                    override fun onAudio(data: ByteArray, encoding: AudioEncoding) {
                        assertEquals(AudioEncoding.IMA_WAV, encoding)
                        gotAudio.set(data); peerDone.countDown(); connection.close()
                    }
                    override fun onAudioUnavailable(message: String) { fail(message) }
                }, server.localPort)
            connection.run()
            peer.join(3000)
            peerFailure.get()?.let { throw AssertionError("Test camera failed", it) }
            assertEquals(VideoEncoding.MJPEG, gotVideo.get()?.encoding)
            assertTrue(gotVideo.get()!!.keyframe)
            assertArrayEquals(jpeg, gotVideo.get()?.data)
            assertArrayEquals(audioBlock, gotAudio.get())
        }
    }
}
