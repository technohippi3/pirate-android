package com.pirate.app.player

import android.content.Context
import android.net.Uri
import com.pirate.app.BuildConfig
import com.pirate.app.ViewerContentLocaleResolver
import com.pirate.app.music.CoverRef
import com.pirate.app.music.MusicTrack
import com.pirate.app.tempo.TempoClient
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64

internal data class PlayerLyricsWord(
  val text: String,
  val startMs: Long,
  val endMs: Long,
)

internal data class PlayerLyricsLine(
  val text: String,
  val sourceIndex: Int = -1,
  val startMs: Long? = null,
  val endMs: Long? = null,
  val words: List<PlayerLyricsWord>? = null,
  val translationText: String? = null,
)

internal data class PlayerLyricsDoc(
  val lines: List<PlayerLyricsLine>,
  val timed: Boolean,
)

private data class SyltSegment(
  val text: String,
  val timestampMs: Long?,
)

private data class TrackLyricsRefCacheEntry(
  val ref: String,
  val expiresAtMs: Long,
)

internal object PlayerLyricsRepository {
  private const val TAG = "PlayerLyricsDebug"
  private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
  private const val CANONICAL_TRACK_REF_TTL_MS = 10L * 60L * 1000L
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val client =
    OkHttpClient.Builder()
      .callTimeout(12, TimeUnit.SECONDS)
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .build()

  private val trackRefCache = ConcurrentHashMap<String, TrackLyricsRefCacheEntry>()
  private val refDocCache = ConcurrentHashMap<String, PlayerLyricsDoc?>()
  private val localDocCache = ConcurrentHashMap<String, PlayerLyricsDoc?>()
  private val addressRegex = Regex("(?i)^0x[a-f0-9]{40}$")
  private val bytes32Regex = Regex("(?i)0x[a-f0-9]{64}")
  private val dataItemIdRegex = Regex("^[A-Za-z0-9_-]{32,}$")
  private val cidRegex = Regex("^(Qm[1-9A-HJ-NP-Za-km-z]{44,}|b[a-z2-7]{20,})$")
  private val bracketedSectionHeaderRegex = Regex("^\\[[^\\]]+\\]$")
  private val parenthesizedSectionHeaderRegex = Regex("^\\([^\\)]+\\)$")
  private val bareSectionHeaderRegex =
    Regex("^(?i)(verse|chorus|hook|refrain|bridge|intro|outro|pre-chorus|post-chorus|interlude|instrumental|solo|break|drop|breakdown)(?:\\s*[-:#.]?\\s*(?:\\d+|[ivxlcdm]+|[a-z]))?$")
  private val getLyricsOutputParameters: List<TypeReference<*>> =
    listOf(
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Bytes32>() {},
      object : TypeReference<Uint32>() {},
      object : TypeReference<Address>() {},
      object : TypeReference<Uint64>() {},
    )

  suspend fun loadLyrics(context: Context, track: MusicTrack): PlayerLyricsDoc? = withContext(Dispatchers.IO) {
    val viewerLocale = ViewerContentLocaleResolver.resolve(context)
    val trackId = deriveTrackId(track)
    if (trackId != null) {
      val canonicalRef = resolveCachedTrackLyricsRef(trackId)?.ref
      if (!canonicalRef.isNullOrBlank()) {
        val doc = resolveFromRef(canonicalRef, mutableSetOf(), viewerLocale)
        if (doc != null) return@withContext doc
      }

      for (statusRef in fetchTrackStatusLyricsRefs(trackId)) {
        val doc = resolveFromRef(statusRef, mutableSetOf(), viewerLocale)
        if (doc != null) return@withContext doc
      }
    }

    val directRef = normalizeRef(track.lyricsRef)
    if (!directRef.isNullOrBlank()) {
      val doc = resolveFromRef(directRef, mutableSetOf(), viewerLocale)
      if (doc != null) return@withContext doc
    }

    if (trackId != null) {

      // Fallback: read pirate.lyrics.textRef from the presentation registry metadata.
      val presentationRef = runCatching { PlayerPresentationRepository.resolveLyricsTextRef(track) }.getOrNull()
      if (!presentationRef.isNullOrBlank()) {
        val doc = resolveFromRef(presentationRef, mutableSetOf(), viewerLocale)
        if (doc != null) return@withContext doc
      }
    }

    val uri = track.uri.trim()
    if (uri.startsWith("content://") || uri.startsWith("file://")) {
      if (localDocCache.containsKey(uri)) return@withContext localDocCache[uri]
      val local = extractLocalLyrics(context, uri)
      localDocCache[uri] = local
      return@withContext local
    }

    null
  }

