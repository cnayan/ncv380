package com.nc.v380.camera.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Independently implemented wire formats. Reference provenance is in docs/IMPLEMENTATION.md. */
internal object V380Wire {
    fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun password(password: String, older: Boolean, random: SecureRandom = SecureRandom()): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val randomKey = ByteArray(16) { alphabet[random.nextInt(alphabet.length)].code.toByte() }
        val raw = password.toByteArray(Charsets.US_ASCII)
        val padded = raw.copyOf(if (older) ((raw.size / 16) + 1) * 16 else 48)
        fun encrypt(key: ByteArray, value: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(value)
        }
        val result = randomKey + encrypt(randomKey, encrypt("macrovideo+*#!^@".toByteArray(Charsets.US_ASCII), padded))
        raw.fill(0)
        padded.fill(0)
        return result
    }

    fun login(deviceId: Long, username: String, password: String, older: Boolean): ByteArray {
        val request = ByteArray(if (older) 256 else 520)
        buffer(request).apply {
            putInt(0, 1167)
            putInt(4, if (older) 1022 else 120)
            put(8, if (older) 2.toByte() else 31.toByte())
            putInt(9, 1)
            putInt(13, deviceId.toInt())
        }
        username.toByteArray(Charsets.US_ASCII).copyInto(request, 49)
        val encoded = password(password, older)
        encoded.copyInto(request, 81)
        encoded.fill(0)
        return request
    }

    fun streamLogin(deviceId: Long, ticket: Int): ByteArray = ByteArray(256).also {
        buffer(it).apply {
            putInt(0, 301)
            putInt(4, deviceId.toInt())
            putShort(12, 20)
            putInt(14, ticket)
            putInt(22, 4097) // Request video AND camera audio.
            putInt(26, 1)
        }
    }

    fun streamStart(token: Int): ByteArray = ByteArray(256).also {
        buffer(it).putInt(0, 303).putInt(4, token)
    }

    fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("Camera disconnected")
            if (read == 0) continue
            offset += read
        }
        return result
    }

    fun mediaKey(ticket: Int): ByteArray = ByteArray(16).also {
        buffer(it).putInt(0, ticket).putLong(4, 0x618123462c14795cL).putInt(12, 0x82800df0.toInt())
    }

    fun decrypt(payload: ByteArray, key: ByteArray, video: Boolean, communicationVersion: Int): ByteArray {
        val output = payload.copyOf()
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        if (!video || communicationVersion == 21) {
            val length = output.size / 16 * 16
            if (length > 0) cipher.doFinal(output, 0, length).copyInto(output)
        } else {
            var offset = 0
            while (offset + 64 <= output.size) {
                cipher.doFinal(output, offset, 64).copyInto(output, offset)
                offset += 80
            }
        }
        return output
    }
}

internal data class Fragment(val type: Int, val total: Int, val index: Int, val payload: ByteArray)

/** Reject missing/reordered fragments and cap allocation on an untrusted camera stream. */
internal class FrameAssembler(private val maxBytes: Int = 2 * 1024 * 1024) {
    private val bytes = ByteArrayOutputStream()
    private var total = 0
    private var next = 0
    private var type = -1

    fun accept(fragment: Fragment): ByteArray? {
        if (fragment.index == 0) {
            reset()
            total = fragment.total
            type = fragment.type
        }
        if (fragment.total !in 1..512 || fragment.index != next || total != fragment.total || type != fragment.type ||
            fragment.index >= fragment.total || bytes.size() + fragment.payload.size > maxBytes) {
            reset()
            return null
        }
        bytes.write(fragment.payload)
        next++
        if (next != total) return null
        return bytes.toByteArray().also { reset() }
    }

    private fun reset() { bytes.reset(); total = 0; next = 0; type = -1 }
}

internal object AnnexB {
    fun startPosition(data: ByteArray): Int? = (0 until minOf(16, data.size - 2)).firstOrNull {
        data[it] == 0.toByte() && data[it + 1] == 0.toByte() && data[it + 2] == 1.toByte()
    }?.let { if (it > 0 && data[it - 1] == 0.toByte()) it - 1 else it }

    fun units(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < data.size) {
            val length = when {
                i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (length > 0) { starts.add(i to length); i += length } else i++
        }
        return starts.mapIndexedNotNull { index, start ->
            val end = starts.getOrNull(index + 1)?.first ?: data.size
            if (start.first + start.second >= end) null else data.copyOfRange(start.first + start.second, end)
        }
    }

    fun startCoded(unit: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) + unit
}
