package sc.pirate.app.profile

import java.math.BigInteger
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint256
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.util.HttpClients

internal data class EfpFollowSummary(
  val followerCount: Int,
  val followingCount: Int,
)

internal data class EfpProfileLists(
  val primaryList: String?,
)

internal data class EfpListStorage(
  val slot: BigInteger,
)

internal data class EfpFollowTransaction(
  val to: String,
  val data: String,
  val intentType: String,
  val intentArgs: JSONObject,
)

internal object EfpFollowApi {
  private const val EFP_API_URL = "https://data.ethfollow.xyz/api/v1"
  private val jsonType = "application/json; charset=utf-8".toMediaType()

  fun isConfigured(): Boolean =
    PirateChainConfig.EFP_LIST_REGISTRY.isNotBlank() &&
      PirateChainConfig.EFP_LIST_MINTER.isNotBlank() &&
      PirateChainConfig.EFP_LIST_RECORDS_BASE_SEPOLIA.isNotBlank()

  suspend fun fetchProfileFollowSummary(address: String): EfpFollowSummary = withContext(Dispatchers.IO) {
    val target = normalizeAddress(address) ?: return@withContext EfpFollowSummary(0, 0)
    runCatching {
      val payload = requestJson("$EFP_API_URL/users/$target/stats?live=true&cache=fresh")
      EfpFollowSummary(
        followerCount = asPositiveInt(payload.opt("followers_count")),
        followingCount = asPositiveInt(payload.opt("following_count")),
      )
    }.getOrElse { EfpFollowSummary(0, 0) }
  }

  suspend fun fetchViewerFollowState(
    viewerAddress: String,
    targetAddress: String,
  ): Boolean = withContext(Dispatchers.IO) {
    val viewer = normalizeAddress(viewerAddress) ?: return@withContext false
    val target = normalizeAddress(targetAddress) ?: return@withContext false
    if (viewer.equals(target, ignoreCase = true)) return@withContext true
    runCatching {
      val payload = requestJson("$EFP_API_URL/users/$viewer/$target/buttonState?cache=fresh")
      payload.optJSONObject("state")?.optBoolean("follow", false) == true
    }.getOrDefault(false)
  }

  suspend fun fetchProfileLists(address: String): EfpProfileLists = withContext(Dispatchers.IO) {
    val viewer = normalizeAddress(address) ?: return@withContext EfpProfileLists(primaryList = null)
    runCatching {
      val payload = requestJson("$EFP_API_URL/users/$viewer/lists?cache=fresh")
      EfpProfileLists(primaryList = payload.optString("primary_list", "").trim().ifBlank { null })
    }.getOrElse { EfpProfileLists(primaryList = null) }
  }

  suspend fun getListStorageLocation(listId: String): EfpListStorage = withContext(Dispatchers.IO) {
    val parsedListId = listId.trim().toBigIntegerOrNull() ?: error("Invalid EFP list id.")
    val callData =
      FunctionEncoder.encode(
        Function(
          "getListStorageLocation",
          listOf(Uint256(parsedListId)),
          emptyList(),
        ),
      )
    val result = rpcEthCall(PirateChainConfig.EFP_LIST_REGISTRY, callData)
    decodeStorageLocation(decodeDynamicBytesResult(result))
  }

