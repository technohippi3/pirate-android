package com.pirate.app.tempo

object TempoAccountFactory {
  fun fromSession(
    tempoAddress: String?,
    tempoCredentialId: String?,
    tempoPubKeyX: String?,
    tempoPubKeyY: String?,
    tempoRpId: String,
  ): TempoPasskeyManager.PasskeyAccount? {
    val addr = tempoAddress?.trim().orEmpty()
    val cred = tempoCredentialId?.trim().orEmpty()
    val xHex = tempoPubKeyX?.trim().orEmpty()
    val yHex = tempoPubKeyY?.trim().orEmpty()
    if (addr.isBlank() || cred.isBlank() || xHex.isBlank() || yHex.isBlank()) return null

    return runCatching {
      TempoPasskeyManager.PasskeyAccount(
        pubKey = P256Utils.P256PublicKey(P256Utils.hexToBytes(xHex), P256Utils.hexToBytes(yHex)),
        address = addr,
        credentialId = cred,
        rpId = tempoRpId.ifBlank { TempoPasskeyManager.DEFAULT_RP_ID },
      )
    }.getOrNull()
  }
}
