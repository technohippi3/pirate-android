package sc.pirate.app.profile

import sc.pirate.app.onboarding.steps.LANGUAGE_OPTIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProfileLanguageEntry(
  val code: String,
  val proficiency: Int,
)

data class ProfileEnumOption(
  val value: Int,
  val label: String,
)

data class ContractProfileData(
  val profileVersion: Int = 2,
  val displayName: String = "",
  val nameHash: String = "",
  val age: Int = 0,
  val heightCm: Int = 0,
  val nationality: String = "",
  val languages: List<ProfileLanguageEntry> = emptyList(),
  val friendsOpenToMask: Int = 0,
  val locationCityId: String = "",
  val locationLatE6: Int = 0,
  val locationLngE6: Int = 0,
  val schoolId: String = "",
  val skillsCommit: String = "",
  val hobbiesCommit: String = "",
  val photoUri: String = "",
  val gender: Int = 0,
  val relocate: Int = 0,
  val degree: Int = 0,
  val fieldBucket: Int = 0,
  val profession: Int = 0,
  val industry: Int = 0,
  val relationshipStatus: Int = 0,
  val sexuality: Int = 0,
  val ethnicity: Int = 0,
  val datingStyle: Int = 0,
  val children: Int = 0,
  val wantsChildren: Int = 0,
  val drinking: Int = 0,
  val smoking: Int = 0,
  val drugs: Int = 0,
  val lookingFor: Int = 0,
  val religion: Int = 0,
  val pets: Int = 0,
  val diet: Int = 0,
)

object ProfileContractApi {
  const val ZERO_HASH =
    "0x0000000000000000000000000000000000000000000000000000000000000000"

  private val languageLabels = LANGUAGE_OPTIONS.associate { it.code.lowercase() to it.label }

  val GENDER_OPTIONS = PROFILE_GENDER_OPTIONS
  val RELOCATE_OPTIONS = PROFILE_RELOCATE_OPTIONS
  val DEGREE_OPTIONS = PROFILE_DEGREE_OPTIONS
  val FIELD_OPTIONS = PROFILE_FIELD_OPTIONS
  val PROFESSION_OPTIONS = PROFILE_PROFESSION_OPTIONS
  val INDUSTRY_OPTIONS = PROFILE_INDUSTRY_OPTIONS
  val RELATIONSHIP_OPTIONS = PROFILE_RELATIONSHIP_OPTIONS
  val SEXUALITY_OPTIONS = PROFILE_SEXUALITY_OPTIONS
  val ETHNICITY_OPTIONS = PROFILE_ETHNICITY_OPTIONS
  val DATING_STYLE_OPTIONS = PROFILE_DATING_STYLE_OPTIONS
  val CHILDREN_OPTIONS = PROFILE_CHILDREN_OPTIONS
  val WANTS_CHILDREN_OPTIONS = PROFILE_WANTS_CHILDREN_OPTIONS
  val DRINKING_OPTIONS = PROFILE_DRINKING_OPTIONS
  val SMOKING_OPTIONS = PROFILE_SMOKING_OPTIONS
  val DRUGS_OPTIONS = PROFILE_DRUGS_OPTIONS
  val LOOKING_FOR_OPTIONS = PROFILE_LOOKING_FOR_OPTIONS
  val RELIGION_OPTIONS = PROFILE_RELIGION_OPTIONS
  val PETS_OPTIONS = PROFILE_PETS_OPTIONS
  val DIET_OPTIONS = PROFILE_DIET_OPTIONS

  fun emptyProfile(): ContractProfileData = ContractProfileData()

  suspend fun fetchProfile(address: String): ContractProfileData? = withContext(Dispatchers.IO) {
    val addr = normalizeProfileAddress(address) ?: return@withContext null
    runCatching { fetchProfileFromRpc(addr) }.getOrNull()
  }

  private fun fetchProfileFromRpc(addr: String): ContractProfileData? {
    val addrWord = addr.removePrefix("0x").padStart(64, '0')
    val selector = profileFunctionSelector("getProfile(address)")
    val data = "0x$selector$addrWord"
    val result = profileEthCall(TempoProfileContractApi.PROFILE_V2, data).removePrefix("0x")
    return decodeProfileTuple(result)
  }

