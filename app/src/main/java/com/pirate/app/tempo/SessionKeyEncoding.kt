package com.pirate.app.tempo

import java.io.ByteArrayOutputStream
import java.math.BigInteger

private val P256_ORDER = BigInteger(
  "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
  16,
)
private val P256_HALF_ORDER = P256_ORDER.shiftRight(1)

private data class RawRlp(val encoded: ByteArray)

internal fun derToFixedRs(der: ByteArray, size: Int = 32): Pair<ByteArray, ByteArray> {
  fun readLen(start: Int): Pair<Int, Int> {
    var index = start
    val head = der[index++].toInt() and 0xFF
    if (head and 0x80 == 0) return head to index
    val count = head and 0x7F
    var len = 0
    repeat(count) {
      len = (len shl 8) or (der[index++].toInt() and 0xFF)
    }
    return len to index
  }

  var index = 0
  require(der[index++] == 0x30.toByte()) { "Invalid DER sequence" }
  val (_, afterSeqLen) = readLen(index)
  index = afterSeqLen

  require(der[index++] == 0x02.toByte()) { "Invalid DER integer (r)" }
  val (rLen, afterRLen) = readLen(index)
  index = afterRLen
  val r = der.copyOfRange(index, index + rLen)
  index += rLen

  require(der[index++] == 0x02.toByte()) { "Invalid DER integer (s)" }
  val (sLen, afterSLen) = readLen(index)
  index = afterSLen
  val s = der.copyOfRange(index, index + sLen)

  fun toFixed(input: ByteArray): ByteArray {
    val stripped = bigIntToUnsigned(input)
    return when {
      stripped.size == size -> stripped
      stripped.size > size -> stripped.copyOfRange(stripped.size - size, stripped.size)
      else -> ByteArray(size - stripped.size) + stripped
    }
  }

  val rFixed = toFixed(r)
  val sFixed = normalizeP256LowS(toFixed(s))
  return rFixed to sFixed
}

internal fun bigIntToUnsigned(bytes: ByteArray): ByteArray {
  if (bytes.isEmpty()) return byteArrayOf(0)
  var start = 0
  while (start < bytes.lastIndex && bytes[start] == 0.toByte()) {
    start += 1
  }
  return bytes.copyOfRange(start, bytes.size)
}

private fun normalizeP256LowS(s: ByteArray): ByteArray {
  val sValue = BigInteger(1, s)
  val normalized = if (sValue > P256_HALF_ORDER) P256_ORDER.subtract(sValue) else sValue
  val unsigned = bigIntToUnsigned(normalized.toByteArray())
  return if (unsigned.size >= 32) {
    unsigned.copyOfRange(unsigned.size - 32, unsigned.size)
  } else {
    ByteArray(32 - unsigned.size) + unsigned
  }
}

internal fun rlpEncodeList(items: List<Any>): ByteArray {
  val buffer = ByteArrayOutputStream()
  for (item in items) buffer.write(rlpEncode(item))
  val payload = buffer.toByteArray()
  return rlpLengthPrefix(payload, 0xc0)
}

@Suppress("UNCHECKED_CAST")
private fun rlpEncode(item: Any): ByteArray =
  when (item) {
    is RawRlp -> item.encoded
    is ByteArray -> rlpEncodeBytes(item)
    is Long -> rlpEncodeLong(item)
    is Int -> rlpEncodeLong(item.toLong())
    is Byte -> rlpEncodeBytes(byteArrayOf(item))
    is List<*> -> rlpEncodeList(item as List<Any>)
    else -> throw IllegalArgumentException("unsupported RLP type: ${item::class}")
  }

private fun rlpEncodeBytes(bytes: ByteArray): ByteArray {
  if (bytes.size == 1 && bytes[0].toInt() and 0xFF < 0x80) return bytes
  return rlpLengthPrefix(bytes, 0x80)
}

private fun rlpEncodeLong(value: Long): ByteArray {
  if (value == 0L) return byteArrayOf(0x80.toByte())
  if (value < 128) return byteArrayOf(value.toByte())
  return rlpLengthPrefix(longToMinimalBytes(value), 0x80)
}

private fun longToMinimalBytes(value: Long): ByteArray {
  if (value == 0L) return ByteArray(0)
  var v = value
  val buffer = ByteArrayOutputStream()
  while (v > 0) {
    buffer.write((v and 0xFF).toInt())
    v = v shr 8
  }
  return buffer.toByteArray().reversedArray()
}

private fun rlpLengthPrefix(payload: ByteArray, offset: Int): ByteArray {
  return if (payload.size < 56) {
    byteArrayOf((offset + payload.size).toByte()) + payload
  } else {
    val lenBytes = longToMinimalBytes(payload.size.toLong())
    byteArrayOf((offset + 55 + lenBytes.size).toByte()) + lenBytes + payload
  }
}

internal fun padTo32(bytes: ByteArray): ByteArray {
  if (bytes.size >= 32) return bytes.copyOfRange(bytes.size - 32, bytes.size)
  return ByteArray(32 - bytes.size) + bytes
}

internal fun buildSecp256k1Signature(
  r: ByteArray,
  s: ByteArray,
  v: Byte,
): ByteArray {
  val r32 = normalizeScalar32(r)
  val s32 = normalizeScalar32(s)
  val vNorm = normalizeV(v)
  return r32 + s32 + byteArrayOf(vNorm)
}

private fun normalizeScalar32(input: ByteArray): ByteArray {
  require(input.isNotEmpty()) { "secp256k1 scalar is empty" }
  val unsigned = bigIntToUnsigned(input)
  require(unsigned.size <= 32) { "secp256k1 scalar exceeds 32 bytes" }
  return if (unsigned.size == 32) unsigned else ByteArray(32 - unsigned.size) + unsigned
}

private fun normalizeV(v: Byte): Byte {
  val asInt = v.toInt() and 0xFF
  return when (asInt) {
    0, 1 -> asInt.toByte()
    27, 28 -> (asInt - 27).toByte()
    in 35..Int.MAX_VALUE -> ((asInt - 35) % 2).toByte()
    else -> throw IllegalArgumentException("invalid secp256k1 v: $asInt")
  }
}
