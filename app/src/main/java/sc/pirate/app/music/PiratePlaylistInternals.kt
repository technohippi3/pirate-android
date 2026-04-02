package sc.pirate.app.music

import sc.pirate.app.PirateChainConfig
import sc.pirate.app.crypto.P256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint8
import java.util.Locale

private val client = OkHttpClient()
private val jsonType = "application/json; charset=utf-8".toMediaType()
private const val GAS_LIMIT_BUFFER = 250_000L
internal val EXPIRING_NONCE_KEY = ByteArray(32) { 0xFF.toByte() }
internal const val EXPIRY_WINDOW_SEC = 25L
internal const val MAX_UNDERPRICED_RETRIES = 4
internal const val RETRY_DELAY_MS = 220L
internal const val RELAY_MIN_PRIORITY_FEE_PER_GAS = 6_000_000_000L
internal const val RELAY_MIN_MAX_FEE_PER_GAS = 120_000_000_000L

internal data class PlaylistEip1559Fees(
  val maxPriorityFeePerGas: Long,
  val maxFeePerGas: Long,
)

private val bidFloorLock = Any()
private val lastBidByAddress = mutableMapOf<String, PlaylistEip1559Fees>()

internal data class PlaylistTransactionReceipt(
  val txHash: String,
  val statusHex: String,
) {
  val isSuccess: Boolean
    get() = statusHex.equals("0x1", ignoreCase = true)
}

