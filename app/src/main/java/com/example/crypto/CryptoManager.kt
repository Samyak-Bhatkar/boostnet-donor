package com.example.crypto

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val PREFS_NAME = "boostnet_session"
    private const val KEY_SESSION_SECRET = "session_secret"
    private const val KEY_NODE_ID = "node_id"

    private val secureRandom = SecureRandom()

    fun getOrCreateNodeId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var nodeId = prefs.getString(KEY_NODE_ID, null)
        if (nodeId.isNullOrEmpty()) {
            val randomBytes = ByteArray(4)
            secureRandom.nextBytes(randomBytes)
            val suffix = randomBytes.joinToString("") { "%02x".format(it) }
            nodeId = "donor_${suffix}"
            prefs.edit().putString(KEY_NODE_ID, nodeId).apply()
        }
        return nodeId
    }

    fun getOrCreateSessionSecret(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var secret = prefs.getString(KEY_SESSION_SECRET, null)
        if (secret.isNullOrEmpty()) {
            secret = generateNewSecret(context)
        }
        return secret
    }

    fun generateNewSecret(context: Context): String {
        val secretBytes = ByteArray(32)
        secureRandom.nextBytes(secretBytes)
        val secret = Base64.encodeToString(secretBytes, Base64.NO_WRAP)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SESSION_SECRET, secret).apply()
        return secret
    }

    fun getPairingCode(secret: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(secret.toByteArray(StandardCharsets.UTF_8))
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Crockford-style Base32 subset (no ambiguous 0/O, 1/I)
        val code = StringBuilder()
        for (i in 0 until 6) {
            val index = (hash[i].toInt() and 0xFF) % alphabet.length
            code.append(alphabet[index])
        }
        return code.toString()
    }

    /**
     * HKDF-SHA256 Extract and Expand (RFC 5869)
     */
    fun deriveKey(masterSecret: String, infoLabel: String, lengthBytes: Int = 32): ByteArray {
        val ikm = masterSecret.toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(32) // zero salt per RFC 5869 if not specified

        // 1. HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
        val macExtract = Mac.getInstance("HmacSHA256")
        macExtract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = macExtract.doFinal(ikm)

        // 2. HKDF-Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
        val macExpand = Mac.getInstance("HmacSHA256")
        macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
        val infoBytes = infoLabel.toByteArray(StandardCharsets.UTF_8)
        val input = ByteArray(infoBytes.size + 1)
        System.arraycopy(infoBytes, 0, input, 0, infoBytes.size)
        input[infoBytes.size] = 0x01.toByte()

        val t1 = macExpand.doFinal(input)
        return if (lengthBytes <= t1.size) {
            t1.copyOf(lengthBytes)
        } else {
            t1
        }
    }

    fun getSocksAuthKey(masterSecret: String): ByteArray {
        return deriveKey(masterSecret, "boostnet-socks-auth-v1", 32)
    }

    fun getDiscoveryKey(masterSecret: String): ByteArray {
        return deriveKey(masterSecret, "boostnet-discovery-hmac-v1", 32)
    }

    fun getControlKey(masterSecret: String): ByteArray {
        return deriveKey(masterSecret, "boostnet-control-auth-v1", 32)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    fun constantTimeStringEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    fun hmacSha256(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val rawHmac = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return rawHmac.joinToString("") { "%02x".format(it) }
    }

    fun hmacSha256Bytes(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
