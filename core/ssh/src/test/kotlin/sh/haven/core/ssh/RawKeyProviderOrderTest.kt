package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #451: on some Android builds an unpinned `KeyFactory.getInstance("X25519")`
 * resolves to AndroidKeyStore, which can only ever return keys held in the
 * keystore — so sshlib's curve25519 key exchange dies converting the server's
 * ephemeral public key. Upstream: connectbot/cbssh#246.
 */
class RawKeyProviderOrderTest {

    @Test
    fun `AndroidKeyStore must not serve an unpinned raw-key lookup`() {
        assertTrue(RawKeyProviderOrder.shouldDemote("AndroidKeyStore"))
    }

    /** Every other provider is left alone — this must not reorder the JCE at large. */
    @Test
    fun `working providers are left where they are`() {
        assertFalse(RawKeyProviderOrder.shouldDemote("AndroidOpenSSL"))
        assertFalse(RawKeyProviderOrder.shouldDemote("BC"))
        assertFalse(RawKeyProviderOrder.shouldDemote("Conscrypt"))
        assertFalse(RawKeyProviderOrder.shouldDemote(null))
    }

    /** The affected device: demote once, naming the algorithm that proved it. */
    @Test
    fun `demotes when the keystore wins an X25519 lookup`() {
        val demoted = mutableListOf<String>()
        val trigger = RawKeyProviderOrder.applyWith(
            probe = { if (it == "X25519") "AndroidKeyStore" else "AndroidOpenSSL" },
            demote = { demoted += it; true },
        )
        assertEquals("X25519", trigger)
        assertEquals(listOf("AndroidKeyStore"), demoted)
    }

    /** Most devices: nothing is touched at all. */
    @Test
    fun `does nothing when the provider order is already sane`() {
        val demoted = mutableListOf<String>()
        val trigger = RawKeyProviderOrder.applyWith(
            probe = { "AndroidOpenSSL" },
            demote = { demoted += it; true },
        )
        assertNull(trigger)
        assertTrue("must not reorder providers on a healthy device", demoted.isEmpty())
    }

    /** An algorithm the device lacks must not be mistaken for a broken one. */
    @Test
    fun `an unavailable algorithm is not a reason to reorder`() {
        val demoted = mutableListOf<String>()
        assertNull(
            RawKeyProviderOrder.applyWith(
                probe = { null },
                demote = { demoted += it; true },
            ),
        )
        assertTrue(demoted.isEmpty())
    }

    /** A refused reorder is reported, not silently swallowed. */
    @Test
    fun `reports the trigger even when the reorder fails`() {
        val trigger = RawKeyProviderOrder.applyWith(
            probe = { "AndroidKeyStore" },
            demote = { false },
        )
        assertEquals("X25519", trigger)
    }
}
