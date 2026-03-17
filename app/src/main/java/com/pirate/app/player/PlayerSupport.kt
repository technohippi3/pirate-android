package com.pirate.app.player

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.pirate.app.music.MusicTrack
import com.pirate.app.widget.NowPlayingWidget
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class WidgetSyncBridge(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  fun sync(track: MusicTrack?, isPlaying: Boolean) {
    scope.launch(Dispatchers.IO) {
      if (track == null) {
        NowPlayingWidget.clearState(context)
      } else {
        NowPlayingWidget.pushState(
          context = context,
          title = track.title,
          artist = track.artist,
          artworkUri = track.artworkUri,
          isPlaying = isPlaying,
        )
      }
    }
  }
}

internal class PlaybackServiceBridge(
  private val context: Context,
  private val controllerProvider: () -> PlayerController,
) {
  private var serviceStarted = false

  fun start() {
    PlaybackService.playerRef = controllerProvider()
    val intent = Intent(context, PlaybackService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent)
    } else {
      context.startService(intent)
    }
    serviceStarted = true
  }

  fun update() {
    if (!serviceStarted) return
    val intent = Intent(context, PlaybackService::class.java).apply {
      action = PlaybackService.ACTION_UPDATE
    }
    context.startService(intent)
  }

  fun stop() {
    if (!serviceStarted) return
    val intent = Intent(context, PlaybackService::class.java).apply {
      action = PlaybackService.ACTION_STOP
    }
    context.startService(intent)
    serviceStarted = false
  }
}

internal object ExoCacheStore {
  private const val EXO_CACHE_DIR = "exo_audio_cache"
  private const val EXO_CACHE_MAX_BYTES = 64L * 1024 * 1024
  private val cacheLock = Any()
  @Volatile private var sharedExoCache: SimpleCache? = null
  @Volatile private var sharedDbProvider: StandaloneDatabaseProvider? = null

  fun getOrCreate(context: Context): SimpleCache {
    synchronized(cacheLock) {
      sharedExoCache?.let { return it }
      val dbProvider = sharedDbProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
        sharedDbProvider = it
      }
      val cacheDir = File(context.cacheDir, EXO_CACHE_DIR).apply { mkdirs() }
      return SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(EXO_CACHE_MAX_BYTES), dbProvider).also {
        sharedExoCache = it
      }
    }
  }
}
