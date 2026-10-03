package com.nc.v380

import com.nc.v380.camera.CameraCredentials
import com.nc.v380.camera.CameraSettings
import com.nc.v380.camera.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

class CameraProtocolTest {
    @Test fun readsFragmentedTcpDataAndRejectsTruncation() {
        val expected = ByteArray(256) { it.toByte() }
        val fragmented = object : InputStream() {
            val input = ByteArrayInputStream(expected)
            override fun read(): Int = input.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, minOf(length, 3))
        }
        assertArrayEquals(expected, V380Wire.readExact(fragmented, 256))
        assertThrows(EOFException::class.java) { V380Wire.readExact(ByteArrayInputStream(byteArrayOf(1)), 2) }
    }

    @Test fun incompleteFramesAreDiscardedBeforeTheNextKeyframe() {
        val assembler = FrameAssembler()
        assertNull(assembler.accept(Fragment(0, 3, 0, byteArrayOf(1))))
        assertNull(assembler.accept(Fragment(0, 3, 2, byteArrayOf(3))))
        assertNull(assembler.accept(Fragment(0, 3, 1, byteArrayOf(2))))
        assertNull(assembler.accept(Fragment(0, 2, 0, byteArrayOf(4))))
        assertArrayEquals(byteArrayOf(4, 5), assembler.accept(Fragment(0, 2, 1, byteArrayOf(5))))
    }

    @Test fun cameraCannotAllocateUnboundedFrameMemory() {
        val assembler = FrameAssembler(4)
        assertNull(assembler.accept(Fragment(0, 2, 0, byteArrayOf(1, 2, 3))))
        assertNull(assembler.accept(Fragment(0, 2, 1, byteArrayOf(4, 5))))
        assertNull(assembler.accept(Fragment(0, 65535, 0, byteArrayOf(1))))
        assertArrayEquals(byteArrayOf(7), assembler.accept(Fragment(0, 1, 0, byteArrayOf(7))))
    }

    @Test fun alawMatchesStandardSilenceAndFullScaleVectors() {
        assertArrayEquals(shortArrayOf(8, -8, 32256, -32256), ALaw.decode(byteArrayOf(0xd5.toByte(), 0x55, 0xaa.toByte(), 0x2a)))
    }

    @Test fun adpcmPreservesPredictorAcrossPacketsAndUsesHighNibbleFirst() {
        val decoder = ImaAdpcm()
        assertArrayEquals(shortArrayOf(13, 15), decoder.decode(byteArrayOf(0x70)))
        assertArrayEquals(shortArrayOf(14, 15), decoder.decode(byteArrayOf(0x80.toByte())))
    }

    @Test fun imaWavUsesEachBlocksSeedAndMatchesFfmpegDecodedSamples() {
        // Golden PCM from FFmpeg's adpcm_ima_wav decoder, not the Westwood decoder.
        val block = ByteArray(256).also {
            V380Wire.buffer(it).putShort(0, 1000)
            it[2] = 10; it[4] = 0x17; it[5] = 0x80.toByte(); it[6] = 0x70
        }
        val expected = shortArrayOf(1000, 1034, 1049, 1053, 1049, 1052, 1104, 1111, 1117, 1123)
        val decoded = ImaWav.decode(block)
        assertEquals(505, decoded.size)
        assertArrayEquals(expected, decoded.copyOf(10))
        assertArrayEquals(decoded, ImaWav.decode(block)) // A dropped block cannot change the next block's state.
        block[2] = 89
        assertEquals(0, ImaWav.decode(block).size)
    }

    @Test fun jpegIsRecognizedBeforeItsJfifBytesCanBeMistakenForAnnexB() {
        val jpeg = cameraJpegFixture()
        assertNotNull(AnnexB.startPosition(jpeg)) // JFIF contains 00 00 01, but is not H.264.
        assertEquals(64 to 64, JpegFrame.dimensions(jpeg))
        assertEquals(jpeg.size, JpegFrame.endPosition(jpeg + ByteArray(512)))
        assertNull(JpegFrame.dimensions(jpeg.copyOf(24)))
        val huge = jpeg.copyOf().also { it[25] = 0x7f; it[26] = 0xff.toByte() }
        assertNull(JpegFrame.dimensions(huge))
    }

    @Test fun aesDecryptionMatchesNistKnownAnswerAndPreservesClearTail() {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val ciphertext = hex("69c4e0d86a7b0430d8cdb78070b4c55a") + byteArrayOf(9)
        assertArrayEquals(hex("00112233445566778899aabbccddeeff") + byteArrayOf(9), V380Wire.decrypt(ciphertext, key, false, 0))
    }

    @Test fun extractsMixedAnnexBStartCodesWithoutEmptyUnits() {
        val units = AnnexB.units(byteArrayOf(0, 0, 0, 1, 0x67, 2, 0, 0, 1, 0x68, 3, 0, 0, 1))
        assertEquals(2, units.size)
        assertArrayEquals(byteArrayOf(0x67, 2), units[0])
        assertArrayEquals(byteArrayOf(0x68, 3), units[1])
    }

    @Test fun streamRequestEnablesCameraSoundAndCredentialErrorsAreRedacted() {
        assertEquals(4097, V380Wire.buffer(V380Wire.streamLogin(123, 456)).getInt(22))
        assertFalse(CameraCredentials("admin", "private-password").toString().contains("private-password"))
        assertNotNull(CameraSettings(address = "192.168.0.999").validationError())
        assertNull(CameraSettings().validationError())
    }
}

/** Valid segment structure for wire tests; it has no camera imagery and is not a display fixture. */
internal fun cameraJpegFixture(): ByteArray = byteArrayOf(
    0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 16,
    0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0,
    0xff.toByte(), 0xc0.toByte(), 0, 17, 8, 0, 64, 0, 64, 3,
    1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1,
    0xff.toByte(), 0xda.toByte(), 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0,
) + ByteArray(160) { 0x24 } + byteArrayOf(0xff.toByte(), 0xd9.toByte())
