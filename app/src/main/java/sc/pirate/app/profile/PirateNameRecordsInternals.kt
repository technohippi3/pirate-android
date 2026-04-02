package sc.pirate.app.profile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONArray
import org.json.JSONObject
import sc.pirate.app.PirateChainConfig

internal data class ParsedPirateName(
  val label: String,
  val parentNode: String,
)

private val pirateNameJsonType = "application/json; charset=utf-8".toMediaType()
private val pirateNameClient = OkHttpClient()

internal fun pirateNameEthCall(to: String, data: String): String {
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
  val request =
    Request.Builder()
      .url(PirateChainConfig.BASE_SEPOLIA_RPC_URL)
      .post(payload.toString().toRequestBody(pirateNameJsonType))
      .build()
  pirateNameClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    return body.optString("result", "0x")
  }
}

internal fun pirateNameNormalizeAddress(raw: String): String? {
  val value = raw.trim().lowercase()
  if (!value.startsWith("0x") || value.length != 42) return null
  if (!value.drop(2).all { it.isDigit() || it in 'a'..'f' }) return null
  return value
}

internal fun pirateNameNormalizeBytes32(raw: String): String? {
  val value = raw.trim().lowercase().removePrefix("0x")
  if (value.length != 64) return null
  if (!value.all { it.isDigit() || it in 'a'..'f' }) return null
  return value
}

internal fun pirateNameFunctionSelector(signature: String): String {
  val hash = pirateNameKeccak256(signature.toByteArray(Charsets.UTF_8))
  return pirateNameBytesToHex(hash.copyOfRange(0, 4))
}

internal fun pirateNameKeccak256(input: ByteArray): ByteArray {
  val digest = Keccak.Digest256()
  digest.update(input, 0, input.size)
  return digest.digest()
}

internal fun pirateNameHexToBytes(hex: String): ByteArray {
  val clean = hex.removePrefix("0x").removePrefix("0X")
  require(clean.length % 2 == 0) { "hex length must be even" }
  return ByteArray(clean.length / 2) { index ->
    clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
  }
}

internal fun pirateNameBytesToHex(bytes: ByteArray): String {
  val sb = StringBuilder(bytes.size * 2)
  for (b in bytes) {
    sb.append(((b.toInt() ushr 4) and 0x0f).toString(16))
    sb.append((b.toInt() and 0x0f).toString(16))
  }
  return sb.toString()
}

internal fun pirateNameIsValidContentPubKey(publicKey: ByteArray): Boolean =
  publicKey.size == 65 && publicKey[0] == 0x04.toByte()

internal fun parsePirateName(
  nameOrLabel: String,
  parentNodeByTld: Map<String, String>,
  defaultParentNode: String,
): ParsedPirateName {
  val normalized = nameOrLabel.trim().lowercase()
  require(normalized.isNotBlank()) { "name is empty" }

  val parts = normalized.split('.')
  if (parts.size >= 2) {
    val label = parts.first().trim()
    val tld = parts[1].trim()
    if (label.isNotBlank()) {
      val parentNode = parentNodeByTld[tld]
      if (!parentNode.isNullOrBlank()) {
        return ParsedPirateName(label = label, parentNode = parentNode)
      }
    }
  }

  val fallbackLabel = parts.firstOrNull()?.trim().orEmpty().ifBlank { normalized }
  return ParsedPirateName(label = fallbackLabel, parentNode = defaultParentNode)
}
