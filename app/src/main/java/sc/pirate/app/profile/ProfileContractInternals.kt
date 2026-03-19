package sc.pirate.app.profile

import sc.pirate.app.onboarding.OnboardingRpcHelpers
import sc.pirate.app.tempo.TempoClient
import java.math.BigInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val profileContractJsonMediaType = "application/json; charset=utf-8".toMediaType()
private val profileContractClient = OkHttpClient()

internal fun normalizeProfileAddress(address: String?): String? {
  val value = address?.trim()?.lowercase().orEmpty()
  if (!value.startsWith("0x") || value.length != 42) return null
  return value
}

internal fun normalizeProfileHash(raw: String?): String {
  val value = raw?.trim()?.lowercase().orEmpty()
  if (value.isBlank() || value == ProfileContractApi.ZERO_HASH) return ""
  return value
}

internal fun profileBytes2ToCode(hex: String): String? {
  val raw = hex.removePrefix("0x")
  if (raw.length < 4) return null
  val value = raw.take(4).toIntOrNull(16) ?: return null
  if (value == 0) return null
  val c1 = ((value ushr 8) and 0xff).toChar()
  val c2 = (value and 0xff).toChar()
  if (!c1.isLetter() || !c2.isLetter()) return null
  return "$c1$c2".uppercase()
}

internal fun profileCodeToBytes2(code: String): String {
  val trimmed = code.trim().uppercase()
  if (trimmed.length < 2) return "0x0000"
  val c1 = trimmed[0].code
  val c2 = trimmed[1].code
  return "0x${c1.toString(16).padStart(2, '0')}${c2.toString(16).padStart(2, '0')}"
}

internal fun profileToBytes32(value: String): String {
  val trimmed = value.trim()
  if (trimmed.isBlank()) return ProfileContractApi.ZERO_HASH
  if (
    trimmed.startsWith("0x") &&
      trimmed.length == 66 &&
      trimmed.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
  ) {
    return trimmed.lowercase()
  }
  val hash = OnboardingRpcHelpers.keccak256(trimmed.toByteArray(Charsets.UTF_8))
  return "0x${OnboardingRpcHelpers.bytesToHex(hash)}"
}

internal fun packProfileLanguages(entries: List<ProfileLanguageEntry>): String {
  var packed = BigInteger.ZERO
  val slots = entries.take(8)
  for ((index, entry) in slots.withIndex()) {
    val code = entry.code.trim().lowercase()
    if (code.length != 2 || !code.all { it.isLetter() }) continue
    val upper = code.uppercase()
    val langVal = (upper[0].code shl 8) or upper[1].code
    val slotVal = ((langVal and 0xFFFF) shl 16) or ((entry.proficiency and 0xFF) shl 8)
    val shift = (7 - index) * 32
    packed = packed.or(BigInteger.valueOf(slotVal.toLong()).shiftLeft(shift))
  }
  return packed.toString(10)
}

internal fun unpackProfileLanguages(packedDec: String): List<ProfileLanguageEntry> {
  val packed = packedDec.trim().ifBlank { "0" }.toBigIntegerOrNull() ?: BigInteger.ZERO
  if (packed == BigInteger.ZERO) return emptyList()

  val mask = BigInteger("ffffffff", 16)
  val out = ArrayList<ProfileLanguageEntry>(8)
  for (i in 0 until 8) {
    val shift = (7 - i) * 32
    val slot = packed.shiftRight(shift).and(mask).toLong()
    if (slot == 0L) continue
    val langVal = ((slot ushr 16) and 0xFFFF).toInt()
    val proficiency = ((slot ushr 8) and 0xFF).toInt()
    if (langVal == 0 || proficiency !in 1..7) continue

    val c1 = ((langVal ushr 8) and 0xFF).toChar()
    val c2 = (langVal and 0xFF).toChar()
    val code = "$c1$c2".lowercase()
    if (!code.all { it.isLetter() }) continue

    out.add(ProfileLanguageEntry(code, proficiency))
  }
  return out
}

internal fun profileToTagCommit(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.isBlank()) return ProfileContractApi.ZERO_HASH
  if (
    trimmed.startsWith("0x") &&
      trimmed.length == 66 &&
      trimmed.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
  ) {
    return trimmed.lowercase()
  }
  return packProfileTagIds(parseProfileTagCsv(trimmed))
}

private fun parseProfileTagCsv(csv: String): List<Int> =
  csv
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .mapNotNull { it.toIntOrNull() }
    .filter { it in 1..0xFFFF }
    .distinct()
    .sorted()
    .take(16)

private fun packProfileTagIds(ids: List<Int>): String {
  if (ids.isEmpty()) return ProfileContractApi.ZERO_HASH
  val slots = ids.toMutableList()
  while (slots.size < 16) slots.add(0)
  val hex = buildString {
    append("0x")
    for (id in slots.take(16)) {
      append(id.toString(16).padStart(4, '0'))
    }
  }
  return hex
}

internal fun unpackProfileTagIds(hex: String): List<Int> {
  val raw = hex.removePrefix("0x")
  if (raw.length != 64) return emptyList()
  return buildList {
    var index = 0
    while (index < raw.length) {
      val id = raw.substring(index, index + 4).toIntOrNull(16) ?: 0
      if (id > 0) add(id)
      index += 4
    }
  }
}

