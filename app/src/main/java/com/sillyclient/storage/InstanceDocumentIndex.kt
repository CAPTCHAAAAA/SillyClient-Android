package com.sillyclient.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.FileNotFoundException
import java.util.UUID

/** Persistent opaque handles contain no physical paths in exported document IDs. */
internal class InstanceDocumentIndex(context: Context) : SQLiteOpenHelper(context, "shared-instance-documents.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE documents (
            id TEXT PRIMARY KEY, instance_id TEXT NOT NULL, instance_path TEXT NOT NULL,
            identity TEXT NOT NULL, relative_path TEXT NOT NULL,
            UNIQUE(instance_id, instance_path, identity, relative_path))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun <T> transaction(action: () -> T): T {
        val database = writableDatabase
        database.beginTransaction()
        return try {
            action().also { database.setTransactionSuccessful() }
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun idFor(reference: InstanceDocumentPolicy.Reference): String {
        val args = arrayOf(reference.instanceId, reference.directory, reference.identity, reference.relativePath)
        readableDatabase.query("documents", arrayOf("id"),
            "instance_id=? AND instance_path=? AND identity=? AND relative_path=?", args, null, null, null).use {
            if (it.moveToFirst()) return it.getString(0)
        }
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("instance_id", reference.instanceId)
            put("instance_path", reference.directory)
            put("identity", reference.identity)
            put("relative_path", reference.relativePath)
        }
        writableDatabase.insertOrThrow("documents", null, values)
        return id
    }

    fun referenceFor(id: String): InstanceDocumentPolicy.Reference {
        require(id.length == 36 && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "Invalid shared document identifier"
        }
        readableDatabase.query("documents", arrayOf("instance_id", "instance_path", "identity", "relative_path"),
            "id=?", arrayOf(id), null, null, null).use {
            if (!it.moveToFirst()) throw FileNotFoundException("Unknown shared document")
            return InstanceDocumentPolicy.Reference(it.getString(0), it.getString(1), it.getString(2), it.getString(3))
        }
    }
}
