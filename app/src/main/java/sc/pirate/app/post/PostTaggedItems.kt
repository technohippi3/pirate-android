package sc.pirate.app.post

import sc.pirate.app.music.SongPublishService
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAGGED_ITEMS_RESOLVE_TIMEOUT_MS = 30_000

data class PostTaggedItem(
  val requestedUrl: String,
  val normalizedUrl: String,
  val canonicalUrl: String,
  val merchant: String,
  val itemId: String?,
  val title: String,
  val brand: String?,
  val price: Double?,
  val currency: String?,
  val size: String?,
  val sizeSystem: String?,
  val condition: String?,
  val category: String?,
  val subcategory: String?,
  val material: String?,
  val color: String?,
  val description: String?,
  val seller: String?,
  val location: String?,
  val images: List<String>,
  val imageUrl: String?,
  val listedAt: String?,
)

data class PostTaggedItemsRejected(
  val input: String,
  val code: String,
  val message: String,
)

data class PostTaggedItemsResolveResult(
  val items: List<PostTaggedItem>,
  val rejected: List<PostTaggedItemsRejected>,
)

object PostTaggedItemsApi {
  fun resolve(
    userAddress: String,
    pastedText: String,
  ): PostTaggedItemsResolveResult {
    val owner = userAddress.trim().lowercase()
    if (owner.isBlank()) throw IllegalStateException("Missing user address")

    val input = pastedText.trim()
    if (input.isBlank()) {
      return PostTaggedItemsResolveResult(items = emptyList(), rejected = emptyList())
    }

    val url = URL("${SongPublishService.API_CORE_URL}/api/music/post-tagged-items/resolve")
    val conn =
      (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = TAGGED_ITEMS_RESOLVE_TIMEOUT_MS
        readTimeout = TAGGED_ITEMS_RESOLVE_TIMEOUT_MS
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("X-User-Address", owner)
      }

    val body = JSONObject().put("text", input).toString()
    conn.outputStream.use { out ->
      out.write(body.toByteArray(Charsets.UTF_8))
    }

    val status = conn.responseCode
    val raw =
      (if (status in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()
    val json = runCatching { JSONObject(raw) }.getOrNull()
    if (status !in 200..299) {
      val code = json?.optString("code", "").orEmpty()
      val details = json?.optString("error", "").orEmpty().ifBlank { raw.ifBlank { "HTTP $status" } }
      throw IllegalStateException(mapResolveErrorMessage(status = status, code = code, details = details))
    }
    if (json == null) throw IllegalStateException("Tagged item resolver returned invalid JSON")

    val items =
      jsonObjects(json.optJSONArray("items")).map { item ->
        PostTaggedItem(
          requestedUrl = optStringOrNull(item, "requestedUrl").orEmpty(),
          normalizedUrl = optStringOrNull(item, "normalizedUrl").orEmpty(),
          canonicalUrl = optStringOrNull(item, "canonicalUrl").orEmpty(),
          merchant = optStringOrNull(item, "merchant").orEmpty(),
          itemId = optStringOrNull(item, "itemId"),
          title = optStringOrNull(item, "title").orEmpty(),
          brand = optStringOrNull(item, "brand"),
          price = optDoubleOrNull(item.opt("price")),
          currency = optStringOrNull(item, "currency"),
          size = optStringOrNull(item, "size"),
          sizeSystem = optStringOrNull(item, "sizeSystem"),
          condition = optStringOrNull(item, "condition"),
          category = optStringOrNull(item, "category"),
          subcategory = optStringOrNull(item, "subcategory"),
          material = optStringOrNull(item, "material"),
          color = optStringOrNull(item, "color"),
          description = optStringOrNull(item, "description"),
          seller = optStringOrNull(item, "seller"),
          location = optStringOrNull(item, "location"),
          images = jsonStrings(item.optJSONArray("images")),
          imageUrl = optStringOrNull(item, "imageUrl"),
          listedAt = optStringOrNull(item, "listedAt"),
        )
      }.filter { it.title.isNotBlank() }

    val rejected =
      jsonObjects(json.optJSONArray("rejected")).map { item ->
        PostTaggedItemsRejected(
          input = optStringOrNull(item, "input").orEmpty(),
          code = optStringOrNull(item, "code").orEmpty(),
          message = optStringOrNull(item, "message").orEmpty(),
        )
      }

    return PostTaggedItemsResolveResult(items = items, rejected = rejected)
  }
}

private fun mapResolveErrorMessage(
  status: Int,
  code: String,
  details: String,
): String {
  val normalizedCode = code.trim()
  val normalizedDetails = details.trim()
  if (normalizedCode == "tagged_items_unavailable") {
    return "Shopping links are unavailable right now. Try again later."
  }
  if (
    status == 503 &&
    normalizedDetails.contains("MUSIC_TAGGED_ITEMS_WORKER or MUSIC_TAGGED_ITEMS_RESOLVER_URL must be configured")
  ) {
    return "Shopping links are unavailable right now. Try again later."
  }
  return normalizedDetails.ifBlank { "HTTP $status" }
}

private fun jsonObjects(array: JSONArray?): List<JSONObject> {
  if (array == null) return emptyList()
  val out = ArrayList<JSONObject>(array.length())
  for (index in 0 until array.length()) {
    val entry = array.opt(index) as? JSONObject ?: continue
    out += entry
  }
  return out
}

private fun jsonStrings(array: JSONArray?): List<String> {
  if (array == null) return emptyList()
  val out = ArrayList<String>(array.length())
  for (index in 0 until array.length()) {
    val value = array.optString(index, "").trim()
    if (value.isNotBlank()) out += value
  }
  return out
}

private fun optStringOrNull(
  json: JSONObject,
  key: String,
): String? {
  if (!json.has(key) || json.isNull(key)) return null
  return json.optString(key, "").trim().ifBlank { null }
}

private fun optDoubleOrNull(value: Any?): Double? =
  when (value) {
    null, JSONObject.NULL -> null
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
  }
