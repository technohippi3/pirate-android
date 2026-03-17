package com.pirate.app.home

import android.content.Context
import android.util.Log
import com.pirate.app.BuildConfig
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.TempoClient
import com.pirate.app.util.tempoFeedSubgraphUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256

object FeedRepository {
  private const val TAG = "FeedRepository"
  private const val POST_CREATED_TOPIC =
    "0xf33ccb9d20522e9ebf5a1d6b9caba40fb396e20cd76a5e4b4bff494bdea84b9f"
  private const val FEED_START_BLOCK = 6_142_696L
  // RPC allows a max inclusive range of 100_000 blocks; use 99_999 delta from latest.
  private const val FEED_FALLBACK_BLOCK_WINDOW = 99_999L
  private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
  private val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")
  private val client = OkHttpClient()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val metadataResolvers = FeedMetadataResolvers(client = client)
  private val postsOutputTypes =
    listOf(
      object : TypeReference<Bytes32>() {},
      object : TypeReference<Bytes32>() {},
      object : TypeReference<Address>() {},
      object : TypeReference<Uint256>() {},
      object : TypeReference<Uint256>() {},
      object : TypeReference<Address>() {},
      object : TypeReference<Address>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Utf8String>() {},
    )

  suspend fun fetchFeedPage(
    context: Context,
    limit: Int,
    cursor: FeedPageCursor? = null,
  ): FeedPage = withContext(Dispatchers.IO) {
    val safeLimit = limit.coerceIn(1, 100)
    val viewerLocaleTag = com.pirate.app.ViewerContentLocaleResolver.resolve(context)
    val chainPosts =
      runCatching { fetchCorePostsFromChain(limit = safeLimit, cursor = cursor) }
        .onFailure { error -> Log.w(TAG, "fetchFeedPage chain source failed", error) }
        .getOrDefault(emptyList())
    if (chainPosts.isNotEmpty()) {
      val resolved = metadataResolvers.resolvePosts(chainPosts, viewerLocaleTag = viewerLocaleTag)
      val nextCursor = chainPosts.lastOrNull()?.let { FeedPageCursor(createdAtSec = it.createdAtSec, postId = it.id) }
      return@withContext FeedPage(
        posts = resolved,
        nextCursor = nextCursor,
        hasMore = chainPosts.size >= safeLimit,
      )
    }

    var sawSuccessfulEmpty = false
    var lastError: Throwable? = null

    for (subgraphUrl in tempoFeedSubgraphUrls()) {
      try {
        val corePosts = fetchCorePostsFromSubgraph(subgraphUrl = subgraphUrl, limit = safeLimit, cursor = cursor)
        if (corePosts.isEmpty()) {
          sawSuccessfulEmpty = true
          continue
        }
        val resolved = metadataResolvers.resolvePosts(corePosts, viewerLocaleTag = viewerLocaleTag)
        val nextCursor = corePosts.lastOrNull()?.let { FeedPageCursor(createdAtSec = it.createdAtSec, postId = it.id) }
        return@withContext FeedPage(
          posts = resolved,
          nextCursor = nextCursor,
          hasMore = corePosts.size >= safeLimit,
        )
      } catch (error: Throwable) {
        Log.w(TAG, "fetchFeedPage failed for $subgraphUrl", error)
        lastError = error
      }
    }

    if (sawSuccessfulEmpty) {
      return@withContext FeedPage(posts = emptyList(), nextCursor = null, hasMore = false)
    }
    if (lastError != null) {
      if (isRecoverableFeedFetchError(lastError)) {
        Log.w(TAG, "fetchFeedPage returning empty after recoverable upstream failure", lastError)
        return@withContext FeedPage(posts = emptyList(), nextCursor = null, hasMore = false)
      }
      throw lastError
    }
    FeedPage(posts = emptyList(), nextCursor = null, hasMore = false)
  }

