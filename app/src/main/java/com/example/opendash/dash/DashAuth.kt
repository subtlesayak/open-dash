package com.example.opendash.dash

import com.example.opendash.dash.protocol.DashCommands
import com.example.opendash.dash.protocol.Tlv
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/** Result of feeding one incoming TLV into the auth state machine. */
sealed class AuthEvent {
    /** Both pubkey halves received — send this q3c.d packet now. */
    data class SendKey(val packet: ByteArray) : AuthEvent()
    /** Dash confirmed (07 01 01). */
    object Confirmed : AuthEvent()
    /** Dash rejected (07 01 != 01) — resend authRequest if retries remain. */
    object Rejected : AuthEvent()
    object None : AuthEvent()
}

/**
 * RSA-1024 + AES-256 handshake state machine, ported from better-dash AuthState.
 *
 * The dash sends modulus (07 00) and exponent (07 03) — possibly in SEPARATE
 * packets — so state accumulates across calls. The session-key packet is
 * emitted exactly once per attempt; [reset] re-arms it after a rejection.
 */
class DashAuth(private val ssid: String) {
    private var modulus: BigInteger? = null
    private var exponent: BigInteger? = null
    private var keySent = false

    var sessionKey: ByteArray? = null
        private set

    fun ingest(tlv: Tlv): AuthEvent {
        if (tlv.type != 0x07) return AuthEvent.None
        when (tlv.sub) {
            0x00 -> modulus  = BigInteger(1, tlv.value)
            0x03 -> exponent = BigInteger(1, tlv.value)
            0x01 -> return if (tlv.value.firstOrNull() == 0x01.toByte())
                AuthEvent.Confirmed else AuthEvent.Rejected
            else -> return AuthEvent.None
        }

        val m = modulus; val e = exponent
        if (!keySent && m != null && e != null) {
            keySent = true
            return AuthEvent.SendKey(buildKeyPacket(m, e))
        }
        return AuthEvent.None
    }

    /** Re-arm after a 07 01 != 01 rejection so the dash can re-offer its pubkey. */
    fun reset() {
        modulus = null
        exponent = null
        keySent = false
    }

    private fun buildKeyPacket(modulus: BigInteger, exponent: BigInteger): ByteArray {
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sessionKey = aes

        val payload = ssid.toByteArray(Charsets.UTF_8) + aes
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        return DashCommands.authSendKey(cipher.doFinal(payload))
    }
}
