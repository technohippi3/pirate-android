package com.pirate.app.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchRecord(
  val postId: String,
  val watchPct: Float,
  val lastSeenAtSec: Long,
)

class WatchHistoryStore(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val lock = Any()

  fun recordWatch(
    postId: String,
    watchPct: Float,
    lastSeenAtSec: Long = System.currentTimeMillis() / 1000L,
  ) {
    val normalizedPostId = postId.trim().lowercase()
    if (normalizedPostId.isBlank()) return
    val safePct = watchPct.coerceIn(0f, 1f)
    synchronized(lock) {
      val records = loadMutableMapLocked()
      records[normalizedPostId] = WatchRecord(
        postId = normalizedPostId,
        watchPct = safePct,
        lastSeenAtSec = lastSeenAtSec.coerceAtLeast(0L),
      )
      trimToLimitLocked(records)
      persistLocked(records)
    }
  }

  fun getRecord(postId: String): WatchRecord? {
    val normalizedPostId = postId.trim().lowercase()
    if (normalizedPostId.isBlank()) return null
    synchronized(lock) {
      return loadMutableMapLocked()[normalizedPostId]
    }
  }

  fun getAll(): Map<String, WatchRecord> {
    synchronized(lock) {
      return loadMutableMapLocked().toMap()
    }
  }

  private fun loadMutableMapLocked(): MutableMap<String, WatchRecord> {
    val raw = prefs.getString(KEY_FEED_WATCH_HISTORY, null).orEmpty()
    if (raw.isBlank()) return linkedMapOf()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return linkedMapOf()
    val out = linkedMapOf<String, WatchRecord>()
    for (idx in 0 until array.length()) {
      val row = array.optJSONObject(idx) ?: continue
      val postId = row.optString("postId", "").trim().lowercase()
      if (postId.isBlank()) continue
      val watchPct = parseFloatField(row = row, field = "watchPct").coerceIn(0f, 1f)
      val lastSeenAtSec = parseLongField(row = row, field = "lastSeenAtSec").coerceAtLeast(0L)
      out[postId] = WatchRecord(postId = postId, watchPct = watchPct, lastSeenAtSec = lastSeenAtSec)
    }
    return out
  }

  private fun trimToLimitLocked(records: MutableMap<String, WatchRecord>) {
    if (records.size <= MAX_ENTRIES) return
    val sortedByOldest =
      records.values.sortedWith(
        compareBy<WatchRecord> { it.lastSeenAtSec }
          .thenBy { it.postId },
      )
    val toDrop = sortedByOldest.take(records.size - MAX_ENTRIES)
    for (record in toDrop) {
      records.remove(record.postId)
    }
  }

  private fun persistLocked(records: Map<String, WatchRecord>) {
    val array = JSONArray()
    records.values.forEach { record ->
      array.put(
        JSONObject()
          .put("postId", record.postId)
          .put("watchPct", record.watchPct.toDouble())
          .put("lastSeenAtSec", record.lastSeenAtSec),
      )
    }
    prefs.edit().putString(KEY_FEED_WATCH_HISTORY, array.toString()).apply()
  }

  private fun parseLongField(row: JSONObject, field: String): Long {
    val value = row.opt(field)
    return when (value) {
      is Number -> value.toLong()
      is String -> value.trim().toLongOrNull() ?: 0L
      else -> 0L
    }
  }

  private fun parseFloatField(row: JSONObject, field: String): Float {
    val value = row.opt(field)
    return when (value) {
      is Number -> value.toFloat()
      is String -> value.trim().toFloatOrNull() ?: 0f
      else -> 0f
    }
  }

  private companion object {
    const val PREFS_NAME = "home_feed_prefs"
    const val KEY_FEED_WATCH_HISTORY = "feed_watch_history"
    const val MAX_ENTRIES = 500
  }
}