  private fun fetchTrackStatusLyricsRefs(trackId: String): List<String> {
    val apiBase = BuildConfig.API_CORE_URL.trim().trimEnd('/')
    if (apiBase.isBlank()) return emptyList()
    val request = Request.Builder().url("$apiBase/api/music/tracks/$trackId/status").get().build()
    return runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use emptyList()
        val body = response.body?.string()?.trim().orEmpty()
        if (body.isBlank()) return@use emptyList()
        val lyrics = JSONObject(body).optJSONObject("lyrics") ?: return@use emptyList()
        listOfNotNull(
          normalizeRef(lyrics.optString("manifestRef", "")),
          normalizeRef(lyrics.optString("timedRef", "")),
          normalizeRef(lyrics.optString("textRef", "")),
        )
      }
    }.getOrElse { emptyList() }
  }

  private fun deriveTrackId(track: MusicTrack): String? {
    val direct = normalizeBytes32(track.canonicalTrackId)
    if (direct != null) return direct

    val candidates = listOf(track.id, track.contentId.orEmpty())
    for (candidate in candidates) {
      val hit = bytes32Regex.find(candidate)?.value ?: continue
      val normalized = normalizeBytes32(hit)
      if (normalized != null) return normalized
    }
    return null
  }

  private fun normalizeBytes32(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    val hit = bytes32Regex.matchEntire(value) ?: return null
    return hit.value.lowercase()
  }

  private fun normalizeRef(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    return decodeBytesUtf8(value).trim().ifBlank { null }
  }

  private fun resolveCachedTrackLyricsRef(trackId: String): TrackLyricsRefCacheEntry? {
    val nowMs = System.currentTimeMillis()
    val cached = trackRefCache[trackId]
    if (cached != null && cached.expiresAtMs > nowMs) return cached

    val fetched = fetchCanonicalLyricsRefForTrack(trackId) ?: run {
      trackRefCache.remove(trackId)
      return null
    }
    val entry = TrackLyricsRefCacheEntry(
      ref = fetched,
      expiresAtMs = nowMs + CANONICAL_TRACK_REF_TTL_MS,
    )
    trackRefCache[trackId] = entry
    return entry
  }

  private fun fetchCanonicalLyricsRefForTrack(trackId: String): String? {
    val registryAddress = normalizeAddress(BuildConfig.TEMPO_CANONICAL_LYRICS_REGISTRY)
    if (registryAddress == null || registryAddress == ZERO_ADDRESS) return null
    val trackIdBytes = runCatching { hexToBytes(trackId) }.getOrNull() ?: return null
    if (trackIdBytes.size != 32) return null

    val function = Function("getLyrics", listOf(Bytes32(trackIdBytes)), getLyricsOutputParameters)
    val callData = FunctionEncoder.encode(function)
    val result = runCatching { ethCall(registryAddress, callData) }.getOrNull().orEmpty()
    if (!result.startsWith("0x") || result.length <= 2) return null
    val decoded = runCatching { decodeFunctionResult(result, getLyricsOutputParameters) }.getOrNull() ?: return null
    val ref = (decoded.getOrNull(0) as? Utf8String)?.value?.trim().orEmpty()
    return normalizeRef(ref)
  }

  private fun ethCall(to: String, data: String): String {
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
    val request = Request.Builder().url(TempoClient.RPC_URL).post(payload.toString().toRequestBody(jsonMediaType)).build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val json = JSONObject(response.body?.string().orEmpty())
      val error = json.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      json.optString("result", "0x")
    }
  }

  private fun resolveFromRef(ref: String, visited: MutableSet<String>, viewerLocale: String): PlayerLyricsDoc? {
    val normalizedRef = normalizeRef(ref) ?: return null
    val cached = refDocCache[normalizedRef]
    if (cached != null) return cached
    if (!visited.add(normalizedRef)) return null

    val text = fetchRefText(normalizedRef)
    if (text == null) {
      android.util.Log.w(TAG, "fetch failed ref=$normalizedRef")
      return null
    }
    val parsed = parseLyricsPayload(text, visited, viewerLocale)
    if (parsed != null) {
      refDocCache[normalizedRef] = parsed
    } else {
      android.util.Log.w(TAG, "parse failed ref=$normalizedRef")
    }
    return parsed
  }

  private fun parseLyricsPayload(text: String, visited: MutableSet<String>, viewerLocale: String): PlayerLyricsDoc? {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return null

    if (trimmed.startsWith("{")) {
      val json = runCatching { JSONObject(trimmed) }.getOrNull()
      if (json != null) {
        val kind = json.optString("kind", "").trim()
        if (kind == "lyrics.manifest.v1") {
          val primaryRef = normalizeRef(json.optJSONObject("primary")?.optString("ref", ""))
          if (!primaryRef.isNullOrBlank()) {
            return resolveFromRef(primaryRef, visited, viewerLocale)
          }
        }
        if (kind == "lyrics.manifest.v2") {
          val primaryRef = normalizeRef(json.optJSONObject("primary")?.optString("ref", ""))
          if (!primaryRef.isNullOrBlank()) {
            val primaryDoc = resolveFromRef(primaryRef, visited, viewerLocale)
            if (primaryDoc == null) return null
            val translationRef = resolveTranslationRefFromManifest(json, viewerLocale)
            if (translationRef.isNullOrBlank()) return primaryDoc
            val translationText = fetchRefText(translationRef)
            val translationByLine = parseTranslationDocByLineIndex(translationText)
            if (translationByLine.isEmpty()) return primaryDoc
            return primaryDoc.copy(
              lines = primaryDoc.lines.map { line ->
                line.copy(translationText = translationByLine[line.sourceIndex])
              },
            )
          }
        }
        if (kind == "lyrics.timed.v1") {
          val lines = parseTimedLines(json)
          if (lines.isNotEmpty()) {
            return PlayerLyricsDoc(lines = lines, timed = lines.any { it.startMs != null })
          }
          return null
        }
      }
    }

    val lines =
      trimmed
        .replace("\r\n", "\n")
        .split('\n')
        .mapIndexedNotNull { index, value ->
          val text = value.trim()
          if (text.isBlank()) null else PlayerLyricsLine(text = text, sourceIndex = index)
        }
        .let(::filterDisplayableLyricsLines)
    if (lines.isEmpty()) return null
    return PlayerLyricsDoc(lines = lines, timed = false)
  }

  private fun parseTimedLines(json: JSONObject): List<PlayerLyricsLine> {
    val rows = json.optJSONArray("lines") ?: return emptyList()
    val out = ArrayList<PlayerLyricsLine>(rows.length())
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val text = row.optString("text", "").trim()
      if (text.isBlank()) continue
      out.add(
        PlayerLyricsLine(
          text = text,
          sourceIndex = i,
          startMs = row.optLongOrNull("startMs"),
          endMs = row.optLongOrNull("endMs"),
          words = parseTimedWords(row),
        ),
      )
    }
    return filterDisplayableLyricsLines(out)
  }

  private fun parseTimedWords(row: JSONObject): List<PlayerLyricsWord>? {
    val wordsArray = row.optJSONArray("words") ?: return null
    val out = ArrayList<PlayerLyricsWord>(wordsArray.length())
    for (index in 0 until wordsArray.length()) {
      val wordRow = wordsArray.optJSONObject(index) ?: continue
      val text = wordRow.optString("text", "").trim()
      val startMs = wordRow.optLongOrNull("startMs")
      val endMs = wordRow.optLongOrNull("endMs")
      if (text.isBlank() || startMs == null || endMs == null) continue
      if (endMs <= startMs) continue
      out.add(PlayerLyricsWord(text = text, startMs = startMs, endMs = endMs))
    }
    return if (out.isEmpty()) null else out
  }

  private fun resolveTranslationRefFromManifest(manifest: JSONObject, locale: String): String? {
    val translations = manifest.optJSONObject("translations") ?: return null
    for (candidate in localeFallbacks(locale)) {
      val entry = translations.optJSONObject(candidate) ?: continue
      val ref = normalizeRef(entry.optString("ref", ""))
      if (!ref.isNullOrBlank()) return ref
    }
    return null
  }

  private fun parseTranslationDocByLineIndex(raw: String?): Map<Int, String> {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return emptyMap()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
    if (json.optString("kind", "").trim() != "lyrics.translation.v1") return emptyMap()
    val rows = json.optJSONArray("lines") ?: return emptyMap()
    val out = LinkedHashMap<Int, String>()
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val index = row.optInt("index", -1)
      val value = row.optString("text", "").trim()
      if (index < 0 || value.isBlank()) continue
      out[index] = value
    }
    return out
  }

  private fun localeFallbacks(raw: String?): List<String> {
    val normalized = normalizeLocaleTag(raw)
    val out = ArrayList<String>(5)
    val seen = HashSet<String>()
    fun push(value: String?) {
      val candidate = normalizeLocaleTag(value)
      if (seen.add(candidate)) out.add(candidate)
    }
    push(normalized)
    val parts = normalized.split('-')
    if (parts.firstOrNull() == "pt") {
      push("pt-BR")
    }
    if (parts.size >= 2) push(parts[0])
    if (parts.firstOrNull() == "zh") {
      if (normalized == "zh-Hans") {
        push("zh-CN")
        push("zh-SG")
      } else if (normalized == "zh-Hant") {
        push("zh-TW")
        push("zh-HK")
      }
      push("zh")
    }
    return out
  }

  private fun normalizeLocaleTag(raw: String?): String {
    val base = raw.orEmpty().trim().replace('_', '-')
    if (base.isBlank()) return "en"
    val parts = base.split('-').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return "en"
    val language = parts[0].lowercase()
    var script = ""
    var region = ""
    val variants = ArrayList<String>()
    for (i in 1 until parts.size) {
      val token = parts[i]
      if (script.isEmpty() && Regex("^[A-Za-z]{4}$").matches(token)) {
        script = token.replaceFirstChar { c -> c.uppercase() }
        continue
      }
      if (region.isEmpty() && Regex("^([A-Za-z]{2}|\\d{3})$").matches(token)) {
        region = token.uppercase()
        continue
      }
      variants.add(token.lowercase())
    }
    if (language == "zh" && script.isEmpty()) {
      if (region == "TW" || region == "HK" || region == "MO") {
        script = "Hant"
        region = ""
      } else if (region == "CN" || region == "SG" || region == "MY") {
        script = "Hans"
        region = ""
      }
    }
    val out = ArrayList<String>(4)
    out.add(language)
    if (script.isNotEmpty()) out.add(script)
    if (region.isNotEmpty()) out.add(region)
    out.addAll(variants)
    return out.joinToString("-")
  }

  private fun fetchRefText(ref: String): String? {
    val url = resolveRefUrl(ref) ?: return null
    val request = Request.Builder().url(url).get().build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return null
      response.body?.string()?.trim()
    }
  }

  private fun resolveRefUrl(ref: String): String? {
    val raw = ref.trim()
    if (raw.isBlank()) return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw

    if (raw.startsWith("ar://")) {
      val id = raw.removePrefix("ar://").trim()
      if (id.isBlank()) return null
      val gateway = BuildConfig.ARWEAVE_GATEWAY_URL.trim().trimEnd('/')
      return "$gateway/$id"
    }

    if (raw.startsWith("ipfs://")) {
      return CoverRef.resolveCoverUrl(
        ref = raw,
        width = null,
        height = null,
        format = null,
        quality = null,
      )
    }

    if (cidRegex.matches(raw)) {
      return CoverRef.resolveCoverUrl(
        ref = "ipfs://$raw",
        width = null,
        height = null,
        format = null,
        quality = null,
      )
    }

    if (dataItemIdRegex.matches(raw)) {
      return CoverRef.resolveCoverUrl(
        ref = "ar://$raw",
        width = null,
        height = null,
        format = null,
        quality = null,
      )
    }

    return null
  }

  private fun extractLocalLyrics(context: Context, uriString: String): PlayerLyricsDoc? {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null

    runCatching {
      context.contentResolver.openInputStream(uri)?.use { input ->
        parseId3Lyrics(input)
      }
    }.getOrNull()?.let { return it }

    return null
  }

  private fun parseId3Lyrics(input: InputStream): PlayerLyricsDoc? {
    val header = ByteArray(10)
    if (!readFully(input, header, 10)) return null
    if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
      return null
    }

    val version = header[3].toInt() and 0xFF
    if (version !in 3..4) return null

    val tagSize = decodeSyncSafeInt(header, 6)
    if (tagSize <= 0 || tagSize > 4_000_000) return null

    val tagBytes = ByteArray(tagSize)
    if (!readFully(input, tagBytes, tagSize)) return null

    var usltText: String? = null
    var syltLines: List<PlayerLyricsLine>? = null
    var offset = 0

    while (offset + 10 <= tagBytes.size) {
      val frameId = String(tagBytes, offset, 4, StandardCharsets.ISO_8859_1)
      if (frameId.all { it == '\u0000' }) break

      val frameSize =
        if (version == 4) decodeSyncSafeInt(tagBytes, offset + 4)
        else decodeInt32(tagBytes, offset + 4)
      if (frameSize <= 0) break

      val next = offset + 10 + frameSize
      if (next > tagBytes.size) break
      val payload = tagBytes.copyOfRange(offset + 10, next)

      if (frameId == "USLT" && usltText == null) {
        usltText = parseUslt(payload)
      } else if (frameId == "SYLT" && syltLines == null) {
        syltLines = parseSylt(payload)
      }

      offset = next
    }

    if (!syltLines.isNullOrEmpty()) {
      return PlayerLyricsDoc(lines = syltLines, timed = syltLines.any { it.startMs != null })
    }

    val usltLines =
      usltText
        ?.replace("\r\n", "\n")
        ?.split('\n')
        ?.mapIndexedNotNull { index, value ->
          val text = value.trim()
          if (text.isBlank()) null else PlayerLyricsLine(text = text, sourceIndex = index)
        }
        ?.let(::filterDisplayableLyricsLines)
        .orEmpty()
    if (usltLines.isNotEmpty()) return PlayerLyricsDoc(lines = usltLines, timed = false)

    return null
  }

  private fun parseUslt(payload: ByteArray): String? {
    if (payload.size <= 4) return null
    val encoding = payload[0].toInt() and 0xFF
    var offset = 4 // [encoding][lang(3)]
    offset = skipEncodedTerminated(payload, offset, encoding)
    if (offset >= payload.size) return null
    return decodeString(payload.copyOfRange(offset, payload.size), encoding)?.trim()?.ifBlank { null }
  }

  private fun parseSylt(payload: ByteArray): List<PlayerLyricsLine> {
    if (payload.size <= 6) return emptyList()
    val encoding = payload[0].toInt() and 0xFF
    val timestampFormat = payload[4].toInt() and 0xFF
    var offset = 6 // [encoding][lang(3)][timestampFormat][contentType]
    offset = skipEncodedTerminated(payload, offset, encoding)
    if (offset >= payload.size) return emptyList()

    val segments = ArrayList<SyltSegment>(64)
    while (offset < payload.size) {
      val textEnd = findEncodedTerminator(payload, offset, encoding)
      if (textEnd < 0) break
      val text = decodeString(payload.copyOfRange(offset, textEnd), encoding)?.trim().orEmpty()
      val afterText = textEnd + encodedTerminatorLength(encoding)
      if (afterText + 4 > payload.size) break
      val tsRaw = decodeInt32(payload, afterText).toLong() and 0xFFFFFFFFL
      val tsMs = if (timestampFormat == 1) tsRaw else null
      segments.add(SyltSegment(text = text, timestampMs = tsMs))
      offset = afterText + 4
    }

    if (segments.isEmpty()) return emptyList()
    return segmentsToLines(segments)
  }

  private fun segmentsToLines(segments: List<SyltSegment>): List<PlayerLyricsLine> {
    val lines = ArrayList<PlayerLyricsLine>(segments.size)
    val current = StringBuilder()
    var lineStart: Long? = null
    var sourceIndex = 0

    fun flush(endMs: Long?) {
      val text = current.toString().trim()
      if (text.isBlank()) {
        current.setLength(0)
        lineStart = null
        return
      }
      lines.add(PlayerLyricsLine(text = text, sourceIndex = sourceIndex++, startMs = lineStart, endMs = endMs))
      current.setLength(0)
      lineStart = null
    }

    for (i in segments.indices) {
      val segment = segments[i]
      val nextTs = segments.getOrNull(i + 1)?.timestampMs
      val tokenRaw = segment.text.replace('\r', '\n')
      val parts = tokenRaw.split('\n')

      for (p in parts.indices) {
        val part = parts[p].trim()
        if (part.isNotBlank()) {
          if (current.isNotEmpty()) current.append(' ')
          if (lineStart == null) lineStart = segment.timestampMs
          current.append(part)
        }
        if (p < parts.lastIndex) {
          flush(segment.timestampMs)
        }
      }

      val gapLarge = (segment.timestampMs != null && nextTs != null && nextTs - segment.timestampMs > 1_600L)
      if (gapLarge) {
        flush(nextTs)
      }
    }

    flush(null)
    return filterDisplayableLyricsLines(lines)
  }

  private fun filterDisplayableLyricsLines(lines: List<PlayerLyricsLine>): List<PlayerLyricsLine> {
    return lines.filterNot { isSectionHeaderLine(it.text) }
  }

  private fun isSectionHeaderLine(raw: String): Boolean {
    val text = raw.trim()
    if (text.isBlank()) return true
    return bracketedSectionHeaderRegex.matches(text) ||
      parenthesizedSectionHeaderRegex.matches(text) ||
      bareSectionHeaderRegex.matches(text)
  }

  private fun readFully(input: InputStream, buffer: ByteArray, bytes: Int): Boolean {
    var offset = 0
    while (offset < bytes) {
      val read = input.read(buffer, offset, bytes - offset)
      if (read <= 0) return false
      offset += read
    }
    return true
  }

  private fun decodeSyncSafeInt(buffer: ByteArray, offset: Int): Int {
    if (offset + 4 > buffer.size) return 0
    return ((buffer[offset].toInt() and 0x7F) shl 21) or
      ((buffer[offset + 1].toInt() and 0x7F) shl 14) or
      ((buffer[offset + 2].toInt() and 0x7F) shl 7) or
      (buffer[offset + 3].toInt() and 0x7F)
  }

  private fun decodeInt32(buffer: ByteArray, offset: Int): Int {
    if (offset + 4 > buffer.size) return 0
    return ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.BIG_ENDIAN).int
  }

  private fun skipEncodedTerminated(data: ByteArray, start: Int, encoding: Int): Int {
    val end = findEncodedTerminator(data, start, encoding)
    if (end < 0) return data.size
    return end + encodedTerminatorLength(encoding)
  }

  private fun findEncodedTerminator(data: ByteArray, start: Int, encoding: Int): Int {
    val term = encodedTerminatorLength(encoding)
    if (term == 1) {
      for (i in start until data.size) {
        if (data[i].toInt() == 0) return i
      }
      return -1
    }
    var i = start
    while (i + 1 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
      i += 1
    }
    return -1
  }

  private fun encodedTerminatorLength(encoding: Int): Int {
    return when (encoding) {
      1, 2 -> 2
      else -> 1
    }
  }

  private fun decodeString(bytes: ByteArray, encoding: Int): String? {
    if (bytes.isEmpty()) return ""
    val charset: Charset =
      when (encoding) {
        0 -> StandardCharsets.ISO_8859_1
        1 -> StandardCharsets.UTF_16
        2 -> Charset.forName("UTF-16BE")
        3 -> StandardCharsets.UTF_8
        else -> StandardCharsets.UTF_8
      }
    return runCatching { String(bytes, charset) }.getOrNull()
  }

  private fun decodeBytesUtf8(value: String): String {
    val v = value.trim()
    if (!v.startsWith("0x")) return v
    val hex = v.removePrefix("0x")
    if (hex.isEmpty() || hex.length % 2 != 0) return v
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return v
    return runCatching {
      val out = ByteArray(hex.length / 2)
      var i = 0
      while (i < hex.length) {
        out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        i += 2
      }
      String(out, StandardCharsets.UTF_8).trimEnd('\u0000')
    }.getOrDefault(v)
  }

  private fun normalizeAddress(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (!addressRegex.matches(value)) return null
    return value.lowercase()
  }

  internal fun invalidateTrack(trackId: String?) {
    val normalizedTrackId = normalizeBytes32(trackId) ?: return
    trackRefCache.remove(normalizedTrackId)
  }

  internal fun parseStatusLyricsRefsForTesting(body: String): List<String> {
    val lyrics = JSONObject(body).optJSONObject("lyrics") ?: return emptyList()
    return listOfNotNull(
      normalizeRef(lyrics.optString("manifestRef", "")),
      normalizeRef(lyrics.optString("timedRef", "")),
      normalizeRef(lyrics.optString("textRef", "")),
    )
  }

  internal fun localeFallbacksForTesting(raw: String?): List<String> = localeFallbacks(raw)

  internal fun filterDisplayableLyricsLinesForTesting(lines: List<PlayerLyricsLine>): List<PlayerLyricsLine> =
    filterDisplayableLyricsLines(lines)

  internal fun cacheResolvedRefDocForTesting(doc: PlayerLyricsDoc?): Boolean {
    val key = "test-ref"
    refDocCache.clear()
    if (doc != null) {
      refDocCache[key] = doc
    }
    return refDocCache.containsKey(key)
  }

  private fun hexToBytes(hex0x: String): ByteArray {
    val hex = hex0x.removePrefix("0x").removePrefix("0X")
    if (hex.length % 2 != 0) throw IllegalArgumentException("Odd hex length")
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
      out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
      i += 2
    }
    return out
  }

  @Suppress("UNCHECKED_CAST")
  private fun decodeFunctionResult(result: String, outputs: List<TypeReference<*>>): List<Type<*>> {
    return FunctionReturnDecoder.decode(result, outputs as List<TypeReference<Type<*>>>)
  }
}

private fun JSONObject.optLongOrNull(name: String): Long? {
  if (isNull(name)) return null
  return when (val value = opt(name)) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
  }
}
