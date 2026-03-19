package com.pirate.app.profile

import androidx.fragment.app.FragmentActivity
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32

data class TempoRecordsWriteResult(
  val success: Boolean,
  val txHash: String? = null,
  val error: String? = null,
)

data class TempoPrimaryName(
  val label: String,
  val parentNode: String,
  val tld: String?,
  val fullName: String,
)

object TempoNameRecordsApi {
  // RegistryV2 + RecordsV1 (Tempo Moderato)
  const val REGISTRY_V1 = "0x4377af27381CbC8bdb39330DDc656b8f3648B674"
  const val RECORDS_V1 = "0x3741fDFaEEFe6bA370da44AF8530B6b7361742dD"

  /** HNS_NAMESPACE_NODE = namehash("heaven.hnsbridge.eth") */
  const val HNS_NAMESPACE_NODE = "0x8edf6f47e89d05c0e21320161fda1fd1fabd0081a66c959691ea17102e39fb27"
  /** PIRATE_NODE = namehash("pirate.hnsbridge.eth") */
  const val PIRATE_NODE = "0xace9c9c435cf933be3564cdbcf7b7e2faee63e4f39034849eacb82d13f32f02a"

  private const val TLD_HNS = "heaven"
  private const val TLD_PIRATE = "pirate"
  private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
  private const val MIN_GAS_LIMIT_SET_TEXT = 420_000L
  private const val MIN_GAS_LIMIT_SET_RECORDS = 700_000L
  private const val GAS_LIMIT_BUFFER = 300_000L
  const val PROFILE_COVER_RECORD_KEY = "heaven.cover"
  const val CONTENT_PUBKEY_RECORD_KEY = "contentPubKey"
  const val XMTP_INBOX_ID_RECORD_KEY = "xmtp.inboxId"

  private val parentNodeByTld =
    mapOf(
      TLD_HNS to HNS_NAMESPACE_NODE,
      TLD_PIRATE to PIRATE_NODE,
    )
  private val tldByParentNode =
    parentNodeByTld.entries.associate { (tld, node) ->
      node.removePrefix("0x").lowercase() to tld
    }

  fun supportedTlds(): List<String> = parentNodeByTld.keys.toList()

  fun parentNodeForTld(tld: String): String? = parentNodeByTld[tld.trim().lowercase()]

  fun computeNode(nameOrLabel: String): String {
    val parsed = parseTempoName(nameOrLabel, parentNodeByTld, HNS_NAMESPACE_NODE)
    val labelHash = tempoNameKeccak256(parsed.label.toByteArray(Charsets.UTF_8))
    val parentBytes = tempoNameHexToBytes(parsed.parentNode.removePrefix("0x"))
    val node = tempoNameKeccak256(parentBytes + labelHash)
    return "0x${tempoNameBytesToHex(node)}"
  }

  fun formatName(label: String, parentNode: String): String {
    val cleanLabel = label.trim().lowercase()
    if (cleanLabel.isBlank()) return ""
    val parent = parentNode.removePrefix("0x").lowercase()
    val tld = tldByParentNode[parent]
    return if (tld.isNullOrBlank()) cleanLabel else "$cleanLabel.$tld"
  }

  suspend fun getPrimaryName(userAddress: String): String? = withContext(Dispatchers.IO) {
    getPrimaryNameDetails(userAddress)?.fullName
  }

  suspend fun resolveAddressForName(nameOrLabel: String): String? = withContext(Dispatchers.IO) {
    val normalized = nameOrLabel.trim().lowercase().removePrefix("@")
    if (normalized.isBlank()) return@withContext null

    val node = runCatching { computeNode(normalized) }.getOrNull() ?: return@withContext null
    val tokenId = node.removePrefix("0x").padStart(64, '0')
    val data = "0x${tempoNameFunctionSelector("ownerOf(uint256)")}$tokenId"
    val result = runCatching { tempoNameEthCall(REGISTRY_V1, data) }.getOrNull() ?: return@withContext null
    val clean = result.removePrefix("0x").lowercase()
    if (clean.length < 64) return@withContext null

    val owner = "0x${clean.takeLast(40)}"
    if (owner == ZERO_ADDRESS) return@withContext null
    tempoNameNormalizeAddress(owner)
  }

