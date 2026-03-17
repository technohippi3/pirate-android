package com.pirate.app.home

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

internal object FeedVideoCacheStore {
  private const val CACHE_DIR_NAME = "feed_video_cache"
  private const val CACHE_MAX_BYTES = 128L * 1024L * 1024L
  private val cacheLock = Any()
  @Volatile private var sharedCache: SimpleCache? = null
  @Volatile private var sharedDbProvider: StandaloneDatabaseProvider? = null

  fun getOrCreate(context: Context): SimpleCache {
    synchronized(cacheLock) {
      sharedCache?.let { return it }
      val dbProvider = sharedDbProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
        sharedDbProvider = it
      }
      val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
      return SimpleCache(
        cacheDir,
        LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
        dbProvider,
      ).also { sharedCache = it }
    }
  }
}
