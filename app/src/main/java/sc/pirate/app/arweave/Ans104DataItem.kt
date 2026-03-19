package sc.pirate.app.arweave

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONObject
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign

object Ans104DataItem {
  private const val SIGNATURE_TYPE_ETHEREUM = 3
  private const val MAX_TAGS = 128
  private const val MAX_TAG_NAME_BYTES = 1024
  private const val MAX_TAG_VALUE_BYTES = 3072
  private const val HTTP_TIMEOUT_MS = 60_000

  data class Tag(
    val name: String,
    val value: String,
  )

  data class BuildResult(
    val id: String,
    val bytes: ByteArray,
  )

  private sealed class DeepHashNode {
    data class Blob(val bytes: ByteArray) : DeepHashNode()

    data class ListNode(val items: List<DeepHashNode>) : DeepHashNode()
  }

  fun buildAndSign(
    payload: ByteArray,
    tags: List<Tag>,
    signingKeyPair: ECKeyPair,
  ): BuildResult {
    require(payload.isNotEmpty()) { "ANS-104 payload is empty." }
    validateTags(tags)

    val owner = buildOwnerPublicKey(signingKeyPair)
    val tagBytes = encodeTagsAvro(tags)
    val signatureType = SIGNATURE_TYPE_ETHEREUM.toString().toByteArray(Charsets.UTF_8)

    val signingMessage = deepHash(
      DeepHashNode.ListNode(
        listOf(
          DeepHashNode.Blob("dataitem".toByteArray(Charsets.UTF_8)),
          DeepHashNode.Blob("1".toByteArray(Charsets.UTF_8)),
          DeepHashNode.Blob(signatureType),
          DeepHashNode.Blob(owner),
          DeepHashNode.Blob(ByteArray(0)),
          DeepHashNode.Blob(ByteArray(0)),
          DeepHashNode.Blob(tagBytes),
          DeepHashNode.Blob(payload),
        ),
      ),
    )

    val signature = signEthereumPersonal(signingMessage, signingKeyPair)
    val id = base64UrlNoPad(sha256(signature))

    val out = ByteArrayOutputStream(
      2 + signature.size + owner.size + 1 + 1 + 8 + 8 + tagBytes.size + payload.size,
    )
    putU16Le(out, SIGNATURE_TYPE_ETHEREUM)
    out.write(signature)
    out.write(owner)
    out.write(0) // target absent
    out.write(0) // anchor absent
    putU64Le(out, tags.size.toLong())
    putU64Le(out, tagBytes.size.toLong())
    out.write(tagBytes)
    out.write(payload)

    return BuildResult(
      id = id,
      bytes = out.toByteArray(),
    )
  }

  fun uploadSignedDataItem(
    signedDataItem: ByteArray,
    uploadUrl: String = defaultUploadEndpoint(),
  ): String {
    require(signedDataItem.isNotEmpty()) { "Signed ANS-104 item is empty." }
    val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = HTTP_TIMEOUT_MS
      readTimeout = HTTP_TIMEOUT_MS
      setRequestProperty("Content-Type", "application/octet-stream")
    }

    conn.outputStream.use { out -> out.write(signedDataItem) }

    val status = conn.responseCode
    val body = if (status in 200..299) {
      conn.inputStream.bufferedReader().use { it.readText() }
    } else {
      conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    if (status !in 200..299) {
      throw IllegalStateException(if (body.isBlank()) "ANS-104 upload failed: HTTP $status" else "ANS-104 upload failed: $body")
    }

    return parseUploadId(body) ?: throw IllegalStateException("ANS-104 upload succeeded but no dataitem id was returned.")
  }

  private fun defaultUploadEndpoint(): String {
    return "${ArweaveTurboConfig.DEFAULT_UPLOAD_URL.trimEnd('/')}/v1/tx/${ArweaveTurboConfig.DEFAULT_TOKEN.trim().lowercase()}"
  }

  private fun validateTags(tags: List<Tag>) {
    require(tags.size <= MAX_TAGS) { "Too many ANS-104 tags (${tags.size} > $MAX_TAGS)." }
    for (tag in tags) {
      val name = tag.name.trim()
      val value = tag.value.trim()
      require(name.isNotEmpty()) { "ANS-104 tag name is empty." }
      require(value.isNotEmpty()) { "ANS-104 tag value is empty." }
      require(name.toByteArray(Charsets.UTF_8).size <= MAX_TAG_NAME_BYTES) {
        "ANS-104 tag name exceeds $MAX_TAG_NAME_BYTES bytes."
      }
      require(value.toByteArray(Charsets.UTF_8).size <= MAX_TAG_VALUE_BYTES) {
        "ANS-104 tag value exceeds $MAX_TAG_VALUE_BYTES bytes."
      }
    }
  }

