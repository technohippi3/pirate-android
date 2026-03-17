package com.pirate.app.tempo

import android.util.Base64
import com.upokecenter.cbor.CBORObject
import org.bouncycastle.jcajce.provider.digest.Keccak
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

object P256Utils {

    // NIST P-256 group order.
    private val P256_ORDER = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        16
    )
    private val P256_HALF_ORDER = P256_ORDER.shiftRight(1)

    data class P256PublicKey(
        val x: ByteArray,  // 32 bytes
        val y: ByteArray,  // 32 bytes
    ) {
        val xHex get() = bytesToHex(x)
        val yHex get() = bytesToHex(y)
    }

    /** Derive Tempo account address from P256 public key: keccak256(x || y)[12:] */
    fun deriveAddress(pubKey: P256PublicKey): String {
        val input = pubKey.x + pubKey.y
        val hash = Keccak.Digest256().digest(input)
        return "0x" + bytesToHex(hash.copyOfRange(12, 32))
    }

    /** Extract P256 (x, y) coordinates from CBOR COSE key bytes. */
    fun extractP256CoordsFromCose(coseKeyBytes: ByteArray): P256PublicKey {
        val cose = CBORObject.DecodeFromBytes(coseKeyBytes)
        // COSE key: -2 = x coordinate, -3 = y coordinate
        val x = cose[-2]?.GetByteString()
            ?: throw IllegalArgumentException("COSE key missing x coordinate (-2)")
        val y = cose[-3]?.GetByteString()
            ?: throw IllegalArgumentException("COSE key missing y coordinate (-3)")
        require(x.size == 32) { "P256 x coordinate must be 32 bytes, got ${x.size}" }
        require(y.size == 32) { "P256 y coordinate must be 32 bytes, got ${y.size}" }
        return P256PublicKey(x, y)
    }

    /** Extract COSE public key bytes from WebAuthn registration authData. */
    fun extractCoseKeyFromAuthData(authData: ByteArray): ByteArray {
        require(authData.size >= 55) { "authData too short" }
        val flags = authData[32].toInt() and 0xFF
        require((flags and 0x40) != 0) { "attested credential data flag not set" }

        var offset = 37  // rpIdHash(32) + flags(1) + signCount(4)
        offset += 16     // aaguid
        require(authData.size >= offset + 2) { "authData missing credentialId length" }

        val credIdLen = ((authData[offset].toInt() and 0xFF) shl 8) or
            (authData[offset + 1].toInt() and 0xFF)
        offset += 2 + credIdLen
        require(authData.size > offset) { "authData missing CBOR public key" }

        val remaining = authData.copyOfRange(offset, authData.size)
        val stream = ByteArrayInputStream(remaining)
        CBORObject.Read(stream) ?: throw IllegalArgumentException("failed to parse CBOR key")
        val consumed = remaining.size - stream.available()
        return remaining.copyOfRange(0, consumed)
    }

    /** Extract P256 public key from registration attestationObject. */
    fun extractP256KeyFromRegistration(attestationObjectB64: String): P256PublicKey {
        val attestationBytes = base64UrlToBytes(attestationObjectB64)
        val attestation = CBORObject.DecodeFromBytes(attestationBytes)
        val authData = attestation["authData"]?.GetByteString()
            ?: throw IllegalArgumentException("missing authData")
        val coseKey = extractCoseKeyFromAuthData(authData)
        return extractP256CoordsFromCose(coseKey)
    }

    /** Parse DER-encoded ECDSA signature into (r, s) each 32 bytes, zero-padded. */
    fun parseDerSignature(derBytes: ByteArray): Pair<ByteArray, ByteArray> {
        // DER: 0x30 <len> 0x02 <rLen> <r> 0x02 <sLen> <s>
        require(derBytes.size >= 8 && derBytes[0] == 0x30.toByte()) { "not a DER signature" }
        var offset = 2
        require(derBytes[offset] == 0x02.toByte()) { "expected INTEGER tag for r" }
        offset++
        val rLen = derBytes[offset].toInt() and 0xFF
        offset++
        val rRaw = derBytes.copyOfRange(offset, offset + rLen)
        offset += rLen
        require(derBytes[offset] == 0x02.toByte()) { "expected INTEGER tag for s" }
        offset++
        val sLen = derBytes[offset].toInt() and 0xFF
        offset++
        val sRaw = derBytes.copyOfRange(offset, offset + sLen)

        val r = padTo32(stripLeadingZeros(rRaw))
        val s = normalizeP256LowS(padTo32(stripLeadingZeros(sRaw)))
        return Pair(r, s)
    }

    /**
     * Verify WebAuthn assertion signature:
     * signature over authenticatorData || SHA-256(clientDataJSON), using ES256.
     */
    fun verifyAssertionSignature(
        pubKey: P256PublicKey,
        authenticatorData: ByteArray,
        clientDataJSON: ByteArray,
        signatureDer: ByteArray,
    ): Boolean {
        return runCatching {
            val params = AlgorithmParameters.getInstance("EC")
            params.init(ECGenParameterSpec("secp256r1"))
            val ecParams = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, pubKey.x), BigInteger(1, pubKey.y))
            val keySpec = ECPublicKeySpec(point, ecParams)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(keySpec)

            val clientHash = MessageDigest.getInstance("SHA-256").digest(clientDataJSON)
            val signedBytes = authenticatorData + clientHash
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(signedBytes)
            verifier.verify(signatureDer)
        }.getOrDefault(false)
    }

    private fun normalizeP256LowS(s: ByteArray): ByteArray {
        val sValue = BigInteger(1, s)
        val normalized = if (sValue > P256_HALF_ORDER) P256_ORDER.subtract(sValue) else sValue
        return padTo32(stripLeadingZeros(normalized.toByteArray()))
    }

    private fun stripLeadingZeros(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size - 1 && b[i] == 0.toByte()) i++
        return b.copyOfRange(i, b.size)
    }

    private fun padTo32(b: ByteArray): ByteArray {
        if (b.size >= 32) return b.copyOfRange(b.size - 32, b.size)
        return ByteArray(32 - b.size) + b
    }

    // -- encoding helpers --

    fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").removePrefix("0X")
        require(h.length % 2 == 0) { "odd hex length" }
        return ByteArray(h.length / 2) { i ->
            h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun toBase64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
            .replace('+', '-').replace('/', '_').trimEnd('=')

    fun base64UrlToBytes(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }
}
