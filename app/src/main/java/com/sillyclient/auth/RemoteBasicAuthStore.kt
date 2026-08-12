package com.sillyclient.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RemoteBasicAuthStore(context: Context) {
    data class Credentials(val username: String, val password: String)

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(instanceId: String): Credentials? {
        val encoded = preferences.getString(preferenceKey(instanceId), null) ?: return null
        return try {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            val plaintext = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
            val payload = JSONObject(plaintext)
            Credentials(payload.getString("username"), payload.getString("password"))
        } catch (error: Exception) {
            preferences.edit().remove(preferenceKey(instanceId)).apply()
            throw IllegalStateException("远程连接凭据无法解密，请重新保存账号和密码", error)
        }
    }

    fun save(instanceId: String, username: String, password: String?): Credentials {
        val normalizedUsername = username.trim()
        require(normalizedUsername.isNotEmpty()) { "Basic Auth 用户名不能为空" }
        val resolvedPassword = password ?: load(instanceId)?.password
            ?: throw IllegalArgumentException("Basic Auth 密码不能为空")
        val credentials = Credentials(normalizedUsername, resolvedPassword)
        val payload = JSONObject()
            .put("username", credentials.username)
            .put("password", credentials.password)
            .toString()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoded = listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipher.doFinal(payload.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        ).joinToString(".")
        preferences.edit().putString(preferenceKey(instanceId), encoded).apply()
        return credentials
    }

    fun remove(instanceId: String) {
        preferences.edit().remove(preferenceKey(instanceId)).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun preferenceKey(instanceId: String): String {
        val normalized = instanceId.trim()
        require(normalized.isNotEmpty()) { "instanceId required" }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    companion object {
        private const val PREFERENCES = "remote_basic_auth"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "sillyclient.remote_basic_auth"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
