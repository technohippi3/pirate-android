package com.pirate.app.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class FeedVideoPreloader(
  context: Context,
  private val scope: CoroutineScope,
  private val upstreamFactory: DefaultDataSource.Factory,
  private val allowMeteredNetwork: Boolean = false,
  private val unmeteredPrefetchBytesPerItem: Long = 6L * 1024L * 1024L,
  private val meteredPrefetchBytesPerItem: Long = 2L * 1024L * 1024L,
  maxConcurrentPrefetch: Int = 2,
) {
  private val appContext = context.applicationContext
  private val lock = Any()
  private val activePrefetchByPostId = linkedMapOf<String, ActivePrefetch>()
  private val prefetchSemaphore = Semaphore(maxConcurrentPrefetch.coerceAtLeast(1))
  private val connectivityManager =
    appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
  private val cacheDataSourceFactory =
    CacheDataSource.Factory()
      .setCache(FeedVideoCacheStore.getOrCreate(appContext))
      .setUpstreamDataSourceFactory(upstreamFactory)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

  fun updateTargets(
    posts: List<FeedPostResolved>,
    anchorIndex: Int,
  ) {
    if (!canPrefetchOnCurrentNetwork()) {
      Log.d(TAG, "prefetch skipped: network policy blocked (metered=${connectivityManager?.isActiveNetworkMetered})")
      clear()
      return
    }
    val targets = buildTargets(posts = posts, anchorIndex = anchorIndex)
    val targetSummary = targets.joinToString(separator = ",") { "${it.postId.take(10)}…"}
    Log.d(TAG, "prefetch update anchor=$anchorIndex targets=[$targetSummary]")
    synchronized(lock) {
      val targetIds = targets.mapTo(mutableSetOf()) { it.postId }
      val staleIds = activePrefetchByPostId.keys.filter { it !in targetIds }
      staleIds.forEach(::cancelLocked)
      targets.forEach { target ->
        if (activePrefetchByPostId.containsKey(target.postId)) return@forEach
        startLocked(target)
      }
    }
  }

  fun clear() {
    synchronized(lock) {
      activePrefetchByPostId.keys.toList().forEach(::cancelLocked)
    }
  }

  private fun buildTargets(
    posts: List<FeedPostResolved>,
    anchorIndex: Int,
  ): List<PrefetchTarget> {
    if (posts.isEmpty()) return emptyList()
    val safeAnchor = anchorIndex.coerceIn(0, posts.lastIndex)
    val out = ArrayList<PrefetchTarget>(2)
    val seenPostIds = HashSet<String>(2)

    val next = posts.getOrNull(safeAnchor + 1)
    if (next != null && !next.videoUrl.isNullOrBlank() && seenPostIds.add(next.id)) {
      out += PrefetchTarget(postId = next.id, url = next.videoUrl.orEmpty().trim())
    }

    val previous = posts.getOrNull(safeAnchor - 1)
    if (previous != null && !previous.videoUrl.isNullOrBlank() && seenPostIds.add(previous.id)) {
      out += PrefetchTarget(postId = previous.id, url = previous.videoUrl.orEmpty().trim())
    }

    return out
  }

  private fun startLocked(target: PrefetchTarget) {
    val byteBudget = resolveByteBudgetForCurrentNetwork()
    if (byteBudget <= 0L) {
      Log.d(TAG, "prefetch skipped postId=${target.postId} reason=byteBudgetZero")
      return
    }

    val dataSpec =
      DataSpec.Builder()
        .setUri(target.url)
        .setLength(byteBudget)
        .build()

    val cache = cacheDataSourceFactory.cache
    val cacheKeyFactory = cacheDataSourceFactory.cacheKeyFactory
    if (isRangeAlreadyCached(cache = cache, cacheKeyFactory = cacheKeyFactory, dataSpec = dataSpec, byteBudget = byteBudget)) {
      Log.d(TAG, "prefetch skipped postId=${target.postId} reason=alreadyCached bytes=$byteBudget")
      return
    }

    val cacheDataSource =
      (cacheDataSourceFactory.createDataSourceForDownloading() as? CacheDataSource)
        ?: (cacheDataSourceFactory.createDataSource() as? CacheDataSource)
        ?: return

    val writer = CacheWriter(cacheDataSource, dataSpec, ByteArray(DEFAULT_PREFETCH_BUFFER_BYTES), null)
    val job =
      scope.launch(Dispatchers.IO) {
        try {
          prefetchSemaphore.withPermit {
            Log.d(TAG, "prefetch start postId=${target.postId} bytes=$byteBudget")
            writer.cache()
            Log.d(TAG, "prefetch complete postId=${target.postId}")
          }
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (error: Throwable) {
          Log.w(TAG, "prefetch failed postId=${target.postId}", error)
        } finally {
          synchronized(lock) {
            val active = activePrefetchByPostId[target.postId]
            if (active?.writer === writer) {
              activePrefetchByPostId.remove(target.postId)
            }
          }
        }
      }
    activePrefetchByPostId[target.postId] = ActivePrefetch(writer = writer, job = job)
  }

  private fun canPrefetchOnCurrentNetwork(): Boolean {
    val manager = connectivityManager ?: return true
    val activeNetwork = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return false
    val connected =
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    if (!connected) return false
    if (!allowMeteredNetwork && manager.isActiveNetworkMetered) return false
    return true
  }

  private fun resolveByteBudgetForCurrentNetwork(): Long {
    val manager = connectivityManager ?: return unmeteredPrefetchBytesPerItem
    return if (manager.isActiveNetworkMetered) {
      if (!allowMeteredNetwork) 0L else meteredPrefetchBytesPerItem
    } else {
      unmeteredPrefetchBytesPerItem
    }
  }

  private fun isRangeAlreadyCached(
    cache: Cache?,
    cacheKeyFactory: CacheKeyFactory?,
    dataSpec: DataSpec,
    byteBudget: Long,
  ): Boolean {
    if (cache == null || cacheKeyFactory == null || byteBudget <= 0L) return false
    val cacheKey = runCatching { cacheKeyFactory.buildCacheKey(dataSpec) }.getOrNull() ?: return false
    return runCatching { cache.isCached(cacheKey, 0L, byteBudget) }.getOrDefault(false)
  }

  private fun cancelLocked(postId: String) {
    val active = activePrefetchByPostId.remove(postId) ?: return
    runCatching { active.writer.cancel() }
    active.job.cancel()
    Log.d(TAG, "prefetch cancel postId=$postId")
  }

  private data class PrefetchTarget(
    val postId: String,
    val url: String,
  )

  private data class ActivePrefetch(
    val writer: CacheWriter,
    val job: Job,
  )

  private companion object {
    const val TAG = "FeedVideoPreloader"
    const val DEFAULT_PREFETCH_BUFFER_BYTES = 128 * 1024
  }
}
