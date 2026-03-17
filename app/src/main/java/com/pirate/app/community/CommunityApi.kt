package com.pirate.app.community

import com.pirate.app.music.CoverRef
import com.pirate.app.onboarding.steps.LANGUAGE_OPTIONS
import com.pirate.app.util.tempoProfilesSubgraphUrls
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class CommunityLanguage(
  val code: String,
  val proficiency: Int,
)

data class CommunityFilters(
  val gender: Int? = null,
  val minAge: Int? = null,
  val maxAge: Int? = null,
  val nativeLanguage: String? = null,
  val learningLanguage: String? = null,
  val radiusKm: Int? = null,
)

data class CommunityMemberPreview(
  val address: String,
  val displayName: String,
  val photoUrl: String?,
  val age: Int?,
  val gender: Int,
  val languages: List<CommunityLanguage>,
  val locationCityId: String?,
  val locationLatE6: Int?,
  val locationLngE6: Int?,
  val distanceKm: Double?,
)

data class ViewerCommunityDefaults(
  val nativeSpeakerTargetLanguage: String?,
  val locationCityId: String?,
  val locationLatE6: Int?,
  val locationLngE6: Int?,
)

private data class BoundingBoxE6(
  val minLatE6: Int,
  val maxLatE6: Int,
  val minLngE6: Int,
  val maxLngE6: Int,
)

object CommunityApi {
  private const val ZERO_HASH =
    "0x0000000000000000000000000000000000000000000000000000000000000000"
  private const val EARTH_RADIUS_KM = 6371.0088
  private const val MIN_LAT_E6 = -90_000_000
  private const val MAX_LAT_E6 = 90_000_000
  private const val MIN_LNG_E6 = -180_000_000
  private const val MAX_LNG_E6 = 180_000_000

  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val client = OkHttpClient()
  private val languageLabels = LANGUAGE_OPTIONS.associate { it.code.lowercase() to it.label }

  suspend fun fetchViewerDefaults(viewerAddress: String): ViewerCommunityDefaults = withContext(Dispatchers.IO) {
    val addr = viewerAddress.trim().lowercase()
    if (!addr.startsWith("0x") || addr.length != 42) {
      return@withContext ViewerCommunityDefaults(
        nativeSpeakerTargetLanguage = "en",
        locationCityId = null,
        locationLatE6 = null,
        locationLngE6 = null,
      )
    }

    val query = """
      {
        profile(id: "$addr") {
          languagesPacked
          locationCityId
          locationLatE6
          locationLngE6
        }
      }
    """.trimIndent()

    val json = postQuery(query)
    val profile = json.optJSONObject("data")?.optJSONObject("profile")
    if (profile == null) {
      return@withContext ViewerCommunityDefaults(
        nativeSpeakerTargetLanguage = "en",
        locationCityId = null,
        locationLatE6 = null,
        locationLngE6 = null,
      )
    }

    val location = normalizeLocation(profile.optString("locationCityId", ""))
    val latRaw = profile.optInt("locationLatE6", 0)
    val lngRaw = profile.optInt("locationLngE6", 0)
    val hasCoords = hasUsableCoords(latRaw, lngRaw)
    val languages = unpackLanguages(profile.optString("languagesPacked", "0"))
    val firstLearning = languages.firstOrNull { it.proficiency in 1..6 }?.code

    ViewerCommunityDefaults(
      nativeSpeakerTargetLanguage = firstLearning ?: "en",
      locationCityId = location,
      locationLatE6 = if (hasCoords) latRaw else null,
      locationLngE6 = if (hasCoords) lngRaw else null,
    )
  }

