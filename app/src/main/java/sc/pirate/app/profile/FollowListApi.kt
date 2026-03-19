package sc.pirate.app.profile

import sc.pirate.app.BuildConfig
import sc.pirate.app.music.CoverRef
import sc.pirate.app.resolvePublicProfileIdentityWithRetry
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.util.shortAddress
import sc.pirate.app.util.tempoProfilesSubgraphUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class FollowListMember(
  val address: String,
  val name: String,
  val avatarUrl: String?,
  val followedAtSec: Long,
)

object FollowListApi {
  private enum class ChainMode { Followers, Following }

  private data class ChainFollowLog(
    val address: String,
    val blockNumber: Long,
    val logIndex: Long,
    val isFollowed: Boolean,
  )

  private data class ActiveFollow(
    val address: String,
    val blockNumber: Long,
    val logIndex: Long,
  )

  private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
  private val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")
  private const val DEFAULT_FOLLOW_V1 = "0x153DbEcA0CEF8563649cf475a687D14997D2c403"
  private const val DEFAULT_FOLLOW_V1_START_BLOCK = 5_556_047L
  private const val CHAIN_LOG_MAX_BLOCK_RANGE = 100_000L
  private const val FOLLOWED_TOPIC =
    "0x6178e95c138f06036cdc07a49ed6a3d23008969fa143baeceb037ebae22e8d14"
  private const val UNFOLLOWED_TOPIC =
    "0x25b48012798806863072289354ed0b3849b73152482d9c6694e5cbb35f38d5e3"
  private val http = OkHttpClient()

  /** Fetch followers of an address (people who follow this address). */
  suspend fun fetchFollowers(
    address: String,
    first: Int = 50,
    skip: Int = 0,
  ): List<FollowListMember> = withContext(Dispatchers.IO) {
    val addr = address.trim().lowercase()
    val follows = queryFollowPairsFromChain(
      address = addr,
      mode = ChainMode.Followers,
      first = first,
      skip = skip,
    )
    val addresses = follows.map { it.first }
    resolveMembers(addresses, follows)
  }

  /** Fetch users that this address follows. */
  suspend fun fetchFollowing(
    address: String,
    first: Int = 50,
    skip: Int = 0,
  ): List<FollowListMember> = withContext(Dispatchers.IO) {
    val addr = address.trim().lowercase()
    val follows = queryFollowPairsFromChain(
      address = addr,
      mode = ChainMode.Following,
      first = first,
      skip = skip,
    )
    val addresses = follows.map { it.first }
    resolveMembers(addresses, follows)
  }

  /** Reconstruct active follower/following graph from on-chain Followed/Unfollowed logs. */
  private fun queryFollowPairsFromChain(
    address: String,
    mode: ChainMode,
    first: Int,
    skip: Int,
  ): List<Pair<String, Long>> {
    val followContract = followContractOrNull() ?: return emptyList()
    val normalizedAddress = normalizeAddress(address) ?: return emptyList()
    val normalizedFirst = first.coerceAtLeast(0)
    if (normalizedFirst == 0) return emptyList()
    val normalizedSkip = skip.coerceAtLeast(0)
    val requiredActive = normalizedSkip + normalizedFirst
    val startBlock = followStartBlock(followContract)
    val latestBlock = fetchLatestBlockNumber()
    if (latestBlock < startBlock) return emptyList()
    val topicAddress = topicForAddress(normalizedAddress)
    val resolvedAddresses = HashSet<String>()
    val activeByAddress = LinkedHashMap<String, ActiveFollow>()
    var toBlock = latestBlock
    while (toBlock >= startBlock) {
      val fromBlock = maxOf(startBlock, toBlock - CHAIN_LOG_MAX_BLOCK_RANGE + 1)
      val followedLogs = fetchChainLogsChunk(
        followContract = followContract,
        topic0 = FOLLOWED_TOPIC,
        mode = mode,
        topicAddress = topicAddress,
        isFollowed = true,
        fromBlock = fromBlock,
        toBlock = toBlock,
      )
      val unfollowedLogs = fetchChainLogsChunk(
        followContract = followContract,
        topic0 = UNFOLLOWED_TOPIC,
        mode = mode,
        topicAddress = topicAddress,
        isFollowed = false,
        fromBlock = fromBlock,
        toBlock = toBlock,
      )
      val ordered =
        (followedLogs + unfollowedLogs)
          .sortedWith(compareByDescending<ChainFollowLog> { it.blockNumber }.thenByDescending { it.logIndex })
      for (log in ordered) {
        if (!resolvedAddresses.add(log.address)) continue
        if (log.isFollowed) {
          activeByAddress[log.address] =
            ActiveFollow(
              address = log.address,
              blockNumber = log.blockNumber,
              logIndex = log.logIndex,
            )
        }
      }
      if (activeByAddress.size >= requiredActive) break
      if (fromBlock <= startBlock) break
      toBlock = fromBlock - 1
    }

    val active = activeByAddress.values.toList()
    if (active.isEmpty()) return emptyList()

    val paged = active.drop(normalizedSkip).let { candidates ->
      candidates.take(normalizedFirst)
    }
    if (paged.isEmpty()) return emptyList()

    val timestampsByBlock = fetchBlockTimestamps(paged.map { it.blockNumber }.distinct())
    return paged.map { follow ->
      follow.address to (timestampsByBlock[follow.blockNumber] ?: 0L)
    }
  }