  fun buildProfileInput(data: ContractProfileData): JSONObject {
    val cityId = profileToBytes32(data.locationCityId)
    val hasCity = cityId != ZERO_HASH
    val schoolId = profileToBytes32(data.schoolId)
    val nameHash = profileToBytes32(data.nameHash)

    return JSONObject()
      .put("profileVersion", data.profileVersion.coerceAtLeast(2))
      .put("displayName", data.displayName.trim())
      .put("nameHash", nameHash)
      .put("age", data.age.coerceAtLeast(0))
      .put("heightCm", data.heightCm.coerceAtLeast(0))
      .put("nationality", profileCodeToBytes2(data.nationality))
      .put("languagesPacked", packProfileLanguages(data.languages))
      .put("friendsOpenToMask", data.friendsOpenToMask and 0x07)
      .put("locationCityId", cityId)
      .put("locationLatE6", if (hasCity) data.locationLatE6.coerceIn(-90_000_000, 90_000_000) else 0)
      .put("locationLngE6", if (hasCity) data.locationLngE6.coerceIn(-180_000_000, 180_000_000) else 0)
      .put("schoolId", schoolId)
      .put("skillsCommit", profileToTagCommit(data.skillsCommit))
      .put("hobbiesCommit", profileToTagCommit(data.hobbiesCommit))
      .put("photoURI", data.photoUri.trim())
      .put("gender", data.gender.coerceIn(0, 7))
      .put("relocate", data.relocate.coerceIn(0, 3))
      .put("degree", data.degree.coerceIn(0, 9))
      .put("fieldBucket", data.fieldBucket.coerceIn(0, 16))
      .put("profession", data.profession.coerceIn(0, 10))
      .put("industry", data.industry.coerceIn(0, 10))
      .put("relationshipStatus", data.relationshipStatus.coerceIn(0, 7))
      .put("sexuality", data.sexuality.coerceIn(0, 9))
      .put("ethnicity", data.ethnicity.coerceIn(0, 11))
      .put("datingStyle", data.datingStyle.coerceIn(0, 5))
      .put("children", data.children.coerceIn(0, 2))
      .put("wantsChildren", data.wantsChildren.coerceIn(0, 4))
      .put("drinking", data.drinking.coerceIn(0, 4))
      .put("smoking", data.smoking.coerceIn(0, 4))
      .put("drugs", data.drugs.coerceIn(0, 3))
      .put("lookingFor", data.lookingFor.coerceIn(0, 7))
      .put("religion", data.religion.coerceIn(0, 10))
      .put("pets", data.pets.coerceIn(0, 4))
      .put("diet", data.diet.coerceIn(0, 7))
  }

  fun languageLabel(code: String): String {
    return languageLabels[code.trim().lowercase()] ?: code.uppercase()
  }

  fun proficiencyLabel(level: Int): String = when (level) {
    7 -> "Native"
    6 -> "C2"
    5 -> "C1"
    4 -> "B2"
    3 -> "B1"
    2 -> "A2"
    1 -> "A1"
    else -> "Not specified"
  }

  fun selectedFriendsLabels(mask: Int): List<String> {
    val out = mutableListOf<String>()
    if ((mask and 0x1) != 0) out.add("Men")
    if ((mask and 0x2) != 0) out.add("Women")
    if ((mask and 0x4) != 0) out.add("Non-binary")
    return out
  }

  fun enumLabel(options: List<ProfileEnumOption>, value: Int): String? {
    if (value <= 0) return null
    return options.firstOrNull { it.value == value }?.label
  }

  fun hasCoords(latE6: Int, lngE6: Int): Boolean {
    if (latE6 == 0 && lngE6 == 0) return false
    return latE6 in -90_000_000..90_000_000 && lngE6 in -180_000_000..180_000_000
  }

  fun bytes2ToCode(hex: String): String? = profileBytes2ToCode(hex)
}
