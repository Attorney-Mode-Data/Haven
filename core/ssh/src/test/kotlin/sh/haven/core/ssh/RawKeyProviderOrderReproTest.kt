package sh.haven.core.ssh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyFactorySpi
import java.security.Key
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * #451 reproduced, rather than reasoned about.
 *
 * The affected device cannot be borrowed and my own resolves these lookups
 * correctly, so the failure is recreated here from its defining property: a
 * provider named AndroidKeyStore that wins an unpinned X25519 KeyFactory lookup
 * and then refuses to build a public key from an X509 spec — exactly what
 * AndroidKeyStoreKeyFactorySpi does, with the same exception type and message.
 *
 * This exercises the REAL [RawKeyProviderOrder.apply], including the
 * Security.removeProvider/addProvider reordering that the fake-injected tests
 * in RawKeyProviderOrderTest deliberately stub out.
 */
class RawKeyProviderOrderReproTest {

    /** Stands in for AndroidKeyStore: registered for X25519, useless for raw keys. */
    private class KeystoreLikeProvider : Provider("AndroidKeyStore", 1.0, "#451 repro") {
        init {
            put("KeyFactory.X25519", RefusingKeyFactory::class.java.name)
        }
    }

    class RefusingKeyFactory : KeyFactorySpi() {
        override fun engineGeneratePublic(keySpec: KeySpec?): PublicKey =
            throw InvalidKeySpecException(
                "To generate a key pair in Android Keystore, use KeyPairGenerator " +
                    "initialized with android.security.keystore.KeyGenParameterSpec",
            )
        override fun engineGeneratePrivate(keySpec: KeySpec?): PrivateKey =
            throw InvalidKeySpecException("keystore only")
        override fun <T : KeySpec?> engineGetKeySpec(key: Key?, keySpec: Class<T>?): T =
            throw InvalidKeySpecException("keystore only")
        override fun engineTranslateKey(key: Key?): Key = throw InvalidKeySpecException("keystore only")
    }

    private val fake = KeystoreLikeProvider()

    @Before
    fun installAhead() {
        Security.insertProviderAt(fake, 1)
    }

    @After
    fun cleanUp() {
        Security.removeProvider(fake.name)
    }

    /** First: prove the arrangement really does reproduce the reported failure. */
    @Test
    fun `the keystore-like provider reproduces the reported failure`() {
        val kf = KeyFactory.getInstance("X25519")
        assertEquals("AndroidKeyStore", kf.provider.name)

        val thrown = assertThrows(InvalidKeySpecException::class.java) {
            kf.generatePublic(X509EncodedKeySpec(ByteArray(44)))
        }
        assertTrue(
            "should fail the way #451 does: ${thrown.message}",
            thrown.message!!.contains("Android Keystore"),
        )
    }

    /** Then: the real apply() rescues it, reordering live JCE providers. */
    @Test
    fun `apply demotes the keystore so X25519 resolves to a working provider`() {
        assertEquals("AndroidKeyStore", KeyFactory.getInstance("X25519").provider.name)

        val trigger = RawKeyProviderOrder.apply()
        assertEquals("X25519", trigger)

        val after = KeyFactory.getInstance("X25519")
        assertNotEquals(
            "AndroidKeyStore must no longer win the unpinned lookup",
            "AndroidKeyStore",
            after.provider.name,
        )
        // And the provider it now picks can actually do the job sshlib needs.
        assertThrows(InvalidKeySpecException::class.java) {
            // Garbage bytes still fail, but with a DECODING error from a real
            // implementation rather than the keystore's blanket refusal.
            after.generatePublic(X509EncodedKeySpec(ByteArray(44)))
        }.also {
            assertTrue(
                "expected a real decode failure, got: ${it.message}",
                it.message == null || !it.message!!.contains("Android Keystore"),
            )
        }
    }

    /** The demoted provider stays reachable by name — Haven's own keys depend on that. */
    @Test
    fun `the keystore is still resolvable by name after demotion`() {
        RawKeyProviderOrder.apply()
        assertEquals("AndroidKeyStore", Security.getProvider("AndroidKeyStore")?.name)
        assertEquals(
            "AndroidKeyStore",
            KeyFactory.getInstance("X25519", "AndroidKeyStore").provider.name,
        )
    }
}
