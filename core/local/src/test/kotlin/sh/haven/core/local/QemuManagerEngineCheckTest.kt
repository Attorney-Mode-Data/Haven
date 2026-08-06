package sh.haven.core.local

import android.content.Context
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.core.local.proot.DistroCatalog
import java.io.File

/**
 * #506: a reporter got "VM didn't reach a login prompt in 420s" — 1.7 seconds
 * after starting — on three different flash drives. The VM was dying instantly
 * with `/bin/sh: 1: exec: qemu-system-x86_64: not found`, because the engine
 * check lived in `openDriveLocked` while `UsbDriveVmManager` calls
 * `ensureProvisionedAppliance` directly as its documented pre-step.
 *
 * So the property worth pinning is not "the check exists" — it did — but that
 * it runs on the path that actually launches QEMU.
 */
class QemuManagerEngineCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun stubLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun unstubLog() = unmockkStatic(Log::class)

    /**
     * The appliance is left already provisioned on purpose. That takes the
     * cheap early-return path — which is precisely the path that skipped the
     * engine check — and it keeps the test off the network, since the
     * re-provision branch would try to download a ~270 MB ISO.
     */
    private fun provisionedContext(): Context {
        val cache = tmp.newFolder("cache")
        File(cache, "haven-vm").mkdirs()
        File(cache, "haven-vm/${QemuManager.APPLIANCE_DISK}").writeText("not empty")
        File(cache, "haven-vm/${QemuManager.APPLIANCE_DISK}.ok").writeText("${QemuManager.APPLIANCE_PROVISION_VERSION}\n")
        return mockk<Context>(relaxed = true).also { every { it.cacheDir } returns cache }
    }

    @Test
    fun `provisioning refuses to boot when the guest has no QEMU`() {
        val proot = mockk<ProotManager>(relaxed = true)
        // Not installed, and the install does not fix that.
        coEvery { proot.runCommandInProot(any(), any()) } returns Pair("", 1)
        every { proot.activeDistro } returns DistroCatalog.lookup(DistroCatalog.DEFAULT_ID)!!
        every { proot.activeDistroId } returns DistroCatalog.DEFAULT_ID

        val error = runCatching {
            runBlocking { QemuManager(provisionedContext(), proot).ensureProvisionedAppliance {} }
        }.exceptionOrNull()

        assertTrue(
            "a guest with no QEMU must fail saying so, rather than booting a VM that dies " +
                "with 'exec: qemu-system-x86_64: not found' and gets reported as a boot " +
                "timeout — got $error",
            error is IllegalStateException && error.message.orEmpty().contains("Could not install"),
        )
    }
}
