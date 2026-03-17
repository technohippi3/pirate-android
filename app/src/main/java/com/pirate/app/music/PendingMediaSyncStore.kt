package com.pirate.app.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PendingTrackMediaEntry(
  val trackId: String,
  val coverRef: String?,
  val updatedAtMs: Long,
)

object PendingMediaSyncStore {
  private const val CACHE_FILENAME = "pirate_pending_track_media_sync.json"
  private const val MAX_ENTRIES = 256

  suspend fun get(
    context: Context,
    trackId: String,
  ): PendingTrackMediaEntry? =
    withContext(Dispatchers.IO) {
      val normalizedTrackId = normalizeTrackIdStrict(trackId)
      loadInternal(context).firstOrNull { it.trackId == normalizedTrackId }
    }

  suspend fun upsertRefs(
    context: Context,
    trackId: String,
    coverRef: String? = null,
    updatedAtMs: Long = System.currentTimeMillis(),
  ): PendingTrackMediaEntry? =
    withContext(Dispatchers.IO) {
      val normalizedTrackId = normalizeTrackIdStrict(trackId)
      val normalizedCover = normalizeNullableRef(coverRef)

      val current = loadInternal(context)
      val existing = current.firstOrNull { it.trackId == normalizedTrackId }
      val merged =
        PendingTrackMediaEntry(
          trackId = normalizedTrackId,
          coverRef = normalizedCover ?: existing?.coverRef,
          updatedAtMs = updatedAtMs,
        )
      val finalized = if (merged.coverRef.isNullOrBlank()) null else merged

      val next = ArrayList<PendingTrackMediaEntry>(MAX_ENTRIES)
      if (finalized != null) next.add(finalized)
      for (entry in current) {
        if (entry.trackId == normalizedTrackId) continue
        next.add(entry)
        if (next.size >= MAX_ENTRIES) break
      }

      write(context, next)
      finalized
    }

  suspend fun markCoverSynced(
    context: Context,
    trackId: String,
  ): PendingTrackMediaEntry? =
    withContext(Dispatchers.IO) {
      val normalizedTrackId = normalizeTrackIdStrict(trackId)
      val current = loadInternal(context)
      val existing = current.firstOrNull { it.trackId == normalizedTrackId } ?: return@withContext null

      val updated =
        existing.copy(
          coverRef = null,
          updatedAtMs = System.currentTimeMillis(),
        )
      val finalized = if (updated.coverRef.isNullOrBlank()) null else updated

      val next = ArrayList<PendingTrackMediaEntry>(MAX_ENTRIES)
      if (finalized != null) next.add(finalized)
      for (entry in current) {
        if (entry.trackId == normalizedTrackId) continue
        next.add(entry)
        if (next.size >= MAX_ENTRIES) break
      }

      write(context, next)
      finalized
    }

  private fun file(context: Context): File = File(context.filesDir, CACHE_FILENAME)

  private fun loadInternal(context: Context): List<PendingTrackMediaEntry> {
    val cache = file(context)
    if (!cache.exists()) return emptyList()

    val raw = runCatching { cache.readText() }.getOrNull()?.trim().orEmpty()
    if (raw.isBlank()) return emptyList()

    return runCatching {
      val arr = JSONArray(raw)
      val out = ArrayList<PendingTrackMediaEntry>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val trackId = normalizeTrackId(obj.optString("trackId", ""))
        if (trackId.isBlank()) continue

        val coverRef = normalizeNullableRef(obj.optString("coverRef", ""))
        if (coverRef == null) continue

        out.add(
          PendingTrackMediaEntry(
            trackId = trackId,
            coverRef = coverRef,
            updatedAtMs = obj.optLong("updatedAtMs", 0L).coerceAtLeast(0L),
          ),
        )
      }
      out.sortedByDescending { it.updatedAtMs }.take(MAX_ENTRIES)
    }.getOrElse { emptyList() }
  }

  private fun write(
    context: Context,
    entries: List<PendingTrackMediaEntry>,
  ) {
    val arr = JSONArray()
    for (entry in entries) {
      arr.put(
        JSONObject()
          .put("trackId", entry.trackId)
          .put("coverRef", entry.coverRef ?: JSONObject.NULL)
          .put("updatedAtMs", entry.updatedAtMs),
      )
    }
    runCatching { file(context).writeText(arr.toString()) }
  }

  private fun normalizeTrackId(raw: String): String {
    val clean = raw.trim().removePrefix("0x").removePrefix("0X").lowercase()
    if (clean.length != 64) return ""
    if (!clean.all { it.isDigit() || it in 'a'..'f' }) return ""
    return "0x$clean"
  }

  private fun normalizeTrackIdStrict(raw: String): String {
    val normalized = normalizeTrackId(raw)
    require(normalized.isNotBlank()) { "Invalid trackId" }
    return normalized
  }

  private fun normalizeNullableRef(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    return value
  }
}
