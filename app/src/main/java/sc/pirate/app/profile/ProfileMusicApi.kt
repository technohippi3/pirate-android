package sc.pirate.app.profile

import sc.pirate.app.BuildConfig
import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.song.SongArtistApi
import sc.pirate.app.util.tempoMusicSocialSubgraphUrls
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
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8

data class PublishedSongRow(
  val contentId: String,
  val trackId: String,
  val title: String,
  val artist: String,
  val album: String,
  val pieceCid: String?,
  val coverCid: String?,
  val lyricsRef: String?,
  val durationSec: Int,
  val publishedAtSec: Long,
)

object ProfileMusicApi {
  private const val VISIBILITY_PUBLIC = 0
  private const val ZERO_BYTES32 = "0x0000000000000000000000000000000000000000000000000000000000000000"
  private val client = OkHttpClient()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  suspend fun fetchPublishedSongs(ownerAddress: String, maxEntries: Int = 100): List<PublishedSongRow> = withContext(Dispatchers.IO) {
    val addr = ownerAddress.trim().lowercase()
    if (addr.isBlank()) return@withContext emptyList()
    fetchPublishedSongsInternal(ownerAddress = addr, maxEntries = maxEntries)
  }

  suspend fun fetchLatestPublishedSongs(maxEntries: Int = 100): List<PublishedSongRow> = withContext(Dispatchers.IO) {
    fetchPublishedSongsInternal(ownerAddress = null, maxEntries = maxEntries)
  }

  suspend fun fetchPlaylistTracks(playlistId: String): List<PublishedSongRow> = withContext(Dispatchers.IO) {
    val trackIds = sc.pirate.app.music.OnChainPlaylistsApi.fetchPlaylistTrackIds(playlistId)
    if (trackIds.isEmpty()) return@withContext emptyList()
    val normalized = trackIds.map { it.trim().lowercase() }.filter { it.isNotBlank() }
    for (subgraphUrl in musicSocialSubgraphUrls()) {
      val meta = runCatching { fetchTrackMetadata(subgraphUrl, normalized) }.getOrNull() ?: continue
      if (meta.isEmpty()) continue
      return@withContext normalized.mapNotNull { trackId ->
        val m = meta[trackId] ?: return@mapNotNull null
        PublishedSongRow(
          contentId = trackId,
          trackId = trackId,
          title = m.title.ifBlank { trackId.take(14) },
          artist = m.artist.ifBlank { "Unknown Artist" },
          album = m.album,
          pieceCid = null,
          coverCid = m.coverCid,
          lyricsRef = m.lyricsRef,
          durationSec = m.durationSec,
          publishedAtSec = 0L,
        )
      }
    }
    emptyList()
  }

  private suspend fun fetchPublishedSongsInternal(ownerAddress: String?, maxEntries: Int): List<PublishedSongRow> {
    var sawSuccessfulEmpty = false
    var subgraphError: Throwable? = null
    for (subgraphUrl in musicSocialSubgraphUrls()) {
      try {
        val rows =
          if (ownerAddress.isNullOrBlank()) {
            fetchLatestFromSubgraph(subgraphUrl, maxEntries)
          } else {
            fetchPublishedByOwnerFromSubgraph(subgraphUrl, ownerAddress, maxEntries)
          }
        if (rows.isNotEmpty()) return rows
        sawSuccessfulEmpty = true
      } catch (error: Throwable) {
        subgraphError = error
      }
    }

    if (ownerAddress.isNullOrBlank() && !sawSuccessfulEmpty && isSubgraphAvailabilityError(subgraphError)) {
      val chainRows = runCatching { SongArtistApi.fetchLatestTracksFromChain(maxEntries = maxEntries) }.getOrNull().orEmpty()
      if (chainRows.isNotEmpty()) {
        return chainRows.map { row ->
          PublishedSongRow(
            contentId = row.trackId,
            trackId = row.trackId,
            title = row.title,
            artist = row.artist,
            album = row.album,
            pieceCid = null,
            coverCid = row.coverCid,
            lyricsRef = row.lyricsRef,
            durationSec = row.durationSec,
            publishedAtSec = row.registeredAtSec,
          )
        }
      }
    }

    if (sawSuccessfulEmpty) return emptyList()
    if (subgraphError != null && ownerAddress.isNullOrBlank() && isSubgraphAvailabilityError(subgraphError)) {
      throw IllegalStateException(buildSubgraphUnavailableMessage(subgraphError), subgraphError)
    }
    if (subgraphError != null) throw subgraphError
    return emptyList()
  }

