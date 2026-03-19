package sc.pirate.app.onboarding

import android.util.Log
import sc.pirate.app.BuildConfig
import sc.pirate.app.profile.decodeProfileTuple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * RPC helpers for onboarding reads: name availability, profile/name lookups, node computation.
 * Talks to Tempo Moderato via eth_call.
 */
object OnboardingRpcHelpers {
  private const val TAG = "OnboardingRpc"
  private const val RPC_URL = "https://rpc.moderato.tempo.xyz"
  private const val REGISTRY_V1 = "0x4377af27381CbC8bdb39330DDc656b8f3648B674"
  private const val RECORDS_V1 = "0x3741fDFaEEFe6bA370da44AF8530B6b7361742dD"
  private const val PROFILE_V2 = "0x58eAC016afD2DFdfb935dFcfa0750F30875ea15c"

  /** HNS_NODE = namehash("heaven.hnsbridge.eth") */
  private const val HNS_NAMESPACE_NODE = "0x8edf6f47e89d05c0e21320161fda1fd1fabd0081a66c959691ea17102e39fb27"

  private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
  private val http = OkHttpClient()

  // ── Node computation ──────────────────────────────────────────────

  /** Compute the namehash node for a .heaven label: keccak256(abi.encodePacked(HNS_NAMESPACE_NODE, keccak256(label))) */
  fun computeNode(label: String): String {
    val labelHash = keccak256(label.lowercase().toByteArray(Charsets.UTF_8))
    // abi.encodePacked(bytes32, bytes32) = just concatenate
    val parentBytes = hexToBytes(HNS_NAMESPACE_NODE.removePrefix("0x"))
    val packed = parentBytes + labelHash
    val node = keccak256(packed)
    return "0x" + bytesToHex(node)
  }

  // ── Name availability ─────────────────────────────────────────────

  /** Check if a .heaven name is available (not yet registered). Returns true if available. */
  suspend fun checkNameAvailable(label: String): Boolean = withContext(Dispatchers.IO) {
    val node = computeNode(label)
    // ownerOf(uint256(node)) — if it reverts, name is available
    // We use a simpler approach: call exists(node) or try ownerOf
    // RegistryV1 is ERC-721 — ownerOf reverts if token doesn't exist
    val tokenId = node.removePrefix("0x").padStart(64, '0')
    // ownerOf(uint256) selector = 0x6352211e
    val data = "0x6352211e$tokenId"
    try {
      ethCall(REGISTRY_V1, data)
      // If it returns without error, the name is taken
      false
    } catch (e: Exception) {
      // Revert means token doesn't exist — name is available
      Log.d(TAG, "checkNameAvailable: name available (call reverted)", e)
      true
    }
  }

  /** Get primary name for an address from RegistryV1.primaryName(address) → (string label, bytes32 parentNode) */
  suspend fun getPrimaryName(userAddress: String): String? = withContext(Dispatchers.IO) {
    val addr = userAddress.trim().lowercase().removePrefix("0x").padStart(64, '0')
    // primaryName(address) selector
    val selector = functionSelector("primaryName(address)")
    val data = "0x$selector$addr"
    try {
      val result = ethCall(REGISTRY_V1, data)
      val hex = result.removePrefix("0x")
      if (hex.length < 128) return@withContext null
      // Decode ABI: (string, bytes32) — string is dynamic
      // offset to string data is first 32 bytes
      val stringOffset = BigInteger(hex.substring(0, 64), 16).toInt() * 2
      if (stringOffset + 64 > hex.length) return@withContext null
      val stringLen = BigInteger(hex.substring(stringOffset, stringOffset + 64), 16).toInt()
      if (stringLen == 0) return@withContext null
      val stringHex = hex.substring(stringOffset + 64, stringOffset + 64 + stringLen * 2)
      val label = String(hexToBytes(stringHex), Charsets.UTF_8)
      label.ifBlank { null }
    } catch (e: Exception) {
      Log.d(TAG, "getPrimaryName: failed for $userAddress", e)
      null
    }
  }

  /** Check if a profile exists for the given address. */
  suspend fun hasProfile(userAddress: String): Boolean = withContext(Dispatchers.IO) {
    // getProfile(address) returns a tuple with an `exists` flag (decoded by decodeProfileTuple).
    val addr = userAddress.trim().lowercase().removePrefix("0x").padStart(64, '0')
    val selector = functionSelector("getProfile(address)")
    val data = "0x$selector$addr"
    try {
      val resultHex = ethCall(PROFILE_V2, data).removePrefix("0x")
      decodeProfileTuple(resultHex) != null
    } catch (e: Exception) {
      Log.d(TAG, "hasProfile: failed for $userAddress", e)
      false
    }
  }

  /** Check if an avatar text record exists for a node */
  suspend fun hasAvatar(node: String): Boolean = withContext(Dispatchers.IO) {
    val nodeHex = node.removePrefix("0x").padStart(64, '0')
    // text(bytes32,string) — need to ABI-encode the call
    // For simplicity, encode manually: selector + node + offset + len + "avatar"
    val selector = functionSelector("text(bytes32,string)")
    val keyBytes = "avatar".toByteArray(Charsets.UTF_8)
    val keyHex = bytesToHex(keyBytes)
    // ABI: node (32 bytes) + offset to string (32 bytes) + string length (32 bytes) + string data (padded to 32)
    val offset = "0000000000000000000000000000000000000000000000000000000000000040" // 64
    val len = keyBytes.size.toString(16).padStart(64, '0')
    val paddedKey = keyHex.padEnd(64, '0')
    val data = "0x$selector$nodeHex$offset$len$paddedKey"
    try {
      val result = ethCall(RECORDS_V1, data)
      val hex = result.removePrefix("0x")
      // Returns a string — decode ABI string
      if (hex.length < 128) return@withContext false
      val stringOffset = BigInteger(hex.substring(0, 64), 16).toInt() * 2
      if (stringOffset + 64 > hex.length) return@withContext false
      val stringLen = BigInteger(hex.substring(stringOffset, stringOffset + 64), 16).toInt()
      stringLen > 0
    } catch (e: Exception) {
      Log.d(TAG, "hasAvatar: failed for node", e)
      false
    }
  }

