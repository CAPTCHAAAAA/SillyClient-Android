package com.sillyclient.runtime

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Minimal ustar reader/writer with GNU long-name extensions. Only the entry
 * kinds npm materializes inside node_modules are supported: regular files,
 * directories and symbolic links; hard links, devices and sparse entries are
 * rejected so a hostile archive can never extract anything this writer would
 * not have produced itself.
 */
internal object UstarArchive {
    const val BLOCK_BYTES = 512
    internal const val MAX_ENTRY_NAME = 1024
    private const val NAME_LIMIT = 100
    private const val LINK_LIMIT = 100
    private const val PREFIX_LIMIT = 155
    private const val PREFIX_OFFSET = 345
    private const val MODE_OFFSET = 100
    private const val SIZE_OFFSET = 124
    private const val MTIME_OFFSET = 136
    private const val CHECKSUM_OFFSET = 148
    private const val TYPE_OFFSET = 156
    private const val LINK_OFFSET = 157
    private const val MAX_METADATA_BYTES = 4096
    private const val LONG_NAME = "././@LongLink"

    class Entry(
        val name: String,
        val isDirectory: Boolean,
        val isSymbolicLink: Boolean,
        val linkTarget: String,
        val mode: Int,
        val size: Long,
        val content: () -> InputStream
    )

    interface Visitor {
        fun directory(name: String, mode: Int)
        fun file(name: String, mode: Int, size: Long, content: InputStream)
        fun symbolicLink(name: String, target: String, mode: Int)
    }

    fun write(file: File, entries: Sequence<Entry>): Long {
        Files.newOutputStream(file.toPath()).buffered().use { stream ->
            var bytes = 0L
            for (entry in entries) bytes += writeEntry(stream, entry)
            stream.write(ByteArray(BLOCK_BYTES * 2))
            return bytes + BLOCK_BYTES * 2L
        }
    }

    fun read(file: File, visitor: Visitor): Int {
        Files.newInputStream(file.toPath()).buffered().use { stream ->
            var entries = 0
            var pendingName: String? = null
            var pendingLink: String? = null
            while (true) {
                val header = readBlock(stream)
                    ?: throw IOException("Archive ended without a terminator block")
                if (header.all { it == 0.toByte() }) break
                verifyChecksum(header)
                val size = parseOctal(header, SIZE_OFFSET, 12)
                val type = header[TYPE_OFFSET].toInt().toChar()
                if (type == 'L' || type == 'K') {
                    val value = String(readData(stream, size), StandardCharsets.UTF_8).trimEnd('\u0000')
                    if (type == 'L') pendingName = value else pendingLink = value
                    continue
                }
                val name = pendingName ?: entryName(header)
                val link = pendingLink ?: fieldString(header, LINK_OFFSET, LINK_LIMIT)
                pendingName = null
                pendingLink = null
                val mode = parseOctal(header, MODE_OFFSET, 8).toInt()
                if (size < 0) throw IOException("Negative entry size")
                when (type) {
                    '5' -> visitor.directory(requireSafe(name), mode)
                    '2' -> visitor.symbolicLink(requireSafe(name), safeLinkTarget(link), mode)
                    '0', '\u0000' -> {
                        val safe = requireSafe(name)
                        val content = LimitedInput(stream, size)
                        visitor.file(safe, mode, size, content)
                        content.discard()
                        skipPadding(stream, size)
                    }
                    else -> throw IOException("Unsupported archive entry type")
                }
                entries++
            }
            return entries
        }
    }