  fun buildFollowTransactions(
    viewerAddress: String,
    targetAddress: String,
    existingStorage: EfpListStorage?,
    followed: Boolean,
  ): List<EfpFollowTransaction> {
    val viewer = normalizeAddress(viewerAddress) ?: error("Invalid viewer address.")
    val target = normalizeAddress(targetAddress) ?: error("Invalid target address.")
    val op = createFollowListOp(target, followed)

    if (existingStorage != null) {
      return listOf(
        EfpFollowTransaction(
          to = PirateChainConfig.EFP_LIST_RECORDS_BASE_SEPOLIA,
          data = encodeApplyListOps(existingStorage.slot, listOf(op)),
          intentType = "pirate.efp.follow.apply",
          intentArgs = JSONObject()
            .put("viewerAddress", viewer)
            .put("targetAddress", target)
            .put("followed", followed)
            .put("slot", existingStorage.slot.toString()),
        ),
      )
    }

    val slot = generateListNonce()
    return listOf(
      EfpFollowTransaction(
        to = PirateChainConfig.EFP_LIST_RECORDS_BASE_SEPOLIA,
        data = encodeSetMetadataAndApplyListOps(slot, viewer, listOf(op)),
        intentType = "pirate.efp.follow.create-list",
        intentArgs = JSONObject()
          .put("viewerAddress", viewer)
          .put("targetAddress", target)
          .put("followed", followed)
          .put("slot", slot.toString()),
      ),
      EfpFollowTransaction(
        to = PirateChainConfig.EFP_LIST_MINTER,
        data = encodeMintPrimaryListNoMeta(slot),
        intentType = "pirate.efp.follow.mint-primary-list",
        intentArgs = JSONObject()
          .put("viewerAddress", viewer)
          .put("targetAddress", target)
          .put("followed", followed)
          .put("slot", slot.toString()),
      ),
    )
  }

