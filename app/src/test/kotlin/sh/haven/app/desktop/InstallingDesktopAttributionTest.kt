package sh.haven.app.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.local.ProotManager

/**
 * #502: "when I click one desktop's button, the other buttons spin too".
 *
 * They did, because the screen could only ask whether *something* was
 * installing and handed every row the same answer. The fix is that the state
 * names the desktop it belongs to, so what matters is that exactly one row can
 * ever say yes — asserted across the whole catalogue rather than for one pair,
 * since the complaint was about the other rows, not the one being installed.
 */
class InstallingDesktopAttributionTest {

    private val catalogue = ProotManager.DesktopEnvironment.entries

    @Test
    fun `only the desktop being installed reports busy`() {
        val target = catalogue.first()
        val state = ProotManager.DesktopSetupState.Installing("Installing…", target)

        val busy = catalogue.filter { isInstallingThisDesktop(state, it) }

        assertEquals("exactly one row may show a spinner", listOf(target), busy)
    }

    @Test
    fun `every desktop in the catalogue can be attributed`() {
        catalogue.forEach { de ->
            val state = ProotManager.DesktopSetupState.Installing("Configuring VNC...", de)
            assertTrue("$de should own its own install", isInstallingThisDesktop(state, de))
        }
    }

    @Test
    fun `an add-on install belongs to no desktop`() {
        // installAddons has no DE in scope and passes none; no row should spin.
        val state = ProotManager.DesktopSetupState.Installing("Installing desktop features...")
        assertTrue(catalogue.none { isInstallingThisDesktop(state, it) })
    }

    @Test
    fun `no desktop is busy when nothing is installing`() {
        listOf(
            ProotManager.DesktopSetupState.Idle,
            ProotManager.DesktopSetupState.Complete,
            null,
        ).forEach { state ->
            assertFalse(
                "$state should leave every row alone",
                catalogue.any { isInstallingThisDesktop(state, it) },
            )
        }
    }
}
