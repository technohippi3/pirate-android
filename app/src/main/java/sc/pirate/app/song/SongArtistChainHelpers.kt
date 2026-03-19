package sc.pirate.app.song

import sc.pirate.app.scrobble.TempoScrobbleApi
import java.math.BigInteger
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.crypto.Hash

private const val TEMPO_RPC_URL = "https://rpc.moderato.tempo.xyz"
private val TRACK_REGISTERED_TOPIC = Hash.sha3String("TrackRegistered(bytes32,uint8,bytes32,bytes32,uint64,uint32)")
private const val TRACK_KIND_IPID_TOPIC = "0x0000000000000000000000000000000000000000000000000000000000000002"
private const val CHAIN_TRACK_SCAN_WINDOW_BLOCKS = 350_000L
private const val CHAIN_TRACK_SCAN_CHUNK_BLOCKS = 20_000L

internal data class ChainTrackMeta(
  val trackId: String,
  val title: String,
  val artist: String,
  val album: String,
  val coverCid: String?,
  val recordingMbid: String?,
  val durationSec: Int,
  val registeredAtSec: Long,
)

internal fun fetchSongStatsFromChain(trackId: String): SongStats? {
  val meta = getTrackMetaFromChain(trackId) ?: return null
  return SongStats(
    trackId = meta.trackId,
    title = meta.title.ifBlank { meta.trackId.take(14) },
    artist = meta.artist.ifBlank { "Unknown Artist" },
    album = meta.album,
    coverCid = meta.coverCid,
    lyricsRef = null,
    scrobbleCountTotal = 0L,
    scrobbleCountVerified = 0L,
    registeredAtSec = meta.registeredAtSec,
  )
}

internal fun fetchArtistTopTracksFromChain(artistName: String, maxEntries: Int): List<ArtistTrackRow> {
  val targetNorm = normalizeArtistName(artistName)
  if (targetNorm.isBlank()) return emptyList()
  val candidateLimit = (maxEntries * 40).coerceIn(maxEntries, 1200)
  val trackIds = fetchRecentRegisteredTrackIdsFromChain(candidateLimit)
  if (trackIds.isEmpty()) return emptyList()
  val metaByTrack = fetchTrackMetaFromChain(trackIds)
  if (metaByTrack.isEmpty()) return emptyList()
  return metaByTrack.values
    .asSequence()
    .filter { artistMatchesTarget(it.artist, targetNorm) }
    .sortedByDescending { it.registeredAtSec }
    .take(maxEntries)
    .map {
      ArtistTrackRow(
        trackId = it.trackId,
        title = it.title.ifBlank { it.trackId.take(14) },
        artist = it.artist.ifBlank { "Unknown Artist" },
        album = it.album,
        coverCid = it.coverCid,
        lyricsRef = null,
        recordingMbid = it.recordingMbid,
        scrobbleCountTotal = 0L,
        scrobbleCountVerified = 0L,
      )
    }.toList()
}

internal fun fetchRecentRegisteredTrackIdsFromChain(maxEntries: Int): List<String> {
  if (maxEntries <= 0) return emptyList()
  val latestBlock = runCatching { ethBlockNumber() }.getOrElse { return emptyList() }
  val minBlock = (latestBlock - CHAIN_TRACK_SCAN_WINDOW_BLOCKS).coerceAtLeast(0L)
  val out = LinkedHashSet<String>(maxEntries)

  var toBlock = latestBlock
  while (toBlock >= minBlock && out.size < maxEntries) {
    val fromBlock = maxOf(minBlock, toBlock - CHAIN_TRACK_SCAN_CHUNK_BLOCKS + 1)
    val logs =
      runCatching {
        ethGetLogs(
          address = TempoScrobbleApi.SCROBBLE_V4,
          fromBlock = fromBlock,
          toBlock = toBlock,
          topics =
            JSONArray()
              .put(TRACK_REGISTERED_TOPIC)
              .put(JSONObject.NULL)
              .put(TRACK_KIND_IPID_TOPIC),
        )
      }.getOrNull() ?: JSONArray()

    for (i in logs.length() - 1 downTo 0) {
      val log = logs.optJSONObject(i) ?: continue
      val topics = log.optJSONArray("topics") ?: continue
      val trackId = normalizeBytes32(topics.optString(1, "")) ?: continue
      out.add(trackId)
      if (out.size >= maxEntries) break
    }

    if (fromBlock == 0L) break
    toBlock = fromBlock - 1
  }

  return out.toList()
}