  private data class ContentRow(
    val contentId: String,
    val trackId: String,
    val owner: String,
    val pieceCid: String?,
    val publishedAtSec: Long,
  )

  private data class ReleaseVisibility(
    val active: Boolean,
    val visibility: Int,
  )

  private data class TrackMeta(
    val title: String,
    val artist: String,
    val album: String,
    val coverCid: String?,
    val lyricsRef: String?,
    val durationSec: Int,
    val kind: Int?,
  )

  private fun musicSocialSubgraphUrls(): List<String> = tempoMusicSocialSubgraphUrls()

  private fun isSubgraphAvailabilityError(error: Throwable?): Boolean {
    val msg = error?.message?.lowercase().orEmpty()
    if (msg.isBlank()) return false
    return msg.contains("subgraph query failed: 530") ||
      msg.contains("subgraph query failed: 52") ||
      msg.contains("subgraph query failed: invalid response") ||
      msg.contains("origin dns")
  }

  private fun buildSubgraphUnavailableMessage(error: Throwable?): String {
    val msg = error?.message?.lowercase().orEmpty()
    return when {
      msg.contains("530") ||
        msg.contains("52") ||
        msg.contains("origin dns") ||
        msg.contains("invalid response") ->
        "New releases temporarily unavailable (subgraph endpoint unreachable)."
      else -> "New releases temporarily unavailable (subgraph error)."
    }
  }

  private fun fetchPublishedByOwnerFromSubgraph(
    subgraphUrl: String,
    ownerAddress: String,
    maxEntries: Int,
  ): List<PublishedSongRow> {
    val contentQuery = """
      {
        contentEntries(
          where: { owner: "$ownerAddress", active: true }
          orderBy: createdAt
          orderDirection: desc
          first: $maxEntries
        ) {
          id
          trackId
          owner
          pieceCid
          createdAt
        }
      }
    """.trimIndent()

    val contentJson = postQuery(subgraphUrl, contentQuery)
    val contentEntries = contentJson.optJSONObject("data")?.optJSONArray("contentEntries") ?: JSONArray()
    if (contentEntries.length() == 0) return emptyList()

    val uniqueByTrackId = LinkedHashMap<String, ContentRow>(contentEntries.length())
    val trackIds = LinkedHashSet<String>(contentEntries.length())
    for (i in 0 until contentEntries.length()) {
      val obj = contentEntries.optJSONObject(i) ?: continue
      val contentId = obj.optString("id", "").trim().lowercase()
      val trackId = obj.optString("trackId", "").trim().lowercase()
      if (contentId.isEmpty() || trackId.isEmpty()) continue
      if (uniqueByTrackId.containsKey(trackId)) continue
      val pieceCid = decodeBytesUtf8(obj.optString("pieceCid", "").trim()).ifBlank { null }
      val owner = obj.optString("owner", "").trim().lowercase()
      val createdAt = obj.optString("createdAt", "0").trim().toLongOrNull() ?: obj.optLong("createdAt", 0L)
      uniqueByTrackId[trackId] = ContentRow(contentId = contentId, trackId = trackId, owner = owner, pieceCid = pieceCid, publishedAtSec = createdAt)
      trackIds.add(trackId)
    }
    if (uniqueByTrackId.isEmpty()) return emptyList()

    val trackMeta = fetchTrackMetadata(subgraphUrl, trackIds.toList())
    val out = ArrayList<PublishedSongRow>(uniqueByTrackId.size)
    for (entry in uniqueByTrackId.values) {
      val meta = trackMeta[entry.trackId] ?: continue
      if (!isPublishedTrackKind(meta.kind)) continue
      out.add(
        PublishedSongRow(
          contentId = entry.contentId,
          trackId = entry.trackId,
          title = meta?.title.orEmpty().ifBlank { entry.trackId.take(14) },
          artist = meta?.artist.orEmpty().ifBlank { "Unknown Artist" },
          album = meta?.album.orEmpty(),
          pieceCid = entry.pieceCid,
          coverCid = meta?.coverCid,
          lyricsRef = meta?.lyricsRef,
          durationSec = meta?.durationSec ?: 0,
          publishedAtSec = entry.publishedAtSec,
        ),
      )
    }
    return out
  }

