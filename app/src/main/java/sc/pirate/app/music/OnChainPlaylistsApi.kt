package sc.pirate.app.music

import android.util.Log
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.util.storyPlaylistsSubgraphUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object OnChainPlaylistsApi {
  private const val TAG = "OnChainPlaylistsApi"

  private const val PLAYLIST_V1 = PirateChainConfig.STORY_PLAYLIST_V1
  private const val PLAYLIST_V1_START_BLOCK = 16_184_777L
  private const val SUBGRAPH_STALE_BLOCKS = 64L

  private const val TOPIC_PLAYLIST_CREATED =
    "0x7a2154ab35d26c1396dcd0e535ee67a8f6c0c0d4ea64f689726204f3af35db73"
  private const val TOPIC_PLAYLIST_META_UPDATED =
    "0xee96f1638dfcd56f7bb0abb8ec08865b23e96bf36a56432a6bdef7f2c819b58c"
  private const val TOPIC_PLAYLIST_TRACKS_SET =
    "0xa5e1bde741e61093f4b3e82bd40e2baef4f37a03777d646f723bd3026b4869a2"

  private val client = OkHttpClient()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  private data class SubgraphSnapshot<T>(
    val blockNumber: Long,
    val data: T,
  )

  suspend fun fetchUserPlaylists(ownerAddress: String, maxEntries: Int = 50): List<OnChainPlaylist> = withContext(Dispatchers.IO) {
    val addr = ownerAddress.trim().lowercase()
    if (addr.isBlank()) return@withContext emptyList()
    val snapshots = ArrayList<SubgraphSnapshot<List<OnChainPlaylist>>>(3)
    var lastError: Throwable? = null
    for (subgraphUrl in playlistsSubgraphUrls()) {
      try {
        snapshots.add(fetchUserPlaylistsFromSubgraph(subgraphUrl, addr, maxEntries))
      } catch (error: Throwable) {
        Log.w(TAG, "fetchUserPlaylists failed for $subgraphUrl", error)
        lastError = error
      }
    }
    val bestSubgraph = snapshots.maxByOrNull { it.blockNumber }
    val shouldUseRpcFallback =
      bestSubgraph == null ||
        bestSubgraph.data.isEmpty() ||
        isSubgraphStale(bestSubgraph.blockNumber)
    if (shouldUseRpcFallback) {
      try {
        snapshots.add(fetchUserPlaylistsFromRpc(addr, maxEntries))
      } catch (error: Throwable) {
        Log.w(TAG, "fetchUserPlaylists RPC fallback failed", error)
        lastError = error
      }
    }
    if (snapshots.isNotEmpty()) {
      return@withContext snapshots.maxByOrNull { it.blockNumber }?.data ?: emptyList()
    }
    if (lastError != null) throw lastError
    emptyList()
  }

  private fun fetchUserPlaylistsFromSubgraph(
    subgraphUrl: String,
    ownerAddress: String,
    maxEntries: Int,
  ): SubgraphSnapshot<List<OnChainPlaylist>> {
    val query = """
      {
        _meta { block { number } }
        playlists(
          where: { owner: "$ownerAddress", exists: true }
          orderBy: updatedAt
          orderDirection: desc
          first: $maxEntries
        ) {
          id owner name coverCid visibility trackCount version exists
          tracksHash createdAt updatedAt
        }
      }
    """.trimIndent()

    val body = JSONObject().put("query", query).toString().toRequestBody(jsonMediaType)
    val req = Request.Builder().url(subgraphUrl).post(body).build()
    return client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("Playlist query failed: ${res.code}")
      val raw = res.body?.string().orEmpty()
      val json = JSONObject(raw)
      val errors = json.optJSONArray("errors")
      if (errors != null && errors.length() > 0) {
        val msg = errors.optJSONObject(0)?.optString("message", "GraphQL error") ?: "GraphQL error"
        throw IllegalStateException(msg)
      }
      val playlists = json.optJSONObject("data")?.optJSONArray("playlists") ?: JSONArray()
      val out = ArrayList<OnChainPlaylist>(playlists.length())
      for (i in 0 until playlists.length()) {
        val p = playlists.optJSONObject(i) ?: continue
        out.add(
          OnChainPlaylist(
            id = p.optString("id", ""),
            owner = p.optString("owner", ""),
            name = p.optString("name", ""),
            coverCid = p.optString("coverCid", ""),
            visibility = p.optInt("visibility", 0),
            trackCount = p.optInt("trackCount", 0),
            version = p.optInt("version", 0),
            exists = p.optBoolean("exists", false),
            tracksHash = p.optString("tracksHash", ""),
            createdAtSec = p.optLong("createdAt", 0L),
            updatedAtSec = p.optLong("updatedAt", 0L),
          ),
        )
      }
      val blockNumber =
        json.optJSONObject("data")
          ?.optJSONObject("_meta")
          ?.optJSONObject("block")
          ?.optLong("number", 0L)
          ?: 0L
      SubgraphSnapshot(blockNumber = blockNumber, data = out)
    }
  }

  suspend fun fetchPlaylistTrackIds(
    playlistId: String,
    maxEntries: Int = 1000,
  ): List<String> = withContext(Dispatchers.IO) {
    val id = playlistId.trim().lowercase()
    if (id.isBlank()) return@withContext emptyList()
    val snapshots = ArrayList<SubgraphSnapshot<List<String>>>(3)
    var lastError: Throwable? = null
    for (subgraphUrl in playlistsSubgraphUrls()) {
      try {
        snapshots.add(fetchPlaylistTrackIdsFromSubgraph(subgraphUrl, id, maxEntries))
      } catch (error: Throwable) {
        Log.w(TAG, "fetchPlaylistTrackIds failed for $subgraphUrl", error)
        lastError = error
      }
    }
    val bestSubgraph = snapshots.maxByOrNull { it.blockNumber }
    val shouldUseRpcFallback =
      bestSubgraph == null ||
        bestSubgraph.data.isEmpty() ||
        isSubgraphStale(bestSubgraph.blockNumber)
    if (shouldUseRpcFallback) {
      try {
        snapshots.add(fetchPlaylistTrackIdsFromRpc(id, maxEntries))
      } catch (error: Throwable) {
        Log.w(TAG, "fetchPlaylistTrackIds RPC fallback failed", error)
        lastError = error
      }
    }
    if (snapshots.isNotEmpty()) {
      return@withContext snapshots.maxByOrNull { it.blockNumber }?.data ?: emptyList()
    }
    if (lastError != null) throw lastError
    emptyList()
  }

  private fun fetchPlaylistTrackIdsFromSubgraph(
    subgraphUrl: String,
    playlistId: String,
    maxEntries: Int,
  ): SubgraphSnapshot<List<String>> {
    val query = """
      {
        _meta { block { number } }
        playlistTracks(
          where: { playlist: "$playlistId" }
          orderBy: position
          orderDirection: asc
          first: $maxEntries
        ) {
          trackId position
        }
      }
    """.trimIndent()

    val body = JSONObject().put("query", query).toString().toRequestBody(jsonMediaType)
    val req = Request.Builder().url(subgraphUrl).post(body).build()
    return client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("Playlist query failed: ${res.code}")
      val raw = res.body?.string().orEmpty()
      val json = JSONObject(raw)
      val errors = json.optJSONArray("errors")
      if (errors != null && errors.length() > 0) {
        val msg = errors.optJSONObject(0)?.optString("message", "GraphQL error") ?: "GraphQL error"
        throw IllegalStateException(msg)
      }
      val tracks = json.optJSONObject("data")?.optJSONArray("playlistTracks") ?: JSONArray()
      val out = ArrayList<String>(tracks.length())
      for (i in 0 until tracks.length()) {
        val t = tracks.optJSONObject(i) ?: continue
        val trackId = t.optString("trackId", "").trim()
        if (trackId.isNotEmpty()) out.add(trackId)
      }
      val blockNumber =
        json.optJSONObject("data")
          ?.optJSONObject("_meta")
          ?.optJSONObject("block")
          ?.optLong("number", 0L)
          ?: 0L
      SubgraphSnapshot(blockNumber = blockNumber, data = out)
    }
  }

  private fun playlistsSubgraphUrls(): List<String> = storyPlaylistsSubgraphUrls()

  private fun fetchUserPlaylistsFromRpc(
    ownerAddress: String,
    maxEntries: Int,
  ): SubgraphSnapshot<List<OnChainPlaylist>> {
    val chainHead = fetchChainHeadBlock()
    val ownerTopic = topicAddress(ownerAddress)
    val createdLogs =
      fetchLogs(
        fromBlock = PLAYLIST_V1_START_BLOCK,
        topics = listOf(TOPIC_PLAYLIST_CREATED, null, ownerTopic),
      )
    if (createdLogs.length() == 0) {
      return SubgraphSnapshot(blockNumber = chainHead, data = emptyList())
    }

    data class CreatedMeta(
      val playlistId: String,
      val name: String,
      val coverCid: String,
      val createdAtSec: Long,
    )

    val createdById = LinkedHashMap<String, CreatedMeta>(createdLogs.length())
    for (i in 0 until createdLogs.length()) {
      val log = createdLogs.optJSONObject(i) ?: continue
      val topics = log.optJSONArray("topics") ?: continue
      if (topics.length() < 3) continue
      val playlistId = normalizeBytes32Hex(topics.optString(1))
      val decoded = decodePlaylistCreatedData(log.optString("data"))
      if (decoded == null) continue
      createdById[playlistId] =
        CreatedMeta(
          playlistId = playlistId,
          name = decoded.first,
          coverCid = decoded.second,
          createdAtSec = decoded.third,
        )
    }

    val out = ArrayList<OnChainPlaylist>(createdById.size)
    for ((playlistId, meta) in createdById) {
      val state = fetchPlaylistState(playlistId) ?: continue
      if (!state.exists) continue
      if (!state.owner.equals(ownerAddress, ignoreCase = true)) continue
      val latestMeta = fetchLatestPlaylistMetaUpdate(playlistId)
      out.add(
        OnChainPlaylist(
          id = playlistId,
          owner = state.owner,
          name = (latestMeta?.name ?: meta.name).ifBlank { "Playlist" },
          coverCid = latestMeta?.coverCid ?: meta.coverCid,
          visibility = state.visibility,
          trackCount = state.trackCount,
          version = state.version,
          exists = state.exists,
          tracksHash = state.tracksHash,
          createdAtSec = state.createdAtSec.takeIf { it > 0L } ?: meta.createdAtSec,
          updatedAtSec = state.updatedAtSec,
        ),
      )
    }

    val sorted =
      out
        .sortedByDescending { it.updatedAtSec }
        .take(maxEntries)
    return SubgraphSnapshot(blockNumber = chainHead, data = sorted)
  }

  private fun fetchPlaylistTrackIdsFromRpc(
    playlistId: String,
    maxEntries: Int,
  ): SubgraphSnapshot<List<String>> {
    val chainHead = fetchChainHeadBlock()
    val playlistTopic = normalizeBytes32Hex(playlistId)
    val logs =
      fetchLogs(
        fromBlock = PLAYLIST_V1_START_BLOCK,
        topics = listOf(TOPIC_PLAYLIST_TRACKS_SET, playlistTopic),
      )
    if (logs.length() == 0) {
      return SubgraphSnapshot(blockNumber = chainHead, data = emptyList())
    }

    var latest: JSONObject? = null
    var latestBlock = -1L
    var latestLogIndex = -1L
    for (i in 0 until logs.length()) {
      val log = logs.optJSONObject(i) ?: continue
      val block = parseHexLong(log.optString("blockNumber"))
      val logIndex = parseHexLong(log.optString("logIndex"))
      if (block > latestBlock || (block == latestBlock && logIndex > latestLogIndex)) {
        latest = log
        latestBlock = block
        latestLogIndex = logIndex
      }
    }
    val chosen = latest ?: return SubgraphSnapshot(blockNumber = chainHead, data = emptyList())
    val trackIds = decodePlaylistTracksSetTrackIds(chosen.optString("data"))
      .take(maxEntries)
    return SubgraphSnapshot(blockNumber = chainHead, data = trackIds)
  }

  private data class PlaylistState(
    val owner: String,
    val visibility: Int,
    val exists: Boolean,
    val version: Int,
    val trackCount: Int,
    val createdAtSec: Long,
    val updatedAtSec: Long,
    val tracksHash: String,
  )

  private fun fetchPlaylistState(playlistId: String): PlaylistState? {
    val function =
      Function(
        "getPlaylist",
        listOf(Bytes32(bytes32HexToBytes(playlistId))),
        listOf(
          object : TypeReference<Address>() {},
          object : TypeReference<Uint8>() {},
          object : TypeReference<Bool>() {},
          object : TypeReference<Uint32>() {},
          object : TypeReference<Uint32>() {},
          object : TypeReference<Uint64>() {},
          object : TypeReference<Uint64>() {},
          object : TypeReference<Bytes32>() {},
        ),
      )
    val data = FunctionEncoder.encode(function)
    val params =
      JSONArray()
        .put(
          JSONObject()
            .put("to", PLAYLIST_V1)
            .put("data", data),
        )
        .put("latest")
    val resultHex = rpcRequest("eth_call", params)
    val decoded = FunctionReturnDecoder.decode(resultHex, function.outputParameters)
    if (decoded.size < 8) return null
    val owner = (decoded[0] as Address).value.lowercase(Locale.US)
    val visibility = (decoded[1] as Uint8).value.toInt()
    val exists = (decoded[2] as Bool).value
    val version = (decoded[3] as Uint32).value.toInt()
    val trackCount = (decoded[4] as Uint32).value.toInt()
    val createdAtSec = (decoded[5] as Uint64).value.toLong()
    val updatedAtSec = (decoded[6] as Uint64).value.toLong()
    val tracksHash = bytes32ToHex((decoded[7] as Bytes32).value)
    return PlaylistState(
      owner = owner,
      visibility = visibility,
      exists = exists,
      version = version,
      trackCount = trackCount,
      createdAtSec = createdAtSec,
      updatedAtSec = updatedAtSec,
      tracksHash = tracksHash,
    )
  }

  private data class PlaylistMetaUpdate(
    val name: String,
    val coverCid: String,
  )

  private fun fetchLatestPlaylistMetaUpdate(playlistId: String): PlaylistMetaUpdate? {
    val playlistTopic = normalizeBytes32Hex(playlistId)
    val logs =
      fetchLogs(
        fromBlock = PLAYLIST_V1_START_BLOCK,
        topics = listOf(TOPIC_PLAYLIST_META_UPDATED, playlistTopic),
      )
    if (logs.length() == 0) return null

    var latest: JSONObject? = null
    var latestBlock = -1L
    var latestLogIndex = -1L
    for (i in 0 until logs.length()) {
      val log = logs.optJSONObject(i) ?: continue
      val block = parseHexLong(log.optString("blockNumber"))
      val logIndex = parseHexLong(log.optString("logIndex"))
      if (block > latestBlock || (block == latestBlock && logIndex > latestLogIndex)) {
        latest = log
        latestBlock = block
        latestLogIndex = logIndex
      }
    }
    val chosen = latest ?: return null
    return decodePlaylistMetaUpdatedData(chosen.optString("data"))
  }

  private fun decodePlaylistCreatedData(dataHex: String): Triple<String, String, Long>? {
    val outputTypes =
      mutableListOf<TypeReference<*>>(
        object : TypeReference<Uint32>() {},
        object : TypeReference<Uint8>() {},
        object : TypeReference<Uint32>() {},
        object : TypeReference<Bytes32>() {},
        object : TypeReference<Uint64>() {},
        object : TypeReference<Utf8String>() {},
        object : TypeReference<Utf8String>() {},
      )
    @Suppress("UNCHECKED_CAST")
    val decoded =
      FunctionReturnDecoder.decode(
        dataHex,
        outputTypes as MutableList<TypeReference<Type<*>>>,
      )
    if (decoded.size < 7) return null
    val createdAtSec = (decoded[4] as Uint64).value.toLong()
    val name = (decoded[5] as Utf8String).value.orEmpty()
    val coverCid = (decoded[6] as Utf8String).value.orEmpty()
    return Triple(name, coverCid, createdAtSec)
  }

  private fun decodePlaylistMetaUpdatedData(dataHex: String): PlaylistMetaUpdate? {
    val outputTypes =
      mutableListOf<TypeReference<*>>(
        object : TypeReference<Uint32>() {},
        object : TypeReference<Uint8>() {},
        object : TypeReference<Uint64>() {},
        object : TypeReference<Utf8String>() {},
        object : TypeReference<Utf8String>() {},
      )
    @Suppress("UNCHECKED_CAST")
    val decoded =
      FunctionReturnDecoder.decode(
        dataHex,
        outputTypes as MutableList<TypeReference<Type<*>>>,
      )
    if (decoded.size < 5) return null
    val name = (decoded[3] as Utf8String).value.orEmpty()
    val coverCid = (decoded[4] as Utf8String).value.orEmpty()
    return PlaylistMetaUpdate(name = name, coverCid = coverCid)
  }

  private fun decodePlaylistTracksSetTrackIds(dataHex: String): List<String> {
    val outputTypes =
      mutableListOf<TypeReference<*>>(
        object : TypeReference<Uint32>() {},
        object : TypeReference<Uint32>() {},
        object : TypeReference<Bytes32>() {},
        object : TypeReference<Uint64>() {},
        object : TypeReference<DynamicArray<Bytes32>>() {},
      )
    @Suppress("UNCHECKED_CAST")
    val decoded =
      FunctionReturnDecoder.decode(
        dataHex,
        outputTypes as MutableList<TypeReference<Type<*>>>,
      )
    if (decoded.size < 5) return emptyList()
    val arr = decoded[4] as DynamicArray<*>
    return arr.value.mapNotNull { element ->
      val bytes = (element as? Bytes32)?.value ?: return@mapNotNull null
      bytes32ToHex(bytes)
    }
  }

  private fun fetchLogs(
    fromBlock: Long,
    topics: List<String?>,
  ): JSONArray {
    val topicsJson = JSONArray()
    for (topic in topics) {
      if (topic == null) {
        topicsJson.put(JSONObject.NULL)
      } else {
        topicsJson.put(topic)
      }
    }
    val filter =
      JSONObject()
        .put("fromBlock", "0x${fromBlock.toString(16)}")
        .put("toBlock", "latest")
        .put("address", PLAYLIST_V1)
        .put("topics", topicsJson)
    val params = JSONArray().put(filter)
    val result = rpcRequest("eth_getLogs", params)
    return JSONArray(result)
  }

  private fun isSubgraphStale(subgraphBlock: Long): Boolean {
    if (subgraphBlock <= 0L) return true
    val chainHead = fetchChainHeadBlock()
    return chainHead - subgraphBlock >= SUBGRAPH_STALE_BLOCKS
  }

  private fun fetchChainHeadBlock(): Long {
    val result = rpcRequest("eth_blockNumber", JSONArray())
    return parseHexLong(result)
  }

  private fun rpcRequest(
    method: String,
    params: JSONArray,
  ): String {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", method)
        .put("params", params)
    val req =
      Request.Builder()
        .url(PirateChainConfig.STORY_AENEID_RPC_URL)
        .post(payload.toString().toRequestBody(jsonMediaType))
        .build()
    return client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("RPC $method failed: ${res.code}")
      val raw = res.body?.string().orEmpty()
      val json = JSONObject(raw)
      val error = json.optJSONObject("error")
      if (error != null) {
        val msg = error.optString("message", error.toString())
        throw IllegalStateException("RPC $method error: $msg")
      }
      json.optString("result", "")
    }
  }

  private fun normalizeBytes32Hex(value: String): String {
    val clean = value.trim().removePrefix("0x").removePrefix("0X").lowercase(Locale.US)
    if (clean.isEmpty()) return "0x" + "0".repeat(64)
    return "0x${clean.padStart(64, '0').takeLast(64)}"
  }

  private fun topicAddress(address: String): String {
    val clean = address.trim().removePrefix("0x").removePrefix("0X").lowercase(Locale.US)
    return "0x${clean.padStart(64, '0')}"
  }

  private fun bytes32HexToBytes(value: String): ByteArray {
    val clean = normalizeBytes32Hex(value).removePrefix("0x")
    return ByteArray(32) { idx ->
      val hi = clean[idx * 2].digitToInt(16)
      val lo = clean[idx * 2 + 1].digitToInt(16)
      ((hi shl 4) or lo).toByte()
    }
  }

  private fun bytes32ToHex(bytes: ByteArray): String {
    val sb = StringBuilder(66)
    sb.append("0x")
    for (b in bytes) {
      sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
    }
    return sb.toString()
  }

  private fun parseHexLong(value: String): Long {
    val clean = value.trim().removePrefix("0x").removePrefix("0X")
    if (clean.isBlank()) return 0L
    return clean.toLongOrNull(16) ?: 0L
  }
}
