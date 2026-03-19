package sc.pirate.app.music

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun fetchPlaylistTrackIdsAtCheckpoint(
  playlistId: String,
  tracksHash: String,
  asOfVersion: Int,
): List<String> = withContext(Dispatchers.IO) {
  val id = playlistId.trim().lowercase()
  val th = tracksHash.trim().lowercase()
  if (id.isEmpty() || th.isEmpty()) return@withContext emptyList()

  val versionFilter = if (asOfVersion > 0) ", version_lte: $asOfVersion" else ""
  val query =
    """
      {
        playlistTrackVersions(
          where: { playlist: "$id", tracksHash: "$th"$versionFilter }
          orderBy: version
          orderDirection: desc
          first: 1000
        ) {
          version
          trackId
          position
        }
      }
    """.trimIndent()

  val json = postQuery(sharedWithYouPlaylistsSubgraphUrl(), query)
  val rows = json.optJSONObject("data")?.optJSONArray("playlistTrackVersions") ?: JSONArray()
  if (rows.length() == 0) return@withContext emptyList()

  data class Row(val version: Int, val trackId: String, val position: Int)
  val parsed = ArrayList<Row>(rows.length())
  var maxVersion = 0
  for (i in 0 until rows.length()) {
    val r = rows.optJSONObject(i) ?: continue
    val version = r.optInt("version", 0)
    val trackId = r.optString("trackId", "").trim().lowercase()
    val position = r.optInt("position", 0)
    if (trackId.isEmpty()) continue
    parsed.add(Row(version = version, trackId = trackId, position = position))
    if (version > maxVersion) maxVersion = version
  }

  parsed
    .filter { it.version == maxVersion }
    .sortedBy { it.position }
    .map { it.trackId }
}