  suspend fun fetchCommunityMembers(
    viewerAddress: String?,
    filters: CommunityFilters,
    viewerLatE6: Int?,
    viewerLngE6: Int?,
    maxEntries: Int = 250,
  ): List<CommunityMemberPreview> = withContext(Dispatchers.IO) {
    val conditions = mutableListOf<String>()
    val gender = filters.gender
    if (gender != null && gender > 0) {
      conditions.add("gender: $gender")
    }

    val minAge = filters.minAge?.coerceAtLeast(18)
    if (minAge != null) {
      conditions.add("age_gte: $minAge")
    }

    val maxAge = filters.maxAge?.coerceAtLeast(18)
    if (maxAge != null) {
      conditions.add("age_lte: $maxAge")
    }

    val radiusKm = filters.radiusKm?.takeIf { it > 0 }
    val bbox = if (radiusKm != null && viewerLatE6 != null && viewerLngE6 != null) {
      buildBoundingBoxE6(viewerLatE6, viewerLngE6, radiusKm)
    } else {
      null
    }
    if (bbox != null) {
      conditions.add("locationLatE6_gte: ${bbox.minLatE6}")
      conditions.add("locationLatE6_lte: ${bbox.maxLatE6}")
      conditions.add("locationLngE6_gte: ${bbox.minLngE6}")
      conditions.add("locationLngE6_lte: ${bbox.maxLngE6}")
    }

    val whereClause =
      if (conditions.isNotEmpty()) {
        "where: { ${conditions.joinToString(", ")} }"
      } else {
        ""
      }

    val query = """
      {
        profiles(
          $whereClause
          orderBy: updatedAt
          orderDirection: desc
          first: $maxEntries
        ) {
          id
          displayName
          photoURI
          age
          gender
          languagesPacked
          locationCityId
          locationLatE6
          locationLngE6
        }
      }
    """.trimIndent()

    val json = postQuery(query)
    val profiles = json.optJSONObject("data")?.optJSONArray("profiles") ?: JSONArray()
    val targetNative = filters.nativeLanguage?.trim()?.lowercase().orEmpty()
    val targetLearning = filters.learningLanguage?.trim()?.lowercase().orEmpty()
    val viewer = viewerAddress?.trim()?.lowercase()
    val centerLat = viewerLatE6?.let { e6ToDegrees(it) }
    val centerLng = viewerLngE6?.let { e6ToDegrees(it) }

    val out = ArrayList<CommunityMemberPreview>(profiles.length())
    for (i in 0 until profiles.length()) {
      val p = profiles.optJSONObject(i) ?: continue
      val address = p.optString("id", "").trim().lowercase()
      if (address.isBlank()) continue
      if (!viewer.isNullOrBlank() && viewer == address) continue

      val displayName = p.optString("displayName", "").trim().ifBlank { shortenAddress(address) }
      val ageRaw = p.optInt("age", 0)
      val genderRaw = p.optInt("gender", 0)
      val languages = unpackLanguages(p.optString("languagesPacked", "0"))
      val location = normalizeLocation(p.optString("locationCityId", ""))
      val latRaw = p.optInt("locationLatE6", 0)
      val lngRaw = p.optInt("locationLngE6", 0)
      val hasCoords = hasUsableCoords(latRaw, lngRaw)
      val latE6 = if (hasCoords) latRaw else null
      val lngE6 = if (hasCoords) lngRaw else null
      val photoUrl = resolvePhotoUrl(p.optString("photoURI", "").trim())

      if (targetNative.isNotEmpty()) {
        val nativeMatch = languages.any { it.code == targetNative && it.proficiency == 7 }
        if (!nativeMatch) continue
      }
      if (targetLearning.isNotEmpty()) {
        val learningMatch = languages.any { it.code == targetLearning && it.proficiency in 1..6 }
        if (!learningMatch) continue
      }

      var distanceKm: Double? = null
      if (radiusKm != null && centerLat != null && centerLng != null) {
        if (latE6 == null || lngE6 == null) continue
        val memberLat = e6ToDegrees(latE6)
        val memberLng = e6ToDegrees(lngE6)
        distanceKm = haversineKm(centerLat, centerLng, memberLat, memberLng)
        if (distanceKm > radiusKm.toDouble()) continue
      }

      out.add(
        CommunityMemberPreview(
          address = address,
          displayName = displayName,
          photoUrl = photoUrl,
          age = if (ageRaw > 0) ageRaw else null,
          gender = genderRaw,
          languages = languages,
          locationCityId = location,
          locationLatE6 = latE6,
          locationLngE6 = lngE6,
          distanceKm = distanceKm,
        ),
      )
    }

    if (centerLat != null && centerLng != null) {
      out.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
    } else {
      out
    }
  }

  fun activeFilterCount(filters: CommunityFilters): Int {
    var count = 0
    if (filters.gender != null) count++
    if (filters.minAge != null || filters.maxAge != null) count++
    if (!filters.nativeLanguage.isNullOrBlank()) count++
    if (!filters.learningLanguage.isNullOrBlank()) count++
    if (filters.radiusKm != null) count++
    return count
  }

