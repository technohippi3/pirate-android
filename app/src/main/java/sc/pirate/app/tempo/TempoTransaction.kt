package sc.pirate.app.tempo

import org.bouncycastle.jcajce.provider.digest.Keccak
import java.io.ByteArrayOutputStream

/**
 * Minimal Tempo transaction (type 0x76) builder.
 *
 * Tempo tx RLP:
 * 0x76 || rlp([
 *   chain_id, max_priority_fee_per_gas, max_fee_per_gas, gas_limit,
 *   calls, access_list, nonce_key, nonce, valid_before, valid_after,
 *   fee_token, fee_payer_signature, aa_authorization_list, key_authorization
 * ])
 *
 * Each call: [to, value, input]
 *
 * For signing, we compute keccak256(0x76 || rlp(unsigned_fields)).
 */
object TempoTransaction {

    const val FEE_PAYER_SENDER_HINT_MARKER_HEX = "feefeefeefee"

    /** Wrapper for values that are already full RLP items and must not be re-encoded. */
    private data class RawRlp(val encoded: ByteArray)

    enum class FeeMode {
        SELF,
        RELAY_SPONSORED,
    }

    data class Call(
        val to: ByteArray,     // 20 bytes
        val value: Long = 0,
        val input: ByteArray = ByteArray(0),
    )

    data class UnsignedTx(
        val chainId: Long = TempoClient.CHAIN_ID,
        val maxPriorityFeePerGas: Long = 1_000_000_000L, // 1 gwei
        val maxFeePerGas: Long = 2_000_000_000L,         // 2 gwei
        val gasLimit: Long = 100_000L,
        val calls: List<Call> = emptyList(),
        val nonceKey: Long = 0,
        val nonceKeyBytes: ByteArray? = null, // Optional raw uint256 for nonce_key (e.g., TIP-1009 max key)
        val nonce: Long = 0,
        val validBeforeSec: Long? = null,
        val validAfterSec: Long? = null,
        val feeMode: FeeMode = FeeMode.SELF,
        val feeToken: ByteArray? = P256Utils.hexToBytes(TempoClient.ALPHA_USD),
        val keyAuthorization: ByteArray? = null,  // SignedKeyAuthorization RLP bytes
    )

    /** Compute the signature hash for the unsigned transaction. */
    fun signatureHash(tx: UnsignedTx): ByteArray {
        val encoded = rlpEncodeList(signingFields(tx))
        val payload = ByteArray(1) { 0x76.toByte() } + encoded
        return Keccak.Digest256().digest(payload)
    }

    /** Encode the full signed transaction with WebAuthn signature. */
    fun encodeSignedWebAuthn(
        tx: UnsignedTx,
        assertion: TempoPasskeyManager.PasskeyAssertion,
    ): String {
        // WebAuthn signature: 0x02 || webauthn_data || r || s || pub_key_x || pub_key_y
        val webauthnData = assertion.authenticatorData + assertion.clientDataJSON
        val sigBytes = byteArrayOf(0x02) +
            webauthnData +
            assertion.signatureR +
            assertion.signatureS +
            assertion.pubKey.x +
            assertion.pubKey.y

        // Signed tx: 0x76 || rlp([...full_fields, sender_signature])
        val allFields = fullFields(tx).toMutableList()
        allFields.add(sigBytes)

        val rlpPayload = rlpEncodeList(allFields)
        return "0x76" + P256Utils.bytesToHex(rlpPayload)
    }

    /** Encode a signed transaction using a session key (Tempo V2 keychain signature). */
    fun encodeSignedSessionKey(
        tx: UnsignedTx,
        keychainSignature: ByteArray,  // type 0x04 || user_address || inner sig
    ): String {
        val allFields = fullFields(tx).toMutableList()
        allFields.add(keychainSignature)

        val rlpPayload = rlpEncodeList(allFields)
        return "0x76" + P256Utils.bytesToHex(rlpPayload)
    }

