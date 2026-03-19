package sc.pirate.app.music

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MusicLibrary {
  private const val TRACKS_CACHE_FILENAME = "pirate_music_tracks.json"

  fun loadCachedTracksBlocking(context: Context): List<MusicTrack> {
    val file = File(context.filesDir, TRACKS_CACHE_FILENAME)
    if (!file.exists()) return emptyList()
    val raw = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()
    return decodeTracksJson(raw)
  }

  suspend fun loadCachedTracks(context: Context): List<MusicTrack> = withContext(Dispatchers.IO) {
    return@withContext loadCachedTracksBlocking(context)
  }

  suspend fun saveCachedTracks(context: Context, tracks: List<MusicTrack>) = withContext(Dispatchers.IO) {
    val file = File(context.filesDir, TRACKS_CACHE_FILENAME)
    runCatching { file.writeText(encodeTracksJson(tracks)) }
  }

  suspend fun scanDeviceTracks(context: Context): List<MusicTrack> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.ALBUM_ID,
      MediaStore.Audio.Media.DATE_ADDED,
    )

    val selection = "${MediaStore.Audio.Media.IS_MUSIC}=1"
    val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

    val out = ArrayList<MusicTrack>(512)
    context.contentResolver.query(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
      projection,
      selection,
      null,
      sortOrder,
    )?.use { cursor ->
      val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
      val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
      val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
      val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
      val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
      val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
      val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

      while (cursor.moveToNext()) {
        val id = cursor.getLong(idCol)
        val title = cursor.getString(titleCol).orEmpty().trim()
        val rawArtist = cursor.getString(artistCol).orEmpty().trim()
        val album = cursor.getString(albumCol).orEmpty().trim()
        val durationMs = cursor.getLong(durationCol).coerceAtLeast(0)
        val displayName = cursor.getString(displayNameCol).orEmpty()
        val albumId = cursor.getLong(albumIdCol).coerceAtLeast(0)

        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val fallbackArtwork = "content://media/external/audio/media/$id/albumart"
        val primaryArtwork = if (albumId > 0) "content://media/external/audio/albumart/$albumId" else fallbackArtwork

        val artist = normalizeArtist(rawArtist, displayName)
        val normalizedTitle = if (title.isNotBlank()) title else extractTitleFromFilename(displayName)

        out.add(
          MusicTrack(
            id = id.toString(),
            title = normalizedTitle.ifBlank { "(untitled)" },
            artist = artist,
            album = album,
            durationSec = (durationMs / 1000L).toInt(),
            uri = contentUri.toString(),
            filename = displayName,
            artworkUri = primaryArtwork,
            artworkFallbackUri = if (primaryArtwork == fallbackArtwork) null else fallbackArtwork,
          ),
        )
      }
    }
    return@withContext out
  }

  private fun normalizeArtist(artist: String, filename: String): String {
    val cleaned = artist.trim()
    if (cleaned.isBlank()) return extractArtistFromFilename(filename)
    val lower = cleaned.lowercase()
    if (lower == "<unknown>" || lower == "unknown" || lower == "unknown artist") {
      return extractArtistFromFilename(filename)
    }
    return cleaned
  }

  private fun extractTitleFromFilename(filename: String): String {
    val name = filename.replace(Regex("\\.[^.]+$"), "")
    val dashIdx = name.indexOf(" - ")
    if (dashIdx > 0) return name.substring(dashIdx + 3).trim()
    val numbered = Regex("^\\d+\\.?\\s+(.+)").find(name)
    if (numbered != null) return numbered.groupValues[1].trim()
    return name.trim()
  }

  private fun extractArtistFromFilename(filename: String): String {
    val name = filename.replace(Regex("\\.[^.]+$"), "")
    val dashIdx = name.indexOf(" - ")
    if (dashIdx > 0) return name.substring(0, dashIdx).trim()
    return "Unknown Artist"
  }

  private fun encodeTracksJson(tracks: List<MusicTrack>): String {
    val arr = JSONArray()
    for (t in tracks) {
      arr.put(
        JSONObject()
          .put("id", t.id)
          .put("canonicalTrackId", t.canonicalTrackId)
          .put("title", t.title)
          .put("artist", t.artist)
          .put("album", t.album)
          .put("durationSec", t.durationSec)
          .put("uri", t.uri)
          .put("filename", t.filename)
          .put("artworkUri", t.artworkUri)
          .put("artworkFallbackUri", t.artworkFallbackUri)
          .put("contentId", t.contentId)
          .put("pieceCid", t.pieceCid)
          .put("datasetOwner", t.datasetOwner)
          .put("algo", t.algo)
          .put("permanentRef", t.permanentRef)
          .put("permanentGatewayUrl", t.permanentGatewayUrl)
          .put("permanentSavedAtMs", t.permanentSavedAtMs)
          .put("lyricsRef", t.lyricsRef)
          .put("savedForever", t.savedForever),
      )
    }
    return arr.toString()
  }

  private fun decodeTracksJson(raw: String): List<MusicTrack> {
    return runCatching {
      val arr = JSONArray(raw)
      val out = ArrayList<MusicTrack>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        out.add(
          MusicTrack(
            id = obj.optString("id", ""),
            canonicalTrackId = obj.optString("canonicalTrackId", "").ifBlank { null },
            title = obj.optString("title", ""),
            artist = obj.optString("artist", ""),
            album = obj.optString("album", ""),
            durationSec = obj.optInt("durationSec", 0),
            uri = obj.optString("uri", ""),
            filename = obj.optString("filename", ""),
            artworkUri = obj.optString("artworkUri", "").ifBlank { null },
            artworkFallbackUri = obj.optString("artworkFallbackUri", "").ifBlank { null },
            contentId = obj.optString("contentId", "").ifBlank { null },
            pieceCid = obj.optString("pieceCid", "").ifBlank { null },
            datasetOwner = obj.optString("datasetOwner", "").ifBlank { null },
            algo = obj.optInt("algo", -1).takeIf { it >= 0 },
            permanentRef = obj.optString("permanentRef", "").ifBlank { null },
            permanentGatewayUrl = obj.optString("permanentGatewayUrl", "").ifBlank { null },
            permanentSavedAtMs = obj.optLong("permanentSavedAtMs", 0L).takeIf { it > 0L },
            lyricsRef = obj.optString("lyricsRef", "").ifBlank { null },
            savedForever = obj.optBoolean("savedForever", false),
          ),
        )
      }
      out
    }.getOrElse { emptyList() }
  }
}