  private fun fetchLatestFromSubgraph(subgraphUrl: String, maxEntries: Int): List<PublishedSongRow> {
    val contentQuery = """
      {
        contentEntries(
          where: { active: true }
          orderBy: createdAt
          orderDirection: desc
          first: $maxEntries
        ) {
          id
          trackId
          owner
          pieceCid
          createdAt
        }
      }
    """.trimIndent()

    val contentJson = postQuery(subgraphUrl, contentQuery)
    val contentEntries = contentJson.optJSONObject("data")?.optJSONArray("contentEntries") ?: JSONArray()
    if (contentEntries.length() == 0) return emptyList()

    val uniqueByTrackId = LinkedHashMap<String, ContentRow>(contentEntries.length())
    val trackIds = LinkedHashSet<String>(contentEntries.length())
    for (i in 0 until contentEntries.length()) {
      val obj = contentEntries.optJSONObject(i) ?: continue
      val contentId = obj.optString("id", "").trim().lowercase()
      val trackId = obj.optString("trackId", "").trim().lowercase()
      if (contentId.isEmpty() || trackId.isEmpty()) continue
      if (uniqueByTrackId.containsKey(trackId)) continue
      val pieceCid = decodeBytesUtf8(obj.optString("pieceCid", "").trim()).ifBlank { null }
      val owner = obj.optString("owner", "").trim().lowercase()
      val createdAt = obj.optString("createdAt", "0").trim().toLongOrNull() ?: obj.optLong("createdAt", 0L)
      uniqueByTrackId[trackId] = ContentRow(contentId = contentId, trackId = trackId, owner = owner, pieceCid = pieceCid, publishedAtSec = createdAt)
      trackIds.add(trackId)
    }
    if (uniqueByTrackId.isEmpty()) return emptyList()
    val publicContentIds = resolvePublicContentIds(uniqueByTrackId.values)

    val trackMeta = fetchTrackMetadata(subgraphUrl, trackIds.toList())
    val out = ArrayList<PublishedSongRow>(uniqueByTrackId.size)
    for (entry in uniqueByTrackId.values) {
      if (!publicContentIds.contains(entry.contentId)) continue
      val meta = trackMeta[entry.trackId] ?: continue
      if (!isPublishedTrackKind(meta.kind)) continue
      out.add(
        PublishedSongRow(
          contentId = entry.contentId,
          trackId = entry.trackId,
          title = meta?.title.orEmpty().ifBlank { entry.trackId.take(14) },
          artist = meta?.artist.orEmpty().ifBlank { "Unknown Artist" },
          album = meta?.album.orEmpty(),
          pieceCid = entry.pieceCid,
          coverCid = meta?.coverCid,
          lyricsRef = meta?.lyricsRef,
          durationSec = meta?.durationSec ?: 0,
          publishedAtSec = entry.publishedAtSec,
        ),
      )
    }
    return out
  }

