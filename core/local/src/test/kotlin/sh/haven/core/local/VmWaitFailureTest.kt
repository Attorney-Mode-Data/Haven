package sh.haven.core.local

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #506: a GrapheneOS reporter was told their USB-drive VM "didn't reach a
 * login prompt in 420s" **1.7 seconds** after it started.
 *
 * `awaitMarker` returns false for two unrelated things — the VM died, or the
 * deadline passed — and every caller reported the deadline. So a VM that
 * crashed on startup was described as a slow boot, which is what the reporter
 * then went looking for. The VM's own output was in the serial buffer the
 * whole time and appeared in no message.
 *
 * These drive a real process, because "the process died" is a property of a
 * real process and a stub cannot be wrong about it in the same way.
 */
class VmWaitFailureTest {

    private fun instanceRunning(script: String): VmInstance =
        VmInstance().apply {
            start(script) { ProcessBuilder("sh", "-c", it).redirectErrorStream(true).start() }
        }

    @Test
    fun `a VM that dies is reported as dying, with its last output`() {
        val vm = instanceRunning("echo 'qemu: could not open disk'; exit 1")
        Thread.sleep(600) // let it exit and the reader drain
        assertTrue("must not claim the marker arrived", !vm.awaitMarker("login:", timeoutMs = 60_000))

        val msg = vm.describeWaitFailure("Appliance provisioning")
        assertTrue("says the VM exited: $msg", msg.contains("exited"))
        assertTrue("quotes the VM's own output: $msg", msg.contains("could not open disk"))
        assertTrue(
            "must not blame a timeout that never elapsed: $msg",
            !msg.contains("nothing matched"),
        )
    }

    @Test
    fun `a VM that is alive but silent is reported as a timeout`() {
        val vm = instanceRunning("echo booting; sleep 30")
        try {
            assertTrue(!vm.awaitMarker("login:", timeoutMs = 1_000))
            val msg = vm.describeWaitFailure("Appliance provisioning")
            assertTrue("says the deadline passed: $msg", msg.contains("nothing matched"))
            assertTrue("still quotes the output: $msg", msg.contains("booting"))
            assertTrue("must not claim the VM died: $msg", !msg.contains("exited"))
        } finally {
            vm.stop()
        }
    }

    /** The point of the change: these two must not read the same. */
    @Test
    fun `dying and timing out do not produce the same message`() {
        val dead = instanceRunning("echo bang; exit 1")
        Thread.sleep(600)
        dead.awaitMarker("login:", timeoutMs = 60_000)

        val slow = instanceRunning("echo bang; sleep 30")
        try {
            slow.awaitMarker("login:", timeoutMs = 1_000)
            assertNotEquals(
                dead.describeWaitFailure("Provisioning"),
                slow.describeWaitFailure("Provisioning"),
            )
        } finally {
            slow.stop()
        }
    }

    @Test
    fun `a silent VM still gets a message rather than an empty one`() {
        val vm = instanceRunning("sleep 30")
        try {
            vm.awaitMarker("login:", timeoutMs = 1_000)
            val msg = vm.describeWaitFailure("Appliance provisioning")
            assertTrue("no output is itself worth saying: $msg", msg.contains("no output"))
        } finally {
            vm.stop()
        }
    }
}
