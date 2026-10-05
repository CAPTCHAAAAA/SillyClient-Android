package com.sillyclient.runtime

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

/**
 * 实例访问密码保护（本地安全锁）存储与校验服务。
 * 纯本地单向加盐哈希（PBKDF2 SHA-256，10000 次迭代），零网络验证。
 */
class InstanceLock(private val file: File) {

    data class PasswordRecord(
        val salt: String,
        val hash: String,
        val updatedAt: String
    )

    data class SetPasswordResult(
        val success: Boolean,
        val hasPassword: Boolean
    )

    private val lock = Any()

    private fun normalizeId(id: String): String = id.trim()

    fun hasPassword(instanceId: String): Boolean = synchronized(lock) {
        val safeId = normalizeId(instanceId)
        val registry = readRegistry()
        return registry.containsKey(safeId)
    }

    fun verifyPassword(instanceId: String, password: String): Boolean = synchronized(lock) {
        val safeId = normalizeId(instanceId)
        val registry = readRegistry()
        val record = registry[safeId] ?: return true // 未设置密码则直接放行
        val computed = computeHash(password, record.salt)
        return MessageDigest.isEqual(hexToBytes(computed), hexToBytes(record.hash))
    }

    fun setPassword(
        instanceId: String,
        newPassword: String?,
        oldPassword: String?
    ): SetPasswordResult = synchronized(lock) {
        val safeId = normalizeId(instanceId)
        val registry = readRegistry().toMutableMap()
        val existing = registry[safeId]

        if (existing != null) {
            if (oldPassword.isNullOrBlank() || !verifyPasswordInternal(existing, oldPassword)) {
                throw IllegalArgumentException("原访问密码错误，无法更改或解除")
            }
        }

        val cleanPassword = (newPassword ?: "").trim()
        if (cleanPassword.isEmpty()) {
            if (existing != null) {
                registry.remove(safeId)
                writeRegistry(registry)
            }
            return SetPasswordResult(success = true, hasPassword = false)
        }

        val random = SecureRandom()
        val saltBytes = ByteArray(16)
        random.nextBytes(saltBytes)
        val saltHex = bytesToHex(saltBytes)
        val hashHex = computeHash(cleanPassword, saltHex)

        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        registry[safeId] = PasswordRecord(
            salt = saltHex,
            hash = hashHex,
            updatedAt = nowIso
        )
        writeRegistry(registry)
        return SetPasswordResult(success = true, hasPassword = true)
    }

    fun clearPassword(instanceId: String, oldPassword: String?): Boolean = synchronized(lock) {
        val safeId = normalizeId(instanceId)
        val registry = readRegistry().toMutableMap()
        val existing = registry[safeId] ?: return true

        if (oldPassword.isNullOrBlank() || !verifyPasswordInternal(existing, oldPassword)) {
            throw IllegalArgumentException("原访问密码错误，无法解除密码保护")
        }

        registry.remove(safeId)
        writeRegistry(registry)
        return true
    }

    fun renamePassword(oldId: String, newId: String): Unit = synchronized(lock) {
        val safeOldId = normalizeId(oldId)
        val safeNewId = normalizeId(newId)
        if (safeOldId == safeNewId) return
        val registry = readRegistry().toMutableMap()
        val record = registry.remove(safeOldId) ?: return
        registry[safeNewId] = record
        writeRegistry(registry)
    }

    fun removePassword(instanceId: String): Unit = synchronized(lock) {
        val safeId = normalizeId(instanceId)
        val registry = readRegistry().toMutableMap()
        if (registry.remove(safeId) != null) {
            writeRegistry(registry)
        }
    }

    fun listStatus(): Map<String, Boolean> = synchronized(lock) {
        val registry = readRegistry()
        val result = mutableMapOf<String, Boolean>()
        for (id in registry.keys) {
            result[id] = true
        }
        return result
    }

    private fun verifyPasswordInternal(record: PasswordRecord, password: String): Boolean {
        val computed = computeHash(password, record.salt)
        return MessageDigest.isEqual(hexToBytes(computed), hexToBytes(record.hash))
    }

    private fun readRegistry(): Map<String, PasswordRecord> {
        if (!file.isFile) return emptyMap()
        return try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            if (json.optInt("version", 0) != 1) return emptyMap()
            val passwordsObj = json.optJSONObject("passwords") ?: return emptyMap()

            val result = mutableMapOf<String, PasswordRecord>()
            val keys = passwordsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = passwordsObj.optJSONObject(key) ?: continue
                val salt = entry.optString("salt", "")
                val hash = entry.optString("hash", "")
                val updatedAt = entry.optString("updatedAt", "")
                if (salt.isNotEmpty() && hash.isNotEmpty()) {
                    result[normalizeId(key)] = PasswordRecord(salt, hash, updatedAt)
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeRegistry(registry: Map<String, PasswordRecord>) {
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("version", 1)
            val passObj = JSONObject()
            for ((k, v) in registry) {
                val item = JSONObject().apply {
                    put("salt", v.salt)
                    put("hash", v.hash)
                    put("updatedAt", v.updatedAt)
                }
                passObj.put(k, item)
            }
            put("passwords", passObj)
        }

        val temp = File(file.parentFile, "${file.name}.tmp-${UUID.randomUUID()}")
        try {
            temp.writeText(json.toString(2) + "\n", Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                    temp.delete()
                }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun computeHash(password: String, saltHex: String): String {
        val salt = hexToBytes(saltHex)
        val spec = PBEKeySpec(password.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return bytesToHex(hash)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
