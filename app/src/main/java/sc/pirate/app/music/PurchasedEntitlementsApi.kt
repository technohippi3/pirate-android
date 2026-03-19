package sc.pirate.app.music

import android.util.Log
import sc.pirate.app.util.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

private const val TAG_PURCHASED_LIBRARY = "PurchasedLibraryApi"

private data class PurchasedEntitlementRow(
  val purchaseId: String,
  val songTrackId: String,
  val status: String,
  val title: String?,
  val artist: String?,
  val album: String?,
)

internal suspend fun fetchPurchasedCloudLibraryTracks(
  ownerEthAddress: String,
  limit: Int = 200,
): List<MusicTrack> = withContext(Dispatchers.IO) {
  val owner = ownerEthAddress.trim().lowercase()
  if (owner.isBlank()) return@withContext emptyList()

  val apiBase = SongPublishService.API_CORE_URL.trim().trimEnd('/')
  if (!apiBase.startsWith("http://") && !apiBase.startsWith("https://")) return@withContext emptyList()

  val safeLimit = limit.coerceIn(1, 500)
  val request =
    Request.Builder()
      .url("$apiBase/api/music/purchase/entitlements?limit=$safeLimit")
      .header("X-User-Address", owner)
      .get()
      .build()

  val rawRows =
    HttpClients.Api.newCall(request).execute().use { response ->
      val payload = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        if (response.code == 401 || response.code == 403 || response.code == 404) return@withContext emptyList()
        throw IllegalStateException("Purchase entitlements fetch failed: HTTP ${response.code}")
      }

      val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@withContext emptyList()
      val arr = json.optJSONArray("entitlements") ?: return@withContext emptyList()
      val rows = ArrayList<PurchasedEntitlementRow>(arr.length())
      for (i in 0 until arr.length()) {
        val row = arr.optJSONObject(i) ?: continue
        val purchaseId = row.optString("purchaseId", "").trim()
        val songTrackId = row.optString("songTrackId", "").trim().lowercase()
        val status = row.optString("status", "").trim().lowercase()
        if (purchaseId.isBlank() || songTrackId.isBlank()) continue
        if (status != "active") continue
        rows.add(
          PurchasedEntitlementRow(
            purchaseId = purchaseId,
            songTrackId = songTrackId,
            status = status,
            title = row.optString("trackTitle", "").trim().ifBlank { null },
            artist = row.optString("trackArtist", "").trim().ifBlank { null },
            album = row.optString("trackAlbum", "").trim().ifBlank { null },
          ),
        )
      }
      rows
    }
  if (rawRows.isEmpty()) return@withContext emptyList()

  val dedupedByTrackId = LinkedHashMap<String, PurchasedEntitlementRow>(rawRows.size)
  for (row in rawRows) {
    if (!dedupedByTrackId.containsKey(row.songTrackId)) {
      dedupedByTrackId[row.songTrackId] = row
    }
  }

  val trackIds = dedupedByTrackId.keys.toList()
  val trackMeta = runCatching { fetchTrackMeta(trackIds) }.getOrElse {
    Log.w(TAG_PURCHASED_LIBRARY, "fetchTrackMeta failed: ${it.message}")
    emptyMap()
  }

  val out = ArrayList<MusicTrack>(dedupedByTrackId.size)
  for ((trackId, row) in dedupedByTrackId) {
    val meta = trackMeta[trackId]
    out.add(
      MusicTrack(
        id = if (trackId.isNotBlank()) trackId else "purchase:${row.purchaseId}",
        canonicalTrackId = trackId.ifBlank { null },
        title = row.title ?: meta?.title ?: "Unknown Track",
        artist = row.artist ?: meta?.artist ?: "Unknown Artist",
        album = row.album ?: meta?.album.orEmpty(),
        durationSec = meta?.durationSec ?: 0,
        uri = "",
        filename = "",
        artworkUri = sharedPlaylistCoverUrl(meta?.coverCid),
        contentId = null,
        pieceCid = null,
        datasetOwner = null,
        algo = null,
        lyricsRef = meta?.lyricsRef,
        isCloudOnly = true,
        purchaseId = row.purchaseId,
      ),
    )
  }
  out
}
