package sc.pirate.app.profile

import sc.pirate.app.music.CoverRef
import sc.pirate.app.music.SHARED_WITH_YOU_SCROBBLE_V4
import sc.pirate.app.music.SHARED_WITH_YOU_STORY_RPC
import sc.pirate.app.music.fetchTrackMetaFromScrobbleV4
import sc.pirate.app.PirateChainConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

data class ScrobbleRow(
  val trackId: String?,
  val playedAtSec: Long,
  val title: String,
  val artist: String,
  val album: String,
  val playedAgo: String,
)

object ProfileScrobbleApi {
  private const val SCROBBLED_TOPIC0 = "0xe4535246dec82f5f9313b71f0e50716106f16725e5528d9ef8f5b670656d19a8"
  private const val SCROBBLE_V4_START_BLOCK = 16_244_936L
  private const val CHAIN_FALLBACK_WINDOW = 100_000L
  private const val SUBGRAPH_STALE_BLOCK_THRESHOLD = 512L
  private val client = OkHttpClient()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  private data class ChainScrobble(
    val trackId: String,
    val playedAtSec: Long,
    val blockNumber: Long,
    val logIndex: Long,
  )

  fun coverUrl(cid: String, size: Int = 96): String? =
    CoverRef.resolveCoverUrl(ref = cid, width = size, height = size, format = "webp", quality = 80)

  suspend fun fetchScrobbles(userAddress: String, max: Int = 100): List<ScrobbleRow> = withContext(Dispatchers.IO) {
    val addr = userAddress.trim().lowercase()
    if (addr.isBlank()) return@withContext emptyList()

    val latestBlock = runCatching { fetchLatestBlockNumber() }.getOrNull()
    var sawSuccessfulEmpty = false
    var subgraphError: Throwable? = null
    for (subgraphUrl in musicSocialSubgraphUrls()) {
      try {
        if (latestBlock != null && isSubgraphStale(subgraphUrl, latestBlock)) {
          return@withContext fetchFromChain(addr, max, latestBlock)
        }
        val rows = fetchFromSubgraph(subgraphUrl, addr, max)
        if (rows.isNotEmpty()) return@withContext rows
        sawSuccessfulEmpty = true
      } catch (error: Throwable) {
        subgraphError = error
      }
    }

    if (latestBlock != null) {
      val onChain = fetchFromChain(addr, max, latestBlock)
      if (onChain.isNotEmpty()) return@withContext onChain
    }
    if (sawSuccessfulEmpty) return@withContext emptyList()
    if (subgraphError != null) throw subgraphError
    emptyList()
  }

  private fun musicSocialSubgraphUrls(): List<String> = listOf(PirateChainConfig.STORY_MUSIC_SOCIAL_SUBGRAPH_URL)

  private fun isSubgraphStale(
    subgraphUrl: String,
    latestBlock: Long,
  ): Boolean {
    val meta =
      postQuery(
        subgraphUrl,
        """{ _meta { block { number } } }""",
      )
    val indexedBlock =
      meta
        .optJSONObject("data")
        ?.optJSONObject("_meta")
        ?.optJSONObject("block")
        ?.optString("number", "0")
        ?.trim()
        ?.toLongOrNull()
        ?: 0L
    if (indexedBlock <= 0L) return true
    return latestBlock - indexedBlock > SUBGRAPH_STALE_BLOCK_THRESHOLD
  }

