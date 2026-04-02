package sc.pirate.app.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import sc.pirate.app.PirateChainConfig

data class PiratePrimaryName(
  val label: String,
  val parentNode: String,
  val tld: String?,
  val fullName: String,
)

object PirateNameRecordsApi {
  const val REGISTRY_V2 = PirateChainConfig.BASE_SEPOLIA_REGISTRY_V2
  const val RECORDS_V1 = PirateChainConfig.BASE_SEPOLIA_RECORDS_V1

  const val HNS_NAMESPACE_NODE = "0x8edf6f47e89d05c0e21320161fda1fd1fabd0081a66c959691ea17102e39fb27"
  const val PIRATE_NODE = "0xace9c9c435cf933be3564cdbcf7b7e2faee63e4f39034849eacb82d13f32f02a"

  private const val TLD_HNS = "heaven"
  private const val TLD_PIRATE = "pirate"
  private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
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
    val parsed = parsePirateName(nameOrLabel, parentNodeByTld, HNS_NAMESPACE_NODE)
    val labelHash = pirateNameKeccak256(parsed.label.toByteArray(Charsets.UTF_8))
    val parentBytes = pirateNameHexToBytes(parsed.parentNode.removePrefix("0x"))
    val node = pirateNameKeccak256(parentBytes + labelHash)
    return "0x${pirateNameBytesToHex(node)}"
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
    val data = "0x${pirateNameFunctionSelector("ownerOf(uint256)")}$tokenId"
    val result = runCatching { pirateNameEthCall(REGISTRY_V2, data) }.getOrNull() ?: return@withContext null
    val clean = result.removePrefix("0x").lowercase()
    if (clean.length < 64) return@withContext null

    val owner = "0x${clean.takeLast(40)}"
    if (owner == ZERO_ADDRESS) return@withContext null
    pirateNameNormalizeAddress(owner)
  }

  suspend fun getPrimaryNameDetails(userAddress: String): PiratePrimaryName? = withContext(Dispatchers.IO) {
    val addr = pirateNameNormalizeAddress(userAddress) ?: return@withContext null
    val data = "0x${pirateNameFunctionSelector("primaryName(address)")}${addr.drop(2).padStart(64, '0')}"
    try {
      val result = pirateNameEthCall(REGISTRY_V2, data)
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
        String(pirateNameHexToBytes(valueHex), Charsets.UTF_8)
          .trim()
          .lowercase()
          .ifBlank { return@withContext null }
      val tld = tldByParentNode[parentHex.lowercase()]
      val full = if (tld.isNullOrBlank()) label else "$label.$tld"
      PiratePrimaryName(
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
    val nodeHex = pirateNameNormalizeBytes32(node) ?: return@withContext null
    val keyBytes = key.toByteArray(Charsets.UTF_8)
    val keyHex = pirateNameBytesToHex(keyBytes)
    val selector = pirateNameFunctionSelector("text(bytes32,string)")
    val offset = "0000000000000000000000000000000000000000000000000000000000000040"
    val len = keyBytes.size.toString(16).padStart(64, '0')
    val paddedKey = keyHex.padEnd(((keyHex.length + 63) / 64) * 64, '0')
    val data = "0x$selector$nodeHex$offset$len$paddedKey"
    try {
      val result = pirateNameEthCall(RECORDS_V1, data)
      val hex = result.removePrefix("0x")
      if (hex.length < 128) return@withContext null
      val stringOffset = hex.substring(0, 64).toBigIntegerOrNull(16)?.toInt() ?: return@withContext null
      val start = stringOffset * 2
      if (start + 64 > hex.length) return@withContext null
      val stringLen = hex.substring(start, start + 64).toBigIntegerOrNull(16)?.toInt() ?: return@withContext null
      if (stringLen == 0) return@withContext null
      val stringHex = hex.substring(start + 64, start + 64 + stringLen * 2)
      String(pirateNameHexToBytes(stringHex), Charsets.UTF_8).ifBlank { null }
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

  fun encodeContentPubKey(publicKey: ByteArray): String {
    require(pirateNameIsValidContentPubKey(publicKey)) {
      "Invalid contentPubKey format (expected 65-byte uncompressed P256 key)."
    }
    return "0x${pirateNameBytesToHex(publicKey)}"
  }

  fun decodeContentPubKey(value: String?): ByteArray? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return null
    val normalized = raw.removePrefix("0x").removePrefix("0X")
    if (normalized.length != 130) return null
    if (!normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return runCatching { pirateNameHexToBytes(normalized) }
      .getOrNull()
      ?.takeIf { pirateNameIsValidContentPubKey(it) }
  }

  internal fun encodeSetRecordsCallForRelay(
    node: String,
    keys: List<String>,
    values: List<String>,
  ): String {
    require(keys.isNotEmpty()) { "At least one record key is required." }
    require(keys.size == values.size) { "Record keys and values must have the same length." }
    val normalizedNode = pirateNameNormalizeBytes32(node) ?: error("invalid node")
    return encodeSetRecordsCall(normalizedNode, keys, values)
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
          Bytes32(pirateNameHexToBytes(nodeHexNoPrefix)),
          Address(ZERO_ADDRESS),
          DynamicArray(Utf8String::class.java, keyValues),
          DynamicArray(Utf8String::class.java, textValues),
          DynamicBytes(ByteArray(0)),
        ),
        emptyList(),
      )
    return FunctionEncoder.encode(function)
  }

  fun isAuthorized(node: String, addr: String): Boolean {
    val nodeHex = pirateNameNormalizeBytes32(node) ?: return false
    val addrHex = pirateNameNormalizeAddress(addr)?.removePrefix("0x")?.padStart(64, '0') ?: return false
    val selector = pirateNameFunctionSelector("isAuthorized(bytes32,address)")
    val data = "0x$selector$nodeHex$addrHex"
    return try {
      val result = pirateNameEthCall(RECORDS_V1, data)
      val hex = result.removePrefix("0x")
      hex.endsWith("1")
    } catch (_: Throwable) {
      false
    }
  }
}