internal fun encodeCreatePlaylist(
  name: String,
  coverCid: String,
  visibility: Int,
  trackIds: List<String>,
): String {
  val function =
    Function(
      "createPlaylist",
      listOf(
        Utf8String(name),
        Utf8String(coverCid),
        Uint8(visibility.toLong().coerceAtLeast(0L)),
        DynamicArray(Bytes32::class.java, trackIds.map { id -> Bytes32(P256Utils.hexToBytes(id)) }),
      ),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal fun encodeSetTracks(
  playlistId: String,
  expectedVersion: Int,
  trackIds: List<String>,
): String {
  val function =
    Function(
      "setTracks",
      listOf(
        Bytes32(P256Utils.hexToBytes(playlistId)),
        Uint32(expectedVersion.toLong().coerceAtLeast(0L)),
        DynamicArray(Bytes32::class.java, trackIds.map { id -> Bytes32(P256Utils.hexToBytes(id)) }),
      ),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal fun encodeUpdateMeta(
  playlistId: String,
  name: String,
  coverCid: String,
  visibility: Int,
): String {
  val function =
    Function(
      "updateMeta",
      listOf(
        Bytes32(P256Utils.hexToBytes(playlistId)),
        Utf8String(name),
        Utf8String(coverCid),
        Uint8(visibility.toLong().coerceAtLeast(0L)),
      ),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal fun encodeDeletePlaylist(playlistId: String): String {
  val function =
    Function(
      "deletePlaylist",
      listOf(Bytes32(P256Utils.hexToBytes(playlistId))),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal fun normalizeTrackIds(trackIds: List<String>): List<String> {
  val out = ArrayList<String>(trackIds.size)
  val seen = LinkedHashSet<String>()
  for (raw in trackIds) {
    val normalized = normalizeBytes32(raw, "trackId")
    if (seen.add(normalized)) out.add(normalized)
  }
  return out
}

internal fun normalizeBytes32(
  value: String,
  fieldName: String,
): String {
  val clean = value.trim().removePrefix("0x").removePrefix("0X").lowercase(Locale.US)
  require(clean.isNotEmpty() && clean.length <= 64 && clean.all { it.isDigit() || it in 'a'..'f' }) {
    "Invalid $fieldName"
  }
  return "0x${clean.padStart(64, '0')}"
}

internal fun truncateUtf8(
  value: String,
  maxBytes: Int,
): String {
  if (value.isEmpty()) return value
  if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value

  var out = value
  while (out.isNotEmpty() && out.toByteArray(Charsets.UTF_8).size > maxBytes) {
    out = out.dropLast(1)
  }
  return out
}

internal fun estimateStoryGas(
  from: String,
  to: String,
  data: String,
): Long {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_estimateGas")
      .put(
        "params",
        JSONArray().put(
          JSONObject()
            .put("from", from)
            .put("to", to)
            .put("data", data),
        ),
      )

  val req =
    Request.Builder()
      .url(PirateChainConfig.STORY_AENEID_RPC_URL)
      .post(payload.toString().toRequestBody(jsonType))
      .build()

  client.newCall(req).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    val hex = body.optString("result", "0x0").removePrefix("0x").ifBlank { "0" }
    return hex.toLongOrNull(16) ?: 0L
  }
}


internal fun normalizeChainAddress(value: String?): String {
  val clean = value?.trim().orEmpty().removePrefix("0x").removePrefix("0X").lowercase(Locale.US)
  require(clean.length == 40 && clean.all { it.isDigit() || it in 'a'..'f' }) {
    "Invalid address: $value"
  }
  return "0x$clean"
}

internal fun estimateGas(
  from: String,
  to: String,
  data: String,
): Long = estimateStoryGas(from = from, to = to, data = data)

internal fun aggressivelyBumpFees(fees: PlaylistEip1559Fees): PlaylistEip1559Fees {
  val bumpedPriority = maxOf(bumpForReplacement(fees.maxPriorityFeePerGas), saturatingMul(fees.maxPriorityFeePerGas, 2))
  val bumpedMax = maxOf(
    bumpForReplacement(fees.maxFeePerGas),
    saturatingMul(fees.maxFeePerGas, 2),
    saturatingAdd(bumpedPriority, 1_000_000L),
  )
  return PlaylistEip1559Fees(
    maxPriorityFeePerGas = bumpedPriority,
    maxFeePerGas = maxOf(bumpedMax, bumpedPriority),
  )
}

private fun bumpForReplacement(value: Long): Long {
  if (value <= 0L) return 1L
  val bump = value / 4L
  return saturatingAdd(value, maxOf(1L, bump))
}

internal fun withRelayMinimumFeeFloor(fees: PlaylistEip1559Fees): PlaylistEip1559Fees {
  val priority = maxOf(fees.maxPriorityFeePerGas, RELAY_MIN_PRIORITY_FEE_PER_GAS)
  val maxFee = maxOf(
    fees.maxFeePerGas,
    RELAY_MIN_MAX_FEE_PER_GAS,
    saturatingAdd(priority, 1_000_000L),
  )
  return PlaylistEip1559Fees(maxPriorityFeePerGas = priority, maxFeePerGas = maxFee)
}

internal fun withAddressBidFloor(
  address: String,
  fees: PlaylistEip1559Fees,
): PlaylistEip1559Fees {
  val key = address.trim().lowercase()
  if (key.isBlank()) return fees
  val previous = synchronized(bidFloorLock) { lastBidByAddress[key] } ?: return fees
  val priority = maxOf(fees.maxPriorityFeePerGas, previous.maxPriorityFeePerGas)
  val maxFee = maxOf(fees.maxFeePerGas, previous.maxFeePerGas, saturatingAdd(priority, 1_000_000L))
  return PlaylistEip1559Fees(maxPriorityFeePerGas = priority, maxFeePerGas = maxFee)
}

internal fun rememberAddressBidFloor(
  address: String,
  fees: PlaylistEip1559Fees,
) {
  val key = address.trim().lowercase()
  if (key.isBlank()) return
  synchronized(bidFloorLock) {
    val previous = lastBidByAddress[key]
    lastBidByAddress[key] =
      if (previous == null) {
        fees
      } else {
        PlaylistEip1559Fees(
          maxPriorityFeePerGas = maxOf(previous.maxPriorityFeePerGas, fees.maxPriorityFeePerGas),
          maxFeePerGas = maxOf(previous.maxFeePerGas, fees.maxFeePerGas),
        )
      }
  }
}

internal fun isReplacementUnderpriced(error: Throwable): Boolean {
  val message = error.message.orEmpty().lowercase()
  return message.contains("replacement transaction underpriced") ||
    message.contains("transaction underpriced") ||
    message.contains("underpriced")
}

internal fun nowSec(): Long = System.currentTimeMillis() / 1000L

internal fun withBuffer(
  estimated: Long,
  minimum: Long,
): Long {
  if (estimated <= 0L) return minimum
  val padded = saturatingAdd(saturatingMul(estimated, 3) / 2, GAS_LIMIT_BUFFER)
  return maxOf(minimum, padded)
}

internal suspend fun awaitPlaylistReceipt(txHash: String): PlaylistTransactionReceipt {
  val deadlineMs = System.currentTimeMillis() + (EXPIRY_WINDOW_SEC + 20L) * 1000L
  while (System.currentTimeMillis() < deadlineMs) {
    fetchPlaylistReceiptOrNull(txHash)?.let { return it }
    delay(1_500L)
  }
  val txStillKnown = withContext(Dispatchers.IO) { hasPlaylistTransaction(txHash) }
  if (!txStillKnown) {
    throw IllegalStateException("Playlist tx dropped before inclusion: $txHash")
  }
  delay(2_000L)
  return fetchPlaylistReceiptOrNull(txHash)
    ?: throw IllegalStateException("Playlist tx not confirmed before expiry: $txHash")
}

internal fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
internal fun saturatingMul(a: Long, factor: Int): Long = if (a > Long.MAX_VALUE / factor) Long.MAX_VALUE else a * factor

internal fun extractCreatedPlaylistIdFromReceipt(txHash: String): String? {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_getTransactionReceipt")
      .put("params", JSONArray().put(txHash))

  val req =
    Request.Builder()
      .url(PirateChainConfig.STORY_AENEID_RPC_URL)
      .post(payload.toString().toRequestBody(jsonType))
      .build()

  client.newCall(req).execute().use { response ->
    if (!response.isSuccessful) return null
    val body = JSONObject(response.body?.string().orEmpty())
    val receipt = body.optJSONObject("result") ?: return null
    val logs = receipt.optJSONArray("logs") ?: return null
    val topic0 = topicHash("PlaylistCreated(bytes32,address,uint32,uint8,uint32,bytes32,uint64,string,string)")
    val playlistContract = PirateChainConfig.STORY_PLAYLIST_V1.lowercase(Locale.US)

    for (i in 0 until logs.length()) {
      val log = logs.optJSONObject(i) ?: continue
      val address = log.optString("address", "").trim().lowercase(Locale.US)
      if (address != playlistContract) continue

      val topics = log.optJSONArray("topics") ?: continue
      val first = topics.optString(0, "").trim().lowercase(Locale.US)
      if (first != topic0) continue

      val rawPlaylistId = topics.optString(1, "").trim()
      if (rawPlaylistId.isBlank()) continue
      return runCatching { normalizeBytes32(rawPlaylistId, "playlistId") }.getOrNull()
    }
  }

  return null
}

private fun fetchPlaylistReceiptOrNull(txHash: String): PlaylistTransactionReceipt? {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_getTransactionReceipt")
      .put("params", JSONArray().put(txHash))

  val req =
    Request.Builder()
      .url(PirateChainConfig.STORY_AENEID_RPC_URL)
      .post(payload.toString().toRequestBody(jsonType))
      .build()

  client.newCall(req).execute().use { response ->
    if (!response.isSuccessful) return null
    val body = JSONObject(response.body?.string().orEmpty())
    val receipt = body.optJSONObject("result") ?: return null
    return PlaylistTransactionReceipt(
      txHash = receipt.optString("transactionHash", txHash),
      statusHex = receipt.optString("status", "0x0"),
    )
  }
}

private fun hasPlaylistTransaction(txHash: String): Boolean {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_getTransactionByHash")
      .put("params", JSONArray().put(txHash))

  val req =
    Request.Builder()
      .url(PirateChainConfig.STORY_AENEID_RPC_URL)
      .post(payload.toString().toRequestBody(jsonType))
      .build()

  client.newCall(req).execute().use { response ->
    if (!response.isSuccessful) return false
    val body = JSONObject(response.body?.string().orEmpty())
    val tx = body.optJSONObject("result") ?: return false
    return tx.optString("hash", "").trim().equals(txHash, ignoreCase = true)
  }
}

private fun topicHash(signature: String): String {
  val digest = Keccak.Digest256().digest(signature.toByteArray(Charsets.UTF_8))
  return "0x" + digest.joinToString("") { "%02x".format(it) }
}
