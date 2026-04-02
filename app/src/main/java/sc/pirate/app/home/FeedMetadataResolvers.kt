package sc.pirate.app.home

import android.util.LruCache
import sc.pirate.app.resolvePublicProfileIdentity
import sc.pirate.app.music.CoverRef
import sc.pirate.app.util.storyMusicSocialSubgraphUrls
import sc.pirate.app.util.baseProfilesSubgraphUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class FeedMetadataResolvers(
  private val client: OkHttpClient = OkHttpClient(),
  trackCacheSize: Int = 100,
  creatorCacheSize: Int = 100,
  captionCacheSize: Int = 256,
  translationCacheSize: Int = 256,
  refFetchParallelism: Int = 4,
) {
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val trackMetaCache = LruCache<String, TrackMeta>(trackCacheSize)
  private val creatorMetaCache = LruCache<String, CreatorMeta>(creatorCacheSize)
  private val captionCache = LruCache<String, CaptionMeta>(captionCacheSize)
  private val translationCache = LruCache<String, TranslationMeta>(translationCacheSize)
  private val trackCacheLock = Any()
  private val creatorCacheLock = Any()
  private val captionCacheLock = Any()
  private val translationCacheLock = Any()
  private val refFetchSemaphore = Semaphore(refFetchParallelism.coerceAtLeast(1))

  suspend fun resolvePosts(posts: List<FeedPostCore>): List<FeedPostResolved> {
    return resolvePosts(posts, viewerLocaleTag = null)
  }

  suspend fun resolvePosts(
    posts: List<FeedPostCore>,
    viewerLocaleTag: String?,
  ): List<FeedPostResolved> {
    if (posts.isEmpty()) return emptyList()
    val viewerLocale = viewerLocaleTag?.trim()?.ifBlank { null } ?: resolveFallbackViewerLocaleTag()

    val normalizedTrackIds =
      posts
        .asSequence()
        .map { it.songTrackId.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()
    val missingTrackIds = normalizedTrackIds.filterTo(mutableSetOf()) { cachedTrackMeta(it) == null }
    val fetchedTracks = fetchTrackMetaBatch(missingTrackIds)
    fetchedTracks.forEach { (trackId, meta) -> cacheTrackMeta(trackId, meta) }
    val normalizedCreators =
      posts
        .asSequence()
        .map { it.creator.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()
    val missingCreators = normalizedCreators.filterTo(mutableSetOf()) { cachedCreatorMeta(it) == null }
    val fetchedCreators = fetchCreatorMetaBatch(missingCreators)
    fetchedCreators.forEach { (creator, meta) -> cacheCreatorMeta(creator, meta) }

    return coroutineScope {
      posts.map { post ->
        async {
          val normalizedTrackId = post.songTrackId.trim().lowercase()
          val normalizedCreator = post.creator.trim().lowercase()
          val trackMeta = cachedTrackMeta(normalizedTrackId) ?: fetchedTracks[normalizedTrackId]
          val creatorMeta = cachedCreatorMeta(normalizedCreator) ?: fetchedCreators[normalizedCreator]
          val captionMeta = resolveCaptionMetadata(post.captionRef)
          val translationMeta = resolveTranslationMetadata(post.translationRef)
          val translatedText =
            if (captionMeta.captionText.isNotBlank()) {
              resolveTranslationForLocale(translationMeta.translations, viewerLocale)
            } else {
              null
            }
          val previewRef = captionMeta.previewRef ?: post.captionRef.trim().ifBlank { null }
          val displayCaption = translatedText ?: captionMeta.captionText
          FeedPostResolved(
            id = post.id,
            creator = post.creator,
            creatorHandle = creatorMeta?.handle,
            creatorDisplayName = creatorMeta?.displayName,
            creatorAvatarRef = creatorMeta?.avatarRef,
            creatorAvatarUrl = resolveCreatorAvatarUrl(creatorMeta?.avatarRef),
            songTrackId = post.songTrackId,
            songStoryIpId = post.songStoryIpId,
            postStoryIpId = post.postStoryIpId,
            videoRef = post.videoRef,
            videoUrl = resolveMediaUrl(post.videoRef),
            captionRef = post.captionRef,
            previewRef = previewRef,
            previewUrl = resolveMediaUrl(previewRef),
            previewAtMs = captionMeta.previewAtMs,
            translationRef = post.translationRef,
            likeCount = post.likeCount,
            createdAtSec = post.createdAtSec,
            captionText = displayCaption,
            captionLanguage = captionMeta.language?.let(::normalizeLocaleTag),
            songTitle = trackMeta?.title,
            songArtist = trackMeta?.artist,
            songCoverRef = trackMeta?.coverRef,
            songCoverUrl = resolveSongCoverUrl(trackMeta?.coverRef),
            taggedItems = captionMeta.taggedItems,
            translationText = translatedText,
            translationSourceLanguage = translationMeta.sourceLanguage?.let(::normalizeLocaleTag),
          )
        }
      }.awaitAll()
    }
  }

  private fun cachedTrackMeta(trackId: String): TrackMeta? {
    if (trackId.isBlank()) return null
    return synchronized(trackCacheLock) { trackMetaCache.get(trackId) }
  }

  private fun cacheTrackMeta(trackId: String, meta: TrackMeta) {
    if (trackId.isBlank()) return
    synchronized(trackCacheLock) { trackMetaCache.put(trackId, meta) }
  }

  private fun cachedCreatorMeta(creator: String): CreatorMeta? {
    if (creator.isBlank()) return null
    return synchronized(creatorCacheLock) { creatorMetaCache.get(creator) }
  }

  private fun cacheCreatorMeta(creator: String, meta: CreatorMeta) {
    if (creator.isBlank()) return
    synchronized(creatorCacheLock) { creatorMetaCache.put(creator, meta) }
  }

  private suspend fun fetchTrackMetaBatch(trackIds: Set<String>): Map<String, TrackMeta> = withContext(Dispatchers.IO) {
    if (trackIds.isEmpty()) return@withContext emptyMap()

    val remaining = trackIds.toMutableSet()
    val resolved = linkedMapOf<String, TrackMeta>()
    for (subgraphUrl in storyMusicSocialSubgraphUrls()) {
      if (remaining.isEmpty()) break
      val query =
        """
          query TrackMetaBatch(${'$'}ids: [ID!], ${'$'}first: Int!) {
            tracks(where: { id_in: ${'$'}ids }, first: ${'$'}first) {
              id
              title
              artist
              coverCid
            }
          }
        """.trimIndent()
      val variables =
        JSONObject()
          .put("ids", JSONArray(remaining.toList()))
          .put("first", remaining.size.coerceAtLeast(1))
      runCatching {
        executeQuery(subgraphUrl = subgraphUrl, query = query, variables = variables)
      }.onSuccess { json ->
        val tracks = json.optJSONObject("data")?.optJSONArray("tracks") ?: JSONArray()
        for (idx in 0 until tracks.length()) {
          val row = tracks.optJSONObject(idx) ?: continue
          val id = row.optString("id", "").trim().lowercase()
          if (id.isBlank()) continue
          val title = row.optString("title", "").trim().ifBlank { null }
          val artist = row.optString("artist", "").trim().ifBlank { null }
          val coverRef = decodeBytesUtf8(row.optString("coverCid", "").trim()).ifBlank { null }
          if (title == null && artist == null && coverRef == null) continue
          resolved[id] = TrackMeta(title = title, artist = artist, coverRef = coverRef)
          remaining.remove(id)
        }
      }
    }
    resolved
  }

  private suspend fun fetchCreatorMetaBatch(creators: Set<String>): Map<String, CreatorMeta> = withContext(Dispatchers.IO) {
    if (creators.isEmpty()) return@withContext emptyMap()

    val profileMeta = fetchCreatorProfilesBatch(creators)
    val identityByCreator =
      coroutineScope {
        creators.map { creator ->
          async {
            val identity = runCatching { resolvePublicProfileIdentity(creator) }.getOrNull()
            creator to identity
          }
        }.awaitAll().toMap()
      }

    val resolved = linkedMapOf<String, CreatorMeta>()
    creators.forEach { creator ->
      val identity = identityByCreator[creator]
      val handle = identity?.first?.trim().ifNullOrBlank()
      val identityAvatarRef = identity?.second?.trim().ifNullOrBlank()
      val profile = profileMeta[creator]
      val displayName = profile?.displayName
      val avatarRef = identityAvatarRef ?: profile?.avatarRef
      if (handle != null || displayName != null || avatarRef != null) {
        resolved[creator] = CreatorMeta(handle = handle, displayName = displayName, avatarRef = avatarRef)
      }
    }
    resolved
  }

  private fun fetchCreatorProfilesBatch(creators: Set<String>): Map<String, CreatorProfileMeta> {
    if (creators.isEmpty()) return emptyMap()

    val remaining = creators.toMutableSet()
    val resolved = linkedMapOf<String, CreatorProfileMeta>()
    for (subgraphUrl in baseProfilesSubgraphUrls()) {
      if (remaining.isEmpty()) break
      val query =
        """
          query CreatorProfileBatch(${'$'}ids: [ID!], ${'$'}first: Int!) {
            profiles(where: { id_in: ${'$'}ids }, first: ${'$'}first) {
              id
              displayName
              photoURI
            }
          }
        """.trimIndent()
      val variables =
        JSONObject()
          .put("ids", JSONArray(remaining.toList()))
          .put("first", remaining.size.coerceAtLeast(1))
      runCatching {
        executeQuery(subgraphUrl = subgraphUrl, query = query, variables = variables)
      }.onSuccess { json ->
        val profiles = json.optJSONObject("data")?.optJSONArray("profiles") ?: JSONArray()
        for (idx in 0 until profiles.length()) {
          val row = profiles.optJSONObject(idx) ?: continue
          val id = row.optString("id", "").trim().lowercase()
          if (id.isBlank()) continue
          val displayName = row.optString("displayName", "").trim().ifBlank { null }
          val avatarRef = row.optString("photoURI", "").trim().ifBlank { null }
          if (displayName == null && avatarRef == null) continue
          resolved[id] = CreatorProfileMeta(displayName = displayName, avatarRef = avatarRef)
          remaining.remove(id)
        }
      }
    }
    return resolved
  }

  private suspend fun resolveCaptionMetadata(captionRef: String): CaptionMeta {
    val normalizedRef = captionRef.trim()
    if (normalizedRef.isBlank()) return CaptionMeta.EMPTY

    synchronized(captionCacheLock) {
      captionCache.get(normalizedRef)?.let { return it }
    }

    return refFetchSemaphore.withPermit {
      synchronized(captionCacheLock) {
        captionCache.get(normalizedRef)?.let { return@withPermit it }
      }

      val resolved =
        withContext(Dispatchers.IO) {
          val url = resolveMediaUrl(normalizedRef) ?: return@withContext CaptionMeta.EMPTY
          val req = Request.Builder().url(url).get().build()
          runCatching {
            client.newCall(req).execute().use { res ->
              if (!res.isSuccessful) {
                return@use CaptionMeta(
                  captionText = "",
                  language = null,
                  previewRef = normalizedRef,
                  previewAtMs = null,
                  taggedItems = emptyList(),
                )
              }
              val raw = res.body?.string().orEmpty().trim()
              if (raw.isBlank()) {
                return@use CaptionMeta(
                  captionText = "",
                  language = null,
                  previewRef = normalizedRef,
                  previewAtMs = null,
                  taggedItems = emptyList(),
                )
              }
              val parsed = parseCaptionPayload(raw)
              if (parsed.previewRef == null && parsed.captionText.isBlank()) {
                return@use parsed.copy(previewRef = normalizedRef)
              }
              parsed
            }
          }.getOrElse {
            CaptionMeta(
              captionText = "",
              language = null,
              previewRef = normalizedRef,
              previewAtMs = null,
              taggedItems = emptyList(),
            )
          }
        }

      synchronized(captionCacheLock) {
        captionCache.put(normalizedRef, resolved)
      }
      resolved
    }
  }

  private suspend fun resolveTranslationMetadata(translationRef: String?): TranslationMeta {
    val normalizedRef = translationRef?.trim().orEmpty()
    if (normalizedRef.isBlank()) return TranslationMeta.EMPTY

    synchronized(translationCacheLock) {
      translationCache.get(normalizedRef)?.let { return it }
    }

    return refFetchSemaphore.withPermit {
      synchronized(translationCacheLock) {
        translationCache.get(normalizedRef)?.let { return@withPermit it }
      }

      val resolved =
        withContext(Dispatchers.IO) {
          val url = resolveMediaUrl(normalizedRef) ?: return@withContext TranslationMeta.EMPTY
          val req = Request.Builder().url(url).get().build()
          runCatching {
            client.newCall(req).execute().use { res ->
              if (!res.isSuccessful) return@use TranslationMeta.EMPTY
              val raw = res.body?.string().orEmpty().trim()
              if (raw.isBlank()) return@use TranslationMeta.EMPTY
              parseTranslationPayload(raw)
            }
          }.getOrElse { TranslationMeta.EMPTY }
        }

      synchronized(translationCacheLock) {
        translationCache.put(normalizedRef, resolved)
      }
      resolved
    }
  }

  private fun parseCaptionPayload(raw: String): CaptionMeta {
    val payload = runCatching { JSONObject(raw) }.getOrNull()
    if (payload != null) {
      val source = payload.optJSONObject("source")
      val captionText =
        payload.optString("caption", "").trim()
          .ifBlank { source?.optString("caption", "")?.trim().orEmpty() }
          .ifBlank { payload.optString("text", "").trim() }
      val previewRef =
        payload.optString("previewRef", "").trim()
          .ifBlank { source?.optString("previewRef", "")?.trim().orEmpty() }
          .ifBlank { null }
      val previewAtMs = parsePreviewAtMs(payload, source)
      val language =
        payload.optString("lang", "").trim()
          .ifBlank { source?.optString("lang", "")?.trim().orEmpty() }
          .ifBlank { null }
      val taggedItems = parseTaggedItems(payload.optJSONArray("affiliateItems"))
      return CaptionMeta(
        captionText = captionText,
        language = language,
        previewRef = previewRef,
        previewAtMs = previewAtMs,
        taggedItems = taggedItems,
      )
    }

    val firstLine =
      raw
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(280)
        .orEmpty()
    return CaptionMeta(
      captionText = firstLine,
      language = null,
      previewRef = null,
      previewAtMs = null,
      taggedItems = emptyList(),
    )
  }

  private fun parseTaggedItems(array: JSONArray?): List<FeedTaggedItem> {
    if (array == null) return emptyList()
    val out = ArrayList<FeedTaggedItem>(array.length())
    for (index in 0 until array.length()) {
      val entry = array.optJSONObject(index) ?: continue
      val title = entry.optString("title", "").trim()
      val requestedUrl = entry.optString("requestedUrl", "").trim()
      val canonicalUrl = entry.optString("canonicalUrl", "").trim().ifBlank { requestedUrl }
      if (title.isBlank() || canonicalUrl.isBlank()) continue
      val images = parseStringArray(entry.optJSONArray("images"))
      out += FeedTaggedItem(
        requestedUrl = requestedUrl,
        canonicalUrl = canonicalUrl,
        merchant = entry.optString("merchant", "").trim(),
        title = title,
        brand = entry.optString("brand", "").trim().ifBlank { null },
        price = optDouble(entry.opt("price")),
        currency = entry.optString("currency", "").trim().ifBlank { null },
        size = entry.optString("size", "").trim().ifBlank { null },
        condition = entry.optString("condition", "").trim().ifBlank { null },
        imageUrl = entry.optString("imageUrl", "").trim().ifBlank { images.firstOrNull() },
        images = images,
      )
    }
    return out
  }

  private fun parseStringArray(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val out = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
      val value = array.optString(index, "").trim()
      if (value.isNotBlank()) out += value
    }
    return out
  }

  private fun optDouble(value: Any?): Double? =
    when (value) {
      null, JSONObject.NULL -> null
      is Number -> value.toDouble()
      is String -> value.trim().toDoubleOrNull()
      else -> null
    }

  private fun parseTranslationPayload(raw: String): TranslationMeta {
    val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return TranslationMeta.EMPTY
    val source = payload.optJSONObject("source")
    val sourceCaption =
      source?.optString("caption", "")
        ?.trim()
        ?.ifBlank { null }
        ?: payload.optString("caption", "").trim().ifBlank { null }
    val sourceLanguage =
      source?.optString("language", "")
        ?.trim()
        ?.ifBlank { null }
        ?: payload.optString("language", "").trim().ifBlank { null }

    val translationsObject =
      payload.optJSONObject("translations")
        ?: payload.optJSONObject("locales")
        ?: payload.optJSONObject("byLocale")
        ?: payload.optJSONObject("items")
        ?: return TranslationMeta(
          sourceCaption = sourceCaption,
          sourceLanguage = sourceLanguage,
          translations = emptyMap(),
        )

    val translations = linkedMapOf<String, String>()
    val iterator = translationsObject.keys()
    while (iterator.hasNext()) {
      val key = iterator.next()
      val value = translationsObject.optString(key, "").trim()
      if (value.isBlank()) continue
      translations[normalizeLocaleTag(key)] = value
    }

    return TranslationMeta(
      sourceCaption = sourceCaption,
      sourceLanguage = sourceLanguage,
      translations = translations,
    )
  }

  private fun parsePreviewAtMs(
    payload: JSONObject,
    source: JSONObject?,
  ): Long? {
    val fromRoot = payload.optLong("previewAtMs", -1L)
    if (fromRoot > 0L) return fromRoot
    val fromSource = source?.optLong("previewAtMs", -1L) ?: -1L
    return fromSource.takeIf { it > 0L }
  }

  private fun resolveFallbackViewerLocaleTag(): String {
    val locale = Locale.getDefault()
    val asTag = runCatching { locale.toLanguageTag() }.getOrNull().orEmpty().trim()
    if (asTag.isNotBlank()) return normalizeLocaleTag(asTag)
    return normalizeLocaleTag(locale.language.ifBlank { "en" })
  }

  private fun resolveTranslationForLocale(
    translations: Map<String, String>,
    rawLocale: String,
  ): String? {
    if (translations.isEmpty()) return null
    val normalized = translations.mapKeys { normalizeLocaleTag(it.key) }
    val candidates = localeFallbacks(rawLocale)
    candidates.forEach { candidate ->
      normalized[candidate]?.let { return it }
    }
    return null
  }

  private fun localeFallbacks(rawLocale: String): List<String> {
    val normalized = normalizeLocaleTag(rawLocale)
    val out = linkedSetOf<String>()
    out += normalized

    val parts = normalized.split('-')
    if (parts.size >= 2) {
      if (parts[1].matches(Regex("^[A-Z][a-z]{3}$"))) {
        out += "${parts[0]}-${parts[1]}"
      }
      out += parts[0]
    }

    if (parts.firstOrNull() == "zh") {
      if (normalized == "zh-Hans") {
        out += normalizeLocaleTag("zh-CN")
        out += normalizeLocaleTag("zh-SG")
      } else if (normalized == "zh-Hant") {
        out += normalizeLocaleTag("zh-TW")
        out += normalizeLocaleTag("zh-HK")
      }
      out += "zh"
    }
    return out.toList()
  }

  private fun normalizeLocaleTag(raw: String): String {
    val cleaned = raw.trim().replace('_', '-')
    if (cleaned.isBlank()) return "en"
    val tokens = cleaned.split('-').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return "en"

    val language = tokens[0].lowercase()
    var script: String? = null
    var region: String? = null
    val variants = mutableListOf<String>()
    tokens.drop(1).forEach { token ->
      when {
        script == null && token.matches(Regex("^[A-Za-z]{4}$")) ->
          script = token.lowercase().replaceFirstChar { it.titlecase() }
        region == null && token.matches(Regex("^([A-Za-z]{2}|\\d{3})$")) ->
          region = token.uppercase()
        else -> variants += token.lowercase()
      }
    }

    if (language == "zh" && script == null) {
      when (region) {
        "TW", "HK", "MO" -> {
          script = "Hant"
          region = null
        }
        "CN", "SG", "MY" -> {
          script = "Hans"
          region = null
        }
      }
    }

    return buildString {
      append(language)
      if (!script.isNullOrBlank()) append('-').append(script)
      if (!region.isNullOrBlank()) append('-').append(region)
      variants.forEach { append('-').append(it) }
    }
  }

  private fun resolveMediaUrl(ref: String?): String? {
    return CoverRef.resolveCoverUrl(
      ref = ref,
      width = null,
      height = null,
      format = null,
      quality = null,
    )
  }

  private fun resolveSongCoverUrl(ref: String?): String? {
    return CoverRef.resolveCoverUrl(
      ref = ref,
      width = 96,
      height = 96,
      format = "webp",
      quality = 80,
    )
  }

  private fun resolveCreatorAvatarUrl(ref: String?): String? {
    return CoverRef.resolveCoverUrl(
      ref = ref,
      width = 192,
      height = 192,
      format = "webp",
      quality = 82,
    )
  }

  private fun decodeBytesUtf8(value: String): String {
    val v = value.trim()
    if (!v.startsWith("0x")) return v
    val hex = v.removePrefix("0x")
    if (hex.isEmpty() || hex.length % 2 != 0) return v
    if (!hex.all { it.isDigit() || (it.lowercaseChar() in 'a'..'f') }) return v
    return try {
      val bytes = ByteArray(hex.length / 2)
      var i = 0
      while (i < hex.length) {
        bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        i += 2
      }
      bytes.toString(Charsets.UTF_8).trimEnd { it == '\u0000' }
    } catch (_: Throwable) {
      v
    }
  }

  private fun String?.ifNullOrBlank(): String? = this?.takeIf { it.isNotBlank() }

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
      if (!res.isSuccessful) throw IllegalStateException("GraphQL query failed: ${res.code}")
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

  private data class TrackMeta(
    val title: String?,
    val artist: String?,
    val coverRef: String?,
  )

  private data class CreatorMeta(
    val handle: String?,
    val displayName: String?,
    val avatarRef: String?,
  )

  private data class CreatorProfileMeta(
    val displayName: String?,
    val avatarRef: String?,
  )

  private data class CaptionMeta(
    val captionText: String,
    val language: String?,
    val previewRef: String?,
    val previewAtMs: Long?,
    val taggedItems: List<FeedTaggedItem>,
  ) {
    companion object {
      val EMPTY = CaptionMeta(
        captionText = "",
        language = null,
        previewRef = null,
        previewAtMs = null,
        taggedItems = emptyList(),
      )
    }
  }

  private data class TranslationMeta(
    val sourceCaption: String?,
    val sourceLanguage: String?,
    val translations: Map<String, String>,
  ) {
    companion object {
      val EMPTY = TranslationMeta(
        sourceCaption = null,
        sourceLanguage = null,
        translations = emptyMap(),
      )
    }
  }
}
