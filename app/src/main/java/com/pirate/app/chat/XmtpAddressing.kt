package com.pirate.app.chat

import android.content.Context
import android.util.Log
import com.pirate.app.security.LocalSecp256k1Store

private const val TAG = "XmtpAddressing"

internal fun getOrCreateLocalSigner(
  appContext: Context,
  address: String,
): LocalSigningKey {
  val identity = LocalSecp256k1Store.getOrCreateIdentity(appContext, address)
  Log.d(TAG, "XMTP local signer: userAddress=$address xmtpAddress=${identity.signerAddress}")
  return LocalSigningKey(identity.keyPair, identity.signerAddress)
}

internal fun normalizeEthAddressOrNull(value: String): String? =
  runCatching { normalizeEthAddress(value) }.getOrNull()

internal fun normalizeEthAddress(value: String): String {
  val trimmed = value.trim()
  val withPrefix =
    if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) trimmed else "0x$trimmed"
  val lower = withPrefix.lowercase()
  require(lower.length == 42) { "Invalid Ethereum address length: $value" }
  require(lower.startsWith("0x")) { "Invalid Ethereum address: $value" }
  for (i in 2 until lower.length) {
    val c = lower[i]
    if (!(c in '0'..'9' || c in 'a'..'f')) {
      throw IllegalArgumentException("Invalid Ethereum address: $value")
    }
  }
  return lower
}
