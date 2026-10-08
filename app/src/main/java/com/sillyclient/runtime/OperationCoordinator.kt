package com.sillyclient.runtime

import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.CompletableFuture

/** One runtime mutation at a time; cancelled generations cannot publish or launch. */
class OperationCoordinator(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SillyClient-runtime").apply { isDaemon = true }
    }
) : AutoCloseable {
    class Operation internal constructor(val instanceId: String, val operationId: String) {
        internal var cancelled = false
        internal var pending = true
        internal var future: Future<*>? = null
        internal val cancellationActions = mutableSetOf<() -> Unit>()
    }

    private val lock = Any()
    private val context = ThreadLocal<Operation?>()
    private var active: Operation? = null
    private var closed = false

    fun begin(instanceId: String, operationId: String? = null): Operation = synchronized(lock) {
        check(!closed) { "Runtime is closed" }
        cancelLocked(active)
        Operation(instanceId, operationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
            .also { active = it }
    }

    fun beginIfIdle(instanceId: String, available: () -> Boolean): Operation = synchronized(lock) {
        check(!closed && active?.pending != true && active?.future?.isDone != false && available()) {
            "Stop the instance and wait for runtime tasks before maintenance"
        }
        begin(instanceId)
    }

    fun current(): Operation? = synchronized(lock) { active }
    fun hasPendingWork(): Boolean = synchronized(lock) {
        active?.pending == true || active?.future?.isDone == false
    }
    fun context(): Operation? = context.get()
    fun isCurrent(operation: Operation): Boolean = synchronized(lock) {
        !closed && active === operation && !operation.cancelled
    }

    fun ensureCurrent(operation: Operation) {
        if (!isCurrent(operation) || Thread.currentThread().isInterrupted) {
            throw CancellationException("Operation cancelled")
        }
    }

    fun <T> commit(operation: Operation, action: () -> T): T = synchronized(lock) {
        ensureCurrent(operation)
        action()
    }

    fun execute(operation: Operation, action: () -> Unit) {
        synchronized(lock) {
            ensureCurrent(operation)
            operation.future = executor.submit {
                context.set(operation)
                try {
                    ensureCurrent(operation)
                    action()
                } finally {
                    context.remove()
                    synchronized(lock) { operation.pending = false }
                }
            }
        }
    }

    fun <T> run(operation: Operation, action: () -> T): T {
        val result = CompletableFuture<T>()
        onCancel(operation) { result.completeExceptionally(CancellationException("Operation cancelled")) }.use {
            execute(operation) {
                try { result.complete(action()) }
                catch (error: Throwable) { result.completeExceptionally(error) }
            }
            return try { result.get() }
            catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
        }
    }

    fun finish(operation: Operation) {
        synchronized(lock) {
            if (active === operation) {
                operation.pending = false
                operation.future = null
            }
        }
    }

    /** Register interruptible I/O or an owned process before starting to wait on it. */
    fun onCancel(operation: Operation, action: () -> Unit): AutoCloseable {
        synchronized(lock) {
            if (!isCurrent(operation)) {
                runCatching(action)
                throw CancellationException("Operation cancelled")
            }
            operation.cancellationActions.add(action)
        }
        return AutoCloseable { synchronized(lock) { operation.cancellationActions.remove(action) } }
    }

    fun cancel(instanceId: String? = null, operationId: String? = null): Boolean = synchronized(lock) {
        val operation = active ?: return instanceId == null && operationId == null
        if (instanceId != null && operation.instanceId != instanceId) return false
        if (operationId != null && operation.operationId != operationId) return false
        cancelLocked(operation)
        active = null
        true
    }

    private fun cancelLocked(operation: Operation?) {
        if (operation == null || operation.cancelled) return
        operation.cancelled = true
        operation.cancellationActions.toList().forEach { runCatching(it) }
        operation.cancellationActions.clear()
        operation.future?.cancel(true)
    }

    override fun close() {
        synchronized(lock) {
            cancelLocked(active)
            active = null
            closed = true
        }
        executor.shutdownNow()
    }
}
