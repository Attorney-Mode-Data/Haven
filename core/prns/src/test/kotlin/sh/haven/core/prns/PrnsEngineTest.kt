package sh.haven.core.prns

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rs.reticulum.prns.SUPPLIED_PIPE_DECLINED

class PrnsEngineTest {

    /** Raw libc dial — the fd is born outside the JVM's Socket machinery, so
     * ownership can transfer to the engine cleanly (same shape as an Android
     * opener using android.system.Os.socket/connect). */
    private interface LibC : Library {
        fun socket(domain: Int, type: Int, protocol: Int): Int
        fun connect(fd: Int, address: Memory, length: Int): Int

        companion object {
            val INSTANCE: LibC = Native.load("c", LibC::class.java)
        }
    }

    private fun dialLoopback(port: Int): Int {
        val fd = LibC.INSTANCE.socket(2 /* AF_INET */, 1 /* SOCK_STREAM */, 0)
        if (fd < 0) return -1
        val address = Memory(16).apply {
            clear()
            setByte(0, 2 /* AF_INET */)
            setByte(2, ((port shr 8) and 0xff).toByte())
            setByte(3, (port and 0xff).toByte())
            setByte(4, 127); setByte(5, 0); setByte(6, 0); setByte(7, 1)
        }
        return if (LibC.INSTANCE.connect(fd, address, 16) == 0) fd else -1
    }

    /**
     * Pure-classpath sanity: proves the upstream JVM SDK compiled through the
     * included build and substituted into this module. Runs everywhere — no
     * native library needed.
     */
    @Test
    fun `sdk classes are on the classpath`() {
        assertEquals(-1, SUPPLIED_PIPE_DECLINED)
    }

    /**
     * Live engine round-trip: open a host, attach a supplied pipe whose
     * opener dials a local listener, and see the attachment succeed and the
     * opener actually pulled. Requires a host-built capsule:
     *
     *   cargo build --release   (in prns/prns-host/abi/c)
     *   ./gradlew :core:prns:testDebugUnitTest \
     *     -Dpersonal.rns.library=$PWD/prns/prns-host/abi/c/target/release/libprns_host.so
     *
     * Skipped (Assume) when the property is absent so CI without a host Rust
     * build stays green — the registered upstream suite covers this path too.
     */
    @Test
    fun `supplied pipe attaches and pulls the opener`() {
        val library = System.getProperty("personal.rns.library")
        assumeTrue(
            "set -Dpersonal.rns.library=<libprns_host.so> to run the live engine test",
            library != null && File(library).isFile,
        )

        runBlocking {
            ServerSocket(0).use { listener ->
                var opened = 0
                PrnsEngine(this).use { engine ->
                    engine.open()
                    assertTrue(engine.supportsSuppliedPipe())
                    val settlement = withTimeout(10_000) {
                        engine.attachSupplied("test-pipe", respawnDelayMillis = 250, opener = {
                            opened++
                            dialLoopback(listener.localPort)
                        })
                    }
                    assertTrue("attach failed: $settlement", settlement.succeeded())
                    withTimeout(5_000) {
                        while (opened == 0) delay(50)
                    }
                }
            }
        }
    }
}