internal fun fetchTrackMetaFromChain(trackIds: List<String>): Map<String, ChainTrackMeta> {
  if (trackIds.isEmpty()) return emptyMap()
  val out = LinkedHashMap<String, ChainTrackMeta>(trackIds.size)
  for (raw in trackIds) {
    val trackId = normalizeBytes32(raw) ?: continue
    if (out.containsKey(trackId)) continue
    val meta = getTrackMetaFromChain(trackId) ?: continue
    out[trackId] = meta
  }
  return out
}

internal fun getTrackMetaFromChain(trackId: String): ChainTrackMeta? {
  val normalizedTrackId = normalizeBytes32(trackId) ?: return null
  val bytes = runCatching { hexToBytes(normalizedTrackId) }.getOrNull() ?: return null
  if (bytes.size != 32) return null

  val outputs =
    listOf(
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Uint8>() {},
      object : TypeReference<Bytes32>() {},
      object : TypeReference<Uint64>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Uint32>() {},
    )

  val function = Function("getTrack", listOf(Bytes32(bytes)), outputs)
  val callData = FunctionEncoder.encode(function)
  val result = runCatching { ethCall(TempoScrobbleApi.SCROBBLE_V4, callData) }.getOrNull().orEmpty()
  if (!result.startsWith("0x") || result.length <= 2) return null
  val decoded = runCatching { FunctionReturnDecoder.decode(result, function.outputParameters) }.getOrNull() ?: return null
  if (decoded.size < 8) return null

  val title = (decoded.getOrNull(0) as? Utf8String)?.value?.trim().orEmpty()
  val artist = (decoded.getOrNull(1) as? Utf8String)?.value?.trim().orEmpty()
  val album = (decoded.getOrNull(2) as? Utf8String)?.value?.trim().orEmpty()
  val kind = (decoded.getOrNull(3) as? Uint8)?.value?.toInt()
  val payloadBytes = (decoded.getOrNull(4) as? Bytes32)?.value
  val registeredAtSec = (decoded.getOrNull(5) as? Uint64)?.value?.toLong() ?: 0L
  val coverCid = (decoded.getOrNull(6) as? Utf8String)?.value?.trim().orEmpty().ifBlank { null }
  val durationSec = (decoded.getOrNull(7) as? Uint32)?.value?.toInt() ?: 0
  val recordingMbid = decodeRecordingMbidFromTrackKindAndPayloadBytes(kind, payloadBytes)

  return ChainTrackMeta(
    trackId = normalizedTrackId,
    title = title,
    artist = artist,
    album = album,
    coverCid = coverCid,
    recordingMbid = recordingMbid,
    durationSec = durationSec,
    registeredAtSec = registeredAtSec,
  )
}

private fun ethBlockNumber(): Long {
  val json = postRpc("eth_blockNumber", JSONArray())
  return parseHexLong(json.optString("result", "0x0"))
}

private fun ethGetLogs(
  address: String,
  fromBlock: Long,
  toBlock: Long,
  topics: JSONArray,
): JSONArray {
  val filter =
    JSONObject()
      .put("address", address)
      .put("fromBlock", hexQuantity(fromBlock))
      .put("toBlock", hexQuantity(toBlock))
      .put("topics", topics)
  val json = postRpc("eth_getLogs", JSONArray().put(filter))
  return json.optJSONArray("result") ?: JSONArray()
}

private fun ethCall(to: String, data: String): String {
  val call =
    JSONObject()
      .put("to", to)
      .put("data", data)
  val json = postRpc("eth_call", JSONArray().put(call).put("latest"))
  return json.optString("result", "0x")
}

private fun postRpc(method: String, params: JSONArray): JSONObject {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", method)
      .put("params", params)
  val req = Request.Builder().url(TEMPO_RPC_URL).post(payload.toString().toRequestBody(songArtistJsonMediaType)).build()
  return songArtistClient.newCall(req).execute().use { res ->
    if (!res.isSuccessful) throw IllegalStateException("RPC failed: ${res.code}")
    val json = JSONObject(res.body?.string().orEmpty())
    val err = json.optJSONObject("error")
    if (err != null) throw IllegalStateException(err.optString("message", err.toString()))
    json
  }
}

private fun parseHexLong(value: String): Long {
  val clean = value.trim().removePrefix("0x").ifBlank { "0" }
  return runCatching { BigInteger(clean, 16).toLong() }.getOrDefault(0L)
}

private fun hexQuantity(value: Long): String {
  if (value <= 0L) return "0x0"
  return "0x" + value.toString(16)
}
