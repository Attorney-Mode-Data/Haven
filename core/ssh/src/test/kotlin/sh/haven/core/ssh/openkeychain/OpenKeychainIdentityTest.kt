package sh.haven.core.ssh.openkeychain

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What Haven hands JSch for a key held in another app (#487).
 *
 * The one rule that matters here: the provider has already encoded a complete
 * SSH signature blob, so anything this class did to those bytes would corrupt
 * them. That is the opposite of the FIDO path, where the raw signature has to
 * be wrapped by hand — hence a test that pins it.
 */
class OpenKeychainIdentityTest {

    private val keyData = OpenKeychainKeyData(
        providerPackage = "org.sufficientlysecure.keychain",
        keyId = "1234567890123456",
        algorithm = "ssh-rsa",
        publicKeyBlob = byteArrayOf(7, 7, 7),
        description = "ian@example.com",
    )

    private val client = mockk<OpenKeychainClient>()
    private val identity = OpenKeychainIdentity(keyData, client)

    @Test
    fun `the provider's signature is passed through untouched`() {
        val fromProvider = byteArrayOf(0, 0, 0, 7, 115, 115, 104, 45, 114, 115, 97, 9, 9)
        every { client.sign(any(), any(), any()) } returns fromProvider

        val signature = identity.getSignature(byteArrayOf(1, 2, 3), "rsa-sha2-512")

        assertArrayEquals(fromProvider, signature)
    }

    @Test
    fun `the algorithm the server negotiated reaches the provider`() {
        // Not cosmetic: for RSA this is what decides whether the provider
        // signs as rsa-sha2-512 or the legacy ssh-rsa a modern server refuses.
        val alg = slot<String>()
        every { client.sign(any(), any(), capture(alg)) } returns byteArrayOf(1)

        identity.getSignature(byteArrayOf(1), "rsa-sha2-256")

        assertEquals("rsa-sha2-256", alg.captured)
    }

    @Test
    fun `the challenge and key id are forwarded as given`() {
        val challenge = byteArrayOf(4, 5, 6, 7)
        every { client.sign(any(), any(), any()) } returns byteArrayOf(1)

        identity.getSignature(challenge, "ssh-rsa")

        verify { client.sign("1234567890123456", challenge, "ssh-rsa") }
    }

    @Test
    fun `the single-argument signature call uses the key's own algorithm`() {
        val alg = slot<String>()
        every { client.sign(any(), any(), capture(alg)) } returns byteArrayOf(1)

        identity.getSignature(byteArrayOf(1))

        assertEquals("ssh-rsa", alg.captured)
    }

    @Test
    fun `the identity offers the stored public key`() {
        assertArrayEquals(byteArrayOf(7, 7, 7), identity.publicKeyBlob)
        assertEquals("ssh-rsa", identity.algName)
    }

    @Test
    fun `the key is not reported as encrypted`() {
        // The provider deals with its own passphrase or PIN. Saying true here
        // would make JSch prompt for a passphrase Haven cannot use.
        assertFalse(identity.isEncrypted)
        assertEquals(true, identity.setPassphrase(null))
        assertEquals(true, identity.decrypt())
    }

    @Test
    fun `identities are named per key so two provider keys do not collide`() {
        val other = OpenKeychainIdentity(keyData.copy(keyId = "9999999999999999"), client)
        assertEquals("haven-openkeychain-1234567890123456", identity.name)
        assertEquals("haven-openkeychain-9999999999999999", other.name)
    }
}
