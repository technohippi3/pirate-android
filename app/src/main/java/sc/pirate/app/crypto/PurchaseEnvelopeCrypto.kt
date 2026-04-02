package sc.pirate.app.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts purchase envelopes and encrypted artifacts using the current device
 * content key. When a purchase was encrypted to an older key, the app asks the
 * API to re-envelope it to the current public key first, then retries locally.
 */
object PurchaseEnvelopeCrypto {

    private const val GCM_TAG_BITS = 128
    private const val DEFAULT_IV_BYTES = 12

    data class PurchaseEnvelope(
        val ephemeralPub: ByteArray,  // 65 bytes (0x04 || x || y)
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    /**
     * ECIES-unwrap the DEK from an Arweave envelope.
     *
     * KDF: SHA-256(raw ECDH shared secret X-coordinate) → 32-byte AES key.
     * This matches EciesContentCrypto.deriveAesKey and the Web implementation.
     */
    fun unwrapDekFromEnvelope(privateKeyBytes: ByteArray, envelope: PurchaseEnvelope): ByteArray {
        require(privateKeyBytes.size == 32) { "private_key_invalid_length" }
        require(
            envelope.ephemeralPub.size == 65 && envelope.ephemeralPub[0] == 0x04.toByte(),
        ) { "envelope_ephemeral_pub_invalid" }

        val privKey = decodePrivateKey(privateKeyBytes)
        val senderPub = decodePublicKey(envelope.ephemeralPub)

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privKey)
        ka.doPhase(senderPub, true)
        val shared = ka.generateSecret()
        val aesKey = MessageDigest.getInstance("SHA-256").digest(shared)

        return aesGcmDecrypt(aesKey, envelope.iv, envelope.ciphertext)
    }

    /**
     * Decrypt an iv-prepended AES-256-GCM artifact.
     * Format: first ivBytes are the IV; remainder is ciphertext (with 16-byte GCM auth tag).
     */
    fun decryptIvPrependedArtifact(
        dekBytes: ByteArray,
        ivPrependedBytes: ByteArray,
        ivBytes: Int = DEFAULT_IV_BYTES,
    ): ByteArray {
        require(dekBytes.size == 32) { "dek_invalid_length" }
        require(ivPrependedBytes.size > ivBytes) { "artifact_ciphertext_invalid" }
        val iv = ivPrependedBytes.copyOfRange(0, ivBytes)
        val ciphertext = ivPrependedBytes.copyOfRange(ivBytes, ivPrependedBytes.size)
        return aesGcmDecrypt(dekBytes, iv, ciphertext)
    }

    // -- Internal --

    private fun decodePublicKey(uncompressed: ByteArray): java.security.interfaces.ECPublicKey {
        require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) {
            "expected 65-byte uncompressed P256 key"
        }
        val x = BigInteger(1, uncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, uncompressed.copyOfRange(33, 65))
        val kf = KeyFactory.getInstance("EC")
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val spec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        return kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
            as java.security.interfaces.ECPublicKey
    }

    private fun decodePrivateKey(raw: ByteArray): java.security.interfaces.ECPrivateKey {
        val s = BigInteger(1, raw)
        val kf = KeyFactory.getInstance("EC")
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val spec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        return kf.generatePrivate(java.security.spec.ECPrivateKeySpec(s, spec))
            as java.security.interfaces.ECPrivateKey
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
