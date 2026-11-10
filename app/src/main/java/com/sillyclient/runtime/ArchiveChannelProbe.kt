package com.sillyclient.runtime

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

internal object ArchiveChannelProbe {
    /** Checks random reads only; archive validation belongs to inspection and import. */
    fun canRead(channel: SeekableByteChannel, archiveBytes: Long): Boolean {
        val originalPosition = try {
            channel.position()
        } catch (_: Exception) {
            return false
        }
        var readable = false
        try {
            if (archiveBytes >= 22) {
                val buffer = ByteBuffer.allocate(1)
                channel.position(archiveBytes - 1)
                val tailReadable = channel.read(buffer) == 1
                buffer.clear()
                channel.position(0)
                readable = tailReadable && channel.read(buffer) == 1
            }
        } catch (_: Exception) {
            readable = false
        } finally {
            try {
                channel.position(originalPosition)
            } catch (_: Exception) {
                readable = false
            }
        }
        return readable
    }
}
