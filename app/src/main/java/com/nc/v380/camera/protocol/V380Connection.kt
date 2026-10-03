package com.nc.v380.camera.protocol

import com.nc.v380.camera.CameraCredentials
import com.nc.v380.camera.CameraSettings
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArraySet

internal enum class VideoEncoding { ANNEX_B, MJPEG }
internal data class VideoFrame(val data: ByteArray, val keyframe: Boolean, val arrivalUs: Long, val encoding: VideoEncoding = VideoEncoding.ANNEX_B)
internal data class StreamInfo(val width: Int, val height: Int, val deviceVersion: Int)
internal enum class AudioEncoding { ALAW, IMA_ADPCM, IMA_WAV }
internal class CameraLoginException(message: String) : IOException(message)
internal class CameraProtocolException(message: String) : IOException(message)
internal enum class ConnectionStage { DISCOVERY, CONNECT, LOGIN, STREAM_CONNECT, STREAM_LOGIN, MEDIA }

internal interface CameraStreamListener {
    fun onStatus(message: String)
    fun onStream(info: StreamInfo)
    fun onVideo(frame: VideoFrame)
    fun onAudio(data: ByteArray, encoding: AudioEncoding)
    fun onAudioUnavailable(message: String)
    /** Bounded protocol metadata only; never credentials, tickets, keys, or media contents. */
    fun onDiagnostic(message: String) = Unit
}

