package com.sillyclient.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * App-private usage ledger for local instances.
 *
 * The ledger deliberately lives below RuntimePaths.tarvenHome rather than in a
 * user-selected instance root. It records server uptime only: WebView
 * visibility and Activity lifecycle do not stop a running session.
 */
class InstanceUsageStore(
    private val file: File,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = System::nanoTime
) {
    data class Usage(
        val createdAt: String?,
        val lastUsedAt: String?,
        val totalUsageMs: Long
    )

    private data class Record(
        val instanceId: String,
        val path: String,
        val identity: String?,
        val createdAt: String,
        val lastUsedAt: String?,
        val totalUsageMs: Long,
        val sessionStartedAt: String?
    )

    private data class State(val records: LinkedHashMap<String, Record> = linkedMapOf())
    private data class ActiveSession(val startedElapsedMs: Long)

    private val lock = fileLocks.computeIfAbsent(file.absoluteFile.path) { Any() }
    private val activeSessions = linkedMapOf<String, ActiveSession>()
    private val checkpointTasks = linkedMapOf<String, ScheduledFuture<*>>()
    private val checkpointExecutor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "SillyClient-usage-checkpoint").apply { isDaemon = true }
    }

    /**
     * Clears sessions left by a previous app process. The last checkpoint is
     * retained, but time after the checkpoint is intentionally not guessed.
     */
    fun finishStaleSessions() = synchronized(lock) {
        activeSessions.clear()
        checkpointTasks.values.forEach { it.cancel(false) }
        checkpointTasks.clear()
        val state = readState()
        var changed = false
        state.records.replaceAll { _, record ->
            if (record.sessionStartedAt == null) record
            else {
                changed = true
                record.copy(sessionStartedAt = null)
            }
        }
        if (changed) writeState(state)
    }

    /** Start or restart one instance session after its server is ready. */
    fun begin(instanceId: String, directory: File): Usage = synchronized(lock) {
        val id = normalizeId(instanceId)
        val nowWall = safeWallNow()
        val nowElapsed = safeElapsedNow()
        val path = canonicalPath(directory)
        val identity = identityOf(directory)
        val state = readState()
        val existing = state.records[id]
        val compatible = existing?.takeIf { matches(it, path, identity) }

        // A same-id restart should not lose the previous in-process session.
        val previousSession = activeSessions.remove(id)
        cancelCheckpointLocked(id)
        val carried = compatible ?: Record(
            instanceId = id,
            path = path,
            identity = identity,
            createdAt = iso(nowWall),
            lastUsedAt = null,
            totalUsageMs = 0L,
            sessionStartedAt = null
        )
        val total = carried.totalUsageMs + if (compatible != null) {
            previousSession?.let { elapsedDelta(it.startedElapsedMs, nowElapsed) } ?: 0L
        } else {
            0L
        }
        val startedAt = iso(nowWall)
        val record = carried.copy(
            path = path,
            identity = identity ?: carried.identity,
            createdAt = if (compatible != null) carried.createdAt else startedAt,
            lastUsedAt = startedAt,
            totalUsageMs = total,
            sessionStartedAt = startedAt
        )
        state.records[id] = record
        writeState(state)
        activeSessions[id] = ActiveSession(nowElapsed)
        scheduleCheckpointLocked(id)
        usage(record, nowElapsed)
    }

    /** Persist a bounded heartbeat and add only elapsed monotonic time. */
    fun checkpoint(instanceId: String): Usage? = synchronized(lock) {
        val id = normalizeId(instanceId)
        val state = readState()
        val record = state.records[id] ?: return@synchronized null
        val active = activeSessions[id]
        if (active == null) {
            if (record.sessionStartedAt == null) return@synchronized usage(record, safeElapsedNow())
            state.records[id] = record.copy(sessionStartedAt = null)
            writeState(state)
            return@synchronized usage(state.records[id]!!, safeElapsedNow())
        }
        val nowElapsed = safeElapsedNow()
        val updated = record.copy(
            totalUsageMs = record.totalUsageMs + elapsedDelta(active.startedElapsedMs, nowElapsed),
            sessionStartedAt = iso(safeWallNow())
        )
        state.records[id] = updated
        writeState(state)
        activeSessions[id] = ActiveSession(nowElapsed)
        usage(updated, nowElapsed)
    }

    /**
     * Finish one active session. A second finish is a no-op, which makes
     * explicit stop and asynchronous process-exit cleanup safe to race.
     */
    fun finish(instanceId: String): Usage? = synchronized(lock) {
        val id = normalizeId(instanceId)
        val state = readState()
        val record = state.records[id] ?: return@synchronized null
        val active = activeSessions.remove(id)
        cancelCheckpointLocked(id)
        if (active == null && record.sessionStartedAt == null) return@synchronized null
        val nowElapsed = safeElapsedNow()
        val updated = record.copy(
            totalUsageMs = record.totalUsageMs + (active?.let {
                elapsedDelta(it.startedElapsedMs, nowElapsed)
            } ?: 0L),
            sessionStartedAt = null
        )
        state.records[id] = updated
        writeState(state)
        usage(updated, nowElapsed)
    }

    /** Read current totals without mutating the ledger. */
    fun usageFor(instanceId: String, directory: File): Usage? = synchronized(lock) {
        val id = normalizeId(instanceId)
        val state = readState()
        val record = state.records[id] ?: return@synchronized null
        val path = canonicalPath(directory)
        val identity = identityOf(directory)
        if (!matches(record, path, identity)) return@synchronized null
        usage(record, safeElapsedNow())
    }

    /** Remove statistics only after the physical instance deletion succeeded. */
    fun remove(instanceId: String, directory: File? = null): Boolean = synchronized(lock) {
        val id = normalizeId(instanceId)
        val state = readState()
        val record = state.records[id] ?: return@synchronized false
        if (directory != null && !matches(record, canonicalPath(directory), identityOf(directory))) {
            return@synchronized false
        }
        activeSessions.remove(id)
        cancelCheckpointLocked(id)
        state.records.remove(id)
        writeState(state)
        true
    }

    private fun usage(record: Record, nowElapsed: Long): Usage {
        val live = activeSessions[record.instanceId]?.let {
            elapsedDelta(it.startedElapsedMs, nowElapsed)
        } ?: 0L
        return Usage(
            createdAt = record.createdAt,
            lastUsedAt = record.lastUsedAt,
            totalUsageMs = record.totalUsageMs + live
        )
    }

    private fun scheduleCheckpointLocked(id: String) {
        cancelCheckpointLocked(id)
        checkpointTasks[id] = checkpointExecutor.scheduleAtFixedRate(
            { runCatching { checkpoint(id) } },
            CHECKPOINT_INTERVAL_MS,
            CHECKPOINT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cancelCheckpointLocked(id: String) {
        checkpointTasks.remove(id)?.cancel(false)
    }

    private fun readState(): State {
        if (!file.isFile) return State()
        val bytes = runCatching {
            Files.readAllBytes(file.toPath()).also {
                require(it.size in 1..MAX_BYTES) { "Instance usage ledger is not bounded" }
            }
        }.getOrElse { return State() }
        val document = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
            .getOrElse { return State() }
        if (document.optInt("version", -1) != VERSION) return State()
        val entries = document.optJSONArray("instances") ?: return State()
        val records = linkedMapOf<String, Record>()
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            val parsed = runCatching { parseRecord(item) }.getOrNull() ?: continue
            if (parsed.instanceId !in records && records.size < MAX_RECORDS) {
                records[parsed.instanceId] = parsed
            }
        }
        return State(records)
    }

    private fun parseRecord(item: JSONObject): Record {
        val id = normalizeId(item.getString("instanceId"))
        val path = item.getString("path")
        require(File(path).isAbsolute && path.none { it.code < 32 })
        val identity = item.optString("identity", "").takeIf { it.isNotBlank() }
        val createdAt = item.optString("createdAt", "").takeIf { it.length in 1..MAX_DATE_CHARS }
            ?: error("Missing usage creation time")
        val lastUsedAt = item.optString("lastUsedAt", "").takeIf { it.length in 1..MAX_DATE_CHARS }
        val sessionStartedAt = item.optString("sessionStartedAt", "")
            .takeIf { it.length in 1..MAX_DATE_CHARS }
        val total = (item.opt("totalUsageMs") as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
        return Record(id, path, identity, createdAt, lastUsedAt, total, sessionStartedAt)
    }

    private fun writeState(state: State) {
        require(state.records.size <= MAX_RECORDS) { "Instance usage ledger exceeds its entry limit" }
        val entries = JSONArray()
        state.records.values.forEach { record ->
            val item = JSONObject()
                .put("instanceId", record.instanceId)
                .put("path", record.path)
                .put("createdAt", record.createdAt)
                .put("totalUsageMs", record.totalUsageMs)
            record.identity?.let { item.put("identity", it) }
            record.lastUsedAt?.let { item.put("lastUsedAt", it) }
            record.sessionStartedAt?.let { item.put("sessionStartedAt", it) }
            entries.put(item)
        }
        val bytes = JSONObject().put("version", VERSION).put("instances", entries)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Instance usage ledger exceeds its size limit" }
        val parent = requireNotNull(file.parentFile) { "Instance usage ledger needs a parent directory" }
        check((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) {
            "Could not create instance usage ledger parent"
        }
        val temporary = File(parent, ".instance-usage-${UUID.randomUUID()}.tmp")
        try {
            require(temporary.createNewFile()) { "Could not stage instance usage ledger" }
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun matches(record: Record, path: String, identity: String?): Boolean =
        if (record.identity != null && identity != null) {
            record.identity == identity
        } else {
            record.path == path
        }

    private fun identityOf(directory: File): String? {
        val marker = File(directory, ".sc-identity")
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            marker.length() !in 1..MAX_IDENTITY_BYTES
        ) return null
        return runCatching {
            Files.readAllBytes(marker.toPath()).toString(Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() && it.length <= MAX_IDENTITY_CHARS }
        }.getOrNull()
    }

    private fun canonicalPath(directory: File): String =
        runCatching { directory.canonicalFile.path }.getOrDefault(directory.absoluteFile.path)

    private fun normalizeId(value: String): String = InstallLocationRegistry.normalizeInstanceId(value)

    private fun elapsedDelta(start: Long, now: Long): Long =
        ((now - start) / 1_000_000L).coerceAtLeast(0L)

    private fun safeWallNow(): Long = wallClock().coerceAtLeast(0L)
    private fun safeElapsedNow(): Long = elapsedClock()
    private fun iso(value: Long): String = Instant.ofEpochMilli(value).toString()

    companion object {
        private const val VERSION = 1
        private const val MAX_BYTES = 256 * 1024
        private const val MAX_RECORDS = 1024
        private const val MAX_DATE_CHARS = 64
        private const val MAX_IDENTITY_BYTES = 1024L
        private const val MAX_IDENTITY_CHARS = 2048
        private const val CHECKPOINT_INTERVAL_MS = 15_000L
        private val fileLocks = ConcurrentHashMap<String, Any>()
        private val sharedStores = ConcurrentHashMap<String, InstanceUsageStore>()

        /**
         * One process-wide store per app-private file. This keeps Activity
         * recreation from creating competing session clocks.
         */
        fun shared(file: File): InstanceUsageStore {
            val key = file.absoluteFile.path
            return sharedStores.computeIfAbsent(key) {
                InstanceUsageStore(file).also { it.finishStaleSessions() }
            }
        }

        fun ledgerFile(tarvenHome: File): File = File(tarvenHome, "instance-usage.json")
    }
}