internal fun decodeProfileTuple(hex: String): ContractProfileData? {
  if (hex.length < 64) return null
  val tupleOffsetBytes = profileHexWordToBigInt(profileWordAt(hex, 0)).toInt()
  val tupleBase = tupleOffsetBytes * 2
  val requiredHeadWords = 17
  if (tupleBase < 0 || tupleBase + (requiredHeadWords * 64) > hex.length) return null

  fun tupleWord(index: Int): String =
    hex.substring(tupleBase + (index * 64), tupleBase + ((index + 1) * 64))

  val exists = profileHexWordToBigInt(tupleWord(1)) != BigInteger.ZERO
  if (!exists) return null

  val profileVersion = profileHexWordToBigInt(tupleWord(0)).toInt()
  val age = profileHexWordToBigInt(tupleWord(2)).toInt()
  val heightCm = profileHexWordToBigInt(tupleWord(3)).toInt()
  val nationality = profileBytes2ToCode("0x${tupleWord(4).take(4)}") ?: ""
  val friendsOpenToMask = profileHexWordToBigInt(tupleWord(5)).toInt()
  val languagesPacked = profileHexWordToBigInt(tupleWord(6))
  val locationCityId = normalizeProfileHash("0x${tupleWord(7)}")
  val locationLatE6 = decodeProfileInt32Word(tupleWord(8))
  val locationLngE6 = decodeProfileInt32Word(tupleWord(9))
  val schoolId = normalizeProfileHash("0x${tupleWord(10)}")
  val skillsCommit = unpackProfileTagIds("0x${tupleWord(11)}").joinToString(",")
  val hobbiesCommit = unpackProfileTagIds("0x${tupleWord(12)}").joinToString(",")
  val nameHash = normalizeProfileHash("0x${tupleWord(13)}")
  val packedEnums = profileHexWordToBigInt(tupleWord(14))

  val displayNameOffset = profileHexWordToBigInt(tupleWord(15)).toInt()
  val photoUriOffset = profileHexWordToBigInt(tupleWord(16)).toInt()
  val displayName = decodeProfileDynamicString(hex, tupleBase, displayNameOffset)
  val photoUri = decodeProfileDynamicString(hex, tupleBase, photoUriOffset)

  fun enumAt(index: Int): Int =
    packedEnums.shiftRight(index * 8).and(BigInteger.valueOf(0xFF)).toInt()

  return ContractProfileData(
    profileVersion = profileVersion,
    displayName = displayName,
    nameHash = nameHash,
    age = age,
    heightCm = heightCm,
    nationality = nationality,
    languages = unpackProfileLanguages(languagesPacked.toString(10)),
    friendsOpenToMask = friendsOpenToMask,
    locationCityId = locationCityId,
    locationLatE6 = locationLatE6,
    locationLngE6 = locationLngE6,
    schoolId = schoolId,
    skillsCommit = skillsCommit,
    hobbiesCommit = hobbiesCommit,
    photoUri = photoUri,
    gender = enumAt(0),
    relocate = enumAt(1),
    degree = enumAt(2),
    fieldBucket = enumAt(3),
    profession = enumAt(4),
    industry = enumAt(5),
    relationshipStatus = enumAt(6),
    sexuality = enumAt(7),
    ethnicity = enumAt(8),
    datingStyle = enumAt(9),
    children = enumAt(10),
    wantsChildren = enumAt(11),
    drinking = enumAt(12),
    smoking = enumAt(13),
    drugs = enumAt(14),
    lookingFor = enumAt(15),
    religion = enumAt(16),
    pets = enumAt(17),
    diet = enumAt(18),
  )
}

private fun decodeProfileDynamicString(
  hex: String,
  tupleBase: Int,
  offsetBytes: Int,
): String {
  if (offsetBytes < 0) return ""
  val start = tupleBase + (offsetBytes * 2)
  if (start + 64 > hex.length) return ""
  val len = profileHexWordToBigInt(hex.substring(start, start + 64)).toInt()
  if (len <= 0) return ""
  val dataStart = start + 64
  val dataEnd = dataStart + (len * 2)
  if (dataEnd > hex.length) return ""
  val raw = hex.substring(dataStart, dataEnd)
  return String(OnboardingRpcHelpers.hexToBytes(raw), Charsets.UTF_8)
}

private fun decodeProfileInt32Word(word: String): Int {
  val low32 = profileHexWordToBigInt(word).and(BigInteger("ffffffff", 16)).toLong()
  return if (low32 >= 0x80000000L) (low32 - 0x1_0000_0000L).toInt() else low32.toInt()
}

private fun profileWordAt(hex: String, index: Int): String {
  val start = index * 64
  val end = start + 64
  if (start < 0 || end > hex.length) return "0".repeat(64)
  return hex.substring(start, end)
}

private fun profileHexWordToBigInt(word: String): BigInteger {
  if (word.isBlank()) return BigInteger.ZERO
  return word.toBigIntegerOrNull(16) ?: BigInteger.ZERO
}

internal fun profileFunctionSelector(sig: String): String {
  val hash = OnboardingRpcHelpers.keccak256(sig.toByteArray(Charsets.UTF_8))
  return OnboardingRpcHelpers.bytesToHex(hash.copyOfRange(0, 4))
}

internal fun profileEthCall(to: String, data: String): String {
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
      .post(payload.toString().toRequestBody(profileContractJsonMediaType))
      .build()

  profileContractClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    return body.optString("result", "0x")
  }
}
