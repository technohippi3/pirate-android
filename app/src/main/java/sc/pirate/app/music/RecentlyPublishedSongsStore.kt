package sc.pirate.app.music

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecentlyPublishedSongEntry(
  val title: String,
  val artist: String,
  val audioCid: String?,
  val coverCid: String?,
  val publishedAtMs: Long,
)

object RecentlyPublishedSongsStore {
  private const val TAG = "RecentlyPublishedSongs"
  private const val CACHE_FILENAME = "pirate_recently_published_songs.json"
  private const val MAX_ENTRIES = 20
  private const val COVERS_DIR = "recent_release_covers"
  private const val MAX_COVER_BYTES = 10 * 1024 * 1024

  private fun normalizeNullableString(value: String?): String? {
    val v = value?.trim().orEmpty()
    if (v.isBlank()) return null
    if (v.equals("null", ignoreCase = true)) return null
    if (v.equals("undefined", ignoreCase = true)) return null
    return v
  }

  suspend fun load(context: Context): List<RecentlyPublishedSongEntry> = withContext(Dispatchers.IO) {
    val loaded = loadInternal(context)
    stabilizeCoverRefs(context, loaded)
  }

  suspend fun record(
    context: Context,
    title: String,
    artist: String,
    audioCid: String?,
    coverCid: String?,
    publishedAtMs: Long = System.currentTimeMillis(),
  ): List<RecentlyPublishedSongEntry> = withContext(Dispatchers.IO) {
    val safeTitle = title.trim().ifBlank { "Untitled" }
    val safeArtist = artist.trim().ifBlank { "Unknown Artist" }
    val normalizedAudioCid = normalizeNullableString(audioCid)
    val normalizedCoverCid = normalizeNullableString(coverCid)

    val next = ArrayList<RecentlyPublishedSongEntry>(MAX_ENTRIES)
    next.add(
      RecentlyPublishedSongEntry(
        title = safeTitle,
        artist = safeArtist,
        audioCid = normalizedAudioCid,
        coverCid = normalizedCoverCid,
        publishedAtMs = publishedAtMs,
      ),
    )

    for (entry in loadInternal(context)) {
      if (isSameSong(entry, safeTitle, safeArtist)) continue
      next.add(entry)
      if (next.size >= MAX_ENTRIES) break
    }

    write(context, next)
    next
  }

  private fun file(context: Context): File {
    return File(context.filesDir, CACHE_FILENAME)
  }

  private fun coverCacheDir(context: Context): File {
    return File(context.filesDir, COVERS_DIR)
  }

  private fun guessImageExtension(mimeType: String?): String {
    val normalized = mimeType?.trim()?.lowercase().orEmpty()
    return when {
      normalized.contains("png") -> "png"
      normalized.contains("webp") -> "webp"
      normalized.contains("gif") -> "gif"
      else -> "jpg"
    }
  }

  private fun persistContentCoverRef(
    context: Context,
    rawCoverRef: String,
    seed: String,
  ): String? {
    if (!rawCoverRef.startsWith("content://")) return rawCoverRef
    return runCatching {
      val uri = Uri.parse(rawCoverRef)
      val mime = context.contentResolver.getType(uri)
      val ext = guessImageExtension(mime)
      val dir = coverCacheDir(context)
      if (!dir.exists()) dir.mkdirs()
      val file = File(dir, "cover_${seed.hashCode().toUInt().toString(16)}.$ext")

      context.contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { out ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var total = 0L
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_COVER_BYTES) {
              throw IllegalStateException("Cover image exceeds 10MB local cache limit")
            }
            out.write(buffer, 0, read)
          }
          if (total == 0L) throw IllegalStateException("Cover image is empty")
        }
      } ?: return null

      Uri.fromFile(file).toString()
    }.getOrNull()
  }

  private fun stabilizeCoverRefs(
    context: Context,
    entries: List<RecentlyPublishedSongEntry>,
  ): List<RecentlyPublishedSongEntry> {
    if (entries.isEmpty()) return entries
    var changed = false
    val updated = entries.mapIndexed { index, entry ->
      val cover = entry.coverCid
      if (cover.isNullOrBlank() || !cover.startsWith("content://")) return@mapIndexed entry
      val stable = persistContentCoverRef(
        context = context,
        rawCoverRef = cover,
        seed = "${entry.publishedAtMs}:${entry.title}:${entry.artist}:$index",
      )
      if (stable.isNullOrBlank() || stable == cover) return@mapIndexed entry
      changed = true
      entry.copy(coverCid = stable)
    }
    if (changed) write(context, updated)
    return updated
  }

  private fun loadInternal(context: Context): List<RecentlyPublishedSongEntry> {
    val cacheFile = file(context)
    if (!cacheFile.exists()) return emptyList()

    val raw = runCatching { cacheFile.readText() }.getOrNull()?.trim().orEmpty()
    if (raw.isBlank()) return emptyList()

    return runCatching {
      val arr = JSONArray(raw)
      val out = ArrayList<RecentlyPublishedSongEntry>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val title = obj.optString("title", "").trim().ifBlank { "Untitled" }
        val artist = obj.optString("artist", "").trim().ifBlank { "Unknown Artist" }
        val publishedAtMs = obj.optLong("publishedAtMs", 0L)
        out.add(
          RecentlyPublishedSongEntry(
            title = title,
            artist = artist,
            audioCid = normalizeNullableString(obj.optString("audioCid", "")),
            coverCid = normalizeNullableString(obj.optString("coverCid", "")),
            publishedAtMs = publishedAtMs,
          ),
        )
      }
      out
        .sortedByDescending { it.publishedAtMs }
        .take(MAX_ENTRIES)
    }.getOrElse { emptyList() }
  }

  private fun write(context: Context, entries: List<RecentlyPublishedSongEntry>) {
    val arr = JSONArray()
    for (entry in entries) {
      arr.put(
        JSONObject()
          .put("title", entry.title)
          .put("artist", entry.artist)
          .put("audioCid", entry.audioCid ?: JSONObject.NULL)
          .put("coverCid", entry.coverCid ?: JSONObject.NULL)
          .put("publishedAtMs", entry.publishedAtMs),
      )
    }

    val cacheFile = file(context)
    runCatching { cacheFile.writeText(arr.toString()) }
      .onFailure { error ->
        Log.w(TAG, "Failed writing recent publish cache: ${error.message}", error)
      }
  }

  private fun isSameSong(
    entry: RecentlyPublishedSongEntry,
    title: String,
    artist: String,
  ): Boolean {
    return entry.title.trim().lowercase() == title.trim().lowercase() &&
      entry.artist.trim().lowercase() == artist.trim().lowercase()
  }
}
