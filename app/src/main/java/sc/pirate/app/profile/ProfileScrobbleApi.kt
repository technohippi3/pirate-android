package sc.pirate.app.profile

import sc.pirate.app.music.CoverRef
import sc.pirate.app.util.tempoMusicSocialSubgraphUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class ScrobbleRow(
  val trackId: String?,
  val playedAtSec: Long,
  val title: String,
  val artist: String,
  val album: String,
  val playedAgo: String,
)

object ProfileScrobbleApi {
  private val client = OkHttpClient()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  fun coverUrl(cid: String, size: Int = 96): String? =
    CoverRef.resolveCoverUrl(ref = cid, width = size, height = size, format = "webp", quality = 80)

  suspend fun fetchScrobbles(userAddress: String, max: Int = 100): List<ScrobbleRow> = withContext(Dispatchers.IO) {
    val addr = userAddress.trim().lowercase()
    if (addr.isBlank()) return@withContext emptyList()

    var sawSuccessfulEmpty = false
    var subgraphError: Throwable? = null
    for (subgraphUrl in musicSocialSubgraphUrls()) {
      try {
        val rows = fetchFromSubgraph(subgraphUrl, addr, max)
        if (rows.isNotEmpty()) return@withContext rows
        sawSuccessfulEmpty = true
      } catch (error: Throwable) {
        subgraphError = error
      }
    }

    if (sawSuccessfulEmpty) return@withContext emptyList()
    if (subgraphError != null) throw subgraphError
    emptyList()
  }

  private fun musicSocialSubgraphUrls(): List<String> = tempoMusicSocialSubgraphUrls()

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