  private fun fetchFromSubgraph(subgraphUrl: String, userAddress: String, max: Int): List<ScrobbleRow> {
    // Fetch scrobbles with inline track metadata.
    val query = """
      { scrobbles(where: { user: "$userAddress" }, orderBy: timestamp, orderDirection: desc, first: $max) {
          timestamp blockTimestamp track { id title artist album }
      } }
    """.trimIndent()

    val scrobbles =
      postQuery(subgraphUrl, query)
        .optJSONObject("data")
        ?.optJSONArray("scrobbles")
        ?: throw IllegalStateException("Subgraph query failed: missing scrobbles")

    // Collect unique track IDs for metadata fallback.
    val trackIds = mutableSetOf<String>()
    for (i in 0 until scrobbles.length()) {
      val track = scrobbles.optJSONObject(i)?.optJSONObject("track") ?: continue
      val id = track.optString("id", "").trim()
      if (id.isNotEmpty()) trackIds.add(id)
    }

    // Fetch full track metadata map.
    val trackMap = if (trackIds.isEmpty()) emptyMap() else fetchTrackMetadata(subgraphUrl, trackIds.toList())

    val now = System.currentTimeMillis() / 1000
    val rows = ArrayList<ScrobbleRow>(scrobbles.length())
    for (i in 0 until scrobbles.length()) {
      val s = scrobbles.optJSONObject(i) ?: continue
      val timestamp = s.optString("timestamp", s.optString("blockTimestamp", "0")).trim().toLongOrNull() ?: 0L

      val track = s.optJSONObject("track")
      val trackId = track?.optString("id", "")?.trim()?.ifEmpty { null }

      // Try inline metadata first, then fallback map.
      val inlineTitle = track?.optString("title", "")?.trim().orEmpty()
      val inlineArtist = track?.optString("artist", "")?.trim().orEmpty()
      val inlineAlbum = track?.optString("album", "")?.trim().orEmpty()
      val fallback = trackId?.let { trackMap[it] }

      val title = inlineTitle.ifEmpty { fallback?.title ?: trackId?.take(14) ?: "Unknown Track" }
      val artist = inlineArtist.ifEmpty { fallback?.artist ?: "Unknown Artist" }
      val album = inlineAlbum.ifEmpty { fallback?.album.orEmpty() }
      rows.add(ScrobbleRow(trackId, timestamp, title, artist, album, formatTimeAgo(timestamp, now)))
    }
    return rows
  }

  private fun fetchFromChain(
    userAddress: String,
    max: Int,
    latestBlock: Long,
  ): List<ScrobbleRow> {
    val rows = ArrayList<ChainScrobble>(max)
    var toBlock = latestBlock
    while (toBlock >= SCROBBLE_V4_START_BLOCK && rows.size < max) {
      val fromBlock = max(SCROBBLE_V4_START_BLOCK, toBlock - CHAIN_FALLBACK_WINDOW + 1L)
      rows += fetchScrobbleLogsWindow(userAddress, fromBlock, toBlock)
      toBlock = fromBlock - 1L
    }

    val deduped =
      rows
        .sortedWith(
          compareByDescending<ChainScrobble> { it.playedAtSec }
            .thenByDescending { it.blockNumber }
            .thenByDescending { it.logIndex },
        )
        .take(max)

    if (deduped.isEmpty()) return emptyList()

    val trackMeta = fetchTrackMetaFromScrobbleV4(deduped.map { it.trackId }.distinct())
    val now = System.currentTimeMillis() / 1000
    return deduped.map { row ->
      val meta = trackMeta[row.trackId]
      ScrobbleRow(
        trackId = row.trackId,
        playedAtSec = row.playedAtSec,
        title = meta?.title ?: row.trackId.take(14),
        artist = meta?.artist ?: "Unknown Artist",
        album = meta?.album.orEmpty(),
        playedAgo = formatTimeAgo(row.playedAtSec, now),
      )
    }
  }

