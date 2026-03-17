package com.pirate.app.tempo

import org.bouncycastle.jce.ECNamedCurveTable
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Purchase-only content decryption rooted in WebAuthn PRF.
 *
 * Key derivation exactly matches the canonical Web implementation in
 * apps/web/src/lib/music/content-crypto.ts. Conformance vectors are
 * in apps/web/tests/content-crypto.test.ts.
 *
 * Flow:
 *   rootSeed (32 B, from WebAuthn PRF)
 *     → HKDF-SHA256(salt=32 zeros, info=UTF-8("pirate-content-key-v1:${purchaseId}"))
 *     → purchaseSeed (32 B)
 *     → scalar = purchaseSeed mod P256_ORDER
 *     → P256 keypair (privateScalar, publicKey)
 *
 * The buyer's public key is sent to the server at purchase-confirm / re-envelope time.
 * At playback time the private scalar is used to ECIES-unwrap the DEK from the
 * Arweave envelope, then AES-256-GCM-decrypt the iv-prepended ciphertext artifact.
 */
object PurchaseContentCrypto {

    const val CONTENT_KEY_DERIVATION_LABEL = "pirate-content-key-v1"

    private val P256_CURVE_ORDER = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        16,
    )
    private const val GCM_TAG_BITS = 128
    private const val DEFAULT_IV_BYTES = 12

    data class PurchaseKeyPair(
        val privateScalarBytes: ByteArray,  // 32 bytes
        val publicKeyBytes: ByteArray,      // 65 bytes (0x04 || x || y)
    )

    data class PurchaseEnvelope(
        val ephemeralPub: ByteArray,  // 65 bytes (0x04 || x || y)
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    /**
     * HKDF-SHA256 per-purchase seed derivation.
     *
     * Salt is 32 zero bytes — Web Crypto spec §29.3.1 treats an empty-buffer salt
     * as HashLen (32) zero bytes, which matches RFC 5869 §2.2 "if not provided,
     * set to a string of HashLen zeros."
     */
    fun derivePurchaseSeed(rootSeed: ByteArray, purchaseId: String): ByteArray {
        require(rootSeed.size == 32) { "root_seed_invalid_length" }
        val trimmedId = purchaseId.trim()
        require(trimmedId.isNotEmpty()) { "purchase_id_required" }
        val info = "$CONTENT_KEY_DERIVATION_LABEL:$trimmedId".toByteArray(Charsets.UTF_8)

        // HKDF-Extract: PRK = HMAC-SHA256(salt=32_zeros, IKM=rootSeed)
        val prk = hmacSha256(ByteArray(32), rootSeed)

        // HKDF-Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        return mac.doFinal()
    }

    /** Derive P256 keypair from PRF rootSeed + purchaseId. */
    fun derivePurchaseKeyPair(rootSeed: ByteArray, purchaseId: String): PurchaseKeyPair {
        val seed = derivePurchaseSeed(rootSeed, purchaseId)
        val scalar = BigInteger(1, seed).mod(P256_CURVE_ORDER)
        check(scalar != BigInteger.ZERO) { "derived_scalar_zero" }
        val privateScalarBytes = padTo32(scalar.toByteArray())
        val publicKeyBytes = derivePublicKeyFromScalar(scalar)
        return PurchaseKeyPair(
            privateScalarBytes = privateScalarBytes,
            publicKeyBytes = publicKeyBytes,
        )
    }

    /**
     * Return the buyer content public key as an 0x-prefixed hex string, suitable
     * for sending to /purchase/confirm or /purchase/:id/re-envelope.
     */
    fun derivePurchasePublicKeyHex(rootSeed: ByteArray, purchaseId: String): String {
        val pair = derivePurchaseKeyPair(rootSeed, purchaseId)
        return "0x" + P256Utils.bytesToHex(pair.publicKeyBytes)
    }

    /**
     * ECIES-unwrap the DEK from an Arweave envelope.
     *
     * KDF: SHA-256(raw ECDH shared secret X-coordinate) → 32-byte AES key.
     * This matches EciesContentCrypto.deriveAesKey and the Web implementation.
     */
    fun unwrapDekFromEnvelope(privateScalarBytes: ByteArray, envelope: PurchaseEnvelope): ByteArray {
        require(privateScalarBytes.size == 32) { "private_scalar_invalid_length" }
        require(
            envelope.ephemeralPub.size == 65 && envelope.ephemeralPub[0] == 0x04.toByte(),
        ) { "envelope_ephemeral_pub_invalid" }

        val privKey = decodePrivateKey(privateScalarBytes)
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

    private fun derivePublicKeyFromScalar(scalar: BigInteger): ByteArray {
        // BouncyCastle EC point multiplication: Q = scalar * G
        val ecParams = ECNamedCurveTable.getParameterSpec("prime256v1")
        val q = ecParams.g.multiply(scalar).normalize()
        // getEncoded(false) → uncompressed: 0x04 || x(32) || y(32) = 65 bytes
        return q.getEncoded(false)
    }

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

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    internal fun padTo32(b: ByteArray): ByteArray = when {
        b.size == 32 -> b
        b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
        else -> ByteArray(32 - b.size) + b
    }
}
