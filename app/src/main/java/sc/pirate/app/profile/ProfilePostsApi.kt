package sc.pirate.app.profile

import android.content.Context
import sc.pirate.app.ViewerContentLocaleResolver
import sc.pirate.app.home.FeedMetadataResolvers
import sc.pirate.app.home.FeedPostCore
import sc.pirate.app.home.FeedPostResolved
import sc.pirate.app.util.storyFeedSubgraphUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object ProfilePostsApi {
  private const val MAX_POSTS = 30
  private val client = OkHttpClient()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val metadataResolvers = FeedMetadataResolvers(client = client)

  suspend fun fetchPostsByCreator(creatorAddress: String, limit: Int = MAX_POSTS): List<FeedPostResolved> =
    fetchPostsByCreator(context = null, creatorAddress = creatorAddress, limit = limit)

  suspend fun fetchPostsByCreator(
    context: Context?,
    creatorAddress: String,
    limit: Int = MAX_POSTS,
  ): List<FeedPostResolved> =
    withContext(Dispatchers.IO) {
      val normalizedCreator = creatorAddress.trim().lowercase()
      if (!normalizedCreator.matches(Regex("^0x[0-9a-f]{40}$"))) return@withContext emptyList()

      val first = limit.coerceIn(1, MAX_POSTS)
      var lastError: Throwable? = null
      val viewerLocaleTag = context?.let { ViewerContentLocaleResolver.resolve(it.applicationContext) }

      for (subgraphUrl in storyFeedSubgraphUrls()) {
        try {
          val posts = fetchCorePostsFromSubgraph(subgraphUrl, normalizedCreator, first)
          return@withContext metadataResolvers.resolvePosts(posts, viewerLocaleTag = viewerLocaleTag)
        } catch (error: Throwable) {
          lastError = error
        }
      }

      if (lastError != null) throw lastError
      emptyList()
    }

  private fun fetchCorePostsFromSubgraph(
    subgraphUrl: String,
    creator: String,
    limit: Int,
  ): List<FeedPostCore> {
    val query =
      """
        query FeedPostsByCreator(${'$'}creator: Bytes!, ${'$'}first: Int!) {
          posts(
            first: ${'$'}first
            where: { creator: ${'$'}creator, status: 1 }
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
    val variables = JSONObject().put("creator", creator).put("first", limit)
    val json = executeQuery(subgraphUrl, query, variables)
    val rows = json.optJSONObject("data")?.optJSONArray("posts") ?: JSONArray()
    val posts = ArrayList<FeedPostCore>(rows.length())
    for (idx in 0 until rows.length()) {
      val row = rows.optJSONObject(idx) ?: continue
      val id = row.optString("id", "").trim().lowercase()
      if (id.isBlank()) continue
      posts += FeedPostCore(
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
    return posts
  }

  private fun executeQuery(
    subgraphUrl: String,
    query: String,
    variables: JSONObject,
  ): JSONObject {
    val body =
      JSONObject()
        .put("query", query)
        .put("variables", variables)
        .toString()
        .toRequestBody(jsonMediaType)
    val request = Request.Builder().url(subgraphUrl).post(body).build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("Profile posts query failed: ${response.code}")
      val json = JSONObject(response.body?.string().orEmpty())
      val errors = json.optJSONArray("errors")
      if (errors != null && errors.length() > 0) {
        val message = errors.optJSONObject(0)?.optString("message", "GraphQL error") ?: "GraphQL error"
        throw IllegalStateException(message)
      }
      json
    }
  }

  private fun parseLongField(row: JSONObject, field: String): Long {
    val value = row.opt(field)
    return when (value) {
      is Number -> value.toLong()
      is String -> value.trim().toLongOrNull() ?: 0L
      else -> 0L
    }
  }
}
