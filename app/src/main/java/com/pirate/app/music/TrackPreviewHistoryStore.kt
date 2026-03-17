package com.pirate.app.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class TrackPreviewRecord(
  val trackId: String,
  val title: String,
  val artist: String,
  val previewCount: Int,
  val lastPreviewAtSec: Long,
)

internal object TrackPreviewHistoryStore {
  private const val PREFS_NAME = "music_preview_history"
  private const val KEY_TRACK_PREVIEW_HISTORY = "track_preview_history"
  private const val MAX_ENTRIES = 120

  fun recordPreview(
    context: Context,
    trackId: String,
    title: String,
    artist: String,
    lastPreviewAtSec: Long = System.currentTimeMillis() / 1000L,
  ) {
    val normalizedTrackId = trackId.trim().lowercase()
    if (normalizedTrackId.isBlank()) return

    val safeTitle = title.trim().ifBlank { normalizedTrackId.take(14) }
    val safeArtist = artist.trim().ifBlank { "Unknown Artist" }
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val records = loadRecords(prefs.getString(KEY_TRACK_PREVIEW_HISTORY, null).orEmpty())
    val prior = records.remove(normalizedTrackId)
    records[normalizedTrackId] =
      TrackPreviewRecord(
        trackId = normalizedTrackId,
        title = safeTitle,
        artist = safeArtist,
        previewCount = (prior?.previewCount ?: 0) + 1,
        lastPreviewAtSec = lastPreviewAtSec.coerceAtLeast(0L),
      )
    trimToLimit(records)
    prefs.edit().putString(KEY_TRACK_PREVIEW_HISTORY, encodeRecords(records.values.toList())).apply()
  }

  fun listRecent(
    context: Context,
    limit: Int = 24,
  ): List<TrackPreviewRecord> {
    if (limit <= 0) return emptyList()
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return loadRecords(prefs.getString(KEY_TRACK_PREVIEW_HISTORY, null).orEmpty())
      .values
      .sortedWith(
        compareByDescending<TrackPreviewRecord> { it.lastPreviewAtSec }
          .thenByDescending { it.previewCount }
          .thenBy { it.trackId },
      )
      .take(limit)
  }

  private fun loadRecords(raw: String): LinkedHashMap<String, TrackPreviewRecord> {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return linkedMapOf()

    val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return linkedMapOf()
    val out = linkedMapOf<String, TrackPreviewRecord>()
    for (index in 0 until array.length()) {
      val row = array.optJSONObject(index) ?: continue
      val trackId = row.optString("trackId", "").trim().lowercase()
      if (trackId.isBlank()) continue
      out[trackId] =
        TrackPreviewRecord(
          trackId = trackId,
          title = row.optString("title", "").trim().ifBlank { trackId.take(14) },
          artist = row.optString("artist", "").trim().ifBlank { "Unknown Artist" },
          previewCount = row.optInt("previewCount", 0).coerceAtLeast(0),
          lastPreviewAtSec = row.optLong("lastPreviewAtSec", 0L).coerceAtLeast(0L),
        )
    }
    return out
  }

  private fun trimToLimit(records: LinkedHashMap<String, TrackPreviewRecord>) {
    if (records.size <= MAX_ENTRIES) return
    val toDrop =
      records.values
        .sortedWith(
          compareBy<TrackPreviewRecord> { it.lastPreviewAtSec }
            .thenBy { it.trackId },
        ).take(records.size - MAX_ENTRIES)
    for (record in toDrop) {
      records.remove(record.trackId)
    }
  }

  private fun encodeRecords(records: List<TrackPreviewRecord>): String {
    val array = JSONArray()
    records
      .sortedWith(
        compareByDescending<TrackPreviewRecord> { it.lastPreviewAtSec }
          .thenByDescending { it.previewCount }
          .thenBy { it.trackId },
      ).take(MAX_ENTRIES)
      .forEach { record ->
        array.put(
          JSONObject()
            .put("trackId", record.trackId)
            .put("title", record.title)
            .put("artist", record.artist)
            .put("previewCount", record.previewCount)
            .put("lastPreviewAtSec", record.lastPreviewAtSec),
        )
      }
    return array.toString()
  }
}