/** One authenticated LAN session; closing cancels both discovery and blocking socket reads. */
internal class V380Connection(
    private val settings: CameraSettings,
    private val credentials: CameraCredentials,
    private val listener: CameraStreamListener,
    private val port: Int = 8800,
) : Closeable {
    private val resources = CopyOnWriteArraySet<Closeable>()
    private val resourceLock = Any()
    @Volatile private var closed = false
    @Volatile var stage = ConnectionStage.DISCOVERY
        private set

    fun run() {
        settings.validationError()?.let { throw CameraProtocolException(it) }
        credentials.validationError(settings.olderCamera)?.let { throw CameraLoginException(it) }
        try {
            listener.onStatus("Finding your camera…")
            val id = settings.deviceId.toLongOrNull() ?: discoverId()
            stage = ConnectionStage.CONNECT
            listener.onStatus("Connecting to your camera…")
            val authentication = openSocket().use { socket ->
                stage = ConnectionStage.LOGIN
                listener.onStatus("Checking the camera login…")
                val request = V380Wire.login(id, credentials.username, credentials.password, settings.olderCamera)
                try { socket.getOutputStream().write(request) } finally { request.fill(0) }
                val response = reply(socket, 256)
                val fields = V380Wire.buffer(response)
                if (fields.getInt(0) != 1168) throw CameraProtocolException("The camera uses an unsupported login response.")
                when (fields.getInt(4)) {
                    1001 -> Unit
                    1011 -> throw CameraLoginException("The camera rejected the username. Check your camera login.")
                    1012 -> throw CameraLoginException("The camera rejected the password. Check your camera login.")
                    1018 -> throw CameraLoginException("The camera rejected the device ID. Check Camera settings.")
                    else -> throw CameraLoginException("The camera rejected this login. Check your login or try older-camera mode in settings.")
                }
                if (response.size < 17) throw CameraProtocolException("The camera sent an incomplete login response.")
                (response[12].toInt() and 255) to fields.getInt(13)
            }
            val (deviceVersion, ticket) = authentication
            listener.onDiagnostic("Login accepted; protocol version=$deviceVersion")
            val mediaKey = V380Wire.mediaKey(ticket)
            try {
                stage = ConnectionStage.STREAM_CONNECT
                openSocket().use { socket ->
                    stage = ConnectionStage.STREAM_LOGIN
                    listener.onStatus("Starting live video and audio…")
                    socket.getOutputStream().write(V380Wire.streamLogin(id, ticket))
                    val response = reply(socket, 412)
                    val fields = V380Wire.buffer(response)
                    if (fields.getInt(0) != 401 || fields.getInt(4) < 0) {
                        throw CameraProtocolException("The camera rejected the live stream. Close other viewers and reconnect.")
                    }
                    if (response.size < 18) throw CameraProtocolException("The camera sent incomplete stream information.")
                    val communicationVersion = fields.getShort(8).toInt() and 65535
                    listener.onDiagnostic("Stream accepted; communication=$communicationVersion; reply bytes=${response.size}")
                    val width = fields.getInt(10).takeIf { it in 16..4096 } ?: 1280
                    val height = fields.getInt(14).takeIf { it in 16..4096 } ?: 720
                    val startToken = if (settings.olderCamera || deviceVersion <= 30) fields.getInt(4) else 0x3001
                    socket.getOutputStream().write(V380Wire.streamStart(startToken))
                    listener.onStream(StreamInfo(width, height, deviceVersion))
                    stage = ConnectionStage.MEDIA
                    listener.onStatus("Waiting for video…")
                    receive(socket, deviceVersion, communicationVersion, mediaKey)
                }
            } finally { mediaKey.fill(0) }
        } finally { close() }
    }

    private fun openSocket(): Socket = track(Socket()).apply {
        tcpNoDelay = true
        soTimeout = 10_000
        receiveBufferSize = 256 * 1024
        connect(InetSocketAddress(settings.address, this@V380Connection.port), 5_000)
        if (closed) { close(); throw InterruptedIOException() }
    }

    /** Responses vary in size across firmware; collect fragments before sending the next command. */
    private fun reply(socket: Socket, maximum: Int): ByteArray {
        val input = socket.getInputStream()
        val output = java.io.ByteArrayOutputStream()
        output.write(V380Wire.readExact(input, 8))
        val previousTimeout = socket.soTimeout
        try {
            socket.soTimeout = 400
            val buffer = ByteArray(maximum)
            while (output.size() < maximum) {
                val size = try { input.read(buffer, 0, maximum - output.size()) } catch (_: SocketTimeoutException) { break }
                if (size < 0) break
                if (size > 0) output.write(buffer, 0, size)
            }
        } finally { socket.soTimeout = previousTimeout }
        return output.toByteArray()
    }

    private fun discoverId(): Long {
        val address = InetAddress.getByName(settings.address)
        track(DatagramSocket(null)).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(10009))
            val request = "NVDEVSEARCH^100".toByteArray(Charsets.US_ASCII)
            repeat(3) {
                if (closed) throw InterruptedIOException()
                socket.send(DatagramPacket(request, request.size, address, 10008))
                val deadline = System.nanoTime() + 1_000_000_000L
                while (!closed && System.nanoTime() < deadline) {
                    socket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceIn(1, 1000)
                    val packet = DatagramPacket(ByteArray(2048), 2048)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                    if (packet.address != address) continue
                    val parts = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).trimEnd('\u0000').split('^')
                    if (parts.size >= 13 && parts[0] == "NVDEVRESULT") {
                        parts[12].toLongOrNull()?.takeIf { it in 1..0xffffffffL }?.let { return it }
                    }
                }
            }
        }
        throw CameraProtocolException("Couldn't discover the camera ID. Check Wi-Fi, or enter its device ID in Camera settings.")
    }

    private fun receive(socket: Socket, version: Int, communicationVersion: Int, key: ByteArray) {
        val input = socket.getInputStream()
        val video = FrameAssembler()
        val audio = FrameAssembler(256 * 1024)
        var badVideoFrames = 0
        var reportedUnsupportedAudio = false
        val packetsReported = HashMap<Int, Int>()
        var videoReported = 0
        while (!closed) {
            var skipped = 0
            var marker: Int
            do {
                marker = input.read()
                if (marker < 0) throw java.io.EOFException()
                if (++skipped > 4096) throw CameraProtocolException("The camera's media format is not supported yet.")
            } while (marker != 0x7f && marker != 0x6f)
            val header = byteArrayOf(marker.toByte()) + V380Wire.readExact(input, 11)
            val fields = V380Wire.buffer(header)
            val type = header[1].toInt() and 255
            val total = fields.getShort(3).toInt() and 65535
            val index = fields.getShort(5).toInt() and 65535
            val length = fields.getShort(7).toInt() and 65535
            val reported = packetsReported.getOrDefault(type, 0)
            if (reported < 3) {
                listener.onDiagnostic("Packet type=${type.toString(16)}; fragments=$total; index=$index; bytes=$length")
                packetsReported[type] = reported + 1
            }
            // Always consume the payload, including unknown packet types, to preserve TCP framing.
            val payload = V380Wire.readExact(input, length)
            if (marker == 0x6f) continue
            if (total !in 1..512 || index >= total || length == 0) {
                throw CameraProtocolException("The camera sent an invalid media packet.")
            }
            val fragment = Fragment(type, total, index, payload)
            when (type) {
                0x00, 0x01, 0x08, 0x09, 0x28, 0x29 -> {
                    val full = video.accept(fragment) ?: continue
                    if (full.size <= 16) continue
                    var data = full.copyOfRange(16, full.size)
                    if (version > 30) data = V380Wire.decrypt(data, key, true, communicationVersion)
                    val jpegSize = JpegFrame.dimensions(data)
                    if (videoReported++ < 3) {
                        listener.onDiagnostic("Video assembled: bytes=${full.size}; JPEG=$jpegSize; Annex-B start=${AnnexB.startPosition(data)}")
                    }
                    if (jpegSize != null) {
                        val end = JpegFrame.endPosition(data)
                        if (end != null && data.size - end <= 1024) {
                            badVideoFrames = 0
                            listener.onVideo(VideoFrame(data.copyOf(end), true, System.nanoTime() / 1000, VideoEncoding.MJPEG))
                            continue
                        }
                    }
                    val start = AnnexB.startPosition(data)
                    if (start == null) {
                        if (++badVideoFrames > 60) throw CameraProtocolException("Couldn't decode the camera video. Try older-camera mode in Camera settings.")
                        continue
                    }
                    if (start > 0) data = data.copyOfRange(start, data.size)
                    badVideoFrames = 0
                    listener.onVideo(VideoFrame(data, type == 0x00 || type == 0x08 || type == 0x28, System.nanoTime() / 1000))
                }
                0x1a, 0x16 -> {
                    val full = audio.accept(fragment) ?: continue
                    if (full.size <= 16) continue
                    // Decrypt before removing the ADPCM seed: the seed is inside the AES block.
                    var data = full.copyOfRange(16, full.size)
                    if (version > 30) data = V380Wire.decrypt(data, key, false, communicationVersion)
                    when {
                        type == 0x1a -> listener.onAudio(data, AudioEncoding.ALAW)
                        ImaWav.validBlock(data) -> listener.onAudio(data, AudioEncoding.IMA_WAV)
                        version <= 30 && data.size > 4 -> listener.onAudio(data.copyOfRange(4, data.size), AudioEncoding.IMA_ADPCM)
                        else -> {
                            if (!reportedUnsupportedAudio) listener.onAudioUnavailable("The camera's audio block format isn't supported yet.")
                            reportedUnsupportedAudio = true
                        }
                    }
                }
            }
        }
    }

    fun failureMessage(error: Exception): String = when (error) {
        is CameraLoginException, is CameraProtocolException -> error.message ?: "The camera rejected this connection."
        is SecurityException -> "Allow local network access in the phone's app settings."
        else -> when (stage) {
            ConnectionStage.DISCOVERY -> "Couldn't discover the camera. Close other camera viewers, or enter its device ID in Settings."
            ConnectionStage.CONNECT, ConnectionStage.STREAM_CONNECT -> "Couldn't open the camera connection. Check the IP address and use the same Wi-Fi."
            ConnectionStage.LOGIN -> "The camera didn't complete the login. Check Camera login, or try older-camera compatibility in Settings."
            ConnectionStage.STREAM_LOGIN -> "The camera logged in but didn't start the stream. Close other viewers and reconnect."
            ConnectionStage.MEDIA -> "The camera stopped sending video and audio. Reconnect to continue watching."
        }
    }

    private fun <T : Closeable> track(resource: T): T = synchronized(resourceLock) {
        if (closed) { resource.close(); throw InterruptedIOException() }
        resources.add(resource)
        resource
    }

    override fun close() {
        synchronized(resourceLock) {
            closed = true
            resources.forEach { try { it.close() } catch (_: IOException) { } }
            resources.clear()
        }
    }
}
