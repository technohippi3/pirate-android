package sc.pirate.app.post

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import sc.pirate.app.BuildConfig
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.crypto.P256Utils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256

internal object PostTxInternals {
  private const val ZERO_BYTES32 = "0x0000000000000000000000000000000000000000000000000000000000000000"
  private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
  private const val POST_CREATED_TOPIC =
    "0xf33ccb9d20522e9ebf5a1d6b9caba40fb396e20cd76a5e4b4bff494bdea84b9f"
  private const val MAX_VIDEO_BYTES = 150L * 1024L * 1024L
  private const val MAX_PREVIEW_IMAGE_BYTES = 6L * 1024L * 1024L
  private const val MAX_CAPTION_BYTES = 256 * 1024
  private const val PREVIEW_IMAGE_MAX_DIMENSION_PX = 1280
  private const val PREVIEW_FRAME_DEFAULT_MS = 1_000L
  private const val GAS_LIMIT_BUFFER = 220_000L
  private val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")
  private val jsonType = "application/json".toMediaType()
  private val client = OkHttpClient()

  fun feedContractOrNull(): String? {
    val primary = PirateChainConfig.STORY_FEED_V2.trim()
    if (ADDRESS_REGEX.matches(primary)) {
      return "0x${primary.removePrefix("0x").removePrefix("0X")}"
    }
    return null
  }

  fun normalizeAddress(address: String): String {
    val trimmed = address.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    if (!ADDRESS_REGEX.matches(prefixed)) throw IllegalArgumentException("Invalid address: $address")
    return "0x${prefixed.removePrefix("0x").removePrefix("0X")}"
  }

  fun normalizeBytes32(
    value: String,
    fieldName: String,
  ): String {
    val clean = value.trim().removePrefix("0x").removePrefix("0X").lowercase(Locale.US)
    require(clean.isNotEmpty() && clean.length <= 64 && clean.all { it.isDigit() || it in 'a'..'f' }) {
      "Invalid $fieldName"
    }
    return "0x${clean.padStart(64, '0')}"
  }

  fun withBuffer(
    estimated: Long,
    minimum: Long,
  ): Long {
    if (estimated <= 0L) return minimum
    val buffered = if (estimated > Long.MAX_VALUE - GAS_LIMIT_BUFFER) Long.MAX_VALUE else estimated + GAS_LIMIT_BUFFER
    return maxOf(minimum, buffered)
  }

