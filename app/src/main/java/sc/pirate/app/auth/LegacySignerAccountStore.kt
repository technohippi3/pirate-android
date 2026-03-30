package sc.pirate.app.auth

import android.content.Context
import sc.pirate.app.tempo.TempoAccountFactory
import sc.pirate.app.tempo.TempoPasskeyManager

internal data class LegacySignerAccountSnapshot(
  val address: String,
  val credentialId: String,
  val pubKeyX: String,
  val pubKeyY: String,
  val rpId: String,
)

internal object LegacySignerAccountStore {
  private const val PREFS_NAME = "pirate_legacy_tempo_account"
  private const val LEGACY_AUTH_PREFS_NAME = "pirate_auth"

  fun save(
    context: Context,
    account: TempoPasskeyManager.PasskeyAccount,
  ) {
    save(
      context = context,
      snapshot =
        LegacySignerAccountSnapshot(
          address = account.address,
          credentialId = account.credentialId,
          pubKeyX = account.pubKey.xHex,
          pubKeyY = account.pubKey.yHex,
          rpId = account.rpId,
        ),
    )
  }

  fun save(
    context: Context,
    snapshot: LegacySignerAccountSnapshot,
  ) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString("address", snapshot.address)
      .putString("credentialId", snapshot.credentialId)
      .putString("pubKeyX", snapshot.pubKeyX)
      .putString("pubKeyY", snapshot.pubKeyY)
      .putString("rpId", snapshot.rpId)
      .apply()
  }

  fun load(context: Context): LegacySignerAccountSnapshot? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val address = prefs.getString("address", null)?.trim().orEmpty()
    val credentialId = prefs.getString("credentialId", null)?.trim().orEmpty()
    val pubKeyX = prefs.getString("pubKeyX", null)?.trim().orEmpty()
    val pubKeyY = prefs.getString("pubKeyY", null)?.trim().orEmpty()
    if (
      address.isBlank() ||
        credentialId.isBlank() ||
        pubKeyX.isBlank() ||
        pubKeyY.isBlank()
    ) {
      return null
    }
    return LegacySignerAccountSnapshot(
      address = address,
      credentialId = credentialId,
      pubKeyX = pubKeyX,
      pubKeyY = pubKeyY,
      rpId =
        prefs.getString("rpId", null)?.takeIf { it.isNotBlank() }
          ?: PiratePasskeyDefaults.DEFAULT_RP_ID,
    )
  }

  fun loadAccount(
    context: Context,
    ownerAddress: String? = null,
  ): TempoPasskeyManager.PasskeyAccount? {
    val snapshot = load(context) ?: return null
    val expectedOwner = ownerAddress?.trim()
    if (!expectedOwner.isNullOrBlank() && !snapshot.address.equals(expectedOwner, ignoreCase = true)) {
      return null
    }
    return TempoAccountFactory.fromSession(
      tempoAddress = snapshot.address,
      tempoCredentialId = snapshot.credentialId,
      tempoPubKeyX = snapshot.pubKeyX,
      tempoPubKeyY = snapshot.pubKeyY,
      tempoRpId = snapshot.rpId,
    )
  }

  fun clear(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
  }

  fun migrateFromLegacyAuthPrefs(context: Context) {
    val legacyPrefs = context.getSharedPreferences(LEGACY_AUTH_PREFS_NAME, Context.MODE_PRIVATE)
    val hasLegacyPrefs =
      listOf(
        "tempoAddress",
        "tempoCredentialId",
        "tempoPubKeyX",
        "tempoPubKeyY",
        "tempoRpId",
        "tempoRpcUrl",
        "tempoFeePayerUrl",
        "tempoChainId",
        "signerType",
      ).any(legacyPrefs::contains)
    if (!hasLegacyPrefs) return

    val legacyAddress = legacyPrefs.getString("tempoAddress", null)?.trim().orEmpty()
    val legacyCredentialId = legacyPrefs.getString("tempoCredentialId", null)?.trim().orEmpty()
    val legacyPubKeyX = legacyPrefs.getString("tempoPubKeyX", null)?.trim().orEmpty()
    val legacyPubKeyY = legacyPrefs.getString("tempoPubKeyY", null)?.trim().orEmpty()
    val legacyRpId =
      legacyPrefs.getString("tempoRpId", null)?.takeIf { it.isNotBlank() }
        ?: PiratePasskeyDefaults.DEFAULT_RP_ID

    val hasLegacySignerFields =
      legacyAddress.isNotBlank() &&
        legacyCredentialId.isNotBlank() &&
        legacyPubKeyX.isNotBlank() &&
        legacyPubKeyY.isNotBlank()

    val editor =
      legacyPrefs.edit()
        .putString(
          "walletAddress",
          legacyPrefs.getString("walletAddress", null)?.takeIf { it.isNotBlank() } ?: legacyAddress.takeIf { it.isNotBlank() },
        )
        .putString(
          "authWalletAddress",
          legacyPrefs.getString("authWalletAddress", null)?.takeIf { it.isNotBlank() } ?: legacyAddress.takeIf { it.isNotBlank() },
        )
      .putString(
        "passkeyRpId",
        legacyPrefs.getString("passkeyRpId", null)?.takeIf { it.isNotBlank() } ?: legacyRpId,
      )

    if (hasLegacySignerFields) {
      save(
        context = context,
        snapshot =
          LegacySignerAccountSnapshot(
            address = legacyAddress,
            credentialId = legacyCredentialId,
            pubKeyX = legacyPubKeyX,
            pubKeyY = legacyPubKeyY,
            rpId = legacyRpId,
          ),
      )
    }

    editor
      .remove("tempoAddress")
      .remove("tempoCredentialId")
      .remove("tempoPubKeyX")
      .remove("tempoPubKeyY")
      .remove("tempoRpId")
      .remove("tempoRpcUrl")
      .remove("tempoFeePayerUrl")
      .remove("tempoChainId")
      .remove("signerType")
      .apply()
  }
}