    /**
     * Encode the full signed transaction with secp256k1 signature bytes.
     * Tempo expects secp256k1 signatures in raw 65-byte form: r(32) || s(32) || v(1),
     * where v is yParity (0/1). There is no type prefix byte for secp256k1.
     */
    fun encodeSignedSecp256k1(
        tx: UnsignedTx,
        r: ByteArray,
        s: ByteArray,
        v: Byte,
    ): String {
        val sigBytes = buildSecp256k1Signature(r = r, s = s, v = v)
        val allFields = fullFields(tx).toMutableList()
        allFields.add(sigBytes)

        val rlpPayload = rlpEncodeList(allFields)
        return "0x76" + P256Utils.bytesToHex(rlpPayload)
    }

    /**
     * Appends sender hint bytes expected by `eth_signRawTransaction` fee payer relays.
     * Format: `<serialized_tx><sender_20_bytes><0xfeefeefeefee>`.
     */
    fun appendSenderHint(serializedTxHex: String, senderAddress: String): String {
        val txHex = serializedTxHex.removePrefix("0x").removePrefix("0X")
        val senderHex = senderAddress.trim().removePrefix("0x").removePrefix("0X").lowercase()
        require(senderHex.length == 40 && senderHex.all { it in "0123456789abcdef" }) {
            "invalid sender address"
        }
        return "0x${txHex}${senderHex}${FEE_PAYER_SENDER_HINT_MARKER_HEX}"
    }

    // -- internal --

    /**
     * Fields for signing hash — key_authorization is EXCLUDED,
     * fee_payer_signature is encoded as None (0x80) for self-pay txs.
     */
    private fun signingFields(tx: UnsignedTx): List<Any> {
        val callsList = tx.calls.map { call ->
            listOf(call.to, call.value, call.input)
        }

        val fields = mutableListOf<Any>(
            tx.chainId,                    // chain_id
            tx.maxPriorityFeePerGas,       // max_priority_fee_per_gas
            tx.maxFeePerGas,               // max_fee_per_gas
            tx.gasLimit,                   // gas_limit
            callsList,                     // calls
            emptyList<Any>(),              // access_list (empty)
            nonceKeyField(tx),             // nonce_key
            tx.nonce,                      // nonce
            validBeforeField(tx),          // valid_before
            validAfterField(tx),           // valid_after
            feeTokenField(tx),             // fee_token (empty for relay-sponsored sender signing)
            feePayerSignatureField(tx),    // fee_payer_signature (0x00 marker for relay-sponsored tx)
            emptyList<Any>(),              // aa_authorization_list (empty)
        )

        // key_authorization is part of the signing payload when present.
        if (tx.keyAuthorization != null) {
            fields.add(RawRlp(tx.keyAuthorization))
        }

        return fields
    }

    /**
     * Fields for the full encoded transaction — includes key_authorization
     * (only when present, completely omitted when null).
     */
    private fun fullFields(tx: UnsignedTx): List<Any> {
        val callsList = tx.calls.map { call ->
            listOf(call.to, call.value, call.input)
        }

        val fields = mutableListOf<Any>(
            tx.chainId,                    // chain_id
            tx.maxPriorityFeePerGas,       // max_priority_fee_per_gas
            tx.maxFeePerGas,               // max_fee_per_gas
            tx.gasLimit,                   // gas_limit
            callsList,                     // calls
            emptyList<Any>(),              // access_list (empty)
            nonceKeyField(tx),             // nonce_key
            tx.nonce,                      // nonce
            validBeforeField(tx),          // valid_before
            validAfterField(tx),           // valid_after
            feeTokenField(tx),             // fee_token
            feePayerSignatureField(tx),    // fee_payer_signature
            emptyList<Any>(),              // aa_authorization_list (empty)
        )

        // key_authorization: only include when present (no bytes when null)
        if (tx.keyAuthorization != null) {
            fields.add(RawRlp(tx.keyAuthorization))
        }

        return fields
    }

    private fun feeTokenField(tx: UnsignedTx): ByteArray {
        return when (tx.feeMode) {
            FeeMode.SELF -> tx.feeToken ?: ByteArray(0)
            FeeMode.RELAY_SPONSORED -> ByteArray(0)
        }
    }

    private fun feePayerSignatureField(tx: UnsignedTx): ByteArray {
        return when (tx.feeMode) {
            FeeMode.SELF -> ByteArray(0)
            FeeMode.RELAY_SPONSORED -> byteArrayOf(0x00)
        }
    }