  private fun buildOwnerPublicKey(signingKeyPair: ECKeyPair): ByteArray {
    val pubNoPrefix = leftPadTo(signingKeyPair.publicKey.toByteArray().trimLeadingZero(), 64)
    return byteArrayOf(0x04) + pubNoPrefix
  }

  private fun encodeTagsAvro(tags: List<Tag>): ByteArray {
    if (tags.isEmpty()) return ByteArray(0)
    val out = ByteArrayOutputStream()
    writeAvroLong(out, tags.size.toLong())
    for (tag in tags) {
      val nameBytes = tag.name.trim().toByteArray(Charsets.UTF_8)
      val valueBytes = tag.value.trim().toByteArray(Charsets.UTF_8)
      writeAvroLong(out, nameBytes.size.toLong())
      out.write(nameBytes)
      writeAvroLong(out, valueBytes.size.toLong())
      out.write(valueBytes)
    }
    writeAvroLong(out, 0L)
    return out.toByteArray()
  }

  private fun writeAvroLong(out: ByteArrayOutputStream, value: Long) {
    require(value >= 0) { "Avro long must be non-negative for ANS-104 tags." }
    var v = (value shl 1)
    while ((v and 0x7FL.inv()) != 0L) {
      out.write(((v and 0x7F) or 0x80).toInt())
      v = v ushr 7
    }
    out.write((v and 0x7F).toInt())
  }

  private fun deepHash(node: DeepHashNode): ByteArray {
    return when (node) {
      is DeepHashNode.Blob -> {
        val hashTag = sha384("blob${node.bytes.size}".toByteArray(Charsets.UTF_8))
        val hashData = sha384(node.bytes)
        sha384(hashTag + hashData)
      }
      is DeepHashNode.ListNode -> {
        var acc = sha384("list${node.items.size}".toByteArray(Charsets.UTF_8))
        for (child in node.items) {
          acc = sha384(acc + deepHash(child))
        }
        acc
      }
    }
  }

  private fun signEthereumPersonal(message: ByteArray, signingKeyPair: ECKeyPair): ByteArray {
    val prefix = "\u0019Ethereum Signed Message:\n${message.size}".toByteArray(Charsets.UTF_8)
    val digest = keccak256(prefix + message)
    val sig = Sign.signMessage(digest, signingKeyPair, false)

    val sigBytes = ByteArray(65)
    System.arraycopy(sig.r, 0, sigBytes, 0, 32)
    System.arraycopy(sig.s, 0, sigBytes, 32, 32)
    var v = sig.v[0].toInt() and 0xFF
    if (v < 27) v += 27
    sigBytes[64] = v.toByte()
    return sigBytes
  }

  private fun parseUploadId(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
      return extractUploadId(json)
    }

    if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      val unwrapped = runCatching { JSONObject("{\"id\":$trimmed}") }.getOrNull()
      val id = unwrapped?.optString("id", "").orEmpty().trim()
      if (id.isNotEmpty()) return id
    }

    return if (trimmed.any { it.isWhitespace() }) null else trimmed
  }

  private fun extractUploadId(json: JSONObject): String? {
    val directKeys = arrayOf("id", "dataitem_id", "dataitemId")
    for (key in directKeys) {
      val value = json.optString(key, "").trim()
      if (value.isNotEmpty()) return value
    }

    val result = json.optJSONObject("result") ?: return null
    for (key in directKeys) {
      val value = result.optString(key, "").trim()
      if (value.isNotEmpty()) return value
    }
    return null
  }

  private fun putU16Le(out: ByteArrayOutputStream, value: Int) {
    val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    out.write(b)
  }

  private fun putU64Le(out: ByteArrayOutputStream, value: Long) {
    val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    out.write(b)
  }

  private fun leftPadTo(input: ByteArray, size: Int): ByteArray {
    if (input.size == size) return input
    if (input.size > size) return input.copyOfRange(input.size - size, input.size)
    return ByteArray(size - input.size) + input
  }

  private fun ByteArray.trimLeadingZero(): ByteArray {
    var start = 0
    while (start < this.lastIndex && this[start] == 0.toByte()) {
      start += 1
    }
    return copyOfRange(start, size)
  }

  private fun keccak256(input: ByteArray): ByteArray {
    val digest = Keccak.Digest256()
    return digest.digest(input)
  }

  private fun sha384(input: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-384").digest(input)
  }

  private fun sha256(input: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(input)
  }

  private fun base64UrlNoPad(input: ByteArray): String {
    return Base64.encodeToString(input, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
  }
}
