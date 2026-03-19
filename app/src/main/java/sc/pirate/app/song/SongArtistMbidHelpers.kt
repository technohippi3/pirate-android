package sc.pirate.app.song

internal fun decodeRecordingMbidFromTrackKindAndPayloadHex(
  kind: Int?,
  payloadHex: String?,
): String? {
  val payload = payloadHex?.trim().orEmpty()
  if (payload.isBlank()) return null
  val bytes = runCatching { hexToBytes(payload) }.getOrNull() ?: return null
  return decodeRecordingMbidFromTrackKindAndPayloadBytes(kind, bytes)
}

internal fun decodeRecordingMbidFromTrackKindAndPayloadBytes(
  kind: Int?,
  payloadBytes: ByteArray?,
): String? {
  if (kind != 1) return null
  val payload = payloadBytes ?: return null
  if (payload.size != 32) return null
  for (i in 16 until 32) {
    if (payload[i].toInt() != 0) return null
  }

  val mbidHex = buildString(capacity = 32) {
    for (i in 0 until 16) {
      append("%02x".format(payload[i]))
    }
  }
  return buildString(capacity = 36) {
    append(mbidHex.substring(0, 8))
    append('-')
    append(mbidHex.substring(8, 12))
    append('-')
    append(mbidHex.substring(12, 16))
    append('-')
    append(mbidHex.substring(16, 20))
    append('-')
    append(mbidHex.substring(20, 32))
  }
}