  private fun resolvePublicContentIds(entries: Collection<ContentRow>): Set<String> {
    if (entries.isEmpty()) return emptySet()
    val coordinatorAddress = resolvePublishCoordinatorAddressOrNull()
    if (coordinatorAddress == null) {
      return entries.map { it.contentId }.toSet()
    }

    val include = LinkedHashSet<String>(entries.size)
    val cachedStateByOwnerTrack = HashMap<String, ReleaseVisibility?>()
    for (entry in entries) {
      val owner = normalizeAddressOrNull(entry.owner)
      val trackId = normalizeBytes32OrNull(entry.trackId)
      if (owner == null || trackId == null) {
        include.add(entry.contentId)
        continue
      }

      val cacheKey = "$owner|$trackId"
      val state =
        if (cachedStateByOwnerTrack.containsKey(cacheKey)) {
          cachedStateByOwnerTrack[cacheKey]
        } else {
          val resolved =
            queryActivePublishId(
              coordinatorAddress = coordinatorAddress,
              ownerAddress = owner,
              trackId = trackId,
            )?.let { publishId ->
              queryReleaseVisibility(coordinatorAddress = coordinatorAddress, publishId = publishId)
            }
          cachedStateByOwnerTrack[cacheKey] = resolved
          resolved
        }

      // If release state can't be resolved, keep row to avoid hard-failing new releases.
      if (state == null) {
        include.add(entry.contentId)
        continue
      }
      if (state.active && state.visibility == VISIBILITY_PUBLIC) {
        include.add(entry.contentId)
      }
    }
    return include
  }

  private fun resolvePublishCoordinatorAddressOrNull(): String? {
    val raw = BuildConfig.TEMPO_PUBLISH_COORDINATOR.trim()
    if (raw.isBlank()) return null
    val normalized = normalizeAddressOrNull(raw) ?: return null
    if (normalized == "0x0000000000000000000000000000000000000000") return null
    return normalized
  }

  private fun normalizeAddressOrNull(raw: String?): String? {
    val value = raw?.trim().orEmpty().lowercase()
    if (!value.matches(Regex("^0x[0-9a-f]{40}$"))) return null
    return value
  }

  private fun normalizeBytes32OrNull(raw: String?): String? {
    val value = raw?.trim().orEmpty().lowercase()
    if (!value.matches(Regex("^0x[0-9a-f]{64}$"))) return null
    return value
  }

  private fun queryActivePublishId(
    coordinatorAddress: String,
    ownerAddress: String,
    trackId: String,
  ): String? {
    val normalizedOwner = normalizeAddressOrNull(ownerAddress) ?: return null
    val normalizedTrackId = normalizeBytes32OrNull(trackId) ?: return null
    val function =
      Function(
        "activePublishId",
        listOf(
          Address(normalizedOwner),
          Bytes32(P256Utils.hexToBytes(normalizedTrackId)),
        ),
        listOf(object : TypeReference<Bytes32>() {}),
      )
    val data = FunctionEncoder.encode(function)
    val raw = ethCall(coordinatorAddress, data) ?: return null
    val decoded = runCatching { FunctionReturnDecoder.decode(raw, function.outputParameters) }.getOrNull() ?: return null
    val value = (decoded.getOrNull(0) as? Bytes32)?.value ?: return null
    val hex = "0x" + value.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    return if (hex == ZERO_BYTES32) null else hex
  }

