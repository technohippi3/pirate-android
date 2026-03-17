package com.pirate.app.profile

import com.pirate.app.tempo.TempoClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONArray
import org.json.JSONObject

internal data class ParsedTempoName(
  val label: String,
  val parentNode: String,
)

private val tempoNameJsonType = "application/json; charset=utf-8".toMediaType()
private val tempoNameClient = OkHttpClient()

internal fun tempoNameEthCall(to: String, data: String): String {
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
      .url(TempoClient.RPC_URL)
      .post(payload.toString().toRequestBody(tempoNameJsonType))
      .build()
  tempoNameClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    return body.optString("result", "0x")
  }
}

internal fun tempoNameEstimateGas(
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
  val request =
    Request.Builder()
      .url(TempoClient.RPC_URL)
      .post(payload.toString().toRequestBody(tempoNameJsonType))
      .build()
  tempoNameClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("Gas estimate failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    val result = body.optString("result", "0x0")
    val value = result.removePrefix("0x").removePrefix("0X")
    return value.toLongOrNull(16) ?: 0L
  }
}

internal fun tempoNameWithBuffer(
  estimated: Long,
  minimum: Long,
  buffer: Long,
): Long {
  val buffered = tempoNameSaturatingAdd(estimated, buffer)
  return if (buffered < minimum) minimum else buffered
}

private fun tempoNameSaturatingAdd(a: Long, b: Long): Long =
  if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

internal fun tempoNameNormalizeAddress(raw: String): String? {
  val value = raw.trim().lowercase()
  if (!value.startsWith("0x") || value.length != 42) return null
  if (!value.drop(2).all { it.isDigit() || it in 'a'..'f' }) return null
  return value
}

internal fun tempoNameNormalizeBytes32(raw: String): String? {
  val value = raw.trim().lowercase().removePrefix("0x")
  if (value.length != 64) return null
  if (!value.all { it.isDigit() || it in 'a'..'f' }) return null
  return value
}

internal fun tempoNameFunctionSelector(signature: String): String {
  val hash = tempoNameKeccak256(signature.toByteArray(Charsets.UTF_8))
  return tempoNameBytesToHex(hash.copyOfRange(0, 4))
}

internal fun tempoNameKeccak256(input: ByteArray): ByteArray {
  val digest = Keccak.Digest256()
  digest.update(input, 0, input.size)
  return digest.digest()
}

internal fun tempoNameHexToBytes(hex: String): ByteArray {
  val clean = hex.removePrefix("0x").removePrefix("0X")
  require(clean.length % 2 == 0) { "hex length must be even" }
  return ByteArray(clean.length / 2) { index ->
    clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
  }
}

internal fun tempoNameBytesToHex(bytes: ByteArray): String {
  val sb = StringBuilder(bytes.size * 2)
  for (b in bytes) {
    sb.append(((b.toInt() ushr 4) and 0x0f).toString(16))
    sb.append((b.toInt() and 0x0f).toString(16))
  }
  return sb.toString()
}

internal fun tempoNameIsValidContentPubKey(publicKey: ByteArray): Boolean =
  publicKey.size == 65 && publicKey[0] == 0x04.toByte()

internal fun parseTempoName(
  nameOrLabel: String,
  parentNodeByTld: Map<String, String>,
  defaultParentNode: String,
): ParsedTempoName {
  val normalized = nameOrLabel.trim().lowercase()
  require(normalized.isNotBlank()) { "name is empty" }

  val parts = normalized.split('.')
  if (parts.size >= 2) {
    val label = parts.first().trim()
    val tld = parts[1].trim()
    if (label.isNotBlank()) {
      val parentNode = parentNodeByTld[tld]
      if (!parentNode.isNullOrBlank()) {
        return ParsedTempoName(label = label, parentNode = parentNode)
      }
    }
  }

  val fallbackLabel = parts.firstOrNull()?.trim().orEmpty().ifBlank { normalized }
  return ParsedTempoName(label = fallbackLabel, parentNode = defaultParentNode)
}
