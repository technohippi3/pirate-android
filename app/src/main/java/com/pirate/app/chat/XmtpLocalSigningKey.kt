package com.pirate.app.chat

import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.xmtp.android.library.SignedData
import org.xmtp.android.library.SignerType
import org.xmtp.android.library.SigningKey
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.PublicIdentity
import uniffi.xmtpv3.ethereumHashPersonal

/**
 * XMTP SigningKey backed by a local secp256k1 key pair.
 * Signs messages locally without any network calls.
 */
internal class LocalSigningKey(
  private val keyPair: ECKeyPair,
  private val ethAddress: String,
) : SigningKey {

  override val publicIdentity: PublicIdentity
    get() = PublicIdentity(IdentityKind.ETHEREUM, ethAddress.lowercase())

  override val type: SignerType
    get() = SignerType.EOA

  override suspend fun sign(message: String): SignedData {
    val hash = ethereumHashPersonal(message)
    val sigData = Sign.signMessage(hash, keyPair, false)

    // Sign.signMessage returns SignatureData with r(32), s(32), v(1 byte, 27 or 28)
    val sigBytes = ByteArray(65)
    System.arraycopy(sigData.r, 0, sigBytes, 0, 32)
    System.arraycopy(sigData.s, 0, sigBytes, 32, 32)
    sigBytes[64] = ((sigData.v[0].toInt() - 27) and 1).toByte()

    return SignedData(sigBytes, ByteArray(0), ByteArray(0), ByteArray(0))
  }
}
