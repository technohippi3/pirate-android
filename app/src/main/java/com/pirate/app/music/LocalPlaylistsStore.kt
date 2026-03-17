package com.pirate.app.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LocalPlaylistsStore {
  private const val PLAYLISTS_FILENAME = "pirate_local_playlists.json"

  suspend fun getLocalPlaylists(context: Context): List<LocalPlaylist> = withContext(Dispatchers.IO) {
    readAll(context)
  }

  suspend fun createLocalPlaylist(
    context: Context,
    name: String,
    initialTrack: LocalPlaylistTrack? = null,
  ): LocalPlaylist = withContext(Dispatchers.IO) {
    val playlists = readAll(context).toMutableList()
    val now = System.currentTimeMillis()
    val playlist = LocalPlaylist(
      id = generateId(),
      name = name,
      tracks = if (initialTrack != null) listOf(initialTrack) else emptyList(),
      coverUri = null,
      createdAtMs = now,
      updatedAtMs = now,
      syncedPlaylistId = null,
    )
    playlists.add(0, playlist)
    writeAll(context, playlists)
    playlist
  }

  suspend fun addTrackToLocalPlaylist(
    context: Context,
    playlistId: String,
    track: LocalPlaylistTrack,
  ): LocalPlaylist? = withContext(Dispatchers.IO) {
    val playlists = readAll(context).toMutableList()
    val idx = playlists.indexOfFirst { it.id == playlistId }
    if (idx == -1) return@withContext null

    val existing = playlists[idx]
    val isDupe = existing.tracks.any { it.artist == track.artist && it.title == track.title }
    if (isDupe) return@withContext existing

    val updated = existing.copy(
      tracks = existing.tracks + track,
      updatedAtMs = System.currentTimeMillis(),
    )
    playlists[idx] = updated
    writeAll(context, playlists)
    updated
  }

  private fun generateId(): String {
    val ts = java.lang.Long.toString(System.currentTimeMillis(), 36)
    val rand = java.lang.Integer.toString(kotlin.random.Random.nextInt(), 36).removePrefix("-").take(6)
    return "local:$ts-$rand"
  }

  private fun readAll(context: Context): List<LocalPlaylist> {
    val file = File(context.filesDir, PLAYLISTS_FILENAME)
    if (!file.exists()) return emptyList()
    val raw = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()
    return decodePlaylists(raw)
  }

  private fun writeAll(context: Context, playlists: List<LocalPlaylist>) {
    val file = File(context.filesDir, PLAYLISTS_FILENAME)
    runCatching { file.writeText(encodePlaylists(playlists)) }
  }

  private fun encodePlaylists(playlists: List<LocalPlaylist>): String {
    val arr = JSONArray()
    for (p in playlists) {
      val tracksArr = JSONArray()
      for (t in p.tracks) {
        tracksArr.put(
          JSONObject()
            .put("artist", t.artist)
            .put("title", t.title)
            .put("album", t.album)
            .put("durationSec", t.durationSec)
            .put("uri", t.uri)
            .put("artworkUri", t.artworkUri)
            .put("artworkFallbackUri", t.artworkFallbackUri),
        )
      }

      arr.put(
        JSONObject()
          .put("id", p.id)
          .put("name", p.name)
          .put("tracks", tracksArr)
          .put("coverUri", p.coverUri)
          .put("createdAtMs", p.createdAtMs)
          .put("updatedAtMs", p.updatedAtMs)
          .put("syncedPlaylistId", p.syncedPlaylistId),
      )
    }
    return arr.toString()
  }

  private fun decodePlaylists(raw: String): List<LocalPlaylist> {
    return runCatching {
      val arr = JSONArray(raw)
      val out = ArrayList<LocalPlaylist>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val tracksArr = obj.optJSONArray("tracks") ?: JSONArray()
        val tracks = ArrayList<LocalPlaylistTrack>(tracksArr.length())
        for (j in 0 until tracksArr.length()) {
          val t = tracksArr.optJSONObject(j) ?: continue
          tracks.add(
            LocalPlaylistTrack(
              artist = t.optString("artist", ""),
              title = t.optString("title", ""),
              album = t.optString("album", "").ifBlank { null },
              durationSec = t.optInt("durationSec", 0).takeIf { it > 0 },
              uri = t.optString("uri", "").ifBlank { null },
              artworkUri = t.optString("artworkUri", "").ifBlank { null },
              artworkFallbackUri = t.optString("artworkFallbackUri", "").ifBlank { null },
            ),
          )
        }
        out.add(
          LocalPlaylist(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            tracks = tracks,
            coverUri = obj.optString("coverUri", "").ifBlank { null },
            createdAtMs = obj.optLong("createdAtMs", 0L),
            updatedAtMs = obj.optLong("updatedAtMs", 0L),
            syncedPlaylistId = obj.optString("syncedPlaylistId", "").ifBlank { null },
          ),
        )
      }
      out
    }.getOrElse { emptyList() }
  }
}