    private fun nonceKeyField(tx: UnsignedTx): Any {
        val raw = tx.nonceKeyBytes
        if (raw == null) return tx.nonceKey
        require(raw.isNotEmpty()) { "nonceKeyBytes must not be empty" }
        require(raw.size <= 32) { "nonceKeyBytes must be <= 32 bytes" }
        if (raw.size > 1) {
            require(raw[0] != 0.toByte()) { "nonceKeyBytes must be minimal (no leading zero)" }
        }
        return raw
    }

    private fun validBeforeField(tx: UnsignedTx): Any = tx.validBeforeSec ?: ByteArray(0)

    private fun validAfterField(tx: UnsignedTx): Any = tx.validAfterSec ?: ByteArray(0)

    private fun buildSecp256k1Signature(
        r: ByteArray,
        s: ByteArray,
        v: Byte,
    ): ByteArray {
        val r32 = normalizeScalar32(r)
        val s32 = normalizeScalar32(s)
        val vNorm = normalizeV(v)
        return r32 + s32 + byteArrayOf(vNorm)
    }

    private fun normalizeScalar32(input: ByteArray): ByteArray {
        require(input.isNotEmpty()) { "secp256k1 scalar is empty" }
        var start = 0
        while (start < input.lastIndex && input[start] == 0.toByte()) {
            start += 1
        }
        val unsigned = input.copyOfRange(start, input.size)
        require(unsigned.size <= 32) { "secp256k1 scalar exceeds 32 bytes" }
        return if (unsigned.size == 32) unsigned else ByteArray(32 - unsigned.size) + unsigned
    }

    private fun normalizeV(v: Byte): Byte {
        val asInt = v.toInt() and 0xFF
        return when (asInt) {
            0, 1 -> asInt.toByte()
            27, 28 -> (asInt - 27).toByte()
            // EIP-155 style v values can be mapped back to yParity.
            in 35..Int.MAX_VALUE -> ((asInt - 35) % 2).toByte()
            else -> throw IllegalArgumentException("invalid secp256k1 v: $asInt")
        }
    }

    // -- minimal RLP encoder --

    private fun rlpEncodeList(items: List<Any>): ByteArray {
        val buf = ByteArrayOutputStream()
        for (item in items) {
            buf.write(rlpEncode(item))
        }
        val payload = buf.toByteArray()
        return rlpLengthPrefix(payload, 0xc0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun rlpEncode(item: Any): ByteArray {
        return when (item) {
            is RawRlp -> item.encoded
            is ByteArray -> rlpEncodeBytes(item)
            is Long -> rlpEncodeLong(item)
            is Int -> rlpEncodeLong(item.toLong())
            is List<*> -> rlpEncodeList(item as List<Any>)
            else -> throw IllegalArgumentException("unsupported RLP type: ${item::class}")
        }
    }

    private fun rlpEncodeBytes(bytes: ByteArray): ByteArray {
        if (bytes.size == 1 && bytes[0].toInt() and 0xFF < 0x80) {
            return bytes
        }
        return rlpLengthPrefix(bytes, 0x80)
    }

    private fun rlpEncodeLong(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0x80.toByte())
        if (value < 128) return byteArrayOf(value.toByte())
        val bytes = longToMinimalBytes(value)
        return rlpLengthPrefix(bytes, 0x80)
    }

    private fun longToMinimalBytes(value: Long): ByteArray {
        if (value == 0L) return ByteArray(0)
        var v = value
        val buf = ByteArrayOutputStream()
        while (v > 0) {
            buf.write((v and 0xFF).toInt())
            v = v shr 8
        }
        return buf.toByteArray().reversedArray()
    }

    private fun rlpLengthPrefix(payload: ByteArray, offset: Int): ByteArray {
        return if (payload.size < 56) {
            byteArrayOf((offset + payload.size).toByte()) + payload
        } else {
            val lenBytes = longToMinimalBytes(payload.size.toLong())
            byteArrayOf((offset + 55 + lenBytes.size).toByte()) + lenBytes + payload
        }
    }
}
