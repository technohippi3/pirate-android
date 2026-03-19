package sc.pirate.app.post

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PendingPostStoryEnqueue(
  val ownerAddress: String,
  val chainId: Long,
  val txHash: String,
  val postId: String?,
  val attemptCount: Int,
  val updatedAtMs: Long,
)

object PostStoryEnqueueSyncStore {
  private const val CACHE_FILENAME = "pirate_post_story_enqueue_pending.json"
  private const val MAX_ENTRIES = 256
  private val TX_HASH_REGEX = Regex("^0x[0-9a-fA-F]{64}$")
  private val lock = Any()

  fun listForOwner(
    context: Context,
    ownerAddress: String,
    limit: Int = 64,
  ): List<PendingPostStoryEnqueue> {
    val normalizedOwner = normalizeOwnerAddress(ownerAddress) ?: return emptyList()
    synchronized(lock) {
      val safeLimit = limit.coerceIn(1, MAX_ENTRIES)
      return loadInternal(context)
        .asSequence()
        .filter { it.ownerAddress == normalizedOwner }
        .sortedByDescending { it.updatedAtMs }
        .take(safeLimit)
        .toList()
    }
  }

  fun upsert(
    context: Context,
    ownerAddress: String,
    chainId: Long,
    txHash: String,
    postId: String?,
    attemptCount: Int,
    updatedAtMs: Long = System.currentTimeMillis(),
  ) {
    val normalizedOwner = normalizeOwnerAddress(ownerAddress) ?: return
    val normalizedPostId = normalizePostId(postId)
    val normalizedTxHash = normalizeTxHash(txHash) ?: return
    val normalizedChainId = chainId.coerceAtLeast(1L)
    val normalizedAttempts = attemptCount.coerceAtLeast(0)

    synchronized(lock) {
      val current = loadInternal(context)
      val next = ArrayList<PendingPostStoryEnqueue>(MAX_ENTRIES)
      next.add(
        PendingPostStoryEnqueue(
          ownerAddress = normalizedOwner,
          chainId = normalizedChainId,
          txHash = normalizedTxHash,
          postId = normalizedPostId,
          attemptCount = normalizedAttempts,
          updatedAtMs = updatedAtMs.coerceAtLeast(0L),
        ),
      )
      for (entry in current) {
        if (
          entry.ownerAddress == normalizedOwner
          && (
            entry.txHash == normalizedTxHash
              || (normalizedPostId != null && entry.postId == normalizedPostId)
            )
        ) {
          continue
        }
        next.add(entry)
        if (next.size >= MAX_ENTRIES) break
      }
      writeInternal(context, next)
    }
  }

  fun remove(
    context: Context,
    ownerAddress: String,
    postId: String? = null,
    txHash: String? = null,
  ) {
    val normalizedOwner = normalizeOwnerAddress(ownerAddress) ?: return
    val normalizedPostId = normalizePostId(postId)
    val normalizedTxHash = normalizeTxHash(txHash.orEmpty())
    if (normalizedPostId == null && normalizedTxHash == null) return

    synchronized(lock) {
      val current = loadInternal(context)
      val next = current.filterNot {
        it.ownerAddress == normalizedOwner
          && (
            (normalizedPostId != null && it.postId == normalizedPostId)
              || (normalizedTxHash != null && it.txHash == normalizedTxHash)
            )
      }
      if (next.size == current.size) return
      writeInternal(context, next)
    }
  }

  private fun loadInternal(context: Context): List<PendingPostStoryEnqueue> {
    val cacheFile = cacheFile(context)
    if (!cacheFile.exists()) return emptyList()

    val raw = runCatching { cacheFile.readText() }.getOrNull()?.trim().orEmpty()
    if (raw.isBlank()) return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      val out = ArrayList<PendingPostStoryEnqueue>(array.length())
      for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val ownerAddress = normalizeOwnerAddress(row.optString("ownerAddress", "")) ?: continue
        val postId = normalizePostId(row.optString("postId", ""))
        val txHash = normalizeTxHash(row.optString("txHash", "")) ?: continue
        val chainId = row.optLong("chainId", 0L).coerceAtLeast(1L)
        val attemptCount = row.optInt("attemptCount", 0).coerceAtLeast(0)
        val updatedAtMs = row.optLong("updatedAtMs", 0L).coerceAtLeast(0L)
        out.add(
          PendingPostStoryEnqueue(
            ownerAddress = ownerAddress,
            chainId = chainId,
            txHash = txHash,
            postId = postId,
            attemptCount = attemptCount,
            updatedAtMs = updatedAtMs,
          ),
        )
      }
      out
    }.getOrElse { emptyList() }
  }

  private fun writeInternal(
    context: Context,
    entries: List<PendingPostStoryEnqueue>,
  ) {
    val array = JSONArray()
    entries.take(MAX_ENTRIES).forEach { entry ->
      val row =
        JSONObject()
          .put("ownerAddress", entry.ownerAddress)
          .put("chainId", entry.chainId)
          .put("txHash", entry.txHash)
          .put("attemptCount", entry.attemptCount)
          .put("updatedAtMs", entry.updatedAtMs)
      if (!entry.postId.isNullOrBlank()) {
        row.put("postId", entry.postId)
      }
      array.put(row)
    }
    runCatching { cacheFile(context).writeText(array.toString()) }
  }

  private fun cacheFile(context: Context): File {
    return File(context.filesDir, CACHE_FILENAME)
  }

  private fun normalizeOwnerAddress(raw: String): String? {
    return runCatching { PostTxInternals.normalizeAddress(raw) }.getOrNull()?.lowercase()
  }

  private fun normalizePostId(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isBlank()) return null
    return runCatching { PostTxInternals.normalizeBytes32(candidate, "postId") }.getOrNull()?.lowercase()
  }

  private fun normalizeTxHash(raw: String): String? {
    val normalized = raw.trim().lowercase()
    return if (TX_HASH_REGEX.matches(normalized)) normalized else null
  }
}
