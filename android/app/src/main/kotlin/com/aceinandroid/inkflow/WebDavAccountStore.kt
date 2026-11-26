package com.aceinandroid.inkflow

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class WebDavAccount(val endpoint: String, val username: String, val password: String)

internal class WebDavAccountStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("native_webdav_account", Context.MODE_PRIVATE)

    fun load(): WebDavAccount? {
        val endpoint = preferences.getString("endpoint", null) ?: return null
        val username = preferences.getString("username", null) ?: return null
        val iv = preferences.getString("iv", null) ?: return null
        val encrypted = preferences.getString("password", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val password = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
            WebDavAccount(endpoint, username, password)
        } catch (failure: Exception) {
            throw IOException("Cannot unlock WebDAV credentials", failure)
        }
    }

    fun save(endpoint: String, username: String, password: String) {
        require(endpoint.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            "WebDAV account is incomplete"
        }
        WebDavTransport(endpoint, username, password)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        if (!preferences.edit().putString("endpoint", endpoint.trim())
                .putString("username", username)
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()) throw IOException("Cannot save WebDAV account")
    }

    fun clear() {
        if (!preferences.edit().clear().commit()) throw IOException("Cannot remove WebDAV account")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "inkflow_webdav_password_v1"
    }
}
