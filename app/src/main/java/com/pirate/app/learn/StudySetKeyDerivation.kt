package com.pirate.app.learn

import org.bouncycastle.jcajce.provider.digest.Keccak

private val BYTES32_REGEX = Regex("^0x[a-fA-F0-9]{64}$")

internal object StudySetKeyDerivation {
  fun derive(
    trackId: String,
    language: String,
    version: Int,
  ): String? {
    val normalizedTrackId = normalizeBytes32(trackId) ?: return null
    if (version !in 1..255) return null
    val lang = language.trim()
    if (lang.isBlank()) return null

    val trackIdBytes = hexToBytes32(normalizedTrackId)
    val langHash = keccak256(lang.toByteArray(Charsets.UTF_8))
    val encoded = ByteArray(96)
    System.arraycopy(trackIdBytes, 0, encoded, 0, 32)
    System.arraycopy(langHash, 0, encoded, 32, 32)
    encoded[95] = version.toByte()

    return "0x${keccak256(encoded).toHex()}"
  }

  private fun normalizeBytes32(raw: String): String? {
    val trimmed = raw.trim()
    if (!BYTES32_REGEX.matches(trimmed)) return null
    return trimmed.lowercase()
  }

  private fun hexToBytes32(value: String): ByteArray {
    val clean = value.removePrefix("0x").removePrefix("0X")
    val out = ByteArray(32)
    var i = 0
    while (i < 64) {
      out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
      i += 2
    }
    return out
  }

  private fun keccak256(input: ByteArray): ByteArray {
    val digest = Keccak.Digest256()
    digest.update(input, 0, input.size)
    return digest.digest()
  }
}

private fun ByteArray.toHex(): String {
  val hex = "0123456789abcdef"
  val out = CharArray(size * 2)
  var i = 0
  for (b in this) {
    val v = b.toInt() and 0xff
    out[i++] = hex[v ushr 4]
    out[i++] = hex[v and 0x0f]
  }
  return String(out)
}