  fun genderLabel(gender: Int): String? = when (gender) {
    1 -> "Woman"
    2 -> "Man"
    3 -> "Non-binary"
    4 -> "Trans woman"
    5 -> "Trans man"
    6 -> "Intersex"
    7 -> "Other"
    else -> null
  }

  fun languageLabel(code: String): String {
    return languageLabels[code.trim().lowercase()] ?: code.uppercase()
  }

  private fun normalizeLocation(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.isBlank() || value == ZERO_HASH) return null
    return value
  }

  private fun resolvePhotoUrl(uri: String?): String? {
    if (uri.isNullOrBlank()) return null
    return CoverRef.resolveCoverUrl(uri, width = null, height = null, format = null, quality = null)
  }

  private fun unpackLanguages(packedDec: String): List<CommunityLanguage> {
    val packed = packedDec.trim().ifBlank { "0" }.toBigIntegerOrNull() ?: BigInteger.ZERO
    if (packed == BigInteger.ZERO) return emptyList()

    val mask = BigInteger("ffffffff", 16)
    val out = ArrayList<CommunityLanguage>(8)
    for (i in 0 until 8) {
      val shift = (7 - i) * 32
      val slot = packed.shiftRight(shift).and(mask).toLong()
      if (slot == 0L) continue

      val langVal = ((slot ushr 16) and 0xFFFF).toInt()
      val proficiency = ((slot ushr 8) and 0xFF).toInt()
      if (langVal == 0 || proficiency <= 0) continue

      val c1 = ((langVal ushr 8) and 0xFF).toChar()
      val c2 = (langVal and 0xFF).toChar()
      val code = "$c1$c2".lowercase()
      if (code.length != 2 || !code.all { it.isLetter() }) continue

      out.add(CommunityLanguage(code = code, proficiency = proficiency))
    }
    return out
  }

  private fun shortenAddress(addr: String): String = "${addr.take(6)}...${addr.takeLast(4)}"

  private fun hasUsableCoords(latE6: Int, lngE6: Int): Boolean {
    if (latE6 == 0 && lngE6 == 0) return false
    return latE6 in MIN_LAT_E6..MAX_LAT_E6 && lngE6 in MIN_LNG_E6..MAX_LNG_E6
  }

  private fun e6ToDegrees(value: Int): Double = value.toDouble() / 1_000_000.0

  private fun buildBoundingBoxE6(centerLatE6: Int, centerLngE6: Int, radiusKm: Int): BoundingBoxE6 {
    val centerLat = e6ToDegrees(centerLatE6)
    val centerLng = e6ToDegrees(centerLngE6)
    val latDeltaDeg = radiusKm.toDouble() / 111.32
    val cosLat = abs(cos(Math.toRadians(centerLat))).coerceAtLeast(0.01)
    val lngDeltaDeg = radiusKm.toDouble() / (111.32 * cosLat)

    val minLatE6 = floor((centerLat - latDeltaDeg) * 1_000_000.0).toInt().coerceIn(MIN_LAT_E6, MAX_LAT_E6)
    val maxLatE6 = ceil((centerLat + latDeltaDeg) * 1_000_000.0).toInt().coerceIn(MIN_LAT_E6, MAX_LAT_E6)
    val minLngE6 = floor((centerLng - lngDeltaDeg) * 1_000_000.0).toInt().coerceIn(MIN_LNG_E6, MAX_LNG_E6)
    val maxLngE6 = ceil((centerLng + lngDeltaDeg) * 1_000_000.0).toInt().coerceIn(MIN_LNG_E6, MAX_LNG_E6)

    return BoundingBoxE6(
      minLatE6 = minLatE6,
      maxLatE6 = maxLatE6,
      minLngE6 = minLngE6,
      maxLngE6 = maxLngE6,
    )
  }

  private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val a = sin(dLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLng / 2).pow(2)
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return EARTH_RADIUS_KM * c
  }

  private fun postQuery(query: String): JSONObject {
    val bodyStr = JSONObject().put("query", query).toString()
    for (url in profileSubgraphUrls()) {
      try {
        val body = bodyStr.toRequestBody(jsonMediaType)
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).execute().use { res ->
          if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
          return JSONObject(res.body?.string().orEmpty())
        }
      } catch (_: Exception) {
        continue
      }
    }
    throw IllegalStateException("Profiles query failed: all subgraph endpoints unreachable")
  }

  private fun profileSubgraphUrls(): List<String> = tempoProfilesSubgraphUrls()
}