  private fun fetchScrobbleLogsWindow(
    userAddress: String,
    fromBlock: Long,
    toBlock: Long,
  ): List<ChainScrobble> {
    val userTopic = "0x" + "0".repeat(24) + userAddress.removePrefix("0x")
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_getLogs")
        .put(
          "params",
          JSONArray().put(
            JSONObject()
              .put("address", SHARED_WITH_YOU_SCROBBLE_V4)
              .put("fromBlock", "0x${fromBlock.toString(16)}")
              .put("toBlock", "0x${toBlock.toString(16)}")
              .put(
                "topics",
                JSONArray()
                  .put(SCROBBLED_TOPIC0)
                  .put(userTopic),
              ),
          ),
        )

    val req =
      Request.Builder()
        .url(SHARED_WITH_YOU_STORY_RPC)
        .post(payload.toString().toRequestBody(jsonMediaType))
        .build()

    client.newCall(req).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val body = JSONObject(response.body?.string().orEmpty())
      val error = body.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      val logs = body.optJSONArray("result") ?: return emptyList()
      val out = ArrayList<ChainScrobble>(logs.length())
      for (i in 0 until logs.length()) {
        val log = logs.optJSONObject(i) ?: continue
        val topics = log.optJSONArray("topics") ?: continue
        if (topics.length() < 3) continue
        val trackId = topics.optString(2, "").trim().lowercase()
        if (!trackId.matches(Regex("^0x[0-9a-f]{64}$"))) continue
        val playedAtSec = hexToLong(log.optString("data", "")) ?: continue
        val blockNumber = hexToLong(log.optString("blockNumber", "")) ?: 0L
        val logIndex = hexToLong(log.optString("logIndex", "")) ?: i.toLong()
        out += ChainScrobble(trackId, playedAtSec, blockNumber, logIndex)
      }
      return out
    }
  }

  private fun fetchLatestBlockNumber(): Long {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_blockNumber")
        .put("params", JSONArray())
    val req =
      Request.Builder()
        .url(SHARED_WITH_YOU_STORY_RPC)
        .post(payload.toString().toRequestBody(jsonMediaType))
        .build()
    client.newCall(req).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val body = JSONObject(response.body?.string().orEmpty())
      val error = body.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      return hexToLong(body.optString("result", "")) ?: 0L
    }
  }

  private fun hexToLong(value: String): Long? =
    value.trim().removePrefix("0x").ifBlank { return 0L }.toLongOrNull(16)

  private fun fetchTrackMetadata(subgraphUrl: String, ids: List<String>): Map<String, TrackMeta> {
    val quoted = ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
    val query = """{ tracks(where: { id_in: [$quoted] }) { id title artist album } }"""
    val json = runCatching { postQuery(subgraphUrl, query) }.getOrNull() ?: return emptyMap()
    val tracks = json.optJSONObject("data")?.optJSONArray("tracks") ?: return emptyMap()
    val map = HashMap<String, TrackMeta>(tracks.length())
    for (i in 0 until tracks.length()) {
      val t = tracks.optJSONObject(i) ?: continue
      val id = t.optString("id", "").trim()
      if (id.isEmpty()) continue
      map[id] = TrackMeta(
        title = t.optString("title", "").trim().ifEmpty { "Unknown Track" },
        artist = t.optString("artist", "").trim().ifEmpty { "Unknown Artist" },
        album = t.optString("album", "").trim(),
      )
    }
    return map
  }

  private data class TrackMeta(val title: String, val artist: String, val album: String)

  private fun formatTimeAgo(playedAtSec: Long, nowSec: Long): String {
    if (playedAtSec <= 0) return "Unknown"
    if (playedAtSec >= nowSec) return "Just now"
    val d = nowSec - playedAtSec
    return when {
      d < 60 -> "${d}s ago"
      d < 3600 -> "${d / 60} ${p(d / 60, "min")} ago"
      d < 86400 -> "${d / 3600} ${p(d / 3600, "hr")} ago"
      d < 604800 -> "${d / 86400} ${p(d / 86400, "day")} ago"
      d < 2592000 -> "${d / 604800} ${p(d / 604800, "wk")} ago"
      else -> "${d / 2592000} ${p(d / 2592000, "mo")} ago"
    }
  }

  private fun p(v: Long, unit: String) = if (v == 1L) unit else "${unit}s"

  private fun postQuery(subgraphUrl: String, query: String): JSONObject {
    val body = JSONObject().put("query", query).toString().toRequestBody(jsonMediaType)
    val req = Request.Builder().url(subgraphUrl).post(body).build()
    return client.newCall(req).execute().use { res ->
      val responseBody = res.body?.string().orEmpty()
      if (!res.isSuccessful) throw IllegalStateException("Subgraph query failed: ${res.code}")
      val json =
        runCatching { JSONObject(responseBody) }
          .getOrElse { throw IllegalStateException("Subgraph query failed: invalid response", it) }
      val errors = json.optJSONArray("errors")
      if (errors != null && errors.length() > 0) {
        val msg = errors.optJSONObject(0)?.optString("message", "GraphQL error") ?: "GraphQL error"
        throw IllegalStateException(msg)
      }
      json
    }
  }
}
