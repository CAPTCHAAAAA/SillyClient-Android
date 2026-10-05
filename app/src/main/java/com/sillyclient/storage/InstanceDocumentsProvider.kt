package com.sillyclient.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.sillyclient.R
import com.sillyclient.runtime.InstanceRepository
import com.sillyclient.runtime.RuntimePaths
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID

/** Read-only SAF access is explicit user sharing, independent of Android/data visibility. */
class InstanceDocumentsProvider : DocumentsProvider() {
    private val policy = InstanceDocumentPolicy()
    private lateinit var index: InstanceDocumentIndex

    override fun onCreate(): Boolean {
        index = InstanceDocumentIndex(requireNotNull(context))
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: ROOT_COLUMNS
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> when (column) {
                Root.COLUMN_ROOT_ID -> ROOT_ID
                Root.COLUMN_DOCUMENT_ID -> ROOT_DOCUMENT
                Root.COLUMN_TITLE -> "SillyClient"
                Root.COLUMN_SUMMARY -> "Read-only instance files"
                Root.COLUMN_ICON -> R.mipmap.ic_launcher
                Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
                Root.COLUMN_MIME_TYPES -> "*/*"
                else -> null
            } })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = documentOperation {
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (documentId == ROOT_DOCUMENT) addRootDocument(this)
            else {
                val reference = index.referenceFor(documentId)
                addDocument(this, documentId, reference, policy.resolve(reference, sources()))
            }
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = documentOperation {
        val sources = sources()
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            index.transaction {
                if (parentDocumentId == ROOT_DOCUMENT) {
                    for (source in sources) {
                        val reference = runCatching { policy.reference(source) }.getOrNull() ?: continue
                        addDocument(this, index.idFor(reference), reference, policy.resolve(reference, sources))
                    }
                } else {
                    val parent = index.referenceFor(parentDocumentId)
                    for (reference in policy.children(parent, sources)) {
                        addDocument(this, index.idFor(reference), reference, policy.resolve(reference, sources))
                    }
                }
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentOperation {
        if (documentId == ROOT_DOCUMENT || parentDocumentId == documentId) return@documentOperation false
        val child = index.referenceFor(documentId)
        val sources = sources()
        policy.resolve(child, sources)
        parentDocumentId == ROOT_DOCUMENT || policy.isChild(index.referenceFor(parentDocumentId), child, sources)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = documentOperation {
        policy.requireReadOnly(mode)
        signal?.throwIfCanceled()
        val reference = index.referenceFor(documentId)
        val file = policy.resolve(reference, sources())
        require(file.isFile) { "Directories cannot be opened as files" }
        val expected = Os.lstat(file.absolutePath)
        val descriptor = Os.open(file.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        try {
            signal?.throwIfCanceled()
            policy.resolve(reference, sources())
            val actual = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(actual.st_mode) && expected.st_dev == actual.st_dev && expected.st_ino == actual.st_ino) {
                "The shared document changed while opening"
            }
            ParcelFileDescriptor.dup(descriptor).also { result ->
                try { signal?.throwIfCanceled() } catch (error: Exception) { result.close(); throw error }
            }
        } finally {
            Os.close(descriptor)
        }
    }

    private fun sources(): List<InstanceDocumentPolicy.Source> {
        val paths = RuntimePaths.from(requireNotNull(context))
        val repository = InstanceRepository(paths.serversDir, installLocations = paths.installLocations,
            legacyServersRoot = paths.legacyServersDir)
        return repository.scan().filter { it.hasServer }.mapNotNull { metadata ->
            runCatching {
                val directory = paths.serverDirFor(metadata.instanceId, metadata.path, create = false)
                val marker = File(directory, ".sc-identity")
                val identity = if (marker.isFile && marker.length() == 36L && !Files.isSymbolicLink(marker.toPath())) {
                    "marker:${readMarker(marker)}"
                } else {
                    val attributes = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    "file:${requireNotNull(attributes.fileKey())}:${attributes.creationTime().toMillis()}"
                }
                InstanceDocumentPolicy.Source(metadata.instanceId, directory, identity)
            }.getOrNull()
        }
    }

    private fun readMarker(file: File): String = Files.newInputStream(file.toPath(),
        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
        val bytes = ByteArray(37)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            count += read
        }
        require(count == 36) { "Invalid shared instance identity" }
        String(bytes, 0, count, Charsets.US_ASCII).also {
            require(UUID.fromString(it).toString() == it) { "Invalid shared instance identity" }
        }
    }

    private fun addRootDocument(cursor: MatrixCursor) {
        cursor.addRow(cursor.columnNames.map { column -> when (column) {
            Document.COLUMN_DOCUMENT_ID -> ROOT_DOCUMENT
            Document.COLUMN_DISPLAY_NAME -> "SillyClient"
            Document.COLUMN_MIME_TYPE -> Document.MIME_TYPE_DIR
            Document.COLUMN_FLAGS -> 0
            Document.COLUMN_ICON -> R.mipmap.ic_launcher
            else -> null
        } })
    }

    private fun addDocument(cursor: MatrixCursor, id: String, reference: InstanceDocumentPolicy.Reference, file: File) {
        cursor.addRow(cursor.columnNames.map { column -> when (column) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> if (reference.relativePath.isEmpty()) File(reference.directory).name else file.name
            Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else mimeType(file)
            Document.COLUMN_FLAGS -> 0
            Document.COLUMN_SIZE -> if (file.isFile) file.length() else null
            Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            else -> null
        } })
    }

    private fun mimeType(file: File): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"

    private fun <T> documentOperation(action: () -> T): T = try {
        action()
    } catch (error: IllegalArgumentException) {
        throw FileNotFoundException(error.message ?: "Shared document is unavailable")
    }

    companion object {
        private const val ROOT_ID = "sillyclient-instances"
        private const val ROOT_DOCUMENT = "root"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
