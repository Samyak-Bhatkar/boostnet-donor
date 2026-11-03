package com.example

import com.example.crypto.CryptoManager
import com.example.model.DonorState
import com.example.network.CapabilityGuard
import com.example.network.SsrfFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ExampleUnitTest {

    @Test
    fun testCryptoKeySeparation() {
        val masterSecret = "TEST_SECRET_KEY_FOR_BOOSTNET_NODE_32_BYTES"

        val socksKey = CryptoManager.getSocksAuthKey(masterSecret)
        val discoveryKey = CryptoManager.getDiscoveryKey(masterSecret)
        val controlKey = CryptoManager.getControlKey(masterSecret)

        assertEquals(32, socksKey.size)
        assertEquals(32, discoveryKey.size)
        assertEquals(32, controlKey.size)

        // Ensure domain separation creates distinct keys from the same master secret
        assertFalse(socksKey.contentEquals(discoveryKey))
        assertFalse(socksKey.contentEquals(controlKey))
        assertFalse(discoveryKey.contentEquals(controlKey))

        // Deterministic derivation test
        val socksKey2 = CryptoManager.getSocksAuthKey(masterSecret)
        assertTrue(socksKey.contentEquals(socksKey2))
    }

    @Test
    fun testConstantTimeComparison() {
        assertTrue(CryptoManager.constantTimeStringEquals("secret123", "secret123"))
        assertFalse(CryptoManager.constantTimeStringEquals("secret123", "secret124"))
        assertFalse(CryptoManager.constantTimeStringEquals("secret123", "secret12"))
    }

    @Test
    fun testPairingCodeGeneration() {
        val secret1 = "SECRET_ALPHA"
        val code1 = CryptoManager.getPairingCode(secret1)
        assertEquals(6, code1.length)

        val secret2 = "SECRET_BETA"
        val code2 = CryptoManager.getPairingCode(secret2)
        assertEquals(6, code2.length)
        assertNotEquals(code1, code2)
    }

    @Test
    fun testSsrfFilterRejections() {
        // Loopback
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("::1")))

        // RFC 1918 Private ranges
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("10.0.0.1")))
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("172.16.1.1")))
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("192.168.43.1")))

        // Link-local
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("169.254.169.254")))

        // CGNAT (100.64.0.0/10)
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(SsrfFilter.isSafePublicAddress(InetAddress.getByName("100.127.255.255")))

        // NAT64 de-encapsulation (64:ff9b::192.168.1.1)
        val nat64PrivateBytes = byteArrayOf(
            0x00, 0x64, 0xff.toByte(), 0x9b.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0,
            192.toByte(), 168.toByte(), 1, 1
        )
        val nat64Private = InetAddress.getByAddress(nat64PrivateBytes)
        assertFalse("NAT64 embedded private IPv4 must be blocked", SsrfFilter.isSafePublicAddress(nat64Private))

        // Public IPv4 must be allowed
        assertTrue(SsrfFilter.isSafePublicAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(SsrfFilter.isSafePublicAddress(InetAddress.getByName("8.8.8.8")))

        // NAT64 public (64:ff9b::1.1.1.1) must be allowed
        val nat64PublicBytes = byteArrayOf(
            0x00, 0x64, 0xff.toByte(), 0x9b.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1
        )
        val nat64Public = InetAddress.getByAddress(nat64PublicBytes)
        assertTrue("NAT64 embedded public IPv4 must be allowed", SsrfFilter.isSafePublicAddress(nat64Public))
    }
}