internal suspend fun fetchTrackMeta(trackIds: List<String>): Map<String, TrackMeta> = withContext(Dispatchers.IO) {
  if (trackIds.isEmpty()) return@withContext emptyMap()

  // Chunk to avoid enormous GraphQL bodies.
  val chunkSize = 200
  val out = HashMap<String, TrackMeta>(trackIds.size)

  for (i in trackIds.indices step chunkSize) {
    val chunk = trackIds.subList(i, minOf(i + chunkSize, trackIds.size))
    val quoted = chunk.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
    val query =
      """
        {
          tracks(where: { id_in: [$quoted] }, first: 1000) {
            id
            title
            artist
            album
            coverCid
            lyricsRef
            durationSec
            metaHash
          }
        }
      """.trimIndent()

    val json = postQuery(musicSocialSubgraphUrl(), query)
    val tracks = json.optJSONObject("data")?.optJSONArray("tracks") ?: JSONArray()
    val missingCoverMetaHashes = LinkedHashSet<String>()
    for (j in 0 until tracks.length()) {
      val t = tracks.optJSONObject(j) ?: continue
      val id = t.optString("id", "").trim().lowercase()
      if (id.isEmpty()) continue
      val metaHash = t.optString("metaHash", "").trim().ifBlank { null }
      val coverCid = normalizeSharedDecodedString(t.optString("coverCid", ""))
      if (coverCid == null && !metaHash.isNullOrBlank()) {
        missingCoverMetaHashes.add(metaHash.lowercase())
      }
      out[id] =
        TrackMeta(
          id = id,
          title = t.optString("title", "").trim().ifEmpty { id.take(14) },
          artist = t.optString("artist", "").trim().ifEmpty { "Unknown Artist" },
          album = t.optString("album", "").trim(),
          coverCid = coverCid,
          lyricsRef = normalizeSharedDecodedString(t.optString("lyricsRef", "")),
          durationSec = t.optInt("durationSec", 0),
          metaHash = metaHash,
        )
    }

    // Fill missing coverCid by metaHash (duplicate track IDs can carry the cover art).
    if (missingCoverMetaHashes.isNotEmpty()) {
      val quotedMeta = missingCoverMetaHashes.joinToString(",") { "\"$it\"" }
      val coverQuery =
        """
          {
            tracks(where: { metaHash_in: [$quotedMeta], coverCid_not: null }, first: 1000) {
              metaHash
              coverCid
            }
          }
        """.trimIndent()
      val coverJson = postQuery(musicSocialSubgraphUrl(), coverQuery)
      val coverTracks = coverJson.optJSONObject("data")?.optJSONArray("tracks") ?: JSONArray()
      if (coverTracks.length() > 0) {
        val coverByMeta = HashMap<String, String>(coverTracks.length())
        for (k in 0 until coverTracks.length()) {
          val t = coverTracks.optJSONObject(k) ?: continue
          val mh = t.optString("metaHash", "").trim().lowercase()
          val cv = normalizeSharedDecodedString(t.optString("coverCid", ""))
          if (mh.isEmpty() || cv == null) continue
          if (!coverByMeta.containsKey(mh)) coverByMeta[mh] = cv
        }

        if (coverByMeta.isNotEmpty()) {
          for (trackId in chunk) {
            val id = trackId.trim().lowercase()
            val prior = out[id] ?: continue
            if (prior.coverCid != null) continue
            val mh = prior.metaHash?.trim()?.lowercase().orEmpty()
            val cv = coverByMeta[mh] ?: continue
            out[id] = prior.copy(coverCid = cv)
          }
        }
      }
    }
  }

  // Subgraph can miss freshly-indexed or alias track rows. Fallback to on-chain ScrobbleV4.getTrack.
  val missing =
    trackIds
      .map { it.trim().lowercase() }
      .filter { it.isNotBlank() && !out.containsKey(it) }
      .distinct()
  if (missing.isNotEmpty()) {
    val onChain = fetchTrackMetaFromScrobbleV4(missing)
    if (onChain.isNotEmpty()) {
      out.putAll(onChain)
    }
    Log.d(SHARED_WITH_YOU_TAG, "fetchTrackMeta fallback v4 requested=${missing.size} resolved=${onChain.size}")

    val unresolved = missing.filter { !out.containsKey(it) }
    if (unresolved.isNotEmpty()) {
      val registration = fetchTrackRegistrationStatusFromScrobbleV4(unresolved)
      val notRegistered = unresolved.count { registration[it] == false }
      val unknownStatus = unresolved.count { registration[it] == null }
      val sample = unresolved.take(5).joinToString(",")
      Log.w(
        SHARED_WITH_YOU_TAG,
        "fetchTrackMeta unresolved=${unresolved.size} notRegistered=$notRegistered unknownStatus=$unknownStatus sample=[$sample]",
      )
    }
  }

  out
}

internal suspend fun resolveTrackIdAliasesFromContentIds(
  ids: List<String>,
): Map<String, String> = withContext(Dispatchers.IO) {
  if (ids.isEmpty()) return@withContext emptyMap()
  val normalized =
    ids
      .map { it.trim().lowercase() }
      .filter { it.isNotBlank() }
      .distinct()
  if (normalized.isEmpty()) return@withContext emptyMap()

  val out = HashMap<String, String>(normalized.size)
  val chunkSize = 200
  for (i in normalized.indices step chunkSize) {
    val chunk = normalized.subList(i, minOf(i + chunkSize, normalized.size))
    val quoted = chunk.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
    val query =
      """
        {
          contentEntries(where: { id_in: [$quoted] }, first: 1000) {
            id
            trackId
          }
        }
      """.trimIndent()

    val json = postQuery(musicSocialSubgraphUrl(), query)
    val rows = json.optJSONObject("data")?.optJSONArray("contentEntries") ?: JSONArray()
    for (j in 0 until rows.length()) {
      val row = rows.optJSONObject(j) ?: continue
      val contentId = row.optString("id", "").trim().lowercase()
      val trackId = row.optString("trackId", "").trim().lowercase()
      if (contentId.isEmpty() || trackId.isEmpty()) continue
      out[contentId] = trackId
    }
  }
  out
}