  fun estimateGas(
    from: String,
    to: String,
    data: String,
  ): Long {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_estimateGas")
        .put(
          "params",
          JSONArray().put(
            JSONObject()
              .put("from", from)
              .put("to", to)
              .put("data", data),
          ),
        )

    val req =
      Request.Builder()
        .url(PirateChainConfig.STORY_AENEID_RPC_URL)
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    client.newCall(req).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val body = JSONObject(response.body?.string().orEmpty())
      val error = body.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      val hex = body.optString("result", "0x0").removePrefix("0x").ifBlank { "0" }
      return hex.toLongOrNull(16) ?: 0L
    }
  }

  fun encodeCreatePostCalldata(
    songTrackId: String,
    songStoryIpId: String = ZERO_ADDRESS,
    parentPostId: String = ZERO_BYTES32,
    videoRef: String,
    captionRef: String,
  ): String {
    val normalizedSongStoryIpId = normalizeAddress(songStoryIpId)
    val normalizedParentPostId = normalizeBytes32(parentPostId, "parent post id")
    val input =
      DynamicStruct(
        Bytes32(P256Utils.hexToBytes(songTrackId)),
        Address(normalizedSongStoryIpId),
        Bytes32(P256Utils.hexToBytes(normalizedParentPostId)),
        Utf8String(videoRef),
        Utf8String(captionRef),
      )
    val function = Function("createPost", listOf(input), emptyList())
    return FunctionEncoder.encode(function)
  }

  fun encodePostActionCalldata(
    functionName: String,
    postId: String,
  ): String {
    val function = Function(
      functionName,
      listOf(Bytes32(P256Utils.hexToBytes(postId))),
      emptyList(),
    )
    return FunctionEncoder.encode(function)
  }

  fun isVerifiedForFeed(
    feed: String,
    userAddress: String,
  ): Boolean {
    val verifierFunction = Function("verifier", emptyList(), listOf(object : TypeReference<Address>() {}))
    val verifierHex = ethCall(to = feed, data = FunctionEncoder.encode(verifierFunction))
    val verifierDecoded = FunctionReturnDecoder.decode(verifierHex, verifierFunction.outputParameters)
    val verifierAddress = (verifierDecoded.firstOrNull() as? Address)?.value?.trim().orEmpty()
    if (verifierAddress.isBlank() || verifierAddress.equals(ZERO_ADDRESS, ignoreCase = true)) {
      throw IllegalStateException("Feed verifier is not configured")
    }

    val verifiedFunction = Function(
      "isVerified",
      listOf(Address(userAddress)),
      listOf(object : TypeReference<Bool>() {}),
    )
    val verifiedHex = ethCall(to = verifierAddress, data = FunctionEncoder.encode(verifiedFunction))
    val verifiedDecoded = FunctionReturnDecoder.decode(verifiedHex, verifiedFunction.outputParameters)
    return (verifiedDecoded.firstOrNull() as? Bool)?.value ?: false
  }

  fun ethCall(
    to: String,
    data: String,
  ): String {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_call")
        .put(
          "params",
          JSONArray()
            .put(JSONObject().put("to", to).put("data", data))
            .put("latest"),
        )

    val req =
      Request.Builder()
        .url(PirateChainConfig.STORY_AENEID_RPC_URL)
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    client.newCall(req).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val body = JSONObject(response.body?.string().orEmpty())
      val error = body.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      return body.optString("result", "0x")
    }
  }

  data class UploadRef(
    val ref: String,
    val id: String,
    val gatewayUrl: String?,
  )

  data class PostStoryEnqueueResult(
    val status: String,
    val postId: String? = null,
    val postStoryIpId: String? = null,
  )

  data class PostStoryStatusResult(
    val status: String,
    val postStoryIpId: String? = null,
  )

  fun uploadVideo(
    context: Context,
    userAddress: String,
    uri: Uri,
  ): UploadRef {
    val name = deriveFileName(uri = uri, fallback = "feed-video.mp4")
    val contentType = context.contentResolver.getType(uri)?.trim().takeUnless { it.isNullOrBlank() } ?: "video/mp4"
    return uploadMultipart(
      userAddress = userAddress,
      fileName = name,
      contentType = contentType,
      maxBytes = MAX_VIDEO_BYTES,
      streamProvider = {
        context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Unable to read selected video")
      },
    )
  }

  fun uploadPreviewImage(
    context: Context,
    userAddress: String,
    uri: Uri,
    previewAtMs: Long? = null,
  ): UploadRef {
    val previewBytes = extractPreviewImageBytes(context = context, uri = uri, previewAtMs = previewAtMs)
    return uploadMultipart(
      userAddress = userAddress,
      fileName = "feed-preview-${System.currentTimeMillis()}.jpg",
      contentType = "image/jpeg",
      maxBytes = MAX_PREVIEW_IMAGE_BYTES,
      streamProvider = { previewBytes.inputStream() },
    )
  }

  fun uploadCaption(
    userAddress: String,
    captionText: String,
    taggedItems: List<PostTaggedItem> = emptyList(),
    previewAtMs: Long? = null,
    previewRef: String? = null,
  ): UploadRef {
    val language = Locale.getDefault().language.ifBlank { "en" }
    val payload =
      JSONObject()
        .put("lang", language)
        .put("caption", captionText.trim())
        .put("createdAtSec", System.currentTimeMillis() / 1000L)

    val normalizedPreviewRef = previewRef?.trim().orEmpty()
    if (normalizedPreviewRef.isNotBlank()) {
      payload.put("previewRef", normalizedPreviewRef)
    }
    val normalizedPreviewAtMs = previewAtMs ?: 0L
    if (normalizedPreviewAtMs > 0L) {
      payload.put("previewAtMs", normalizedPreviewAtMs)
    }
    val affiliateItems = JSONArray()
    for (item in taggedItems.take(5)) {
      if (item.title.isBlank()) continue
      affiliateItems.put(taggedItemJson(item))
    }
    if (affiliateItems.length() > 0) {
      payload.put("affiliateItems", affiliateItems)
    }

    val bytes = payload.toString().toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_CAPTION_BYTES) {
      throw IllegalStateException("Caption payload exceeds 256KB")
    }

    return uploadMultipart(
      userAddress = userAddress,
      fileName = "feed-caption-${System.currentTimeMillis()}.json",
      contentType = "application/json",
      maxBytes = MAX_CAPTION_BYTES.toLong(),
      streamProvider = { bytes.inputStream() },
    )
  }

  private fun taggedItemJson(item: PostTaggedItem): JSONObject {
    val json =
      JSONObject()
        .put("requestedUrl", item.requestedUrl)
        .put("normalizedUrl", item.normalizedUrl)
        .put("canonicalUrl", item.canonicalUrl)
        .put("merchant", item.merchant)
        .put("title", item.title)
    item.itemId?.takeIf { it.isNotBlank() }?.let { json.put("itemId", it) }
    item.brand?.takeIf { it.isNotBlank() }?.let { json.put("brand", it) }
    item.price?.let { json.put("price", it) }
    item.currency?.takeIf { it.isNotBlank() }?.let { json.put("currency", it) }
    item.size?.takeIf { it.isNotBlank() }?.let { json.put("size", it) }
    item.sizeSystem?.takeIf { it.isNotBlank() }?.let { json.put("sizeSystem", it) }
    item.condition?.takeIf { it.isNotBlank() }?.let { json.put("condition", it) }
    item.category?.takeIf { it.isNotBlank() }?.let { json.put("category", it) }
    item.subcategory?.takeIf { it.isNotBlank() }?.let { json.put("subcategory", it) }
    item.material?.takeIf { it.isNotBlank() }?.let { json.put("material", it) }
    item.color?.takeIf { it.isNotBlank() }?.let { json.put("color", it) }
    item.description?.takeIf { it.isNotBlank() }?.let { json.put("description", it) }
    item.seller?.takeIf { it.isNotBlank() }?.let { json.put("seller", it) }
    item.location?.takeIf { it.isNotBlank() }?.let { json.put("location", it) }
    if (item.images.isNotEmpty()) {
      json.put("images", JSONArray(item.images))
    }
    item.imageUrl?.takeIf { it.isNotBlank() }?.let { json.put("imageUrl", it) }
    item.listedAt?.takeIf { it.isNotBlank() }?.let { json.put("listedAt", it) }
    return json
  }

  private fun uploadMultipart(
    userAddress: String,
    fileName: String,
    contentType: String,
    maxBytes: Long,
    streamProvider: () -> InputStream,
  ): UploadRef {
    val boundary = "----PirateFeed${System.currentTimeMillis()}"
    val uploadUrl = URL("${SongPublishService.API_CORE_URL}/api/storage/upload")
    val conn =
      (uploadUrl.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 120_000
        readTimeout = 120_000
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("X-User-Address", userAddress)
      }

    conn.outputStream.use { out ->
      out.write("--$boundary\r\n".toByteArray())
      out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
      out.write("Content-Type: $contentType\r\n\r\n".toByteArray())
      streamProvider().use { input ->
        copyWithLimit(input = input, output = out, maxBytes = maxBytes, overLimitMessage = "Upload exceeds allowed size")
      }
      out.write("\r\n".toByteArray())

      out.write("--$boundary\r\n".toByteArray())
      out.write("Content-Disposition: form-data; name=\"contentType\"\r\n\r\n".toByteArray())
      out.write(contentType.toByteArray(Charsets.UTF_8))
      out.write("\r\n".toByteArray())
      out.write("--$boundary--\r\n".toByteArray())
    }

    val status = conn.responseCode
    val raw =
      (if (status in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()
    val json = runCatching { JSONObject(raw) }.getOrNull()
    if (status !in 200..299) {
      val details = json?.optString("error", "").orEmpty().ifBlank { raw.ifBlank { "HTTP $status" } }
      throw IllegalStateException("Upload failed: $details")
    }
    val cid = sequenceOf(
      json?.optString("cid", "")?.trim(),
      json?.optString("ref", "")?.trim()?.removePrefix("ipfs://"),
      json?.optJSONObject("payload")?.optString("cidHeader", "")?.trim(),
    ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    if (cid.isBlank()) throw IllegalStateException("Upload succeeded but returned no cid")
    val gatewayUrl = json?.optString("gatewayUrl", "").orEmpty().trim().ifBlank { null }
    return UploadRef(ref = "ipfs://$cid", id = cid, gatewayUrl = gatewayUrl)
  }

  private fun copyWithLimit(
    input: InputStream,
    output: java.io.OutputStream,
    maxBytes: Long,
    overLimitMessage: String,
  ) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
      total += read.toLong()
      if (total > maxBytes) throw IllegalStateException(overLimitMessage)
      output.write(buffer, 0, read)
    }
    if (total <= 0L) throw IllegalStateException("Upload payload is empty")
  }

  private fun extractPreviewImageBytes(
    context: Context,
    uri: Uri,
    previewAtMs: Long?,
  ): ByteArray {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, uri)
      val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
      val requestedMs = previewAtMs?.coerceAtLeast(0L) ?: PREVIEW_FRAME_DEFAULT_MS
      val targetMs = if (durationMs > 0L) requestedMs.coerceAtMost(durationMs) else requestedMs
      val targetUs = targetMs * 1_000L

      val frame =
        retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
          ?: throw IllegalStateException("Unable to extract preview frame from selected video")

      val normalizedFrame = downscaleBitmap(frame, PREVIEW_IMAGE_MAX_DIMENSION_PX)
      if (normalizedFrame !== frame) {
        runCatching { frame.recycle() }
      }

      val bytes = encodeBitmapAsJpeg(normalizedFrame)
      runCatching { normalizedFrame.recycle() }
      return bytes
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun downscaleBitmap(
    source: Bitmap,
    maxDimensionPx: Int,
  ): Bitmap {
    val width = source.width
    val height = source.height
    val largest = maxOf(width, height)
    if (largest <= maxDimensionPx) return source

    val scale = maxDimensionPx.toFloat() / largest.toFloat()
    val scaledWidth = maxOf(1, (width * scale).toInt())
    val scaledHeight = maxOf(1, (height * scale).toInt())
    return Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
  }

  private fun encodeBitmapAsJpeg(bitmap: Bitmap): ByteArray {
    val qualityCandidates = intArrayOf(88, 80, 72, 64)
    for (quality in qualityCandidates) {
      val out = ByteArrayOutputStream()
      val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
      if (!encoded) continue
      val bytes = out.toByteArray()
      if (bytes.isNotEmpty() && bytes.size.toLong() <= MAX_PREVIEW_IMAGE_BYTES) {
        return bytes
      }
    }
    throw IllegalStateException("Preview thumbnail exceeds size limit after JPEG compression")
  }

  private fun deriveFileName(
    uri: Uri,
    fallback: String,
  ): String {
    val raw =
      uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.substringAfterLast(File.separatorChar)
        ?.trim()
        .orEmpty()
    return raw.ifBlank { fallback }
  }

  fun enqueuePostStory(
    userAddress: String,
    chainId: Long,
    txHash: String,
    postId: String? = null,
  ): PostStoryEnqueueResult {
    val payload =
      JSONObject()
        .put("chainId", chainId)
        .put("txHash", txHash)
    val normalizedPostId = postId?.trim().orEmpty()
    if (normalizedPostId.isNotBlank()) {
      payload.put("postId", normalizedPostId)
    }
    val req =
      Request.Builder()
        .url("${SongPublishService.API_CORE_URL}/api/music/post-story/enqueue")
        .post(payload.toString().toRequestBody(jsonType))
        .header("X-User-Address", userAddress)
        .build()

    client.newCall(req).execute().use { response ->
      val raw = response.body?.string().orEmpty()
      val json = runCatching { JSONObject(raw) }.getOrNull()
      if (!response.isSuccessful) {
        val details = json?.optString("error", "").orEmpty().ifBlank { raw.ifBlank { "HTTP ${response.code}" } }
        throw IllegalStateException("Post-story enqueue failed: $details")
      }
      return PostStoryEnqueueResult(
        status = json?.optString("status", "")?.trim().orEmpty().ifBlank { "unknown" },
        postId = json?.optString("postId", "")?.trim()?.ifBlank { null },
        postStoryIpId = json?.optString("postStoryIpId", "")?.trim()?.ifBlank { null },
      )
    }
  }

  fun fetchPostStoryStatus(
    userAddress: String,
    postId: String,
  ): PostStoryStatusResult {
    val req =
      Request.Builder()
        .url("${SongPublishService.API_CORE_URL}/api/music/post-story/status?postId=$postId")
        .get()
        .header("X-User-Address", userAddress)
        .build()

    client.newCall(req).execute().use { response ->
      val raw = response.body?.string().orEmpty()
      val json = runCatching { JSONObject(raw) }.getOrNull()
      if (!response.isSuccessful) {
        val details = json?.optString("error", "").orEmpty().ifBlank { raw.ifBlank { "HTTP ${response.code}" } }
        throw IllegalStateException("Post-story status failed: $details")
      }
      val status = json?.optString("status", "")?.trim().orEmpty().ifBlank { "unknown" }
      val postStoryIpId =
        json?.optJSONObject("feed")
          ?.optString("postStoryIpId", "")
          ?.trim()
          ?.ifBlank { null }
      return PostStoryStatusResult(
        status = status,
        postStoryIpId = postStoryIpId,
      )
    }
  }

  data class PostTransactionReceipt(
    val txHash: String,
    val statusHex: String,
    val logs: JSONArray,
  ) {
    val isSuccess: Boolean
      get() = statusHex.equals("0x1", ignoreCase = true)
  }

  fun awaitPostReceipt(txHash: String): PostTransactionReceipt {
    val deadlineMs = System.currentTimeMillis() + 45_000L
    while (System.currentTimeMillis() < deadlineMs) {
      fetchPostReceiptOrNull(txHash)?.let { return it }
      Thread.sleep(1_500L)
    }
    throw IllegalStateException("Post tx not confirmed before timeout: $txHash")
  }

  fun extractPostIdFromReceipt(
    txHash: String,
    feedAddress: String,
  ): String? {
    val receipt = fetchPostReceiptOrNull(txHash) ?: return null
    val logs = receipt.logs
    val targetAddress = feedAddress.lowercase(Locale.US)
    val targetTopic = POST_CREATED_TOPIC.lowercase(Locale.US)
    for (index in 0 until logs.length()) {
      val row = logs.optJSONObject(index) ?: continue
      val address = row.optString("address", "").trim().lowercase(Locale.US)
      if (address != targetAddress) continue
      val topics = row.optJSONArray("topics") ?: continue
      val topic0 = topics.optString(0, "").trim().lowercase(Locale.US)
      if (topic0 != targetTopic) continue
      val topic1 = topics.optString(1, "").trim()
      if (topic1.isBlank()) continue
      return runCatching { normalizeBytes32(topic1, "postId") }.getOrNull()
    }
    return null
  }

  private fun fetchPostReceiptOrNull(txHash: String): PostTransactionReceipt? {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_getTransactionReceipt")
        .put("params", JSONArray().put(txHash))
    val req =
      Request.Builder()
        .url(PirateChainConfig.STORY_AENEID_RPC_URL)
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    client.newCall(req).execute().use { response ->
      if (!response.isSuccessful) return null
      val body = JSONObject(response.body?.string().orEmpty())
      val receipt = body.optJSONObject("result") ?: return null
      return PostTransactionReceipt(
        txHash = receipt.optString("transactionHash", txHash),
        statusHex = receipt.optString("status", "0x0"),
        logs = receipt.optJSONArray("logs") ?: JSONArray(),
      )
    }
  }
}
