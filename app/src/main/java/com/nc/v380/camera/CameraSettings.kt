package com.nc.v380.camera

data class CameraSettings(
    val name: String = "Mini camera",
    val address: String = "192.168.0.111",
    val deviceId: String = "",
    val olderCamera: Boolean = false,
) {
    fun validationError(): String? {
        val octets = address.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || it.any { c -> !c.isDigit() } || it.toIntOrNull() !in 0..255 }) {
            return "Enter a valid camera IP address."
        }
        if (name.isBlank()) return "Enter a camera name."
        if (deviceId.isNotEmpty() && (deviceId.toLongOrNull() ?: -1) !in 1..0xffffffffL) {
            return "Enter the numeric device ID shown in V380, or leave it blank for discovery."
        }
        return null
    }
}

class CameraCredentials(val username: String, val password: String) {
    fun validationError(olderCamera: Boolean): String? {
        if (username.isBlank() || username.length > 32 || username.any { it.code !in 32..126 }) {
            return "The camera username must contain 1–32 English letters, numbers, or symbols."
        }
        if (password.length > (if (olderCamera) 15 else 48) || password.any { it.code !in 32..126 }) {
            return if (olderCamera) "Older-camera mode supports passwords up to 15 characters."
            else "The camera password must contain at most 48 English letters, numbers, or symbols."
        }
        return null
    }

    override fun toString(): String = "CameraCredentials(redacted)"
}
