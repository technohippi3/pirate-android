package com.pirate.app.player

import com.pirate.app.BuildConfig
import com.pirate.app.music.MusicTrack
import com.pirate.app.tempo.TempoClient
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.crypto.Hash

internal object PlayerPresentationRepository {
  private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val client =
    OkHttpClient.Builder()
      .callTimeout(12, TimeUnit.SECONDS)
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .build()
  private val bytes32Regex = Regex("(?i)^0x[a-f0-9]{64}$")
  private val addressRegex = Regex("(?i)^0x[a-f0-9]{40}$")
  private val canvasUrlCache = ConcurrentHashMap<String, String>()
  private val metadataCache = ConcurrentHashMap<String, JSONObject>()
  private val canonicalByTrackOutputTypes: List<TypeReference<*>> =
    listOf(
      object : TypeReference<Bytes32>() {},
      object : TypeReference<Uint32>() {},
      object : TypeReference<Uint64>() {},
      object : TypeReference<Bool>() {},
    )
  private val publishPresentationEventOutputTypes: List<TypeReference<*>> =
    listOf(
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Bytes32>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Bytes32>() {},
      object : TypeReference<Uint32>() {},
      object : TypeReference<Uint64>() {},
    )
  private val publishPresentationSetTopic =
    Hash.sha3String("PublishPresentationSet(bytes32,bytes32,address,string,bytes32,string,bytes32,uint32,uint64)")

  suspend fun resolveCanvasVideoUrl(track: MusicTrack): String? = withContext(Dispatchers.IO) {
    val trackId = deriveTrackId(track) ?: return@withContext null
    canvasUrlCache[trackId]?.let { return@withContext it }

    val metadata = resolveIpMetadata(trackId) ?: return@withContext null
    val canvasUrl = extractCanvasVideoUrl(metadata) ?: return@withContext null

    canvasUrlCache[trackId] = canvasUrl
    canvasUrl
  }

  suspend fun resolveLyricsTextRef(track: MusicTrack): String? = withContext(Dispatchers.IO) {
    val trackId = deriveTrackId(track) ?: return@withContext null
    val metadata = resolveIpMetadata(trackId) ?: return@withContext null
    metadata.optJSONObject("pirate")
      ?.optJSONObject("lyrics")
      ?.optString("textRef", "")
      ?.trim()
      ?.ifBlank { null }
  }

  private fun resolveIpMetadata(trackId: String): JSONObject? {
    metadataCache[trackId]?.let { return it }

    val registryAddress = normalizeAddress(BuildConfig.TEMPO_TRACK_PRESENTATION_REGISTRY)
    if (registryAddress == null || registryAddress == ZERO_ADDRESS) return null

    val publishId = fetchCanonicalPublishId(registryAddress, trackId) ?: return null
    val ipMetadataUri = fetchLatestIpMetadataUri(registryAddress, publishId) ?: return null
    val metadata = fetchJsonFromRef(ipMetadataUri) ?: return null

    metadataCache[trackId] = metadata
    return metadata
  }

  internal fun invalidateTrack(trackId: String?) {
    val normalizedTrackId = normalizeBytes32(trackId) ?: return
    canvasUrlCache.remove(normalizedTrackId)
    metadataCache.remove(normalizedTrackId)
  }

