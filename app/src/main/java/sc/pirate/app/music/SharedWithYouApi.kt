package sc.pirate.app.music

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Shared-with-you data layer (Tempo subgraphs via Goldsky).
 *
 * Semantics:
 * - Tracks: music-social subgraph AccessGrant -> ContentEntry (contentId/pieceCid/datasetOwner/algo)
 * - Playlists: playlists subgraph PlaylistShare -> (playlistId + tracksHash + version snapshot)
 *
 * Notes:
 * - ContentEntry.trackId may not match PlaylistV1 trackId for the "same" song due to duplicate
 *   registrations. We resolve playable content for playlist tracks by matching metaHash.
 */
object SharedWithYouApi {
  suspend fun fetchSharedTracks(granteeAddress: String, maxEntries: Int = 100): List<SharedCloudTrack> =
    withContext(Dispatchers.IO) {
      val addr = granteeAddress.trim().lowercase()
      if (addr.isBlank()) return@withContext emptyList()

      val query =
        """
          {
            accessGrants(
              where: { grantee: "$addr", granted: true }
              orderBy: updatedAt
              orderDirection: desc
              first: $maxEntries
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
      if (grants.length() == 0) return@withContext emptyList()

      val contentRows = ArrayList<ContentRow>(grants.length())
      val trackIds = LinkedHashSet<String>()
      for (i in 0 until grants.length()) {
        val g = grants.optJSONObject(i) ?: continue
        val updatedAt = g.optString("updatedAt", "0").trim().toLongOrNull() ?: 0L
        val c = g.optJSONObject("content") ?: continue
        val contentId = c.optString("id", "").trim().lowercase()
        val trackId = c.optString("trackId", "").trim().lowercase()
        if (contentId.isEmpty() || trackId.isEmpty()) continue
        val pieceCid = decodeBytesUtf8(c.optString("pieceCid", "").trim())
        val datasetOwner = c.optString("datasetOwner", "").trim().lowercase()
        val owner = c.optString("owner", "").trim().lowercase()
        val algo = c.optInt("algo", 1)
        contentRows.add(
          ContentRow(
            contentId = contentId,
            trackId = trackId,
            owner = owner,
            pieceCid = pieceCid,
            datasetOwner = datasetOwner,
            algo = algo,
            updatedAtSec = updatedAt,
          ),
        )
        trackIds.add(trackId)
      }

      val trackMeta = fetchTrackMeta(trackIds.toList())

      val out = ArrayList<SharedCloudTrack>(contentRows.size)
      for (row in contentRows) {
        val meta = trackMeta[row.trackId]
        out.add(
          SharedCloudTrack(
            contentId = row.contentId,
            trackId = row.trackId,
            owner = row.owner,
            pieceCid = row.pieceCid,
            datasetOwner = row.datasetOwner,
            algo = row.algo,
            updatedAtSec = row.updatedAtSec,
            title = meta?.title ?: row.trackId.take(14),
            artist = meta?.artist ?: "Unknown Artist",
            album = meta?.album.orEmpty(),
            coverCid = meta?.coverCid,
            lyricsRef = meta?.lyricsRef,
            durationSec = meta?.durationSec ?: 0,
            metaHash = meta?.metaHash,
          ),
        )
      }

      Log.d(SHARED_WITH_YOU_TAG, "fetchSharedTracks grantee=$addr count=${out.size}")
      out
    }

  suspend fun fetchSharedPlaylists(granteeAddress: String, maxEntries: Int = 50): List<PlaylistShareEntry> =
    withContext(Dispatchers.IO) {
      val addr = granteeAddress.trim().lowercase()
      if (addr.isBlank()) return@withContext emptyList()

      val query =
        """
          {
            playlistShares(
              where: { grantee: "$addr", granted: true }
              orderBy: updatedAt
              orderDirection: desc
              first: $maxEntries
            ) {
              id
              playlistId
              owner
              grantee
              granted
              playlistVersion
              trackCount
              tracksHash
              sharedAt
              updatedAt
              playlist {
                id
                owner
                name
                coverCid
                visibility
                trackCount
                version
                exists
                tracksHash
                createdAt
                updatedAt
              }
            }
          }
        """.trimIndent()

      val json = postQuery(sharedWithYouPlaylistsSubgraphUrl(), query)
      val shares = json.optJSONObject("data")?.optJSONArray("playlistShares") ?: JSONArray()
      if (shares.length() == 0) return@withContext emptyList()

      val out = ArrayList<PlaylistShareEntry>(shares.length())
      for (i in 0 until shares.length()) {
        val s = shares.optJSONObject(i) ?: continue
        val playlistObj = s.optJSONObject("playlist") ?: continue
        val playlist =
          OnChainPlaylist(
            id = playlistObj.optString("id", "").trim(),
            owner = playlistObj.optString("owner", "").trim(),
            name = playlistObj.optString("name", "").trim(),
            coverCid = normalizeSharedDecodedString(playlistObj.optString("coverCid", "")).orEmpty(),
            visibility = playlistObj.optInt("visibility", 0),
            trackCount = playlistObj.optInt("trackCount", 0),
            version = playlistObj.optInt("version", 0),
            exists = playlistObj.optBoolean("exists", false),
            tracksHash = playlistObj.optString("tracksHash", "").trim(),
            createdAtSec = playlistObj.optString("createdAt", "0").trim().toLongOrNull() ?: 0L,
            updatedAtSec = playlistObj.optString("updatedAt", "0").trim().toLongOrNull() ?: 0L,
          )

        out.add(
          PlaylistShareEntry(
            id = s.optString("id", "").trim(),
            playlistId = s.optString("playlistId", "").trim(),
            owner = s.optString("owner", "").trim(),
            grantee = s.optString("grantee", "").trim(),
            granted = s.optBoolean("granted", false),
            playlistVersion = s.optInt("playlistVersion", 0),
            trackCount = s.optInt("trackCount", 0),
            tracksHash = s.optString("tracksHash", "").trim(),
            sharedAtSec = s.optString("sharedAt", "0").trim().toLongOrNull() ?: 0L,
            updatedAtSec = s.optString("updatedAt", "0").trim().toLongOrNull() ?: 0L,
            playlist = playlist,
          ),
        )
      }

      Log.d(SHARED_WITH_YOU_TAG, "fetchSharedPlaylists grantee=$addr count=${out.size}")
      out
    }

  suspend fun fetchSharedPlaylistTracks(share: PlaylistShareEntry): List<SharedCloudTrack> =
    withContext(Dispatchers.IO) {
      val playlistId = share.playlistId.trim().lowercase()
      val tracksHash = share.tracksHash.trim().lowercase()
      if (playlistId.isEmpty() || tracksHash.isEmpty()) return@withContext emptyList()

      val orderedTrackIds = fetchPlaylistTrackIdsAtCheckpoint(playlistId, tracksHash, share.playlistVersion)
      if (orderedTrackIds.isEmpty()) return@withContext emptyList()

      // Resolve only content that this grantee can actually decrypt.
      val granted = buildGrantedContentIndexes(ownerAddress = share.owner, granteeAddress = share.grantee, minUpdatedAtSec = null)
      val aliasByRaw = resolveTrackIdAliasesFromContentIds(orderedTrackIds)
      val resolvedTrackIds =
        orderedTrackIds.map { raw ->
          val key = raw.trim().lowercase()
          aliasByRaw[key] ?: granted.byContentId[key]?.trackId ?: key
        }
      val trackMeta = fetchTrackMeta(resolvedTrackIds.distinct())

      val out = ArrayList<SharedCloudTrack>(orderedTrackIds.size)
      var resolvedCount = 0
      for ((idx, rawTrackId) in orderedTrackIds.withIndex()) {
        val resolvedTrackId = resolvedTrackIds.getOrNull(idx) ?: rawTrackId
        val meta = trackMeta[resolvedTrackId] ?: trackMeta[rawTrackId]
        val mh = meta?.metaHash?.lowercase()
        val content =
          when {
            !mh.isNullOrBlank() -> {
              granted.byMetaHash[mh]
                ?: granted.byTrackId[resolvedTrackId]
                ?: granted.byTrackId[rawTrackId]
                ?: granted.byContentId[rawTrackId]
            }
            else ->
              granted.byTrackId[resolvedTrackId]
                ?: granted.byTrackId[rawTrackId]
                ?: granted.byContentId[rawTrackId]
          }
        if (content != null) resolvedCount += 1

        out.add(
          SharedCloudTrack(
            contentId = content?.contentId.orEmpty(),
            trackId = resolvedTrackId,
            owner = share.owner.trim().lowercase(),
            pieceCid = content?.pieceCid.orEmpty(),
            datasetOwner = content?.datasetOwner.orEmpty(),
            algo = content?.algo ?: ContentCryptoConfig.ALGO_AES_GCM_256,
            updatedAtSec = share.updatedAtSec,
            title = meta?.title ?: "Unknown Track",
            artist = meta?.artist ?: "Unknown Artist",
            album = meta?.album.orEmpty(),
            coverCid = meta?.coverCid,
            lyricsRef = meta?.lyricsRef,
            durationSec = meta?.durationSec ?: 0,
            metaHash = meta?.metaHash,
          ),
        )
      }
      Log.d(
        SHARED_WITH_YOU_TAG,
        "fetchSharedPlaylistTracks playlistId=${share.playlistId.take(10)}.. v=${share.playlistVersion} tracks=${out.size} resolved=$resolvedCount aliases=${aliasByRaw.size}",
      )
      out
    }
}