internal suspend fun buildGrantedContentIndexes(
  ownerAddress: String,
  granteeAddress: String,
  minUpdatedAtSec: Long? = null,
): GrantedContentIndexes = withContext(Dispatchers.IO) {
  val owner = ownerAddress.trim().lowercase()
  val grantee = granteeAddress.trim().lowercase()
  if (owner.isBlank() || grantee.isBlank()) {
    return@withContext GrantedContentIndexes(
      byMetaHash = emptyMap(),
      byTrackId = emptyMap(),
      byContentId = emptyMap(),
    )
  }

  val query =
    """
      {
        accessGrants(
          where: { grantee: "$grantee", granted: true }
          orderBy: updatedAt
          orderDirection: desc
          first: 1000
        ) {
          updatedAt
          content {
            id
            trackId
            owner
            datasetOwner
            pieceCid
            algo
          }
        }
      }
    """.trimIndent()

  val json = postQuery(musicSocialSubgraphUrl(), query)
  val grants = json.optJSONObject("data")?.optJSONArray("accessGrants") ?: JSONArray()
  if (grants.length() == 0) {
    return@withContext GrantedContentIndexes(
      byMetaHash = emptyMap(),
      byTrackId = emptyMap(),
      byContentId = emptyMap(),
    )
  }

  data class Row(val trackId: String, val contentId: String, val updatedAt: Long, val content: ContentMeta)
  val rows = ArrayList<Row>(grants.length())
  val trackIds = LinkedHashSet<String>()

  for (i in 0 until grants.length()) {
    val g = grants.optJSONObject(i) ?: continue
    val updatedAt = g.optString("updatedAt", "0").trim().toLongOrNull() ?: 0L
    if (minUpdatedAtSec != null && updatedAt < minUpdatedAtSec) continue
    val c = g.optJSONObject("content") ?: continue
    if (c.optString("owner", "").trim().lowercase() != owner) continue
    val trackId = c.optString("trackId", "").trim().lowercase()
    if (trackId.isEmpty()) continue
    val contentId = c.optString("id", "").trim().lowercase()
    if (contentId.isEmpty()) continue
    val pieceCid = decodeBytesUtf8(c.optString("pieceCid", "").trim())
    val datasetOwner = c.optString("datasetOwner", "").trim().lowercase()
    val algo = c.optInt("algo", ContentCryptoConfig.ALGO_AES_GCM_256)
    val meta =
      ContentMeta(
        trackId = trackId,
        contentId = contentId,
        pieceCid = pieceCid,
        datasetOwner = datasetOwner,
        algo = algo,
      )
    rows.add(Row(trackId = trackId, contentId = contentId, updatedAt = updatedAt, content = meta))
    trackIds.add(trackId)
  }

  if (rows.isEmpty() || trackIds.isEmpty()) {
    return@withContext GrantedContentIndexes(
      byMetaHash = emptyMap(),
      byTrackId = emptyMap(),
      byContentId = emptyMap(),
    )
  }
  val trackMeta = fetchTrackMeta(trackIds.toList())

  // Resolve the newest decryptable content per metaHash (iterating rows in updatedAt-desc order).
  val out = HashMap<String, ContentMeta>(trackIds.size)
  val byTrackId = HashMap<String, ContentMeta>(trackIds.size)
  val byContentId = HashMap<String, ContentMeta>(rows.size)
  for (row in rows) {
    if (!byTrackId.containsKey(row.trackId)) {
      byTrackId[row.trackId] = row.content
    }
    if (!byContentId.containsKey(row.contentId)) {
      byContentId[row.contentId] = row.content
    }
    val meta = trackMeta[row.trackId] ?: continue
    val mh = meta.metaHash?.lowercase().orEmpty()
    if (mh.isEmpty()) continue
    if (out.containsKey(mh)) continue
    out[mh] = row.content
  }

  GrantedContentIndexes(
    byMetaHash = out,
    byTrackId = byTrackId,
    byContentId = byContentId,
  )
}
