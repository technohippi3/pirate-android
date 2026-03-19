package sc.pirate.app.auth

import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager

data class PirateAuthUiState(
  val authServiceBaseUrl: String = "https://naga-dev-auth-service.getlit.dev",
  val passkeyRpId: String = TempoPasskeyManager.DEFAULT_RP_ID,
  val tempoRpcUrl: String = TempoClient.RPC_URL,
  val tempoFeePayerUrl: String = TempoClient.SPONSOR_URL,
  val tempoChainId: Long = TempoClient.CHAIN_ID,
  val tempoAddress: String? = null,
  val tempoCredentialId: String? = null,
  val tempoPubKeyX: String? = null,
  val tempoPubKeyY: String? = null,
  val tempoRpId: String = TempoPasskeyManager.DEFAULT_RP_ID,
  val signerType: SignerType? = null,
  val selfVerified: Boolean = false,
  val output: String = "Ready.",
  val busy: Boolean = false,
) {
  enum class SignerType {
    PASSKEY,
  }

  fun hasTempoCredentials(): Boolean {
    val address = !tempoAddress.isNullOrBlank()
    if (!address) return false

    val hasPasskeyFields = !tempoCredentialId.isNullOrBlank() &&
      !tempoPubKeyX.isNullOrBlank() &&
      !tempoPubKeyY.isNullOrBlank()

    return hasPasskeyFields
  }

  fun hasAnyCredentials(): Boolean {
    return hasTempoCredentials()
  }

  fun activeAddress(): String? {
    return tempoAddress?.takeIf { it.isNotBlank() }
  }

  /** Build 65-byte uncompressed P256 public key (0x04 || x || y) from stored hex coords. */
  fun tempoP256PubKeyUncompressed(): ByteArray? {
    val xHex = tempoPubKeyX?.takeIf { it.isNotBlank() } ?: return null
    val yHex = tempoPubKeyY?.takeIf { it.isNotBlank() } ?: return null
    val x = hexToBytes(xHex)
    val y = hexToBytes(yHex)
    if (x.size != 32 || y.size != 32) return null
    return byteArrayOf(0x04) + x + y
  }

  private fun hexToBytes(hex: String): ByteArray {
    val h = hex.removePrefix("0x").removePrefix("0X")
    if (h.length % 2 != 0) return ByteArray(0)
    return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
  }

  companion object {
    private const val PREFS_NAME = "pirate_auth"

    fun save(context: android.content.Context, state: PirateAuthUiState) {
      context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit()
        .putString("tempoAddress", state.tempoAddress)
        .putString("tempoCredentialId", state.tempoCredentialId)
        .putString("tempoPubKeyX", state.tempoPubKeyX)
        .putString("tempoPubKeyY", state.tempoPubKeyY)
        .putString("tempoRpId", state.tempoRpId)
        .putString("signerType", state.signerType?.name)
        .putString("tempoFeePayerUrl", state.tempoFeePayerUrl)
        .putBoolean("selfVerified", state.selfVerified)
        .apply()
    }

    fun load(context: android.content.Context): PirateAuthUiState {
      val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
      val signerType =
        prefs.getString("signerType", null)?.let { raw ->
          runCatching { SignerType.valueOf(raw) }.getOrNull()
        }
      return PirateAuthUiState(
        tempoAddress = prefs.getString("tempoAddress", null),
        tempoCredentialId = prefs.getString("tempoCredentialId", null),
        tempoPubKeyX = prefs.getString("tempoPubKeyX", null),
        tempoPubKeyY = prefs.getString("tempoPubKeyY", null),
        tempoRpId = prefs.getString("tempoRpId", null) ?: TempoPasskeyManager.DEFAULT_RP_ID,
        signerType = signerType,
        tempoFeePayerUrl = prefs.getString("tempoFeePayerUrl", null) ?: TempoClient.SPONSOR_URL,
        selfVerified = prefs.getBoolean("selfVerified", false),
      )
    }

    fun clear(context: android.content.Context) {
      context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }
  }
}
