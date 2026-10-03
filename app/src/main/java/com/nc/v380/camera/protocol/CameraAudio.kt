package com.nc.v380.camera.protocol

internal object ALaw {
    fun decode(bytes: ByteArray): ShortArray = ShortArray(bytes.size) { index ->
        val value = (bytes[index].toInt() and 0xff) xor 0x55
        val exponent = (value ushr 4) and 7
        var magnitude = ((value and 15) shl 4) + 8
        if (exponent > 0) magnitude = (magnitude + 256) shl (exponent - 1)
        (if (value and 128 != 0) magnitude else -magnitude).toShort()
    }
}

/** IMA Westwood ADPCM: high nibble first, predictor carried between live packets. */
internal class ImaAdpcm(private var predictor: Int = 0, private var index: Int = 0, private val wav: Boolean = false) {

    fun decode(bytes: ByteArray): ShortArray {
        val result = ShortArray(bytes.size * 2)
        bytes.forEachIndexed { offset, byte ->
            val low = byte.toInt() and 15
            val high = (byte.toInt() ushr 4) and 15
            result[offset * 2] = sample(if (wav) low else high)
            result[offset * 2 + 1] = sample(if (wav) high else low)
        }
        return result
    }

    private fun sample(nibble: Int): Short {
        val step = STEPS[index]
        val delta = if (wav) (step shr 3) + (if (nibble and 1 != 0) step shr 2 else 0) +
            (if (nibble and 2 != 0) step shr 1 else 0) + (if (nibble and 4 != 0) step else 0)
            else ((2 * (nibble and 7) + 1) * step) shr 3
        predictor = (predictor + if (nibble and 8 != 0) -delta else delta).coerceIn(-32768, 32767)
        index = (index + INDEX[nibble and 7]).coerceIn(0, 88)
        return predictor.toShort()
    }

    private companion object {
        val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
        val STEPS = intArrayOf(7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,
            88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,
            796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,
            4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,
            20350,22385,24623,27086,29794,32767)
    }
}

/** Mono IMA WAV blocks carry their own predictor and step index; dropped packets do not corrupt later audio. */
internal object ImaWav {
    fun validBlock(data: ByteArray) = data.size > 4 && (data[2].toInt() and 255) <= 88 && data[3] == 0.toByte()

    fun decode(data: ByteArray): ShortArray {
        if (!validBlock(data)) return shortArrayOf()
        val predictor = V380Wire.buffer(data).getShort(0)
        val index = data[2].toInt() and 255
        return shortArrayOf(predictor) + ImaAdpcm(predictor.toInt(), index, wav = true).decode(data.copyOfRange(4, data.size))
    }
}
