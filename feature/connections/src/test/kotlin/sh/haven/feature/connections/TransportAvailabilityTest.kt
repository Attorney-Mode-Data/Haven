package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #510 — the terminal build ships no RDP or SPICE client, so offering them
 * would create a profile that can only fail at connect with "native library
 * failed to load".
 */
class TransportAvailabilityTest {

    private val all = listOf(
        "SSH" to "SSH",
        "MOSH" to "Mosh",
        "LOCAL" to "Local Shell (PRoot)",
        "VNC" to "VNC (Desktop)",
        "RDP" to "RDP (Desktop)",
        "SPICE" to "SPICE (Desktop)",
        "SMB" to "SMB (File Share)",
    )

    private fun values(rdp: Boolean, spice: Boolean) =
        TransportAvailability.offered(all, rdp, spice).map { it.first }

    @Test
    fun `a full build offers everything`() {
        assertEquals(all.map { it.first }, values(rdp = true, spice = true))
    }

    @Test
    fun `a build without the native clients drops RDP and SPICE`() {
        val offered = values(rdp = false, spice = false)

        assertFalse("RDP", "RDP" in offered)
        assertFalse("SPICE", "SPICE" in offered)
    }

    /**
     * The gate must remove ONLY those two. Filtering by "is it a desktop
     * type" would take VNC with it, and VNC's client is Kotlin — it works in
     * every build, including against a guest desktop the terminal build can
     * still run over X11Vnc.
     */
    @Test
    fun `nothing else is affected, VNC included`() {
        val offered = values(rdp = false, spice = false)

        assertEquals(listOf("SSH", "MOSH", "LOCAL", "VNC", "SMB"), offered)
        assertTrue("VNC must survive", "VNC" in offered)
    }

    @Test
    fun `the two transports are gated independently`() {
        assertTrue("RDP" in values(rdp = true, spice = false))
        assertFalse("SPICE" in values(rdp = true, spice = false))
        assertFalse("RDP" in values(rdp = false, spice = true))
        assertTrue("SPICE" in values(rdp = false, spice = true))
    }

    /** Order is the caller's; filtering must not reshuffle the menu. */
    @Test
    fun `order is preserved`() {
        assertEquals(
            listOf("SSH", "MOSH", "LOCAL", "VNC", "RDP", "SPICE", "SMB"),
            values(rdp = true, spice = true),
        )
    }
}
