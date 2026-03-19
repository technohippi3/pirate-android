package sc.pirate.app.profile

import android.util.Log
import sc.pirate.app.music.SongPublishService
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class ProfileCoverUploadResult(
  val success: Boolean,
  val coverRef: String? = null,
  val dataitemId: String? = null,
  val error: String? = null,
)

object ProfileCoverUploadApi {
  private const val TAG = "ProfileCoverUpload"
  private const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
  private const val CONNECT_TIMEOUT_MS = 120_000
  private const val READ_TIMEOUT_MS = 120_000

  fun uploadCoverJpeg(
    ownerEthAddress: String,
    jpegBytes: ByteArray,
  ): ProfileCoverUploadResult {
    Log.d(TAG, "uploadCoverJpeg: ${jpegBytes.size} bytes, address=${ownerEthAddress.take(10)}")
    val normalizedOwner = ownerEthAddress.trim().lowercase()
    if (!Regex("^0x[a-f0-9]{40}$").matches(normalizedOwner)) {
      return ProfileCoverUploadResult(success = false, error = "Invalid owner address for cover upload.")
    }
    if (jpegBytes.isEmpty()) {
      Log.w(TAG, "uploadCoverJpeg: empty bytes")
      return ProfileCoverUploadResult(success = false, error = "Cover image is empty.")
    }
    if (jpegBytes.size > MAX_UPLOAD_BYTES) {
      Log.w(TAG, "uploadCoverJpeg: too large (${jpegBytes.size} bytes)")
      return ProfileCoverUploadResult(success = false, error = "Cover image exceeds upload size limit.")
    }

    return runCatching {
      val boundary = "----PirateProfileCover${System.currentTimeMillis()}"
      val uploadUrl = URL("${SongPublishService.API_CORE_URL}/api/storage/profile/cover/upload")
      val conn =
        (uploadUrl.openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          doOutput = true
          connectTimeout = CONNECT_TIMEOUT_MS
          readTimeout = READ_TIMEOUT_MS
          setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
          setRequestProperty("X-User-Address", normalizedOwner)
        }

      val fileName = "profile-cover-${System.currentTimeMillis()}.jpg"
      conn.outputStream.use { out ->
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
        out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
        out.write(jpegBytes)
        out.write("\r\n".toByteArray())

        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"contentType\"\r\n\r\n".toByteArray())
        out.write("image/jpeg".toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray())

        out.write("--$boundary--\r\n".toByteArray())
      }

      val status = conn.responseCode
      val raw =
        (if (status in 200..299) conn.inputStream else conn.errorStream)
          ?.bufferedReader()
          ?.use { it.readText() }
          .orEmpty()
      val json = runCatching { JSONObject(raw) }.getOrNull()
      if (status !in 200..299) {
        val details =
          sequenceOf(
            json?.optString("details", "")?.trim(),
            json?.optString("error", "")?.trim(),
            raw.trim().ifBlank { null },
            "HTTP $status",
          ).firstOrNull { !it.isNullOrBlank() } ?: "HTTP $status"
        throw IllegalStateException("Cover upload failed: $details")
      }

      val cid =
        sequenceOf(
          json?.optString("cid", "")?.trim(),
          json?.optJSONObject("payload")?.optString("cidHeader", "")?.trim(),
          json?.optString("ref", "")?.trim()?.removePrefix("ipfs://"),
        ).firstOrNull { !it.isNullOrBlank() }
          ?: throw IllegalStateException("Cover upload succeeded but no CID was returned.")
      val ref = json?.optString("ref", "")?.trim().takeUnless { it.isNullOrBlank() } ?: "ipfs://$cid"
      Log.i(TAG, "uploadCoverJpeg: success, ref=$ref")
      ProfileCoverUploadResult(
        success = true,
        coverRef = ref,
        dataitemId = cid,
      )
    }.getOrElse { err ->
      Log.e(TAG, "uploadCoverJpeg: failed — ${err.javaClass.simpleName}: ${err.message}", err)
      ProfileCoverUploadResult(success = false, error = err.message ?: "Cover upload failed.")
    }
  }
}