  private fun fetchChainLogsChunk(
    followContract: String,
    topic0: String,
    mode: ChainMode,
    topicAddress: String,
    isFollowed: Boolean,
    fromBlock: Long,
    toBlock: Long,
  ): List<ChainFollowLog> {
    val topics = JSONArray().put(topic0)
    when (mode) {
      ChainMode.Followers -> {
        topics.put(JSONObject.NULL)
        topics.put(topicAddress)
      }
      ChainMode.Following -> {
        topics.put(topicAddress)
        topics.put(JSONObject.NULL)
      }
    }
    val filter = JSONObject()
      .put("address", followContract)
      .put("fromBlock", "0x${fromBlock.toString(16)}")
      .put("toBlock", "0x${toBlock.toString(16)}")
      .put("topics", topics)

    val payload = runCatching {
      postRpc(
        method = "eth_getLogs",
        params = JSONArray().put(filter),
      )
    }.getOrNull() ?: return emptyList()

    val rows = payload.optJSONArray("result") ?: return emptyList()
    val output = mutableListOf<ChainFollowLog>()
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val rowTopics = row.optJSONArray("topics") ?: continue
      val topicIndex = if (mode == ChainMode.Followers) 1 else 2
      val otherAddress = parseAddressTopic(rowTopics.optString(topicIndex, "")) ?: continue
      val blockNumber = parseHexLong(row.optString("blockNumber", "0x0"))
      val logIndex = parseHexLong(row.optString("logIndex", "0x0"))
      output += ChainFollowLog(
        address = otherAddress,
        blockNumber = blockNumber,
        logIndex = logIndex,
        isFollowed = isFollowed,
      )
    }
    return output
  }

  private fun fetchBlockTimestamps(blockNumbers: List<Long>): Map<Long, Long> {
    if (blockNumbers.isEmpty()) return emptyMap()
    val out = mutableMapOf<Long, Long>()
    for (blockNumber in blockNumbers) {
      val payload = runCatching {
        postRpc(
          method = "eth_getBlockByNumber",
          params = JSONArray().put("0x${blockNumber.toString(16)}").put(false),
        )
      }.getOrNull() ?: continue
      val block = payload.optJSONObject("result") ?: continue
      val timestamp = parseHexLong(block.optString("timestamp", "0x0"))
      out[blockNumber] = timestamp
    }
    return out
  }

  /** Resolve addresses into FollowListMembers with names and avatars. */
  private suspend fun resolveMembers(
    addresses: List<String>,
    follows: List<Pair<String, Long>>,
  ): List<FollowListMember> = coroutineScope {
    if (addresses.isEmpty()) return@coroutineScope emptyList()

    // Batch-fetch profile data (displayName, photoURI) from profiles subgraph
    val profileMap = runCatching { fetchProfilesBatch(addresses) }.getOrDefault(emptyMap())

    // Resolve public identity labels in parallel (capped).
    val identityJobs = addresses.take(50).map { addr ->
      async(Dispatchers.IO) {
        val identity = runCatching {
          resolvePublicProfileIdentityWithRetry(addr, attempts = 1)
        }.getOrNull()
        addr to identity
      }
    }
    val identityMap = identityJobs.awaitAll().toMap()

    follows.map { (addr, ts) ->
      val profile = profileMap[addr]
      val resolvedIdentity = identityMap[addr]
      val resolvedName = resolvedIdentity?.first?.trim()?.takeIf { it.isNotBlank() }
      val displayName = profile?.first?.trim()?.takeIf { it.isNotBlank() }
      val name = resolvedName
        ?: displayName
        ?: shortAddress(addr, minLengthToShorten = 14)
      val photoUri = resolvedIdentity?.second?.trim()?.takeIf { it.isNotBlank() }
        ?: profile?.second?.trim()?.takeIf { it.isNotBlank() }
      val avatarUrl = photoUri?.let {
        CoverRef.resolveCoverUrl(ref = it, width = null, height = null, format = null, quality = null)
      }
      FollowListMember(
        address = addr,
        name = name,
        avatarUrl = avatarUrl,
        followedAtSec = ts,
      )
    }
  }

  /** Batch-fetch displayName + photoURI from profiles subgraph. Returns map of address → (displayName, photoURI). */
  private fun fetchProfilesBatch(addresses: List<String>): Map<String, Pair<String, String>> {
    if (addresses.isEmpty()) return emptyMap()
    val ids = addresses.joinToString(", ") { "\"$it\"" }
    val query = """
      {
        profiles(where: { id_in: [$ids] }) {
          id
          displayName
          photoURI
        }
      }
    """.trimIndent()

    val urls = profilesSubgraphUrls()
    for (url in urls) {
      val result = runCatching { postGraphQL(url, query) }.getOrNull() ?: continue
      val map = parseProfilesMap(result)
      return map
    }
    return emptyMap()
  }

  private fun postGraphQL(url: String, query: String): JSONObject {
    val body = JSONObject().put("query", query).toString()
    val req = Request.Builder()
      .url(url)
      .post(body.toRequestBody(JSON_TYPE))
      .build()
    http.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("GraphQL failed: ${res.code}")
      return JSONObject(res.body?.string().orEmpty())
    }
  }

  private fun postRpc(method: String, params: JSONArray): JSONObject {
    val body = JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", method)
      .put("params", params)
      .toString()
    val req = Request.Builder()
      .url(TempoClient.RPC_URL)
      .post(body.toRequestBody(JSON_TYPE))
      .build()
    http.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("RPC failed: ${res.code}")
      val response = JSONObject(res.body?.string().orEmpty())
      val error = response.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      return response
    }
  }

  private fun fetchLatestBlockNumber(): Long {
    val payload = runCatching {
      postRpc(method = "eth_blockNumber", params = JSONArray())
    }.getOrNull() ?: return 0L
    return parseHexLong(payload.optString("result", "0x0"))
  }

  private fun followContractOrNull(): String? {
    val configured = BuildConfig.TEMPO_FOLLOW_V1.trim()
    if (!ADDRESS_REGEX.matches(configured)) return null
    return "0x${configured.removePrefix("0x").removePrefix("0X")}"
  }

  private fun followStartBlock(followContract: String): Long {
    return if (followContract.equals(DEFAULT_FOLLOW_V1, ignoreCase = true)) {
      DEFAULT_FOLLOW_V1_START_BLOCK
    } else {
      0L
    }
  }

  private fun normalizeAddress(address: String): String? {
    val trimmed = address.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    if (!ADDRESS_REGEX.matches(prefixed)) return null
    return "0x${prefixed.removePrefix("0x").removePrefix("0X").lowercase()}"
  }

  private fun topicForAddress(address: String): String {
    val clean = address.removePrefix("0x").removePrefix("0X")
    return "0x${clean.padStart(64, '0')}"
  }

  private fun parseAddressTopic(topic: String): String? {
    val clean = topic.removePrefix("0x").removePrefix("0X")
    if (clean.length != 64) return null
    val address = clean.takeLast(40)
    if (!address.all { it in "0123456789abcdefABCDEF" }) return null
    return "0x${address.lowercase()}"
  }

  private fun parseHexLong(value: String): Long {
    val clean = value.trim().removePrefix("0x").removePrefix("0X")
    if (clean.isBlank()) return 0L
    return clean.toLongOrNull(16) ?: 0L
  }

  private fun profilesSubgraphUrls(): List<String> = tempoProfilesSubgraphUrls()

}

internal fun parseProfilesMap(graphqlResponse: JSONObject): Map<String, Pair<String, String>> {
  val data = graphqlResponse.optJSONObject("data") ?: return emptyMap()
  val profiles = data.optJSONArray("profiles") ?: return emptyMap()
  val map = mutableMapOf<String, Pair<String, String>>()
  for (i in 0 until profiles.length()) {
    val row = profiles.optJSONObject(i) ?: continue
    val address = row.optString("id", "").lowercase()
    val displayName = row.optString("displayName", "")
    val photoUri = row.optString("photoURI", "")
    if (address.isNotBlank()) map[address] = displayName to photoUri
  }
  return map
}
