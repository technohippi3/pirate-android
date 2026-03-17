package com.pirate.app.music

import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8

internal fun fetchTrackMetaFromScrobbleV4(trackIds: List<String>): Map<String, TrackMeta> {
  val out = HashMap<String, TrackMeta>(trackIds.size)
  if (trackIds.isEmpty()) return out

  val functionOutputsV4 =
    listOf(
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Uint8>() {},
      object : TypeReference<Bytes32>() {},
      object : TypeReference<Uint64>() {},
      object : TypeReference<Utf8String>() {},
      object : TypeReference<Uint32>() {},
    )

  for (raw in trackIds) {
    val id = raw.trim().lowercase()
    if (!id.matches(Regex("^0x[0-9a-f]{64}$"))) continue
    val bytes = runCatching { sharedHexToBytes(id) }.getOrNull() ?: continue
    if (bytes.size != 32) continue
    val function =
      Function(
        "getTrack",
        listOf(Bytes32(bytes)),
        functionOutputsV4,
      )
    val data = FunctionEncoder.encode(function)
    val result = runCatching { ethCall(SHARED_WITH_YOU_SCROBBLE_V4, data) }.getOrNull().orEmpty()
    if (!result.startsWith("0x") || result.length <= 2) continue

    val decoded = runCatching { FunctionReturnDecoder.decode(result, function.outputParameters) }.getOrNull() ?: continue
    if (decoded.size < 8) continue

    val title = (decoded[0] as? Utf8String)?.value?.trim().orEmpty()
    val artist = (decoded[1] as? Utf8String)?.value?.trim().orEmpty()
    val album = (decoded[2] as? Utf8String)?.value?.trim().orEmpty()
    val payload = (decoded[4] as? Bytes32)?.value
    val coverCid = normalizeSharedNullableString((decoded[6] as? Utf8String)?.value)
    val durationSec = (decoded[7] as? Uint32)?.value?.toInt() ?: 0
    val metaHash = payload?.let { "0x" + it.joinToString("") { b -> "%02x".format(b) } }

    out[id] =
      TrackMeta(
        id = id,
        title = title.ifBlank { id.take(14) },
        artist = artist.ifBlank { "Unknown Artist" },
        album = album,
        coverCid = coverCid,
        durationSec = durationSec,
        metaHash = metaHash,
      )
  }
  return out
}

internal fun fetchTrackRegistrationStatusFromScrobbleV4(trackIds: List<String>): Map<String, Boolean> {
  val out = HashMap<String, Boolean>(trackIds.size)
  if (trackIds.isEmpty()) return out

  val outputs = listOf(object : TypeReference<Bool>() {})
  for (raw in trackIds) {
    val id = raw.trim().lowercase()
    if (!id.matches(Regex("^0x[0-9a-f]{64}$"))) continue
    val bytes = runCatching { sharedHexToBytes(id) }.getOrNull() ?: continue
    if (bytes.size != 32) continue

    val function = Function("isRegistered", listOf(Bytes32(bytes)), outputs)
    val data = FunctionEncoder.encode(function)
    val result = runCatching { ethCall(SHARED_WITH_YOU_SCROBBLE_V4, data) }.getOrNull().orEmpty()
    if (!result.startsWith("0x") || result.length <= 2) continue

    val decoded = runCatching { FunctionReturnDecoder.decode(result, function.outputParameters) }.getOrNull() ?: continue
    val registered = (decoded.getOrNull(0) as? Bool)?.value ?: continue
    out[id] = registered
  }

  return out
}