  suspend fun fetchViewerLikedPostIds(
    ownerAddress: String,
    postIds: List<String>,
  ): Set<String> = withContext(Dispatchers.IO) {
    val viewer = ownerAddress.trim().lowercase()
    if (viewer.isBlank() || postIds.isEmpty()) return@withContext emptySet()
    val normalizedPostIds = postIds.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
    if (normalizedPostIds.isEmpty()) return@withContext emptySet()
    var lastError: Throwable? = null

    for (subgraphUrl in tempoFeedSubgraphUrls()) {
      try {
        val likedIds = fetchViewerLikedPostIdsFromSubgraph(
          subgraphUrl = subgraphUrl,
          viewer = viewer,
          postIds = normalizedPostIds,
        )
        return@withContext likedIds
      } catch (error: Throwable) {
        Log.w(TAG, "fetchViewerLikedPostIds failed for $subgraphUrl", error)
        lastError = error
      }
    }

    if (lastError != null) throw lastError
    emptySet()
  }

  private fun fetchCorePostsFromSubgraph(
    subgraphUrl: String,
    limit: Int,
    cursor: FeedPageCursor?,
  ): List<FeedPostCore> {
    val query: String
    val variables = JSONObject().put("first", limit)
    if (cursor == null) {
      query =
        """
          query FeedPage(${'$'}first: Int!) {
            posts(
              first: ${'$'}first
              where: { status: 1 }
              orderBy: createdAt
              orderDirection: desc
            ) {
              id
              creator
              songTrackId
              songStoryIpId
              postStoryIpId
              videoRef
              captionRef
              translationRef
              likeCount
              createdAt
            }
          }
        """.trimIndent()
    } else {
      query =
        """
          query FeedPage(${'$'}first: Int!, ${'$'}cursorCreatedAt: BigInt!, ${'$'}cursorId: ID!) {
            posts(
              first: ${'$'}first
              where: {
                or: [
                  { status: 1, createdAt_lt: ${'$'}cursorCreatedAt }
                  { status: 1, createdAt: ${'$'}cursorCreatedAt, id_lt: ${'$'}cursorId }
                ]
              }
              orderBy: createdAt
              orderDirection: desc
            ) {
              id
              creator
              songTrackId
              songStoryIpId
              postStoryIpId
              videoRef
              captionRef
              translationRef
              likeCount
              createdAt
            }
          }
        """.trimIndent()
      variables.put("cursorCreatedAt", cursor.createdAtSec.toString())
      variables.put("cursorId", cursor.postId.trim().lowercase())
    }

    val json = executeQuery(subgraphUrl = subgraphUrl, query = query, variables = variables)
    val posts = json.optJSONObject("data")?.optJSONArray("posts") ?: JSONArray()
    val out = ArrayList<FeedPostCore>(posts.length())
    for (idx in 0 until posts.length()) {
      val row = posts.optJSONObject(idx) ?: continue
      val id = row.optString("id", "").trim().lowercase()
      if (id.isBlank()) continue
      out += FeedPostCore(
        id = id,
        creator = row.optString("creator", "").trim().lowercase(),
        songTrackId = row.optString("songTrackId", "").trim().lowercase(),
        songStoryIpId = row.optString("songStoryIpId", "").trim().lowercase(),
        postStoryIpId = row.optString("postStoryIpId", "").trim().lowercase().ifBlank { null },
        videoRef = row.optString("videoRef", "").trim(),
        captionRef = row.optString("captionRef", "").trim(),
        translationRef = row.optString("translationRef", "").trim().ifBlank { null },
        likeCount = parseLongField(row, "likeCount"),
        createdAtSec = parseLongField(row, "createdAt"),
      )
    }
    return out
  }

  private fun fetchViewerLikedPostIdsFromSubgraph(
    subgraphUrl: String,
    viewer: String,
    postIds: List<String>,
  ): Set<String> {
    val query =
      """
        query ViewerLikedBatch(${'$'}viewer: Bytes!, ${'$'}postIds: [ID!], ${'$'}first: Int!) {
          postLikes(
            where: { user: ${'$'}viewer, liked: true, post_in: ${'$'}postIds }
            first: ${'$'}first
          ) {
            post { id }
          }
        }
      """.trimIndent()
    val variables =
      JSONObject()
        .put("viewer", viewer)
        .put("postIds", JSONArray(postIds))
        .put("first", postIds.size.coerceAtLeast(1))
    val json = executeQuery(subgraphUrl = subgraphUrl, query = query, variables = variables)
    val likes = json.optJSONObject("data")?.optJSONArray("postLikes") ?: JSONArray()
    val out = linkedSetOf<String>()
    for (idx in 0 until likes.length()) {
      val row = likes.optJSONObject(idx) ?: continue
      val postId = row.optJSONObject("post")?.optString("id", "")?.trim()?.lowercase().orEmpty()
      if (postId.isNotBlank()) out += postId
    }
    return out
  }

