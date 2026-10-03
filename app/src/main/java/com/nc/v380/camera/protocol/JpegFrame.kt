package com.nc.v380.camera.protocol

/** Validate JPEG segment boundaries before accepting a decrypted frame or allocating pixels. */
internal object JpegFrame {
    fun dimensions(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 4 || u8(data, 0) != 255 || u8(data, 1) != 216) return null
        var offset = 2
        var dimensions: Pair<Int, Int>? = null
        while (offset + 3 < data.size) {
            if (u8(data, offset++) != 255) return null
            while (offset < data.size && u8(data, offset) == 255) offset++
            if (offset + 2 >= data.size) return null
            val marker = u8(data, offset++)
            val length = (u8(data, offset) shl 8) or u8(data, offset + 1)
            if (length < 2 || offset + length > data.size) return null
            if (marker in listOf(0xc0, 0xc1, 0xc2)) {
                if (length < 8) return null
                val height = (u8(data, offset + 3) shl 8) or u8(data, offset + 4)
                val width = (u8(data, offset + 5) shl 8) or u8(data, offset + 6)
                if (width !in 16..4096 || height !in 16..4096) return null
                dimensions = width to height
            }
            if (marker == 0xda) return dimensions
            offset += length
        }
        return null
    }

    private fun u8(data: ByteArray, offset: Int) = data[offset].toInt() and 255

    fun endPosition(data: ByteArray): Int? = (data.size - 2 downTo 2).firstOrNull {
        u8(data, it) == 255 && u8(data, it + 1) == 217
    }?.plus(2)

}
