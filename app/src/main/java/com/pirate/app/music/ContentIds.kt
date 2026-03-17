package com.pirate.app.music

import org.bouncycastle.jcajce.provider.digest.Keccak

object ContentIds {
  /**
   * contentId = keccak256(abi.encode(bytes32 trackId, address owner))
   *
   * Matches apps/web/src/lib/content-crypto.ts computeContentId().
   */
  fun computeContentId(trackIdHex: String, ownerAddress: String): String {
    val trackIdBytes = hexToBytes(trackIdHex)
    require(trackIdBytes.size == 32) { "trackId must be bytes32 hex" }

    val ownerBytes = hexToBytes(ownerAddress)
    require(ownerBytes.size == 20) { "owner must be address hex" }

    val encoded = ByteArray(64)
    System.arraycopy(trackIdBytes, 0, encoded, 0, 32)
    // address is right-aligned in a 32-byte word.
    System.arraycopy(ownerBytes, 0, encoded, 32 + 12, 20)

    val d = Keccak.Digest256()
    d.update(encoded, 0, encoded.size)
    val out = d.digest()
    return "0x${out.toHex()}"
  }
}

private fun hexToBytes(hex: String): ByteArray {
  val clean = hex.trim().removePrefix("0x").removePrefix("0X")
  if (clean.isEmpty()) return ByteArray(0)
  require(clean.length % 2 == 0) { "Invalid hex length" }

  val out = ByteArray(clean.length / 2)
  var i = 0
  while (i < clean.length) {
    out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
    i += 2
  }
  return out
}

private fun ByteArray.toHex(): String {
  val hexChars = "0123456789abcdef"
  val out = CharArray(this.size * 2)
  var i = 0
  for (b in this) {
    val v = b.toInt() and 0xff
    out[i++] = hexChars[v ushr 4]
    out[i++] = hexChars[v and 0x0f]
  }
  return String(out)
}

