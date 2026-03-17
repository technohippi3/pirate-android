package com.pirate.app.profile

import android.util.Log
import com.pirate.app.music.SongPublishService
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class ProfileAvatarUploadResult(
  val success: Boolean,
  val avatarRef: String? = null,
  val dataitemId: String? = null,
  val error: String? = null,
)

object ProfileAvatarUploadApi {
  private const val TAG = "AvatarUpload"
  private const val MAX_UPLOAD_BYTES = 1 * 1024 * 1024
  private const val CONNECT_TIMEOUT_MS = 120_000
  private const val READ_TIMEOUT_MS = 120_000

  fun uploadAvatarJpeg(
    ownerEthAddress: String,
    jpegBytes: ByteArray,
  ): ProfileAvatarUploadResult {
    Log.d(TAG, "uploadAvatarJpeg: ${jpegBytes.size} bytes, address=${ownerEthAddress.take(10)}")
    val normalizedOwner = ownerEthAddress.trim().lowercase()
    if (!Regex("^0x[a-f0-9]{40}$").matches(normalizedOwner)) {
      return ProfileAvatarUploadResult(success = false, error = "Invalid owner address for avatar upload.")
    }
    if (jpegBytes.isEmpty()) {
      Log.w(TAG, "uploadAvatarJpeg: empty bytes")
      return ProfileAvatarUploadResult(success = false, error = "Avatar image is empty.")
    }
    if (jpegBytes.size > MAX_UPLOAD_BYTES) {
      Log.w(TAG, "uploadAvatarJpeg: too large (${jpegBytes.size} bytes)")
      return ProfileAvatarUploadResult(success = false, error = "Avatar image exceeds upload size limit.")
    }

    return runCatching {
      val boundary = "----PirateAvatar${System.currentTimeMillis()}"
      val uploadUrl = URL("${SongPublishService.API_CORE_URL}/api/storage/avatar/upload")
      val conn =
        (uploadUrl.openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          doOutput = true
          connectTimeout = CONNECT_TIMEOUT_MS
          readTimeout = READ_TIMEOUT_MS
          setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
          setRequestProperty("X-User-Address", normalizedOwner)
        }

      val fileName = "avatar-${System.currentTimeMillis()}.jpg"
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
        throw IllegalStateException("Avatar upload failed: $details")
      }

      val cid =
        sequenceOf(
          json?.optString("cid", "")?.trim(),
          json?.optJSONObject("payload")?.optString("cidHeader", "")?.trim(),
          json?.optString("ref", "")?.trim()?.removePrefix("ipfs://"),
        ).firstOrNull { !it.isNullOrBlank() }
          ?: throw IllegalStateException("Avatar upload succeeded but no CID was returned.")
      val ref = json?.optString("ref", "")?.trim().takeUnless { it.isNullOrBlank() } ?: "ipfs://$cid"
      Log.i(TAG, "uploadAvatarJpeg: success, ref=$ref")
      ProfileAvatarUploadResult(
        success = true,
        avatarRef = ref,
        dataitemId = cid,
      )
    }.getOrElse { err ->
      Log.e(TAG, "uploadAvatarJpeg: failed — ${err.javaClass.simpleName}: ${err.message}", err)
      ProfileAvatarUploadResult(success = false, error = err.message ?: "Avatar upload failed.")
    }
  }
}
