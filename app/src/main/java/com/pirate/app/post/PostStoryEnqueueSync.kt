package com.pirate.app.post

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG_POST_STORY_SYNC = "PostStoryEnqueueSync"

object PostStoryEnqueueSync {
  private fun normalizeOwnerOrNull(ownerAddress: String): String? {
    return runCatching { PostTxInternals.normalizeAddress(ownerAddress) }.getOrNull()?.lowercase()
  }

  private fun normalizePostIdOrNull(postId: String?): String? {
    val raw = postId?.trim().orEmpty()
    if (raw.isBlank()) return null
    return runCatching { PostTxInternals.normalizeBytes32(raw, "postId") }.getOrNull()?.lowercase()
  }

  suspend fun enqueueOrPersist(
    context: Context,
    ownerAddress: String,
    chainId: Long,
    txHash: String,
    postId: String?,
  ) {
    withContext(Dispatchers.IO) {
      val normalizedOwner = normalizeOwnerOrNull(ownerAddress)
      val normalizedPostId = normalizePostIdOrNull(postId)
      val normalizedTxHash = txHash.trim().lowercase()
      if (normalizedOwner.isNullOrBlank() || normalizedTxHash.isBlank()) return@withContext

      if (!normalizedPostId.isNullOrBlank()) {
        val status = runCatching {
          PostTxInternals.fetchPostStoryStatus(
            userAddress = normalizedOwner,
            postId = normalizedPostId,
          )
        }.getOrNull()
        if (status != null && (status.status == "confirmed" || !status.postStoryIpId.isNullOrBlank())) {
          PostStoryEnqueueSyncStore.remove(
            context = context,
            ownerAddress = normalizedOwner,
            postId = normalizedPostId,
            txHash = normalizedTxHash,
          )
          return@withContext
        }
      }

      val enqueue =
        try {
          PostTxInternals.enqueuePostStory(
            userAddress = normalizedOwner,
            chainId = chainId,
            txHash = normalizedTxHash,
            postId = normalizedPostId,
          )
        } catch (error: Throwable) {
        val existingAttempt = PostStoryEnqueueSyncStore
          .listForOwner(context, normalizedOwner, limit = 16)
          .firstOrNull {
            it.txHash == normalizedTxHash || (!normalizedPostId.isNullOrBlank() && it.postId == normalizedPostId)
          }
          ?.attemptCount
          ?: 0
        PostStoryEnqueueSyncStore.upsert(
          context = context,
          ownerAddress = normalizedOwner,
          chainId = chainId,
          txHash = normalizedTxHash,
          postId = normalizedPostId,
          attemptCount = existingAttempt + 1,
        )
        Log.w(TAG_POST_STORY_SYNC, "enqueue failed postId=$normalizedPostId txHash=$normalizedTxHash err=${error.message}")
        return@withContext
      }
      val resolvedPostId = normalizePostIdOrNull(enqueue.postId) ?: normalizedPostId

      if (enqueue.status == "already_confirmed" || !enqueue.postStoryIpId.isNullOrBlank()) {
        PostStoryEnqueueSyncStore.remove(
          context = context,
          ownerAddress = normalizedOwner,
          postId = resolvedPostId,
          txHash = normalizedTxHash,
        )
        return@withContext
      }

      PostStoryEnqueueSyncStore.upsert(
        context = context,
        ownerAddress = normalizedOwner,
        chainId = chainId,
        txHash = normalizedTxHash,
        postId = resolvedPostId,
        attemptCount = 0,
      )
    }
  }

  suspend fun retryPendingForOwner(
    context: Context,
    ownerAddress: String,
    maxEntries: Int = 32,
  ) {
    withContext(Dispatchers.IO) {
      val normalizedOwner = normalizeOwnerOrNull(ownerAddress)
      if (normalizedOwner.isNullOrBlank()) return@withContext

      val entries = PostStoryEnqueueSyncStore.listForOwner(context, normalizedOwner, limit = maxEntries)
      for (entry in entries) {
        val normalizedEntryPostId = normalizePostIdOrNull(entry.postId)

        val status = if (normalizedEntryPostId.isNullOrBlank()) {
          null
        } else {
          runCatching {
            PostTxInternals.fetchPostStoryStatus(
              userAddress = normalizedOwner,
              postId = normalizedEntryPostId,
            )
          }.getOrNull()
        }

        if (status != null && (status.status == "confirmed" || !status.postStoryIpId.isNullOrBlank())) {
          PostStoryEnqueueSyncStore.remove(
            context = context,
            ownerAddress = normalizedOwner,
            postId = normalizedEntryPostId,
            txHash = entry.txHash,
          )
          continue
        }

        val enqueue =
          try {
            PostTxInternals.enqueuePostStory(
              userAddress = normalizedOwner,
              chainId = entry.chainId,
              txHash = entry.txHash,
              postId = normalizedEntryPostId,
            )
          } catch (error: Throwable) {
          PostStoryEnqueueSyncStore.upsert(
            context = context,
            ownerAddress = normalizedOwner,
            chainId = entry.chainId,
            txHash = entry.txHash,
            postId = normalizedEntryPostId,
            attemptCount = entry.attemptCount + 1,
          )
          Log.w(TAG_POST_STORY_SYNC, "retry failed postId=$normalizedEntryPostId txHash=${entry.txHash} err=${error.message}")
          continue
        }
        val resolvedPostId = normalizePostIdOrNull(enqueue.postId) ?: normalizedEntryPostId

        if (enqueue.status == "already_confirmed" || !enqueue.postStoryIpId.isNullOrBlank()) {
          PostStoryEnqueueSyncStore.remove(
            context = context,
            ownerAddress = normalizedOwner,
            postId = resolvedPostId,
            txHash = entry.txHash,
          )
          continue
        }

        PostStoryEnqueueSyncStore.upsert(
          context = context,
          ownerAddress = normalizedOwner,
          chainId = entry.chainId,
          txHash = entry.txHash,
          postId = resolvedPostId,
          attemptCount = entry.attemptCount,
        )
      }
    }
  }
}
