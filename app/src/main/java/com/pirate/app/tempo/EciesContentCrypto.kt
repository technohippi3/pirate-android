package com.pirate.app.tempo

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side ECIES content encryption using P256 + AES-256-GCM.
 *
 * Flow:
 *  1. Encrypt file with random AES-256-GCM key.
 *  2. Wrap AES key to owner's P256 public key via ECIES.
 *  3. Share: decrypt AES key → re-encrypt to recipient's P256 public key.
 *  4. Recipient: ECIES-decrypt AES key → AES-decrypt file.
 *
 * No external key network dependency. No server-side decryption. Pure local crypto.
 */
object EciesContentCrypto {

    private const val AES_KEY_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    // -- Data classes --

    /** ECIES-encrypted envelope: ephemeral public key + AES-GCM encrypted payload. */
    data class EciesEnvelope(
        val ephemeralPub: ByteArray, // 65 bytes: 0x04 || x(32) || y(32)
        val iv: ByteArray,           // 12 bytes
        val ciphertext: ByteArray,   // AES-GCM ciphertext (includes 16-byte auth tag)
    )

    /** AES-GCM encrypted file with the raw key (caller wraps via ECIES). */
    data class EncryptedFile(
        val iv: ByteArray,
        val ciphertext: ByteArray,
        val rawKey: ByteArray, // 32-byte AES key — caller must ECIES-wrap this
    )

    // -- File encryption (AES-256-GCM) --

    /** Encrypt file data with a fresh random AES-256-GCM key. */
    fun encryptFile(data: ByteArray): EncryptedFile {
        val rawKey = ByteArray(AES_KEY_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(data)
        return EncryptedFile(iv = iv, ciphertext = ct, rawKey = rawKey)
    }

    /** Decrypt file with a known AES key. */
    fun decryptFile(rawKey: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    // -- ECIES (P256 ECDH + SHA-256 KDF + AES-256-GCM) --

    /**
     * ECIES-encrypt [plaintext] to [recipientPub] (65-byte uncompressed P256 public key).
     *
     * 1. Generate ephemeral P256 keypair.
     * 2. ECDH shared secret with recipient's public key.
     * 3. KDF: SHA-256(shared point, skip 0x04 prefix) → 32-byte AES key.
     * 4. AES-256-GCM encrypt plaintext.
     */
    fun eciesEncrypt(recipientPub: ByteArray, plaintext: ByteArray): EciesEnvelope {
        // Generate ephemeral P256 keypair
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        val ephKp = kpg.generateKeyPair()
        val ephPub = encodeUncompressed(ephKp.public as ECPublicKey)

        // ECDH → shared secret → KDF
        val recipientKey = decodePublicKey(recipientPub)
        val aesKey = deriveAesKey(ephKp.private as ECPrivateKey, recipientKey)

        // AES-GCM encrypt
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)

        return EciesEnvelope(ephemeralPub = ephPub, iv = iv, ciphertext = ct)
    }

    /**
     * ECIES-decrypt [envelope] with [recipientPriv] (32-byte P256 private key scalar).
     */
    fun eciesDecrypt(recipientPriv: ByteArray, envelope: EciesEnvelope): ByteArray {
        val ephPubKey = decodePublicKey(envelope.ephemeralPub)
        val privKey = decodePrivateKey(recipientPriv)
        val aesKey = deriveAesKey(privKey, ephPubKey)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
        return cipher.doFinal(envelope.ciphertext)
    }

    // -- Key helpers --

    /** Generate a fresh P256 keypair. Returns (privateKey: 32 bytes, publicKey: 65 bytes uncompressed). */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = kpg.generateKeyPair()
        val priv = (kp.private as ECPrivateKey).s.toByteArray().let { padTo32(it) }
        val pub = encodeUncompressed(kp.public as ECPublicKey)
        return Pair(priv, pub)
    }

    // -- Internal --

    private fun deriveAesKey(privateKey: ECPrivateKey, publicKey: ECPublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        val shared = ka.generateSecret()
        // KDF: SHA-256 of raw shared secret
        return MessageDigest.getInstance("SHA-256").digest(shared)
    }

    private fun encodeUncompressed(pub: ECPublicKey): ByteArray {
        val x = padTo32(pub.w.affineX.toByteArray())
        val y = padTo32(pub.w.affineY.toByteArray())
        return byteArrayOf(0x04) + x + y
    }

    private fun decodePublicKey(uncompressed: ByteArray): ECPublicKey {
        require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) {
            "expected 65-byte uncompressed P256 public key (0x04 || x || y)"
        }
        val x = java.math.BigInteger(1, uncompressed.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, uncompressed.copyOfRange(33, 65))
        val kf = KeyFactory.getInstance("EC")
        val params = java.security.AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val spec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        return kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    private fun decodePrivateKey(raw: ByteArray): ECPrivateKey {
        val s = java.math.BigInteger(1, raw)
        val kf = KeyFactory.getInstance("EC")
        val params = java.security.AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val spec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        return kf.generatePrivate(java.security.spec.ECPrivateKeySpec(s, spec)) as ECPrivateKey
    }

    private fun padTo32(b: ByteArray): ByteArray {
        // BigInteger.toByteArray() may be 33 bytes (leading 0x00) or shorter
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }
}