  suspend fun waitForTransactionReceipt(
    txHash: String,
    timeoutMs: Long = 90_000L,
  ): Boolean = withContext(Dispatchers.IO) {
    val normalizedHash = txHash.trim().lowercase()
    if (!normalizedHash.startsWith("0x") || normalizedHash.length != 66) return@withContext false
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < timeoutMs) {
      val payload =
        JSONObject()
          .put("jsonrpc", "2.0")
          .put("id", 1)
          .put("method", "eth_getTransactionReceipt")
          .put("params", JSONArray().put(normalizedHash))
      val request =
        Request.Builder()
          .url(PirateChainConfig.BASE_SEPOLIA_RPC_URL)
          .post(payload.toString().toRequestBody(jsonType))
          .build()
      val receipt =
        HttpClients.Api.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
          val body = JSONObject(response.body?.string().orEmpty())
          body.optJSONObject("error")?.let { error ->
            throw IllegalStateException(error.optString("message", error.toString()))
          }
          body.optJSONObject("result")
        }
      if (receipt != null) {
        return@withContext receipt.optString("status", "0x0").equals("0x1", ignoreCase = true)
      }
      delay(1_500)
    }
    false
  }

  private fun requestJson(url: String): JSONObject {
    val request =
      Request.Builder()
        .url(url)
        .get()
        .header("Accept", "application/json")
        .build()
    HttpClients.Api.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("EFP request failed (${response.code}).")
      return JSONObject(response.body?.string().orEmpty())
    }
  }

  private fun rpcEthCall(
    to: String,
    data: String,
  ): String {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_call")
        .put(
          "params",
          JSONArray()
            .put(JSONObject().put("to", to).put("data", data))
            .put("latest"),
        )
    val request =
      Request.Builder()
        .url(PirateChainConfig.BASE_SEPOLIA_RPC_URL)
        .post(payload.toString().toRequestBody(jsonType))
        .build()
    HttpClients.Api.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val body = JSONObject(response.body?.string().orEmpty())
      body.optJSONObject("error")?.let { error ->
        throw IllegalStateException(error.optString("message", error.toString()))
      }
      return body.optString("result", "0x")
    }
  }

  private fun decodeDynamicBytesResult(rawHex: String): String {
    val clean = rawHex.removePrefix("0x")
    if (clean.length < 128) error("Invalid EFP storage location response.")
    val offset = clean.substring(0, 64).toBigInteger(16).toInt() * 2
    val length = clean.substring(offset, offset + 64).toBigInteger(16).toInt()
    return "0x${clean.substring(offset + 64, offset + 64 + length * 2)}"
  }

  private fun decodeStorageLocation(storageLocation: String): EfpListStorage {
    val clean = storageLocation.removePrefix("0x").lowercase()
    require(clean.length >= 172) { "Invalid EFP storage location." }
    val chainId = clean.substring(4, 68).toBigInteger(16).toLong()
    require(chainId == PirateChainConfig.BASE_SEPOLIA_CHAIN_ID) {
      "Unsupported EFP chain ($chainId)."
    }
    return EfpListStorage(slot = clean.takeLast(64).toBigInteger(16))
  }

  private fun encodeApplyListOps(
    slot: BigInteger,
    ops: List<ByteArray>,
  ): String =
    FunctionEncoder.encode(
      Function(
        "applyListOps",
        listOf(Uint256(slot), DynamicArray(DynamicBytes::class.java, ops.map(::DynamicBytes))),
        emptyList(),
      ),
    )

  private fun encodeSetMetadataAndApplyListOps(
    slot: BigInteger,
    viewerAddress: String,
    ops: List<ByteArray>,
  ): String {
    val metadata = DynamicStruct(Utf8String("user"), DynamicBytes(hexToBytes(viewerAddress)))
    return FunctionEncoder.encode(
      Function(
        "setMetadataValuesAndApplyListOps",
        listOf(
          Uint256(slot),
          DynamicArray(DynamicStruct::class.java, listOf(metadata)),
          DynamicArray(DynamicBytes::class.java, ops.map(::DynamicBytes)),
        ),
        emptyList(),
      ),
    )
  }

  private fun encodeMintPrimaryListNoMeta(slot: BigInteger): String =
    FunctionEncoder.encode(
      Function(
        "mintPrimaryListNoMeta",
        listOf(DynamicBytes(createMintStorageLocation(slot))),
        emptyList(),
      ),
    )

  private fun createFollowListOp(targetAddress: String, followed: Boolean): ByteArray =
    byteArrayOf(1, if (followed) 1 else 2, 1) + hexToBytes(targetAddress)

  private fun createMintStorageLocation(slot: BigInteger): ByteArray =
    byteArrayOf(1, 1) +
      uint256Bytes(BigInteger.valueOf(PirateChainConfig.BASE_SEPOLIA_CHAIN_ID)) +
      hexToBytes(PirateChainConfig.EFP_LIST_RECORDS_BASE_SEPOLIA) +
      uint256Bytes(slot)

  private fun generateListNonce(): BigInteger {
    val randomBytes = ByteArray(32)
    Random.nextBytes(randomBytes)
    randomBytes[0] = (randomBytes[0].toInt() and 0x7F).toByte()
    return BigInteger(1, randomBytes)
  }

  private fun normalizeAddress(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (!trimmed.startsWith("0x", ignoreCase = true) || trimmed.length != 42) return null
    val normalized = "0x${trimmed.removePrefix("0x").removePrefix("0X").lowercase()}"
    return normalized.takeIf { candidate -> candidate.drop(2).all { it in '0'..'9' || it in 'a'..'f' } }
  }

  private fun asPositiveInt(value: Any?): Int =
    when (value) {
      is Number -> value.toInt().coerceAtLeast(0)
      is String -> value.toIntOrNull()?.coerceAtLeast(0) ?: 0
      else -> 0
    }

  private fun uint256Bytes(value: BigInteger): ByteArray {
    val source = value.toByteArray()
    val positive = if (source.size > 32) source.copyOfRange(source.size - 32, source.size) else source
    val output = ByteArray(32)
    System.arraycopy(positive, 0, output, 32 - positive.size, positive.size)
    return output
  }

  private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.removePrefix("0x").removePrefix("0X")
    require(clean.length % 2 == 0) { "Hex length must be even." }
    val out = ByteArray(clean.length / 2)
    var index = 0
    while (index < clean.length) {
      out[index / 2] = ((clean[index].digitToInt(16) shl 4) or clean[index + 1].digitToInt(16)).toByte()
      index += 2
    }
    return out
  }
}