    private fun writeEntry(stream: OutputStream, entry: Entry): Long {
        var bytes = 0L
        val split = nameFields(entry.name)
        if (split == null) bytes += writeLongEntry(stream, 'L', entry.name)
        val longLink = entry.isSymbolicLink &&
            entry.linkTarget.toByteArray(StandardCharsets.UTF_8).size > LINK_LIMIT
        if (longLink) bytes += writeLongEntry(stream, 'K', entry.linkTarget)
        val header = ByteArray(BLOCK_BYTES)
        val nameBytes = (split?.second ?: entry.name).toByteArray(StandardCharsets.UTF_8)
        nameBytes.copyInto(header, 0, 0, minOf(nameBytes.size, NAME_LIMIT))
        putOctal(header, MODE_OFFSET, 8, (entry.mode and 0b111_111_111).toLong())
        putOctal(header, SIZE_OFFSET, 12,
            if (entry.isDirectory || entry.isSymbolicLink) 0L else entry.size)
        putOctal(header, MTIME_OFFSET, 12, 0L)
        header[TYPE_OFFSET] = when {
            entry.isDirectory -> '5'.code.toByte()
            entry.isSymbolicLink -> '2'.code.toByte()
            else -> '0'.code.toByte()
        }
        if (entry.isSymbolicLink && !longLink) {
            val linkBytes = entry.linkTarget.toByteArray(StandardCharsets.UTF_8)
            require(linkBytes.size <= LINK_LIMIT) { "Link target exceeds the header field" }
            linkBytes.copyInto(header, LINK_OFFSET, 0, linkBytes.size)
        }
        "ustar".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 257)
        header[262] = 0
        "00".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 263)
        split?.first?.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)?.let { prefix ->
            require(prefix.size <= PREFIX_LIMIT) { "Entry prefix exceeds the header field" }
            prefix.copyInto(header, PREFIX_OFFSET, 0, prefix.size)
        }
        putChecksum(header)
        stream.write(header)
        bytes += BLOCK_BYTES
        if (!entry.isDirectory && !entry.isSymbolicLink && entry.size > 0) {
            entry.content().use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = entry.size
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw EOFException("Entry content ended early: ${entry.name}")
                    stream.write(buffer, 0, read)
                    bytes += read.toLong()
                    remaining -= read
                }
            }
            val padding = ((BLOCK_BYTES - entry.size % BLOCK_BYTES) % BLOCK_BYTES).toInt()
            if (padding > 0) {
                stream.write(ByteArray(padding))
                bytes += padding
            }
        }
        return bytes
    }

    private fun writeLongEntry(stream: OutputStream, type: Char, value: String): Long {
        val data = value.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
        val header = ByteArray(BLOCK_BYTES)
        LONG_NAME.toByteArray(StandardCharsets.US_ASCII).copyInto(header, 0, 0, LONG_NAME.length)
        putOctal(header, MODE_OFFSET, 8, 0b110_100_100)
        putOctal(header, SIZE_OFFSET, 12, data.size.toLong())
        header[TYPE_OFFSET] = type.code.toByte()
        "ustar".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 257)
        header[262] = 0
        "00".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 263)
        putChecksum(header)
        stream.write(header)
        stream.write(data)
        val padding = ((BLOCK_BYTES - data.size % BLOCK_BYTES) % BLOCK_BYTES).toInt()
        if (padding > 0) stream.write(ByteArray(padding))
        return BLOCK_BYTES.toLong() + data.size + padding
    }

    /** Splits [name] into (prefix, name); null when only a GNU long name can hold it. */
    private fun nameFields(name: String): Pair<String, String>? {
        val bytes = name.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= NAME_LIMIT) return "" to name
        var split = lastSlashAtOrBefore(bytes, NAME_LIMIT - 1)
        while (split >= 0) {
            val remainder = bytes.size - split - 1
            if (split <= PREFIX_LIMIT && remainder in 1..NAME_LIMIT) {
                return String(bytes, 0, split, StandardCharsets.UTF_8) to
                    String(bytes, split + 1, remainder, StandardCharsets.UTF_8)
            }
            split = lastSlashAtOrBefore(bytes, split - 1)
        }
        return null
    }

    /** Byte-array lastIndexOf(element, fromIndex) does not exist in Kotlin stdlib. */
    private fun lastSlashAtOrBefore(bytes: ByteArray, fromIndex: Int): Int {
        var index = minOf(fromIndex, bytes.size - 1)
        while (index >= 0) {
            if (bytes[index] == '/'.code.toByte()) return index
            index--
        }
        return -1
    }

    private fun entryName(header: ByteArray): String {
        val prefix = fieldString(header, PREFIX_OFFSET, PREFIX_LIMIT)
        val name = fieldString(header, 0, NAME_LIMIT)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun fieldString(block: ByteArray, offset: Int, length: Int): String {
        var limit = offset + length
        for (i in offset until offset + length) {
            if (block[i] == 0.toByte()) { limit = i; break }
        }
        return String(block, offset, limit - offset, StandardCharsets.UTF_8)
    }

    private fun putOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        require(value >= 0) { "Negative header field" }
        val text = value.toString(8)
        require(text.length <= length - 1) { "Header field overflow" }
        for (i in 0 until length - 1) {
            val digit = i - (length - 1 - text.length)
            block[offset + i] = if (digit < 0) '0'.code.toByte() else text[digit].code.toByte()
        }
        block[offset + length - 1] = 0
    }

    private fun parseOctal(block: ByteArray, offset: Int, length: Int): Long {
        if (block[offset].toInt() and 0x80 != 0) throw IOException("Unsupported header field encoding")
        var value = 0L
        var digits = 0
        for (i in offset until offset + length) {
            val character = block[i].toInt().toChar()
            when {
                character == ' ' || character == '\u0000' -> if (digits > 0) break
                character in '0'..'7' -> { value = value * 8 + (character - '0'); digits++ }
                else -> throw IOException("Invalid header field")
            }
        }
        return value
    }

    private fun putChecksum(header: ByteArray) {
        for (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + 8) header[i] = ' '.code.toByte()
        val sum = header.sumOf { it.toLong() and 0xFF }
        val text = sum.toString(8).padStart(6, '0')
        require(text.length <= 6) { "Header checksum overflow" }
        text.toByteArray(StandardCharsets.US_ASCII).copyInto(header, CHECKSUM_OFFSET)
        header[CHECKSUM_OFFSET + 6] = 0
        header[CHECKSUM_OFFSET + 7] = ' '.code.toByte()
    }

    private fun verifyChecksum(header: ByteArray) {
        var sum = 0L
        for (i in header.indices) {
            sum += if (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + 8) ' '.code.toLong()
            else header[i].toLong() and 0xFF
        }
        if (sum != parseOctal(header, CHECKSUM_OFFSET, 8)) {
            throw IOException("Archive header checksum mismatch")
        }
    }

    private fun readBlock(stream: InputStream): ByteArray? {
        val block = ByteArray(BLOCK_BYTES)
        var filled = 0
        while (filled < BLOCK_BYTES) {
            val read = stream.read(block, filled, BLOCK_BYTES - filled)
            if (read < 0) {
                if (filled == 0) return null
                throw EOFException("Archive header is truncated")
            }
            filled += read
        }
        return block
    }

    private fun readData(stream: InputStream, size: Long): ByteArray {
        if (size < 0 || size > MAX_METADATA_BYTES) throw IOException("Archive metadata entry is oversized")
        val data = ByteArray(size.toInt())
        var filled = 0
        while (filled < data.size) {
            val read = stream.read(data, filled, data.size - filled)
            if (read < 0) throw EOFException("Archive metadata entry ended early")
            filled += read
        }
        skipPadding(stream, size)
        return data
    }

    private fun skipPadding(stream: InputStream, size: Long) {
        var remaining = (BLOCK_BYTES - size % BLOCK_BYTES) % BLOCK_BYTES
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) throw EOFException("Archive ended inside entry padding")
                remaining -= 1
            } else remaining -= skipped
        }
    }

    private fun requireSafe(name: String): String =
        safeEntryPath(name) ?: throw IOException("Unsafe archive entry name")

    internal fun safeEntryPath(name: String): String? {
        if (name.isEmpty() || name.length > MAX_ENTRY_NAME) return null
        if ('\u0000' in name || '\\' in name) return null
        val trimmed = name.trimEnd('/')
        if (trimmed.isEmpty() || trimmed.startsWith("/")) return null
        if (Regex("^[A-Za-z]:/").containsMatchIn(trimmed)) return null
        for (part in trimmed.split('/')) {
            if (part.isEmpty() || part == "." || part == "..") return null
        }
        return trimmed
    }

    private fun safeLinkTarget(target: String): String {
        if (target.isEmpty() || target.length > MAX_ENTRY_NAME || '\u0000' in target) {
            throw IOException("Unsafe archive link target")
        }
        return target
    }

    private class LimitedInput(private val stream: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = stream.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val read = stream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }

        fun discard() {
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                if (read(buffer, 0, buffer.size) < 0) {
                    throw EOFException("Archive entry content ended early")
                }
            }
        }
    }

    internal fun emptyContent(): InputStream = ByteArrayInputStream(ByteArray(0))
}