  suspend fun getPrimaryNameDetails(userAddress: String): TempoPrimaryName? = withContext(Dispatchers.IO) {
    val addr = tempoNameNormalizeAddress(userAddress) ?: return@withContext null
    val data = "0x${tempoNameFunctionSelector("primaryName(address)")}${addr.drop(2).padStart(64, '0')}"
    try {
      val result = tempoNameEthCall(REGISTRY_V1, data)
      val hex = result.removePrefix("0x")
      if (hex.length < 128) return@withContext null

      val parentHex = hex.substring(64, 128)
      val parentNode = "0x$parentHex".lowercase()
      if (parentHex.all { it == '0' }) return@withContext null

      val stringOffset = hex.substring(0, 64).toBigIntegerOrNull(16)?.toInt() ?: return@withContext null
      val start = stringOffset * 2
      if (start + 64 > hex.length) return@withContext null
      val len = hex.substring(start, start + 64).toBigIntegerOrNull(16)?.toInt() ?: return@withContext null
      if (len == 0) return@withContext null
      val valueHex = hex.substring(start + 64, start + 64 + len * 2)
      val label =
        String(tempoNameHexToBytes(valueHex), Charsets.UTF_8)
          .trim()
          .lowercase()
          .ifBlank { return@withContext null }
      val tld = tldByParentNode[parentHex.lowercase()]
      val full = if (tld.isNullOrBlank()) label else "$label.$tld"
      TempoPrimaryName(
        label = label,
        parentNode = parentNode,
        tld = tld,
        fullName = full,
      )
    } catch (_: Throwable) {
      null
    }
  }

  suspend fun getTextRecord(node: String, key: String): String? = withContext(Dispatchers.IO) {
    val nodeHex = tempoNameNormalizeBytes32(node) ?: return@withContext null
    val keyBytes = key.toByteArray(Charsets.UTF_8)
    val keyHex = tempoNameBytesToHex(keyBytes)
    val selector = tempoNameFunctionSelector("text(bytes32,string)")
    val offset = "0000000000000000000000000000000000000000000000000000000000000040"
    val len = keyBytes.size.toString(16).padStart(64, '0')
    val paddedKey = keyHex.padEnd(((keyHex.length + 63) / 64) * 64, '0')
    val data = "0x$selector$nodeHex$offset$len$paddedKey"
    try {
      val result = tempoNameEthCall(RECORDS_V1, data)
      val hex = result.removePrefix("0x")
      if (hex.length < 128) return@withContext null
      val stringOffset = hex.substring(0, 64).toBigIntegerOrNull(16)?.toInt() ?: return@withContext null
      val start = stringOffset * 2
      if (start + 64 > hex.length) return@withContext null
      val stringLen = hex.substring(start, start + 64).toBigIntegerOrNull(16)?.toInt() ?: return@withContext null
      if (stringLen == 0) return@withContext null
      val stringHex = hex.substring(start + 64, start + 64 + stringLen * 2)
      String(tempoNameHexToBytes(stringHex), Charsets.UTF_8).ifBlank { null }
    } catch (_: Throwable) {
      null
    }
  }

  suspend fun getContentPubKeyForAddress(userAddress: String): ByteArray? = withContext(Dispatchers.IO) {
    val primary = getPrimaryNameDetails(userAddress) ?: return@withContext null
    val node = computeNode(primary.fullName)
    val raw = getTextRecord(node, CONTENT_PUBKEY_RECORD_KEY)
    decodeContentPubKey(raw)
  }

  suspend fun getContentPubKeyForName(nameOrLabel: String): ByteArray? = withContext(Dispatchers.IO) {
    val normalized = nameOrLabel.trim().lowercase().removePrefix("@")
    if (normalized.isBlank()) return@withContext null
    val node = runCatching { computeNode(normalized) }.getOrNull() ?: return@withContext null
    val raw = getTextRecord(node, CONTENT_PUBKEY_RECORD_KEY)
    decodeContentPubKey(raw)
  }

  suspend fun getXmtpInboxIdForAddress(userAddress: String): String? = withContext(Dispatchers.IO) {
    val primary = getPrimaryNameDetails(userAddress) ?: return@withContext null
    val node = computeNode(primary.fullName)
    getTextRecord(node, XMTP_INBOX_ID_RECORD_KEY)?.trim()?.ifBlank { null }
  }

  suspend fun getXmtpInboxIdForName(nameOrLabel: String): String? = withContext(Dispatchers.IO) {
    val normalized = nameOrLabel.trim().lowercase().removePrefix("@")
    if (normalized.isBlank()) return@withContext null
    val node = runCatching { computeNode(normalized) }.getOrNull() ?: return@withContext null
    getTextRecord(node, XMTP_INBOX_ID_RECORD_KEY)?.trim()?.ifBlank { null }
  }