  fun hasFollowContract(): Boolean = followContractOrNull() != null

  /** Get a text record value for a node and key from RecordsV1 */
  suspend fun getTextRecord(node: String, key: String): String? = withContext(Dispatchers.IO) {
    val nodeHex = node.removePrefix("0x").padStart(64, '0')
    val selector = functionSelector("text(bytes32,string)")
    val keyBytes = key.toByteArray(Charsets.UTF_8)
    val keyHex = bytesToHex(keyBytes)
    val offset = "0000000000000000000000000000000000000000000000000000000000000040"
    val len = keyBytes.size.toString(16).padStart(64, '0')
    val paddedKey = keyHex.padEnd(((keyHex.length + 63) / 64) * 64, '0')
    val data = "0x$selector$nodeHex$offset$len$paddedKey"
    try {
      val result = ethCall(RECORDS_V1, data)
      val hex = result.removePrefix("0x")
      if (hex.length < 128) return@withContext null
      val stringOffset = BigInteger(hex.substring(0, 64), 16).toInt() * 2
      if (stringOffset + 64 > hex.length) return@withContext null
      val stringLen = BigInteger(hex.substring(stringOffset, stringOffset + 64), 16).toInt()
      if (stringLen == 0) return@withContext null
      val stringHex = hex.substring(stringOffset + 64, stringOffset + 64 + stringLen * 2)
      String(hexToBytes(stringHex), Charsets.UTF_8).ifBlank { null }
    } catch (e: Exception) {
      Log.d(TAG, "getTextRecord: failed for key=$key", e)
      null
    }
  }

  /** Fetch follower and following counts for an address from FollowV1 */
  suspend fun getFollowCounts(userAddress: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
    val followContract = followContractOrNull() ?: return@withContext 0 to 0
    val addr = userAddress.trim().lowercase().removePrefix("0x").padStart(64, '0')
    val tupleSel = functionSelector("getFollowCounts(address)")
    runCatching {
      val tupleRaw = ethCall(followContract, "0x$tupleSel$addr")
      decodeFollowCountsTuple(tupleRaw)
    }.getOrElse { error ->
      Log.d(TAG, "getFollowCounts: getFollowCounts(address) failed", error)
      null
    }
      ?.let { return@withContext it }
    0 to 0
  }

  /** Check if viewer currently follows target on FollowV1 */
  suspend fun getFollowState(viewerAddress: String, targetAddress: String): Boolean = withContext(Dispatchers.IO) {
    val followContract = followContractOrNull() ?: return@withContext false
    val viewer = viewerAddress.trim().lowercase().removePrefix("0x").padStart(64, '0')
    val target = targetAddress.trim().lowercase().removePrefix("0x").padStart(64, '0')
    val followsSel = functionSelector("follows(address,address)")
    try {
      val r = ethCall(followContract, "0x$followsSel$viewer$target")
      BigInteger(r.removePrefix("0x").ifBlank { "0" }, 16) != BigInteger.ZERO
    } catch (e: Exception) {
      Log.d(TAG, "getFollowState: failed", e)
      false
    }
  }

  // ── Internal helpers ──────────────────────────────────────────────

  private fun ethCall(to: String, data: String): String {
    val payload = JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_call")
      .put("params", JSONArray()
        .put(JSONObject().put("to", to).put("data", data))
        .put("latest")
      )
    val req = Request.Builder()
      .url(RPC_URL)
      .post(payload.toString().toRequestBody(JSON_TYPE))
      .build()
    http.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("RPC failed: ${res.code}")
      val json = JSONObject(res.body?.string().orEmpty())
      val err = json.optJSONObject("error")
      if (err != null) throw IllegalStateException(err.optString("message", err.toString()))
      return json.optString("result", "0x")
    }
  }

  private fun functionSelector(sig: String): String {
    val hash = keccak256(sig.toByteArray(Charsets.UTF_8))
    return bytesToHex(hash.copyOfRange(0, 4))
  }

  private fun decodeFollowCountsTuple(rawHex: String): Pair<Int, Int>? {
    val clean = rawHex.removePrefix("0x")
    if (clean.length < 128) return null
    val followers = BigInteger(clean.substring(0, 64), 16).toInt()
    val following = BigInteger(clean.substring(64, 128), 16).toInt()
    return followers to following
  }

  private fun followContractOrNull(): String? {
    val configured = BuildConfig.TEMPO_FOLLOW_V1.trim()
    if (!configured.startsWith("0x", ignoreCase = true)) return null
    if (configured.length != 42) return null
    return configured
  }

  fun keccak256(input: ByteArray): ByteArray {
    val d = Keccak.Digest256()
    d.update(input, 0, input.size)
    return d.digest()
  }

  fun hexToBytes(hex: String): ByteArray {
    val clean = hex.removePrefix("0x").lowercase()
    require(clean.length % 2 == 0) { "hex length must be even" }
    val out = ByteArray(clean.length / 2)
    var i = 0
    while (i < clean.length) {
      out[i / 2] = ((clean[i].digitToInt(16) shl 4) or clean[i + 1].digitToInt(16)).toByte()
      i += 2
    }
    return out
  }

  fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
      sb.append(((b.toInt() ushr 4) and 0x0f).toString(16))
      sb.append((b.toInt() and 0x0f).toString(16))
    }
    return sb.toString()
  }
}
