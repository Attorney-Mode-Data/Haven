package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #503: a desktop install sat at "Checking wayvnc version..." with nothing
 * running in the guest behind it, and there was no way out.
 *
 * These drive real processes rather than a fake, because the two ways the old
 * code could wait forever are both properties of real pipes and cannot be
 * reproduced against a stub: a command that never exits, and a command whose
 * *grandchild* keeps the output pipe open after the command itself is gone.
 * The second is the one that surprises people — the old code waited on the
 * pipe closing, not on the process exiting.
 */
class BoundedProcessReadTest {

    private fun sh(script: String): Process =
        ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()

    @Test
    fun `a normal command returns its output and exit code`() {
        val r = readProcessBounded(sh("echo hello; echo world"), timeoutMs = 10_000)
        assertFalse(r.timedOut)
        assertEquals(0, r.exitCode)
        assertEquals(listOf("hello", "world"), r.output.trim().lines())
    }

    @Test
    fun `a failing command reports its exit code, not a timeout`() {
        val r = readProcessBounded(sh("echo nope >&2; exit 3"), timeoutMs = 10_000)
        assertFalse("exit 3 is a failure, not a hang", r.timedOut)
        assertEquals(3, r.exitCode)
        assertTrue("stderr is merged in", r.output.contains("nope"))
    }

    @Test
    fun `a command that never exits is stopped at the bound`() {
        val started = System.nanoTime()
        val r = readProcessBounded(sh("echo starting; sleep 120"), timeoutMs = 1_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("must report that it gave up", r.timedOut)
        assertEquals("a killed command is not a successful one", -1, r.exitCode)
        assertTrue("output up to the hang is kept", r.output.contains("starting"))
        assertTrue("returned in ${elapsedMs}ms, so it did not wait out the sleep", elapsedMs < 30_000)
    }

    @Test
    fun `a finished command is not held open by a grandchild still owning the pipe`() {
        // The exact shape that wedged the install: the command exits promptly,
        // but a background descendant inherits the write end of the pipe. The
        // old readText()-then-waitFor() blocked here until the grandchild
        // ended — two minutes, or forever.
        val started = System.nanoTime()
        val r = readProcessBounded(sh("sleep 120 & echo done"), timeoutMs = 60_000, drainGraceMs = 1_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertFalse("the command itself exited normally", r.timedOut)
        assertEquals(0, r.exitCode)
        assertTrue("returned in ${elapsedMs}ms, not after the grandchild's 120s", elapsedMs < 30_000)
    }
}