  suspend fun upsertContentPubKey(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    publicKey: ByteArray,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
  ): TempoRecordsWriteResult {
    if (!tempoNameIsValidContentPubKey(publicKey)) {
      return TempoRecordsWriteResult(success = false, error = "Invalid contentPubKey format (expected 65-byte uncompressed P256 key).")
    }

    val primary = getPrimaryNameDetails(account.address)
      ?: return TempoRecordsWriteResult(success = false, error = "Primary name required to publish contentPubKey.")
    val node = computeNode(primary.fullName)
    val targetValue = encodeContentPubKey(publicKey)
    val existing = decodeContentPubKey(getTextRecord(node, CONTENT_PUBKEY_RECORD_KEY))
    if (existing != null && existing.contentEquals(publicKey)) {
      return TempoRecordsWriteResult(success = true)
    }

    return setTextRecords(
      activity = activity,
      account = account,
      node = node,
      keys = listOf(CONTENT_PUBKEY_RECORD_KEY),
      values = listOf(targetValue),
      rpId = rpId,
      sessionKey = sessionKey,
    )
  }

  suspend fun upsertXmtpInboxId(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    inboxId: String,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
  ): TempoRecordsWriteResult {
    val normalizedInboxId = inboxId.trim()
    if (normalizedInboxId.isBlank()) {
      return TempoRecordsWriteResult(success = false, error = "Missing XMTP inbox ID.")
    }

    val primary = getPrimaryNameDetails(account.address)
      ?: return TempoRecordsWriteResult(success = false, error = "Primary name required to publish XMTP inbox ID.")
    val node = computeNode(primary.fullName)
    val existing = getTextRecord(node, XMTP_INBOX_ID_RECORD_KEY)?.trim().orEmpty()
    if (existing.equals(normalizedInboxId, ignoreCase = true)) {
      return TempoRecordsWriteResult(success = true)
    }

    return setTextRecords(
      activity = activity,
      account = account,
      node = node,
      keys = listOf(XMTP_INBOX_ID_RECORD_KEY),
      values = listOf(normalizedInboxId),
      rpId = rpId,
      sessionKey = sessionKey,
    )
  }

  fun encodeContentPubKey(publicKey: ByteArray): String {
    require(tempoNameIsValidContentPubKey(publicKey)) {
      "Invalid contentPubKey format (expected 65-byte uncompressed P256 key)."
    }
    return "0x${tempoNameBytesToHex(publicKey)}"
  }

  fun decodeContentPubKey(value: String?): ByteArray? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return null
    val normalized = raw.removePrefix("0x").removePrefix("0X")
    if (normalized.length != 130) return null
    if (!normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return runCatching { tempoNameHexToBytes(normalized) }
      .getOrNull()
      ?.takeIf { tempoNameIsValidContentPubKey(it) }
  }

