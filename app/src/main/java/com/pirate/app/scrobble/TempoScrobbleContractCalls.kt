package com.pirate.app.scrobble

import com.pirate.app.tempo.P256Utils
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8

internal fun encodeScrobbleBatch(
  user: String,
  trackIds: List<String>,
  timestamps: List<Long>,
): String {
  val function =
    Function(
      "scrobbleBatch",
      listOf(
        Address(user),
        DynamicArray(Bytes32::class.java, trackIds.map { id -> Bytes32(P256Utils.hexToBytes(id)) }),
        DynamicArray(Uint64::class.java, timestamps.map { ts -> Uint64(ts.coerceAtLeast(0L)) }),
      ),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal fun encodeRegisterAndScrobbleBatch(
  user: String,
  kind: Int,
  payloadBytes32: ByteArray,
  title: String,
  artist: String,
  album: String,
  durationSec: Int,
  trackId: String,
  timestamp: Long,
): String {
  val function =
    Function(
      "registerAndScrobbleBatch",
      listOf(
        Address(user),
        DynamicArray(Uint8::class.java, listOf(Uint8(kind.toLong().coerceAtLeast(0L)))),
        DynamicArray(Bytes32::class.java, listOf(Bytes32(payloadBytes32))),
        DynamicArray(Utf8String::class.java, listOf(Utf8String(title))),
        DynamicArray(Utf8String::class.java, listOf(Utf8String(artist))),
        DynamicArray(Utf8String::class.java, listOf(Utf8String(album))),
        DynamicArray(Uint32::class.java, listOf(Uint32(durationSec.coerceAtLeast(0).toLong()))),
        DynamicArray(Bytes32::class.java, listOf(Bytes32(P256Utils.hexToBytes(trackId)))),
        DynamicArray(Uint64::class.java, listOf(Uint64(timestamp.coerceAtLeast(0L)))),
      ),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal fun encodeRegisterTracksForUser(
  user: String,
  kind: Int,
  payloadBytes32: ByteArray,
  title: String,
  artist: String,
  album: String,
  durationSec: Int,
): String {
  val function =
    Function(
      "registerTracksForUser",
      listOf(
        Address(user),
        DynamicArray(Uint8::class.java, listOf(Uint8(kind.toLong().coerceAtLeast(0L)))),
        DynamicArray(Bytes32::class.java, listOf(Bytes32(payloadBytes32))),
        DynamicArray(Utf8String::class.java, listOf(Utf8String(title))),
        DynamicArray(Utf8String::class.java, listOf(Utf8String(artist))),
        DynamicArray(Utf8String::class.java, listOf(Utf8String(album))),
        DynamicArray(Uint32::class.java, listOf(Uint32(durationSec.coerceAtLeast(0).toLong()))),
      ),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal fun isTrackRegistered(trackId: String): Boolean {
  val callData =
    FunctionEncoder.encode(
      Function(
        "isRegistered",
        listOf(Bytes32(P256Utils.hexToBytes(trackId))),
        emptyList(),
      ),
    )
  val result = ethCall(TempoScrobbleApi.SCROBBLE_V4, callData)
  val clean = result.removePrefix("0x").ifBlank { "0" }
  return clean.toBigIntegerOrNull(16)?.let { it != java.math.BigInteger.ZERO } ?: false
}
