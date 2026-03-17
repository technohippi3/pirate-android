package com.pirate.app.music

fun audioExtensionFromMime(mimeType: String?): String {
  val mime = mimeType?.trim()?.lowercase().orEmpty()
  return when (mime) {
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/flac" -> "flac"
    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
    "audio/aac" -> "aac"
    "audio/ogg" -> "ogg"
    "audio/mp4", "audio/m4a" -> "m4a"
    else -> "bin"
  }
}

fun audioMimeFromExtension(ext: String?): String? {
  val normalized = ext?.trim()?.lowercase().orEmpty()
  return when (normalized) {
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "aac" -> "audio/aac"
    "ogg" -> "audio/ogg"
    "m4a", "mp4" -> "audio/mp4"
    else -> null
  }
}
