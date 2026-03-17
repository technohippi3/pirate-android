package com.pirate.app.music

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal fun postQuery(url: String, query: String): JSONObject {
  val body = JSONObject().put("query", query).toString().toRequestBody(sharedWithYouJsonMediaType)
  val request = Request.Builder().url(url).post(body).build()
  sharedWithYouClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("Subgraph query failed: ${response.code}")
    val raw = response.body?.string().orEmpty()
    val json = JSONObject(raw)
    val errors = json.optJSONArray("errors")
    if (errors != null && errors.length() > 0) {
      val message = errors.optJSONObject(0)?.optString("message", "GraphQL error") ?: "GraphQL error"
      throw IllegalStateException(message)
    }
    return json
  }
}

internal fun ethCall(to: String, data: String): String {
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
      .url(SHARED_WITH_YOU_TEMPO_RPC)
      .post(payload.toString().toRequestBody(sharedWithYouJsonMediaType))
      .build()
  sharedWithYouClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC eth_call failed: ${response.code}")
    val raw = response.body?.string().orEmpty()
    val json = JSONObject(raw)
    val error = json.optJSONObject("error")
    if (error != null) {
      throw IllegalStateException(error.optString("message", error.toString()))
    }
    return json.optString("result", "0x")
  }
}

internal fun sharedHexToBytes(hex0x: String): ByteArray {
  val hex = hex0x.removePrefix("0x")
  if (hex.length % 2 != 0) throw IllegalArgumentException("Odd hex length")
  val out = ByteArray(hex.length / 2)
  var i = 0
  while (i < hex.length) {
    out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
    i += 2
  }
  return out
}

internal fun decodeBytesUtf8(value: String): String {
  val normalized = value.trim()
  if (!normalized.startsWith("0x")) return normalized
  val hex = normalized.removePrefix("0x")
  if (hex.isEmpty() || hex.length % 2 != 0) return normalized
  if (!hex.all { it.isDigit() || (it.lowercaseChar() in 'a'..'f') }) return normalized
  return try {
    val bytes = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
      bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
      i += 2
    }
    bytes.toString(Charsets.UTF_8).trimEnd { it == '\u0000' }
  } catch (_: Throwable) {
    normalized
  }
}
