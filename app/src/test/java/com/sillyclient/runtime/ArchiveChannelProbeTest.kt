package com.sillyclient.runtime

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveChannelProbeTest {
    @Test
    fun multiGigabyteChannelUsesOnlyTwoSingleByteReadsAndRestoresPosition() {
        val channel = CountingChannel(length = 5_000_000_000L, initialPosition = 19)
        channel.use {
            assertTrue(ArchiveChannelProbe.canRead(it, it.size()))
            assertEquals(19L, it.position())
            assertEquals(2, channel.readCalls)
            assertEquals(2, channel.bytesRead)
            assertEquals(3, channel.seekCalls)
            assertTrue(channel.isOpen)
        }
        assertFalse(channel.isOpen)
        assertEquals(1, channel.closeCalls)
    }

    @Test
    fun nonseekableChannelFallsBackWithoutReading() {
        val channel = CountingChannel(seekable = false)
        channel.use {
            assertFalse(ArchiveChannelProbe.canRead(it, it.size()))
            assertEquals(0, channel.readCalls)
            assertTrue(channel.isOpen)
        }
        assertFalse(channel.isOpen)
    }

    @Test
    fun readFailureRestoresPositionAndOwnerClosesChannel() {
        val channel = CountingChannel(initialPosition = 7, failReadAt = 0)
        channel.use {
            assertFalse(ArchiveChannelProbe.canRead(it, it.size()))
            assertEquals(7L, it.position())
            assertEquals(2, channel.readCalls)
            assertEquals(1, channel.bytesRead)
        }
        assertFalse(channel.isOpen)
        assertEquals(1, channel.closeCalls)
    }

    @Test
    fun failureToRestorePositionRejectsDirectUse() {
        val channel = CountingChannel(initialPosition = 7, failSeekAt = 7)
        channel.use {
            assertFalse(ArchiveChannelProbe.canRead(it, it.size()))
            assertEquals(2, channel.bytesRead)
        }
        assertFalse(channel.isOpen)
    }

    @Test
    fun shortOrTruncatedChannelCannotPassProbe() {
        CountingChannel(length = 21).use {
            assertFalse(ArchiveChannelProbe.canRead(it, it.size()))
            assertEquals(0, it.readCalls)
        }
        CountingChannel(length = 22).use {
            assertFalse(ArchiveChannelProbe.canRead(it, archiveBytes = 23))
            assertEquals(0, it.bytesRead)
        }
    }

    @Test
    fun capabilityProbeDoesNotParseArchiveContents() {
        CountingChannel(length = 22).use {
            assertTrue(ArchiveChannelProbe.canRead(it, it.size()))
            assertEquals(2, it.bytesRead)
        }
    }

    @Test
    fun closedChannelIsRejected() {
        val channel = CountingChannel()
        channel.close()

        assertFalse(ArchiveChannelProbe.canRead(channel, archiveBytes = 100))
        assertEquals(0, channel.readCalls)
    }

    private class CountingChannel(
        private val length: Long = 100,
        initialPosition: Long = 0,
        private val seekable: Boolean = true,
        private val failReadAt: Long? = null,
        private val failSeekAt: Long? = null
    ) : SeekableByteChannel {
        private var cursor = initialPosition
        private var open = true
        var readCalls = 0
            private set
        var bytesRead = 0
            private set
        var seekCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun read(destination: ByteBuffer): Int {
            requireOpen()
            readCalls++
            if (cursor == failReadAt) throw IOException("Read failed")
            if (cursor >= length) return -1
            val count = minOf(destination.remaining().toLong(), length - cursor).toInt()
            repeat(count) { destination.put(0) }
            cursor += count
            bytesRead += count
            return count
        }

        override fun position(): Long {
            requireOpen()
            return cursor
        }

        override fun position(newPosition: Long): SeekableByteChannel {
            requireOpen()
            seekCalls++
            if (!seekable || newPosition == failSeekAt) throw IOException("Seek failed")
            require(newPosition >= 0)
            cursor = newPosition
            return this
        }

        override fun size(): Long {
            requireOpen()
            return length
        }

        override fun write(source: ByteBuffer): Int = throw UnsupportedOperationException()

        override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()

        override fun isOpen(): Boolean = open

        override fun close() {
            closeCalls++
            open = false
        }

        private fun requireOpen() {
            if (!open) throw ClosedChannelException()
        }
    }
}
