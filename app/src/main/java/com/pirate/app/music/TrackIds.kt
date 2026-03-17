package com.pirate.app.music

import org.bouncycastle.jcajce.provider.digest.Keccak
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object TrackIds {
  data class TrackIdParts(
    val kind: Int,
    val payload: ByteArray, // 32 bytes
    val trackId: ByteArray, // 32 bytes
  )

  fun computeMetaTrackId(
    title: String,
    artist: String,
    album: String?,
  ): String {
    val kind = 3
    val payload =
      keccak256(
        abiEncodeStrings(
          normalize(title),
          normalize(artist),
          normalize(album.orEmpty()),
        ),
      )
    val trackId = keccak256(abiEncodeUint8Bytes32(kind, payload))
    return "0x${trackId.toHex()}"
  }

  fun computeMetaParts(
    title: String,
    artist: String,
    album: String?,
  ): TrackIdParts {
    val kind = 3
    val payload =
      keccak256(
        abiEncodeStrings(
          normalize(title),
          normalize(artist),
          normalize(album.orEmpty()),
        ),
      )
    val trackId = keccak256(abiEncodeUint8Bytes32(kind, payload))
    return TrackIdParts(kind = kind, payload = payload, trackId = trackId)
  }

  private fun normalize(value: String): String {
    return value.lowercase().trim().replace(Regex("\\s+"), " ")
  }

  private fun keccak256(bytes: ByteArray): ByteArray {
    val d = Keccak.Digest256()
    d.update(bytes, 0, bytes.size)
    return d.digest()
  }

  private fun abiEncodeStrings(vararg values: String): ByteArray {
    // abi.encode(string,string,string) – only what we need for trackId derivation.
    val utf8 = values.map { it.toByteArray(StandardCharsets.UTF_8) }
    val headSize = 32 * utf8.size

    val tails = utf8.map { s ->
      val paddedLen = ((s.size + 31) / 32) * 32
      val out = ByteArrayOutputStream()
      out.write(u256Word(s.size.toLong()))
      out.write(s)
      if (paddedLen > s.size) out.write(ByteArray(paddedLen - s.size))
      out.toByteArray()
    }

    var offset = headSize.toLong()
    val out = ByteArrayOutputStream()
    for (t in tails) {
      out.write(u256Word(offset))
      offset += t.size.toLong()
    }
    for (t in tails) out.write(t)
    return out.toByteArray()
  }

  private fun abiEncodeUint8Bytes32(kind: Int, payload32: ByteArray): ByteArray {
    require(kind in 0..255) { "kind must fit in uint8" }
    require(payload32.size == 32) { "payload must be 32 bytes" }

    val out = ByteArray(64)
    // uint8 is encoded as uint256; place it in the last byte of the first word.
    out[31] = kind.toByte()
    System.arraycopy(payload32, 0, out, 32, 32)
    return out
  }

  private fun u256Word(value: Long): ByteArray {
    val out = ByteArray(32)
    var v = value
    var i = 31
    while (i >= 0 && v != 0L) {
      out[i] = (v and 0xff).toByte()
      v = v ushr 8
      i--
    }
    return out
  }
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