  suspend fun setTextRecords(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    node: String,
    keys: List<String>,
    values: List<String>,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
  ): TempoRecordsWriteResult {
    if (keys.isEmpty()) return TempoRecordsWriteResult(success = true)
    if (keys.size != values.size) {
      return TempoRecordsWriteResult(success = false, error = "records length mismatch")
    }
    val normalizedNode = tempoNameNormalizeBytes32(node)
      ?: return TempoRecordsWriteResult(success = false, error = "invalid node")

    return runCatching {
      val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
      if (chainId != TempoClient.CHAIN_ID) {
        throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
      }

      val nonce = withContext(Dispatchers.IO) { TempoClient.getNonce(account.address) }
      val fees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }
      val callData =
        if (keys.size == 1) encodeSetTextCall(normalizedNode, keys.first(), values.first())
        else encodeSetRecordsCall(normalizedNode, keys, values)

      val minimumGasLimit = if (keys.size == 1) MIN_GAS_LIMIT_SET_TEXT else MIN_GAS_LIMIT_SET_RECORDS
      val gasLimit =
        withContext(Dispatchers.IO) {
          val estimated = tempoNameEstimateGas(from = account.address, to = RECORDS_V1, data = callData)
          tempoNameWithBuffer(estimated = estimated, minimum = minimumGasLimit, buffer = GAS_LIMIT_BUFFER)
        }

      fun buildTx(
        feeMode: TempoTransaction.FeeMode,
        txFees: TempoClient.Eip1559Fees,
      ): TempoTransaction.UnsignedTx =
        TempoTransaction.UnsignedTx(
          nonce = nonce,
          maxPriorityFeePerGas = txFees.maxPriorityFeePerGas,
          maxFeePerGas = txFees.maxFeePerGas,
          feeMode = feeMode,
          gasLimit = gasLimit,
          calls =
            listOf(
              TempoTransaction.Call(
                to = P256Utils.hexToBytes(RECORDS_V1),
                value = 0,
                input = P256Utils.hexToBytes(callData),
              ),
            ),
        )

      suspend fun signTx(tx: TempoTransaction.UnsignedTx): String {
        val sigHash = TempoTransaction.signatureHash(tx)
        return if (sessionKey != null) {
          val keychainSig = SessionKeyManager.signWithSessionKey(
            sessionKey = sessionKey,
            userAddress = account.address,
            txHash = sigHash,
          )
          TempoTransaction.encodeSignedSessionKey(tx, keychainSig)
        } else {
          val assertion =
            TempoPasskeyManager.sign(
              activity = activity,
              challenge = sigHash,
              account = account,
              rpId = rpId,
            )
          TempoTransaction.encodeSignedWebAuthn(tx, assertion)
        }
      }

      suspend fun submitWithFallback(): String {
        val relayTx = buildTx(feeMode = TempoTransaction.FeeMode.RELAY_SPONSORED, txFees = fees)
        val relaySignedTxHex = signTx(relayTx)
        return runCatching {
          withContext(Dispatchers.IO) {
            TempoClient.sendSponsoredRawTransaction(
              signedTxHex = relaySignedTxHex,
              senderAddress = account.address,
            )
          }
        }.getOrElse { relayErr ->
          withContext(Dispatchers.IO) { runCatching { TempoClient.fundAddress(account.address) } }
          val selfFees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }
          val selfTx = buildTx(feeMode = TempoTransaction.FeeMode.SELF, txFees = selfFees)
          val selfSignedTxHex = signTx(selfTx)
          runCatching {
            withContext(Dispatchers.IO) { TempoClient.sendRawTransaction(selfSignedTxHex) }
          }.getOrElse { selfErr ->
            throw IllegalStateException(
              "Records submission failed: relay=${relayErr.message}; self=${selfErr.message}",
              selfErr,
            )
          }
        }
      }

      val txHash = submitWithFallback()
      TempoRecordsWriteResult(success = true, txHash = txHash)
    }.getOrElse { err ->
      TempoRecordsWriteResult(success = false, error = err.message ?: "Tempo records tx failed")
    }
  }

  private fun encodeSetTextCall(nodeHexNoPrefix: String, key: String, value: String): String {
    val function =
      Function(
        "setText",
        listOf(
          Bytes32(tempoNameHexToBytes(nodeHexNoPrefix)),
          Utf8String(key),
          Utf8String(value),
        ),
        emptyList(),
      )
    return FunctionEncoder.encode(function)
  }

  private fun encodeSetRecordsCall(
    nodeHexNoPrefix: String,
    keys: List<String>,
    values: List<String>,
  ): String {
    val keyValues = keys.map { Utf8String(it) }
    val textValues = values.map { Utf8String(it) }
    val function =
      Function(
        "setRecords",
        listOf(
          Bytes32(tempoNameHexToBytes(nodeHexNoPrefix)),
          Address(ZERO_ADDRESS),
          DynamicArray(Utf8String::class.java, keyValues),
          DynamicArray(Utf8String::class.java, textValues),
          DynamicBytes(ByteArray(0)),
        ),
        emptyList(),
      )
    return FunctionEncoder.encode(function)
  }

  /** Debug helper: call RecordsV1.isAuthorized(node, addr) view function. */
  fun isAuthorized(node: String, addr: String): Boolean {
    val nodeHex = tempoNameNormalizeBytes32(node) ?: return false
    val addrHex = tempoNameNormalizeAddress(addr)?.removePrefix("0x")?.padStart(64, '0') ?: return false
    val selector = tempoNameFunctionSelector("isAuthorized(bytes32,address)")
    val data = "0x$selector$nodeHex$addrHex"
    return try {
      val result = tempoNameEthCall(RECORDS_V1, data)
      val hex = result.removePrefix("0x")
      hex.endsWith("1")
    } catch (_: Throwable) {
      false
    }
  }
}
