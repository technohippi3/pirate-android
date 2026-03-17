package com.pirate.app.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FeedSessionState(
  context: Context,
  private val scope: CoroutineScope,
  private val ranker: FeedRanker = FeedRanker(),
) {
  private val appContext = context.applicationContext
  private val watchHistoryStore = WatchHistoryStore(appContext)
  private val loadMutex = Mutex()

  var feedItems by mutableStateOf<List<FeedPostResolved>>(emptyList())
    private set
  var isLoading by mutableStateOf(false)
    private set
  var loadError by mutableStateOf<String?>(null)
    private set

  private val candidatePool = mutableListOf<FeedPostResolved>()
  private val candidateIds = mutableSetOf<String>()
  private val rankedQueue = ArrayDeque<FeedPostResolved>()
  private val servedSet = mutableSetOf<String>()
  private val emittedUniqueIds = mutableSetOf<String>()
  private var lastFetchCursor: FeedPageCursor? = null
  private var hasMoreFromServer = true
  private var currentVisibleIndex = 0
  private var initialized = false

  fun initialize() {
    if (initialized) return
    initialized = true
    scope.launch { ensureTailCapacity(forceInitial = true) }
  }

  fun onPageVisible(index: Int) {
    if (index < 0) return
    currentVisibleIndex = maxOf(currentVisibleIndex, index)
    feedItems.getOrNull(index)?.let { servedSet.add(it.id) }
    if (feedItems.size - index < REFILL_TRIGGER_THRESHOLD) {
      scope.launch { ensureTailCapacity(forceInitial = false) }
    }
  }

  fun recordWatch(postId: String, watchPct: Float) {
    watchHistoryStore.recordWatch(postId = postId, watchPct = watchPct)
    scope.launch { rerankPendingQueue() }
  }

  private suspend fun ensureTailCapacity(forceInitial: Boolean) {
    loadMutex.withLock {
      isLoading = true
      try {
        loadError = null
        val targetTail = if (forceInitial) INITIAL_TAIL_TARGET else REFILL_TRIGGER_THRESHOLD
        while (feedItems.size - currentVisibleIndex < targetTail) {
          if (rankedQueue.size < MIN_QUEUE_SIZE_BEFORE_REBUILD) {
            rebuildRankedQueue(fetchFromServer = true)
          }
          if (rankedQueue.isEmpty()) break
          appendFromQueue(batchSize = APPEND_BATCH_SIZE)
        }
      } catch (error: Throwable) {
        loadError = error.message ?: "Failed to load feed"
      } finally {
        isLoading = false
      }
    }
  }

  private suspend fun rerankPendingQueue() {
    loadMutex.withLock {
      if (rankedQueue.isEmpty()) return
      rebuildRankedQueue(fetchFromServer = false)
    }
  }

  private suspend fun rebuildRankedQueue(fetchFromServer: Boolean) {
    rankedQueue.clear()
    var attempts = 0
    while (rankedQueue.isEmpty() && attempts < MAX_REBUILD_ATTEMPTS) {
      attempts += 1
      if (fetchFromServer && shouldFetchMoreCandidates()) {
        fetchCandidatesPage()
      }
      if (candidatePool.isEmpty()) return

      val nowSec = System.currentTimeMillis() / 1000L
      val watchHistory = watchHistoryStore.getAll()
      val ranked = ranker.rankCandidates(candidatePool, watchHistory, nowSec)
      val unseenInSession = ranked.filter { it.id !in emittedUniqueIds }

      if (unseenInSession.isNotEmpty()) {
        rankedQueue.addAll(unseenInSession)
        return
      }

      if (hasMoreFromServer && fetchFromServer) {
        continue
      }

      if (isSparseMode()) {
        // Sparse feed loops content after first full pass; order is still cooldown-ranked.
        rankedQueue.addAll(ranked)
        lastFetchCursor = null
        hasMoreFromServer = true
      }
      return
    }
  }

  private fun shouldFetchMoreCandidates(): Boolean {
    if (!hasMoreFromServer) return false
    if (candidatePool.isEmpty()) return true
    return candidatePool.size < CANDIDATE_POOL_TARGET || candidatePool.all { it.id in emittedUniqueIds }
  }

  private suspend fun fetchCandidatesPage() {
    val page = FeedRepository.fetchFeedPage(context = appContext, limit = FETCH_PAGE_SIZE, cursor = lastFetchCursor)
    page.posts.forEach { post ->
      if (candidateIds.add(post.id)) {
        candidatePool += post
      }
    }
    lastFetchCursor = page.nextCursor
    hasMoreFromServer = page.hasMore && page.nextCursor != null
  }

  private fun appendFromQueue(batchSize: Int) {
    if (batchSize <= 0 || rankedQueue.isEmpty()) return
    val toAppend = ArrayList<FeedPostResolved>(batchSize)
    repeat(batchSize) {
      val next = rankedQueue.removeFirstOrNull() ?: return@repeat
      toAppend += next
      emittedUniqueIds += next.id
    }
    if (toAppend.isEmpty()) return
    feedItems = feedItems + toAppend
  }

  private fun isSparseMode(): Boolean {
    return candidatePool.size < SPARSE_MODE_POOL_MAX && !hasMoreFromServer
  }

  private companion object {
    const val FETCH_PAGE_SIZE = 50
    const val CANDIDATE_POOL_TARGET = 50
    const val APPEND_BATCH_SIZE = 10
    const val INITIAL_TAIL_TARGET = 10
    const val REFILL_TRIGGER_THRESHOLD = 5
    const val MIN_QUEUE_SIZE_BEFORE_REBUILD = 3
    const val MAX_REBUILD_ATTEMPTS = 3
    const val SPARSE_MODE_POOL_MAX = 40
  }
}
