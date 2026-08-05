package sh.haven.core.local

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #501 end-to-end: the scanner has to find a real process in /proc, not just
 * agree with a hand-written string.
 *
 * The bug it replaces was not a wrong match, it was **no match ever** — the
 * old filter looked for the display in `ps -A` output, which prints no
 * arguments. A unit test over literal command lines cannot tell those apart,
 * because both agree on what an Xvnc command line looks like. So this spawns
 * a process whose argv really is an Xvnc argv, runs the production scan
 * script against live /proc, and requires it to come back.
 *
 * `exec -a` sets argv[0], which is how the shape is produced without needing
 * TigerVNC installed on the machine running the tests.
 */
class XvncScanEndToEndTest {

    private val display = 77

    private fun scan(): List<ProcessCmdline> {
        val proc = ProcessBuilder("sh", "-c", CMDLINE_SCAN_SCRIPT).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else ProcessCmdline(line.substring(0, tab), line.substring(tab + 1).trim())
        }.filter { it.cmdline.isNotEmpty() }.toList()
    }

    @Test
    fun `the scan finds a live process whose argv is an Xvnc argv`() {
        assumeTrue("needs /proc", File("/proc/self/cmdline").exists())
        val argv0 = "/usr/bin/Xvnc :$display -geometry 1280x720 -depth 24 -rfbport 5977"
        val fake = ProcessBuilder("bash", "-c", "exec -a \"$argv0\" sleep 30").start()
        try {
            // Give the exec a moment to land before reading /proc.
            Thread.sleep(300)
            val rows = scan()
            assumeTrue("bash exec -a unavailable on this host", rows.any { it.cmdline.contains("Xvnc :$display") })

            val hits = rows.filter { xvncMatches(it.cmdline, display) }
            assertTrue("the production scan must find the Xvnc on :$display", hits.isNotEmpty())
            assertTrue(
                "found rows must really be that Xvnc: ${hits.map { it.cmdline }}",
                hits.all { it.cmdline.contains("Xvnc :$display") },
            )

            // A different display must not sweep it up.
            assertTrue(
                "display ${display + 1} must not match the Xvnc on :$display",
                rows.none { xvncMatches(it.cmdline, display + 1) && it.cmdline.contains("Xvnc :$display") },
            )
        } finally {
            fake.destroyForcibly()
            fake.waitFor()
        }
    }

    @Test
    fun `a shell whose command line merely mentions Xvnc is not matched`() {
        assumeTrue("needs /proc", File("/proc/self/cmdline").exists())
        // This hazard is one the move to /proc *introduced*, not one it
        // inherited: `ps -A` printed names only, so a shell that merely
        // mentions Xvnc never appeared as a candidate there. Reading full
        // command lines makes it one — a script, a log tailer, this test's own
        // shell. Anchoring on argv[0] is what stops it, and matching the whole
        // line instead fails this test against a real process.
        val talker = ProcessBuilder("sh", "-c", "echo 'starting Xvnc :$display'; sleep 30").start()
        try {
            Thread.sleep(300)
            val rows = scan()
            val mentions = rows.filter { it.cmdline.contains("Xvnc") && !it.cmdline.startsWith("/usr/bin/Xvnc") }
            assumeTrue("no mentioning process observed", mentions.isNotEmpty())
            mentions.forEach {
                assertFalse("must not be a kill candidate: ${it.cmdline.take(120)}", xvncMatches(it.cmdline, display))
                assertFalse("must not be a kill candidate: ${it.cmdline.take(120)}", xvncMatches(it.cmdline, null))
            }
        } finally {
            talker.destroyForcibly()
            talker.waitFor()
        }
    }
}
