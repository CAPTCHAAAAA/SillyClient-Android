package com.sillyclient.runtime

import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Own only processes this Activity created, never infer ownership from a port. */
class ProcessSupervisor(private val operations: OperationCoordinator) : AutoCloseable {
    private data class Owned(val instanceId: String, val operation: OperationCoordinator.Operation?, val cancellation: AutoCloseable?)
    data class Result(val exitCode: Int?, val timedOut: Boolean)
    private val processes = ConcurrentHashMap<Process, Owned>()
    private val termination = Executors.newSingleThreadExecutor { Thread(it, "SillyClient-stop").apply { isDaemon = true } }
    private val readers = Executors.newCachedThreadPool { Thread(it, "SillyClient-output").apply { isDaemon = true } }
    @Volatile private var closed = false
    private val lifecycleLock = Any()
    private var generation = 0L

    fun generation(): Long = synchronized(lifecycleLock) { generation }

    fun launch(
        builder: ProcessBuilder, instanceId: String,
        operation: OperationCoordinator.Operation?, expectedGeneration: Long
    ): Process = synchronized(lifecycleLock) {
        if (closed || generation != expectedGeneration) throw CancellationException("Command cancelled")
        operation?.let(operations::ensureCurrent)
        track(builder.start(), instanceId, operation)
    }

    fun track(process: Process, instanceId: String, operation: OperationCoordinator.Operation? = null): Process {
        if (closed) {
            runCatching { process.destroyForcibly() }
            throw CancellationException("Process supervisor is closed")
        }
        processes[process] = Owned(instanceId, operation, null)
        try {
            val cancellation = operation?.let { operations.onCancel(it) { stopAsync(process) } }
            if (processes.containsKey(process)) processes[process] = Owned(instanceId, operation, cancellation)
            else cancellation?.close()
        } catch (error: Exception) {
            stopAsync(process)
            throw error
        }
        return process
    }

    fun forget(process: Process) {
        processes.remove(process)?.cancellation?.close()
    }

    fun hasProcesses(instanceId: String? = null): Boolean = processes.entries.any {
        (instanceId == null || it.value.instanceId == instanceId) && it.key.isAlive
    }

    fun watch(process: Process, onLine: (String) -> Unit, onExit: (Int) -> Unit) {
        val output = readers.submit { readBoundedLines(process.inputStream, onLine) }
        readers.execute {
            try {
                val exit = process.waitFor()
                runCatching { output.get(1, TimeUnit.SECONDS) }
                onExit(exit)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                output.cancel(true)
                if (!process.isAlive) {
                    forget(process)
                    closeInBackground(process)
                }
            }
        }
    }

    fun stopAllAsync(instanceId: String? = null): Future<*> {
        val stopped = mutableListOf<Process>()
        synchronized(lifecycleLock) {
            generation++
            processes.entries.toList()
                .filter { instanceId == null || it.value.instanceId == instanceId }
                .forEach { stopped.add(it.key); stopAsync(it.key) }
        }
        return termination.submit { check(stopped.none { it.isAlive }) { "An owned process has not exited yet" } }
    }

    fun stopAsync(process: Process): Future<*> {
        if (process.isAlive) runCatching { process.destroyForcibly() }
        if (termination.isShutdown) {
            if (!process.isAlive) {
                forget(process)
                closeInBackground(process)
            }
            return CompletableFuture.completedFuture<Unit>(Unit)
        }
        return termination.submit {
            try {
                process.waitFor(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (!process.isAlive) {
                    forget(process)
                    closeInBackground(process)
                }
            }
        }
    }

    /** Process lifetime is supervised concurrently with its output, not after EOF. */
    fun waitFor(process: Process, timeoutMillis: Long, onLine: ((String) -> Unit)? = null): Result {
        val output = onLine?.let { sink -> readers.submit { readBoundedLines(process.inputStream, sink) } }
        try {
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                stopAndWait(process)
                return Result(null, true)
            }
            runCatching { output?.get(1, TimeUnit.SECONDS) }
            return Result(process.exitValue(), false)
        } catch (error: InterruptedException) {
            try { stopAndWait(process) } catch (stopError: Exception) { error.addSuppressed(stopError) }
            Thread.currentThread().interrupt()
            throw error
        } finally {
            output?.cancel(true)
            if (!process.isAlive) {
                closeInBackground(process)
                forget(process)
            }
        }
    }

    /** Only useful output renews the idle deadline; a busy process has no total time limit. */
    fun waitForIdle(
        process: Process,
        idleTimeoutMillis: Long,
        ensureActive: () -> Unit = {},
        onErrorLine: (String) -> Unit = {},
        onLine: (String) -> Boolean
    ): Result {
        require(idleTimeoutMillis > 0) { "The process idle timeout must be positive" }
        val lastProgress = AtomicLong(System.nanoTime())
        var output: Future<*>? = null
        var errors: Future<*>? = null
        val pollMillis = (idleTimeoutMillis / 4).coerceIn(10, 250)
        try {
            output = readers.submit {
                readBoundedLines(process.inputStream) { line ->
                    if (onLine(line)) lastProgress.set(System.nanoTime())
                }
            }
            errors = readers.submit { readBoundedLines(process.errorStream, onErrorLine) }
            while (true) {
                ensureActive()
                if (process.waitFor(pollMillis, TimeUnit.MILLISECONDS)) break
                ensureActive()
                if (System.nanoTime() - lastProgress.get() >= TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis)) {
                    stopAndWait(process)
                    return Result(null, true)
                }
            }
            runCatching { output?.get(1, TimeUnit.SECONDS) }
            runCatching { errors?.get(1, TimeUnit.SECONDS) }
            ensureActive()
            return Result(process.exitValue(), false)
        } catch (error: Exception) {
            try { stopAndWait(process) } catch (stopError: Exception) { error.addSuppressed(stopError) }
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        } finally {
            output?.cancel(true)
            errors?.cancel(true)
            if (!process.isAlive) {
                forget(process)
                closeInBackground(process)
            }
        }
    }

    /** Do not allow rollback or another destructive operation to race a child that is still alive. */
    fun stopAndWait(process: Process, timeoutMillis: Long = 5_000) {
        require(timeoutMillis > 0) { "The process termination timeout must be positive" }
        var interrupted = Thread.interrupted()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            if (process.isAlive) process.destroyForcibly()
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "An owned process has not exited; its files must be preserved" }
                try { process.waitFor(remaining, TimeUnit.NANOSECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            forget(process)
            closeInBackground(process)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun closeHandles(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun closeInBackground(process: Process) {
        runCatching { readers.execute { closeHandles(process) } }.onFailure {
            Thread({ closeHandles(process) }, "SillyClient-close-output").apply { isDaemon = true }.start()
        }
    }

    private fun readBoundedLines(input: InputStream, onLine: (String) -> Unit) {
        fun emit(line: String) { runCatching { onLine(line) } }
        runCatching {
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(2048)
                val line = StringBuilder()
                var count: Int
                while (reader.read(buffer).also { count = it } >= 0) {
                    for (index in 0 until count) {
                        val char = buffer[index]
                        if (char == '\n' || line.length >= 16_384) {
                            emit(line.toString().trimEnd('\r'))
                            line.setLength(0)
                        }
                        if (char != '\n') line.append(char)
                    }
                }
                if (line.isNotEmpty()) emit(line.toString())
            }
        }
    }

    override fun close() {
        closed = true
        stopAllAsync()
        termination.submit { readers.shutdown() }
        termination.shutdown()
    }
}
