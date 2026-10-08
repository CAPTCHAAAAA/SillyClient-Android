package com.sillyclient.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstanceLockTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun pbkdf2MatchesNodeCryptoExpectedHash() {
        val file = File(tempFolder.newFolder(), "instance-passwords.json")
        val lock = InstanceLock(file)

        // Using reflection or testing set/verify directly with known hash
        val res = lock.setPassword("test-id", "secret123", null)
        assertTrue(res.success)
        assertTrue(res.hasPassword)
        assertTrue(lock.verifyPassword("test-id", "secret123"))
        assertFalse(lock.verifyPassword("test-id", "wrong"))
    }

    @Test
    fun unconfiguredInstanceAllowsVerification() {
        val file = File(tempFolder.newFolder(), "instance-passwords.json")
        val lock = InstanceLock(file)

        assertFalse(lock.hasPassword("not-set"))
        assertTrue(lock.verifyPassword("not-set", "any-password"))
    }

    @Test
    fun updateRequiresOldPassword() {
        val file = File(tempFolder.newFolder(), "instance-passwords.json")
        val lock = InstanceLock(file)

        lock.setPassword("my-inst", "pass1", null)
        assertTrue(lock.hasPassword("my-inst"))

        try {
            lock.setPassword("my-inst", "pass2", "wrongOld")
            fail("Expected exception for wrong old password")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("原访问密码错误") == true)
        }

        val updateRes = lock.setPassword("my-inst", "pass2", "pass1")
        assertTrue(updateRes.success)
        assertTrue(updateRes.hasPassword)
        assertTrue(lock.verifyPassword("my-inst", "pass2"))
        assertFalse(lock.verifyPassword("my-inst", "pass1"))
    }

    @Test
    fun renameMovesPasswordToNewId() {
        val file = File(tempFolder.newFolder(), "instance-passwords.json")
        val lock = InstanceLock(file)

        lock.setPassword("old-id", "secret", null)
        lock.renamePassword("old-id", "new-id")

        assertFalse(lock.hasPassword("old-id"))
        assertTrue(lock.hasPassword("new-id"))
        assertTrue(lock.verifyPassword("new-id", "secret"))
    }

    @Test
    fun listStatusReportsAllProtectedInstances() {
        val file = File(tempFolder.newFolder(), "instance-passwords.json")
        val lock = InstanceLock(file)

        lock.setPassword("inst1", "p1", null)
        lock.setPassword("inst2", "p2", null)

        val status = lock.listStatus()
        assertEquals(2, status.size)
        assertEquals(true, status["inst1"])
        assertEquals(true, status["inst2"])
    }

    @Test
    fun clearPasswordRemovesProtection() {
        val file = File(tempFolder.newFolder(), "instance-passwords.json")
        val lock = InstanceLock(file)

        lock.setPassword("inst", "pwd", null)

        try {
            lock.clearPassword("inst", "wrong")
            fail("Expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("原访问密码错误") == true)
        }

        assertTrue(lock.clearPassword("inst", "pwd"))
        assertFalse(lock.hasPassword("inst"))
        assertTrue(lock.verifyPassword("inst", "anything"))
    }

    @Test
    fun removePasswordDeletesRecord() {
        val file = File(tempFolder.newFolder(), "instance-passwords.json")
        val lock = InstanceLock(file)

        lock.setPassword("inst", "pwd", null)
        assertTrue(lock.hasPassword("inst"))

        lock.removePassword("inst")
        assertFalse(lock.hasPassword("inst"))
    }
}
