package com.sillyclient.runtime

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProcessSupervisorIdleTest {
    private fun node(): String {
        val executable = System.getenv("SILLYCLIENT_CONFIGURATION_NODE")?.let(::File)
        assumeTrue("A local Node executable is required for controlled child process tests", executable?.isFile == true)
        return requireNotNull(executable).absolutePath
    }

    private fun withProcess(script: String, test: (ProcessSupervisor, Process) -> Unit) {
        val command = listOf(node(), "-e", script)
        OperationCoordinator().use { operations ->
            ProcessSupervisor(operations).use { supervisor ->
                val process = supervisor.launch(ProcessBuilder(command), "controlled-test", null, supervisor.generation())
                try { test(supervisor, process) } finally { supervisor.stopAndWait(process) }
            }
        }
    }

    @Test
    fun usefulOutputAllowsWorkToOutliveAnEquivalentTotalTimeout() = withProcess(
        "let count=0; const timer=setInterval(()=>{console.log('progress'); if(++count===24)clearInterval(timer);},100);"
    ) { supervisor, process ->
        val lines = AtomicInteger()
        val start = System.nanoTime()
        val result = supervisor.waitForIdle(process, 1_000) { lines.incrementAndGet(); true }
        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals(24, lines.get())
        assertTrue("The test must outlive the old equivalent total deadline", System.nanoTime() - start > 2_000_000_000L)
        assertFalse(process.isAlive)
    }

    @Test
    fun silentTimeoutConfirmsExitBeforeReturning() = withProcess("setInterval(()=>{},1000);") { supervisor, process ->
        val result = supervisor.waitForIdle(process, 150) { true }
        assertTrue(result.timedOut)
        assertFalse(process.isAlive)
        assertFalse(supervisor.hasProcesses())
    }

    @Test
    fun irrelevantOutputDoesNotRenewTheDeadline() = withProcess(
        "setInterval(()=>console.log('not progress'),20);"
    ) { supervisor, process ->
        val result = supervisor.waitForIdle(process, 400) { false }
        assertTrue(result.timedOut)
        assertFalse(process.isAlive)
    }

    @Test
    fun cancellationConfirmsExitBeforePropagating() = withProcess(
        "setInterval(()=>console.log('progress'),20);"
    ) { supervisor, process ->
        val lines = AtomicInteger()
        assertThrows(CancellationException::class.java) {
            supervisor.waitForIdle(process, 2_000, ensureActive = {
                if (lines.get() > 0) throw CancellationException("Synthetic cancellation")
            }) { lines.incrementAndGet(); true }
        }
        assertFalse(process.isAlive)
        assertFalse(supervisor.hasProcesses())
    }

    @Test
    fun interruptionIsRestoredOnlyAfterTheChildHasExited() = withProcess(
        "setInterval(()=>{},1000);"
    ) { supervisor, process ->
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { supervisor.waitForIdle(process, 2_000) { true } }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(process.isAlive)
        } finally { Thread.interrupted() }
    }

    @Test
    fun stderrIsDrainedButDoesNotCountAsUsefulProgress() = withProcess(
        "setInterval(()=>process.stderr.write('error detail\\n'.repeat(1000)),20);"
    ) { supervisor, process ->
        val errors = AtomicInteger()
        val result = supervisor.waitForIdle(process, 1_000, onErrorLine = { errors.incrementAndGet() }) { true }
        assertTrue(result.timedOut)
        assertTrue(errors.get() > 0)
        assertFalse(process.isAlive)
    }

    @Test
    fun totalTimeoutAlsoConfirmsExitBeforeAllowingRollback() = withProcess("setInterval(()=>{},1000);") { supervisor, process ->
        assertTrue(supervisor.hasProcesses("controlled-test"))
        assertFalse(supervisor.hasProcesses("another-instance"))
        assertTrue(supervisor.waitFor(process, 100).timedOut)
        assertFalse(process.isAlive)
        assertFalse(supervisor.hasProcesses("controlled-test"))
    }

    @Test
    fun totalWaitInterruptionStopsTheChildBeforePropagating() = withProcess("setInterval(()=>{},1000);") { supervisor, process ->
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { supervisor.waitFor(process, 2_000) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(process.isAlive)
        } finally { Thread.interrupted() }
    }
}