  private fun parseLongField(row: JSONObject, field: String): Long {
    val value = row.opt(field)
    return when (value) {
      is Number -> value.toLong()
      is String -> value.trim().toLongOrNull() ?: 0L
      else -> 0L
    }
  }

  private fun executeQuery(
    subgraphUrl: String,
    query: String,
    variables: JSONObject? = null,
  ): JSONObject {
    val payload = JSONObject().put("query", query)
    if (variables != null) payload.put("variables", variables)
    val body = payload.toString().toRequestBody(jsonMediaType)
    val req = Request.Builder().url(subgraphUrl).post(body).build()
    return client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("Feed query failed: ${res.code}")
      val raw = res.body?.string().orEmpty()
      val json = JSONObject(raw)
      val errors = json.optJSONArray("errors")
      if (errors != null && errors.length() > 0) {
        val msg = errors.optJSONObject(0)?.optString("message", "GraphQL error") ?: "GraphQL error"
        throw IllegalStateException(msg)
      }
      json
    }
  }

  private fun fetchCorePostsFromChain(
    limit: Int,
    cursor: FeedPageCursor?,
  ): List<FeedPostCore> {
    val safeLimit = limit.coerceIn(1, 100)
    val feedAddress = normalizeAddress(BuildConfig.TEMPO_FEED_V2)
    if (!ADDRESS_REGEX.matches(feedAddress)) return emptyList()

    val latestBlock = rpcBlockNumber()
    val fromBlock = maxOf(FEED_START_BLOCK, latestBlock - FEED_FALLBACK_BLOCK_WINDOW)
    val logs = fetchPostCreatedLogs(feedAddress = feedAddress, fromBlock = fromBlock, toBlock = "latest")
    if (logs.length() == 0) return emptyList()

    val cursorId = cursor?.postId?.trim()?.lowercase().orEmpty()
    val cursorCreatedAt = cursor?.createdAtSec ?: Long.MAX_VALUE
    val out = ArrayList<FeedPostCore>(safeLimit)

    for (idx in logs.length() - 1 downTo 0) {
      if (out.size >= safeLimit) break
      val row = logs.optJSONObject(idx) ?: continue
      val topics = row.optJSONArray("topics") ?: continue
      val postId = topics.optString(1, "").trim().lowercase()
      if (!postId.matches(Regex("^0x[0-9a-f]{64}$"))) continue

      val chainPost = fetchPostCoreFromChain(feedAddress = feedAddress, postId = postId) ?: continue
      if (chainPost.createdAtSec > cursorCreatedAt) continue
      if (chainPost.createdAtSec == cursorCreatedAt && cursorId.isNotBlank() && chainPost.id >= cursorId) continue
      out += chainPost
    }

    return out
  }

  private fun fetchPostCoreFromChain(
    feedAddress: String,
    postId: String,
  ): FeedPostCore? {
    @Suppress("UNCHECKED_CAST")
    val outputTypes = postsOutputTypes as List<TypeReference<Type<*>>>
    val function =
      Function(
        "posts",
        listOf(Bytes32(P256Utils.hexToBytes(postId))),
        outputTypes,
      )
    val resultHex = rpcEthCall(to = feedAddress, data = FunctionEncoder.encode(function))
    if (resultHex.isBlank() || resultHex == "0x") return null

    val decoded = FunctionReturnDecoder.decode(resultHex, outputTypes)
    if (decoded.size < 10) return null

    val status = ((decoded[4] as? Uint256)?.value?.toInt() ?: 0)
    if (status != 1) return null

    val songTrackId = "0x${P256Utils.bytesToHex((decoded[0] as Bytes32).value)}".lowercase()
    val creator = normalizeAddress((decoded[2] as Address).value)
    val createdAtSec = ((decoded[3] as? Uint256)?.value?.toLong() ?: 0L)
    val songStoryIpId = normalizeAddress((decoded[5] as Address).value)
    val postStoryIpIdRaw = normalizeAddress((decoded[6] as Address).value)
    val videoRef = (decoded[7] as Utf8String).value.trim()
    val captionRef = (decoded[8] as Utf8String).value.trim()
    val translationRef = (decoded[9] as Utf8String).value.trim().ifBlank { null }

    if (videoRef.isBlank() || captionRef.isBlank()) return null

    val likeCount =
      runCatching { fetchLikeCountFromChain(feedAddress = feedAddress, postId = postId) }
        .getOrElse { error ->
          Log.w(TAG, "fetchPostCoreFromChain likeCounts failed for $postId", error)
          0L
        }

    return FeedPostCore(
      id = postId,
      creator = creator,
      songTrackId = songTrackId,
      songStoryIpId = songStoryIpId,
      postStoryIpId = postStoryIpIdRaw.takeUnless { it == ZERO_ADDRESS },
      videoRef = videoRef,
      captionRef = captionRef,
      translationRef = translationRef,
      likeCount = likeCount,
      createdAtSec = createdAtSec,
    )
  }

  private fun fetchLikeCountFromChain(
    feedAddress: String,
    postId: String,
  ): Long {
    val function =
      Function(
        "likeCounts",
        listOf(Bytes32(P256Utils.hexToBytes(postId))),
        listOf(object : TypeReference<Uint256>() {}),
      )
    val resultHex = rpcEthCall(to = feedAddress, data = FunctionEncoder.encode(function))
    if (resultHex.isBlank() || resultHex == "0x") return 0L
    val decoded = FunctionReturnDecoder.decode(resultHex, function.outputParameters)
    val likeCount = (decoded.firstOrNull() as? Uint256)?.value?.toLong() ?: 0L
    return likeCount.coerceAtLeast(0L)
  }

  private fun fetchPostCreatedLogs(
    feedAddress: String,
    fromBlock: Long,
    toBlock: String,
  ): JSONArray {
    val filter =
      JSONObject()
        .put("address", feedAddress)
        .put("fromBlock", toHex(fromBlock))
        .put("toBlock", toBlock)
        .put("topics", JSONArray().put(POST_CREATED_TOPIC))
    val response = executeRpc("eth_getLogs", JSONArray().put(filter))
    return response.optJSONArray("result") ?: JSONArray()
  }

  private fun rpcBlockNumber(): Long {
    val response = executeRpc("eth_blockNumber", JSONArray())
    val result = response.optString("result", "0x0")
    return parseHexLong(result)
  }

  private fun rpcEthCall(
    to: String,
    data: String,
  ): String {
    val call =
      JSONObject()
        .put("to", to)
        .put("data", data)
    val response = executeRpc("eth_call", JSONArray().put(call).put("latest"))
    return response.optString("result", "0x")
  }

  private fun executeRpc(
    method: String,
    params: JSONArray,
  ): JSONObject {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", method)
        .put("params", params)
    val body = payload.toString().toRequestBody(jsonMediaType)
    val req = Request.Builder().url(TempoClient.RPC_URL).post(body).build()
    return client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("RPC query failed: ${res.code}")
      val raw = res.body?.string().orEmpty()
      val json = JSONObject(raw)
      val error = json.optJSONObject("error")
      if (error != null) {
        val msg = error.optString("message", error.toString())
        throw IllegalStateException("RPC error: $msg")
      }
      json
    }
  }

  private fun parseHexLong(value: String): Long {
    val raw = value.trim().removePrefix("0x").removePrefix("0X").ifBlank { "0" }
    return raw.toLong(16)
  }

  private fun isRecoverableFeedFetchError(error: Throwable): Boolean {
    if (error is IOException) return true
    val message = error.message?.lowercase().orEmpty()
    return message.contains("feed query failed: 5") ||
      message.contains("feed query failed: 429") ||
      message.contains("rpc query failed: 5") ||
      message.contains("timeout") ||
      message.contains("temporar") ||
      message.contains("connection reset") ||
      message.contains("connection refused")
  }

  private fun toHex(value: Long): String = "0x${value.toString(16)}"

  private fun normalizeAddress(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ZERO_ADDRESS
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    return "0x${prefixed.removePrefix("0x").removePrefix("0X").lowercase()}"
  }
}