  private fun queryReleaseVisibility(
    coordinatorAddress: String,
    publishId: String,
  ): ReleaseVisibility? {
    val normalizedPublishId = normalizeBytes32OrNull(publishId) ?: return null
    val function =
      Function(
        "releases",
        listOf(Bytes32(P256Utils.hexToBytes(normalizedPublishId))),
        listOf(
          object : TypeReference<Bool>() {}, // exists
          object : TypeReference<Bool>() {}, // active
          object : TypeReference<Address>() {},
          object : TypeReference<Bytes32>() {},
          object : TypeReference<Bytes32>() {},
          object : TypeReference<Uint8>() {},
          object : TypeReference<Bytes32>() {},
          object : TypeReference<Uint32>() {},
          object : TypeReference<Uint8>() {}, // visibility
          object : TypeReference<Uint64>() {},
          object : TypeReference<Address>() {},
          object : TypeReference<Uint8>() {},
          object : TypeReference<Bytes32>() {},
          object : TypeReference<Bytes32>() {},
        ),
      )
    val data = FunctionEncoder.encode(function)
    val raw = ethCall(coordinatorAddress, data) ?: return null
    val decoded = runCatching { FunctionReturnDecoder.decode(raw, function.outputParameters) }.getOrNull() ?: return null
    if (decoded.size < 9) return null
    val exists = (decoded[0] as? Bool)?.value ?: false
    if (!exists) return null
    val active = (decoded[1] as? Bool)?.value ?: false
    val visibility = (decoded[8] as? Uint8)?.value?.toInt() ?: return null
    return ReleaseVisibility(active = active, visibility = visibility)
  }

  private fun ethCall(to: String, data: String): String? {
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
        .url(TempoClient.RPC_URL)
        .post(payload.toString().toRequestBody(jsonMediaType))
        .build()
    return runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        val json = JSONObject(response.body?.string().orEmpty())
        if (json.optJSONObject("error") != null) return@use null
        val result = json.optString("result", "")
        if (!result.startsWith("0x")) return@use null
        result
      }
    }.getOrNull()
  }

  private fun fetchTrackMetadata(subgraphUrl: String, ids: List<String>): Map<String, TrackMeta> {
    if (ids.isEmpty()) return emptyMap()
    val out = HashMap<String, TrackMeta>(ids.size)
    val chunkSize = 200
    for (start in ids.indices step chunkSize) {
      val chunk = ids.subList(start, minOf(start + chunkSize, ids.size))
      val quoted = chunk.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
      val query = """
        {
          tracks(where: { id_in: [$quoted], kind: 3 }, first: 1000) {
            id
            title
            artist
            album
            coverCid
            lyricsRef
            durationSec
            kind
          }
        }
      """.trimIndent()

      val json = runCatching { postQuery(subgraphUrl, query) }.getOrNull() ?: continue
      val tracks = json.optJSONObject("data")?.optJSONArray("tracks") ?: JSONArray()
      for (i in 0 until tracks.length()) {
        val t = tracks.optJSONObject(i) ?: continue
        val id = t.optString("id", "").trim().lowercase()
        if (id.isEmpty()) continue
        val cover = t.optString("coverCid", "").trim().ifEmpty { null }?.takeIf { isValidCid(it) }
        out[id] = TrackMeta(
          title = t.optString("title", "").trim(),
          artist = t.optString("artist", "").trim(),
          album = t.optString("album", "").trim(),
          coverCid = cover,
          lyricsRef = decodeBytesUtf8(t.optString("lyricsRef", "").trim()).ifBlank { null },
          durationSec = t.optInt("durationSec", 0),
          kind = t.optString("kind", "").trim().toIntOrNull(),
        )
      }
    }
    return out
  }

  private fun isPublishedTrackKind(kind: Int?): Boolean = kind == 3

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

  private fun isValidCid(v: String): Boolean {
    return v.startsWith("Qm") ||
      v.startsWith("bafy") ||
      v.startsWith("ipfs://") ||
      v.startsWith("ar://") ||
      v.startsWith("http://") ||
      v.startsWith("https://")
  }

  private fun decodeBytesUtf8(value: String): String {
    val v = value.trim()
    if (!v.startsWith("0x")) return v
    val hex = v.removePrefix("0x")
    if (hex.isEmpty() || hex.length % 2 != 0) return v
    if (!hex.all { it.isDigit() || (it.lowercaseChar() in 'a'..'f') }) return v
    return try {
      val bytes = ByteArray(hex.length / 2)
      var i = 0
      while (i < hex.length) {
        bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        i += 2
      }
      bytes.toString(Charsets.UTF_8).trimEnd { it == '\u0000' }
    } catch (_: Throwable) {
      v
    }
  }
}
