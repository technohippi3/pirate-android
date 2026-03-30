package sc.pirate.app.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale

private const val MAX_AVATAR_SIZE = 512
private const val JPEG_QUALITY = 85
private const val MAX_FILE_SIZE = 2 * 1024 * 1024 // 2MB
private const val MAX_COVER_SIZE = 1600
private const val COVER_JPEG_QUALITY = 86
private const val MAX_COVER_FILE_SIZE = 5 * 1024 * 1024 // 5MB

internal enum class ProfileEditSheet {
  DisplayName,
  Cover,
  Photo,
  Basics,
  Languages,
  Location,
  School,
  Preferences,
}

internal data class LoadedProfileContext(
  val profile: ContractProfileData,
  val primaryName: String?,
  val node: String?,
  val avatarRecord: String?,
  val coverRecord: String?,
  val locationRecord: String?,
  val schoolRecord: String?,
)

internal data class CountryOption(
  val code: String,
  val label: String,
)

internal data class LanguageProficiencyOption(
  val value: Int,
  val label: String,
)

internal val proficiencyOptions = listOf(
  LanguageProficiencyOption(1, "A1"),
  LanguageProficiencyOption(2, "A2"),
  LanguageProficiencyOption(3, "B1"),
  LanguageProficiencyOption(4, "B2"),
  LanguageProficiencyOption(5, "C1"),
  LanguageProficiencyOption(6, "C2"),
  LanguageProficiencyOption(7, "Native"),
)

internal val countryOptions: List<CountryOption> by lazy {
  val countries =
    Locale.getISOCountries()
      .mapNotNull { code ->
        runCatching {
          val label = Locale.Builder().setRegion(code).build().displayCountry.trim()
          if (label.isBlank()) null else CountryOption(code = code.uppercase(), label = label)
        }.getOrNull()
      }
      .distinctBy { it.code }
      .sortedBy { it.label }
  listOf(CountryOption("", "Not specified")) + countries
}

internal fun countryLabel(code: String): String {
  val normalized = code.trim().uppercase()
  if (normalized.isBlank()) return "Not specified"
  return countryOptions.firstOrNull { it.code == normalized }?.label ?: normalized
}

internal fun processAvatarImage(context: android.content.Context, uri: Uri): Pair<Bitmap, String> {
  return processProfileImage(
    context = context,
    uri = uri,
    maxFileSize = MAX_FILE_SIZE,
    maxDimension = MAX_AVATAR_SIZE,
    jpegQuality = JPEG_QUALITY,
    tooLargeMessagePrefix = "Image",
  )
}

internal fun processCoverImage(context: android.content.Context, uri: Uri): Pair<Bitmap, String> {
  return processProfileImage(
    context = context,
    uri = uri,
    maxFileSize = MAX_COVER_FILE_SIZE,
    maxDimension = MAX_COVER_SIZE,
    jpegQuality = COVER_JPEG_QUALITY,
    tooLargeMessagePrefix = "Cover image",
  )
}

private fun processProfileImage(
  context: android.content.Context,
  uri: Uri,
  maxFileSize: Int,
  maxDimension: Int,
  jpegQuality: Int,
  tooLargeMessagePrefix: String,
): Pair<Bitmap, String> {
  val inputStream =
    context.contentResolver.openInputStream(uri)
      ?: throw IllegalStateException("Cannot open image")
  val bytes = inputStream.readBytes()
  inputStream.close()

  if (bytes.size > maxFileSize) {
    val sizeMB = String.format(Locale.US, "%.1f", bytes.size / (1024.0 * 1024.0))
    val limitMb = String.format(Locale.US, "%.0f", maxFileSize / (1024.0 * 1024.0))
    throw IllegalStateException("$tooLargeMessagePrefix is too large (${sizeMB} MB). Please use an image under $limitMb MB.")
  }

  val original =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      ?: throw IllegalStateException("Cannot decode image")

  val (w, h) = original.width to original.height
  val scale =
    if (w > maxDimension || h > maxDimension) {
      maxDimension.toFloat() / maxOf(w, h)
    } else {
      1f
    }
  val targetW = (w * scale).toInt().coerceAtLeast(1)
  val targetH = (h * scale).toInt().coerceAtLeast(1)
  val scaled = if (scale < 1f) Bitmap.createScaledBitmap(original, targetW, targetH, true) else original

  val out = ByteArrayOutputStream()
  scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
  val jpegBytes = out.toByteArray()
  val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

  return scaled to base64
}

internal fun basicsSummary(draft: ContractProfileData): String {
  val parts =
    buildList {
      if (draft.age > 0) add("${draft.age} yrs")
      if (draft.heightCm > 0) add("${draft.heightCm} cm")
      if (draft.nationality.isNotBlank()) add(countryLabel(draft.nationality))
    }
  return if (parts.isEmpty()) "Age, height, nationality" else parts.joinToString(" · ")
}

internal fun languageSummary(entries: List<ProfileLanguageEntry>): String {
  if (entries.isEmpty()) return "No languages set"
  return entries.joinToString(" · ") { entry ->
    "${ProfileContractApi.languageLabel(entry.code)} (${ProfileContractApi.proficiencyLabel(entry.proficiency)})"
  }
}

internal fun locationSummary(locationLabel: String, draft: ContractProfileData): String {
  if (locationLabel.isNotBlank()) return locationLabel
  if (draft.locationCityId.isNotBlank()) return "Location set"
  return "No location set"
}

internal fun schoolSummary(schoolName: String, draft: ContractProfileData): String {
  if (schoolName.isNotBlank()) return schoolName
  if (draft.schoolId.isNotBlank()) return "School set"
  return "No school set"
}

internal fun preferenceSummary(draft: ContractProfileData): String {
  var setCount =
    listOf(
      draft.gender,
      draft.relocate,
      draft.degree,
      draft.fieldBucket,
      draft.profession,
      draft.industry,
      draft.relationshipStatus,
      draft.sexuality,
      draft.ethnicity,
      draft.datingStyle,
      draft.children,
      draft.wantsChildren,
      draft.drinking,
      draft.smoking,
      draft.drugs,
      draft.lookingFor,
      draft.religion,
      draft.pets,
      draft.diet,
    ).count { it > 0 }
  if ((draft.friendsOpenToMask and 0x07) != 0) setCount += 1
  return if (setCount == 0) "No preferences set" else "$setCount fields set"
}
