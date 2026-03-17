package com.pirate.app.music

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DownloadedTrackEntry(
  val contentId: String,
  val mediaUri: String,
  val title: String,
  val artist: String,
  val album: String,
  val filename: String,
  val mimeType: String?,
  val pieceCid: String?,
  val datasetOwner: String?,
  val algo: Int?,
  val coverCid: String?,
  val downloadedAtMs: Long,
)

object DownloadedTracksStore {
  private const val CACHE_FILENAME = "pirate_downloaded_tracks.json"

  private fun normalizeNullableString(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    if (value.equals("null", ignoreCase = true)) return null
    if (value.equals("undefined", ignoreCase = true)) return null
    return value
  }

  private fun file(context: Context): File {
    return File(context.filesDir, CACHE_FILENAME)
  }

  suspend fun load(context: Context): Map<String, DownloadedTrackEntry> = withContext(Dispatchers.IO) {
    val f = file(context)
    if (!f.exists()) return@withContext emptyMap()

    val raw = runCatching { f.readText() }.getOrNull()?.trim().orEmpty()
    if (raw.isBlank()) return@withContext emptyMap()

    return@withContext runCatching {
      val arr = JSONArray(raw)
      val out = LinkedHashMap<String, DownloadedTrackEntry>(arr.length())
      var needsRewrite = false
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val contentId = obj.optString("contentId", "").trim().lowercase()
        val mediaUri = obj.optString("mediaUri", "").trim()
        if (contentId.isBlank() || mediaUri.isBlank()) continue
        val rawCoverCid = obj.optString("coverCid", "").trim()
        val normalizedCoverCid = normalizeNullableString(rawCoverCid)
        if (normalizedCoverCid != rawCoverCid.ifBlank { null }) {
          needsRewrite = true
        }
        out[contentId] =
          DownloadedTrackEntry(
            contentId = contentId,
            mediaUri = mediaUri,
            title = obj.optString("title", "").trim(),
            artist = obj.optString("artist", "").trim(),
            album = obj.optString("album", "").trim(),
            filename = obj.optString("filename", "").trim(),
            mimeType = obj.optString("mimeType", "").trim().ifBlank { null },
            pieceCid = obj.optString("pieceCid", "").trim().ifBlank { null },
            datasetOwner = obj.optString("datasetOwner", "").trim().ifBlank { null },
            algo = obj.optInt("algo", -1).takeIf { it >= 0 },
            coverCid = normalizedCoverCid,
            downloadedAtMs = obj.optLong("downloadedAtMs", 0L),
          )
      }
      if (needsRewrite) {
        write(context, out)
      }
      out
    }.getOrElse { emptyMap() }
  }

  suspend fun upsert(
    context: Context,
    entry: DownloadedTrackEntry,
  ): Map<String, DownloadedTrackEntry> = withContext(Dispatchers.IO) {
    val key = entry.contentId.trim().lowercase()
    if (key.isBlank()) return@withContext load(context)

    val next = LinkedHashMap(load(context))
    next[key] =
      entry.copy(
        contentId = key,
        coverCid = normalizeNullableString(entry.coverCid),
      )
    write(context, next)
    next
  }

  suspend fun remove(
    context: Context,
    contentId: String,
  ): Map<String, DownloadedTrackEntry> = withContext(Dispatchers.IO) {
    val key = contentId.trim().lowercase()
    if (key.isBlank()) return@withContext load(context)

    val next = LinkedHashMap(load(context))
    next.remove(key)
    write(context, next)
    next
  }

  private fun write(
    context: Context,
    entries: Map<String, DownloadedTrackEntry>,
  ) {
    val arr = JSONArray()
    for ((_, e) in entries) {
      arr.put(
        JSONObject()
          .put("contentId", e.contentId)
          .put("mediaUri", e.mediaUri)
          .put("title", e.title)
          .put("artist", e.artist)
          .put("album", e.album)
          .put("filename", e.filename)
          .put("mimeType", e.mimeType ?: JSONObject.NULL)
          .put("pieceCid", e.pieceCid ?: JSONObject.NULL)
          .put("datasetOwner", e.datasetOwner ?: JSONObject.NULL)
          .put("algo", e.algo ?: JSONObject.NULL)
          .put("coverCid", e.coverCid ?: JSONObject.NULL)
          .put("downloadedAtMs", e.downloadedAtMs)
      )
    }

    val f = file(context)
    runCatching { f.writeText(arr.toString()) }
  }
}

object MediaStoreAudioDownloads {
  suspend fun uriExists(context: Context, uriString: String): Boolean = withContext(Dispatchers.IO) {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext false
    return@withContext runCatching {
      context.contentResolver.openInputStream(uri)?.use { _ -> true } ?: false
    }.getOrDefault(false)
  }

  suspend fun saveAudio(
    context: Context,
    sourceFile: File,
    title: String,
    artist: String,
    album: String,
    mimeType: String?,
    preferredName: String,
  ): String = withContext(Dispatchers.IO) {
    val safeTitle = title.trim().ifBlank { "Unknown Title" }
    val safeArtist = artist.trim().ifBlank { "Unknown Artist" }
    val ext = sourceFile.extension.trim().lowercase()
    val displayName = buildDisplayName(preferredName, ext)
    val normalizedMime = mimeType?.trim()?.ifBlank { null } ?: audioMimeFromExtension(ext)

    val values =
      ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Audio.Media.TITLE, safeTitle)
        put(MediaStore.Audio.Media.ARTIST, safeArtist)
        if (album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, album.trim())
        if (!normalizedMime.isNullOrBlank()) put(MediaStore.Audio.Media.MIME_TYPE, normalizedMime)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Pirate")
          put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
      }

    val resolver = context.contentResolver
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val inserted = resolver.insert(collection, values)
      ?: throw IllegalStateException("Failed to create MediaStore row")

    try {
      resolver.openOutputStream(inserted, "w")?.use { out ->
        sourceFile.inputStream().use { input ->
          input.copyTo(out)
        }
      } ?: throw IllegalStateException("Failed to open MediaStore output stream")

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val complete =
          ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
          }
        resolver.update(inserted, complete, null, null)
      }

      return@withContext inserted.toString()
    } catch (err: Throwable) {
      runCatching { resolver.delete(inserted, null, null) }
      throw err
    }
  }

  private fun buildDisplayName(preferredName: String, ext: String): String {
    val safeExt = ext.ifBlank { "mp3" }
    val stem =
      preferredName
        .trim()
        .ifBlank { "heaven_track" }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()

    val suffix = ".$safeExt"
    return if (stem.lowercase().endsWith(suffix)) stem else "$stem$suffix"
  }

}