  private fun fetchCurrentBlockNumber(): Long? {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_blockNumber")
        .put("params", JSONArray())
    val request = Request.Builder().url(TempoClient.RPC_URL).post(payload.toString().toRequestBody(jsonMediaType)).build()
    return runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val json = JSONObject(response.body?.string().orEmpty())
        json.optString("result", "").trim().removePrefix("0x").toLongOrNull(16)
      }
    }.getOrNull()
  }

  private fun deriveTrackId(track: MusicTrack): String? {
    val direct = normalizeBytes32(track.canonicalTrackId)
    if (direct != null) return direct
    val candidates = listOf(track.id, track.contentId.orEmpty())
    for (candidate in candidates) {
      val hit = Regex("(?i)0x[a-f0-9]{64}").find(candidate)?.value ?: continue
      val normalized = normalizeBytes32(hit)
      if (normalized != null) return normalized
    }
    return null
  }

  private fun fetchCanonicalPublishId(registryAddress: String, trackId: String): String? {
    val trackIdBytes = runCatching { hexToBytes(trackId) }.getOrNull() ?: return null
    if (trackIdBytes.size != 32) return null

    val function = Function("canonicalByTrackId", listOf(Bytes32(trackIdBytes)), canonicalByTrackOutputTypes)
    val resultHex = runCatching { ethCall(registryAddress, FunctionEncoder.encode(function)) }.getOrNull().orEmpty()
    if (!resultHex.startsWith("0x") || resultHex.length <= 2) return null
    val decoded = runCatching { decodeFunctionResult(resultHex, canonicalByTrackOutputTypes) }.getOrNull() ?: return null
    val exists = (decoded.getOrNull(3) as? Bool)?.value ?: false
    if (!exists) return null
    val publishBytes = (decoded.getOrNull(0) as? Bytes32)?.value ?: return null
    val publishId = "0x${bytesToHex(publishBytes)}".lowercase(Locale.US)
    if (publishId == "0x${"0".repeat(64)}") return null
    return publishId
  }

  private fun fetchLatestIpMetadataUri(registryAddress: String, publishId: String): String? {
    val publishTopic = normalizeBytes32(publishId) ?: return null
    val topics =
      JSONArray()
        .put(publishPresentationSetTopic)
        .put(publishTopic)

    // Bound fromBlock to within 99999 of the current block to satisfy RPC range limits.
    val currentBlock = fetchCurrentBlockNumber() ?: return null
    val fromBlock = maxOf(0L, currentBlock - 99_999L)

    val filter =
      JSONObject()
        .put("address", registryAddress)
        .put("fromBlock", "0x${fromBlock.toString(16)}")
        .put("toBlock", "latest")
        .put("topics", topics)

    val json = runCatching { postRpc("eth_getLogs", JSONArray().put(filter)) }.getOrNull() ?: return null
    val logs = json.optJSONArray("result") ?: return null
    if (logs.length() <= 0) return null

    for (index in logs.length() - 1 downTo 0) {
      val row = logs.optJSONObject(index) ?: continue
      val dataHex = row.optString("data", "").trim()
      if (!dataHex.startsWith("0x") || dataHex.length <= 2) continue
      val decoded = runCatching {
        decodeFunctionResult(dataHex, publishPresentationEventOutputTypes)
      }.getOrNull() ?: continue
      val ipMetadataUri = (decoded.getOrNull(0) as? Utf8String)?.value?.trim().orEmpty()
      if (ipMetadataUri.isNotBlank()) return ipMetadataUri
    }
    return null
  }

  private fun fetchJsonFromRef(ref: String): JSONObject? {
    val url = resolveStorageRefUrl(ref) ?: return null
    val request = Request.Builder().url(url).get().build()
    return runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body?.string()?.trim().orEmpty()
        if (body.isBlank()) return null
        JSONObject(body)
      }
    }.getOrNull()
  }

  private fun extractCanvasVideoUrl(metadata: JSONObject): String? {
    val pirateCanvasRef = metadata
      .optJSONObject("pirate")
      ?.optJSONObject("media")
      ?.optString("canvasRef", "")
      ?.trim()
      ?.ifBlank { null }
    if (!pirateCanvasRef.isNullOrBlank()) return resolveStorageRefUrl(pirateCanvasRef)

    val animationRef = metadata.optString("animation_url", "").trim().ifBlank { null } ?: return null
    if (!isLikelyVideoRef(animationRef)) return null
    return resolveStorageRefUrl(animationRef)
  }

  internal fun extractCanvasVideoUrlForTesting(metadata: JSONObject): String? =
    extractCanvasVideoUrl(metadata)

  private fun isLikelyVideoRef(ref: String): Boolean {
    val lower = ref.lowercase(Locale.US)
    return lower.contains(".mp4")
      || lower.contains(".webm")
      || lower.contains(".m4v")
      || lower.contains(".mov")
  }

  private fun resolveStorageRefUrl(rawRef: String): String? {
    val ref = rawRef.trim()
    if (ref.isBlank()) return null
    if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
    if (ref.startsWith("ipfs://")) {
      val path = ref.removePrefix("ipfs://").removePrefix("ipfs/").trimStart('/')
      if (path.isBlank()) return null
      val gateway = BuildConfig.IPFS_GATEWAY_URL.trim().trimEnd('/')
      return "$gateway/$path"
    }
    if (ref.startsWith("ar://")) {
      val path = ref.removePrefix("ar://").trimStart('/')
      if (path.isBlank()) return null
      val gateway = BuildConfig.ARWEAVE_GATEWAY_URL.trim().trimEnd('/')
      return "$gateway/$path"
    }
    return null
  }

  private fun ethCall(to: String, data: String): String {
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
    val request = Request.Builder().url(TempoClient.RPC_URL).post(payload.toString().toRequestBody(jsonMediaType)).build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val json = JSONObject(response.body?.string().orEmpty())
      val error = json.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      json.optString("result", "0x")
    }
  }

  private fun postRpc(method: String, params: JSONArray): JSONObject {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", method)
        .put("params", params)
    val request = Request.Builder().url(TempoClient.RPC_URL).post(payload.toString().toRequestBody(jsonMediaType)).build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val json = JSONObject(response.body?.string().orEmpty())
      val error = json.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      json
    }
  }

  private fun normalizeBytes32(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (!bytes32Regex.matches(value)) return null
    return value.lowercase(Locale.US)
  }

  private fun normalizeAddress(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (!addressRegex.matches(value)) return null
    return value.lowercase(Locale.US)
  }

  private fun hexToBytes(hex0x: String): ByteArray {
    val hex = hex0x.removePrefix("0x").removePrefix("0X")
    if (hex.length % 2 != 0) throw IllegalArgumentException("Odd hex length")
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
      out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
      i += 2
    }
    return out
  }

  private fun bytesToHex(bytes: ByteArray): String {
    val digits = CharArray(bytes.size * 2)
    val alphabet = "0123456789abcdef"
    var offset = 0
    for (value in bytes) {
      val v = value.toInt() and 0xff
      digits[offset] = alphabet[v ushr 4]
      digits[offset + 1] = alphabet[v and 0x0f]
      offset += 2
    }
    return String(digits)
  }

  private fun decodeFunctionResult(result: String, outputs: List<TypeReference<*>>): List<Type<*>> {
    @Suppress("UNCHECKED_CAST")
    return FunctionReturnDecoder.decode(result, outputs as List<TypeReference<Type<*>>>)
  }
}
