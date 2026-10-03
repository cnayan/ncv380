package com.nc.v380.camera

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the encrypted password is persisted; the key stays in Android Keystore. */
class CameraStore internal constructor(
    context: Context,
    preferencesName: String = "camera",
    private val keyAlias: String = "ncv380.camera.password",
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun settings() = CameraSettings(
        name = preferences.getString("name", "Mini camera") ?: "Mini camera",
        address = preferences.getString("address", "192.168.0.111") ?: "192.168.0.111",
        deviceId = preferences.getString("deviceId", "") ?: "",
        olderCamera = preferences.getBoolean("olderCamera", false),
    )

    fun saveSettings(settings: CameraSettings) {
        val old = settings()
        val editor = preferences.edit()
            .putString("name", settings.name)
            .putString("address", settings.address)
            .putString("deviceId", settings.deviceId)
            .putBoolean("olderCamera", settings.olderCamera)
        if (old.address != settings.address || old.deviceId != settings.deviceId || old.olderCamera != settings.olderCamera) {
            editor.remove("password").remove("username")
        }
        editor.apply()
    }

    fun credentials(): CameraCredentials? {
        val sealed = preferences.getString("password", null) ?: return null
        return try {
            val parts = sealed.split(':')
            if (parts.size != 2) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(settings().address.toByteArray(Charsets.UTF_8))
            CameraCredentials(
                preferences.getString("username", "admin") ?: "admin",
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8),
            )
        } catch (_: Exception) {
            forgetCredentials()
            null
        }
    }

    fun saveCredentials(credentials: CameraCredentials, remember: Boolean) {
        if (!remember) { forgetCredentials(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(settings().address.toByteArray(Charsets.UTF_8))
        val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(credentials.password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        preferences.edit().putString("username", credentials.username).putString("password", sealed).apply()
    }

    fun forgetCredentials() { preferences.edit().remove("username").remove("password").apply() }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

}
