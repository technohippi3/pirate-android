package com.pirate.app.tempo

import android.app.Activity
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.jcajce.provider.digest.Keccak
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Manages ephemeral P256 session keys for silent transaction signing.
 *
 * Flow:
 * 1. Generate non-extractable P256 key in Android Keystore
 * 2. Passkey signs a KeyAuthorization to register the session key on AccountKeychain
 * 3. Session key signs subsequent txs silently (no biometric)
 */
object SessionKeyManager {

  private const val PREFS_NAME = "tempo_session"
  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val KEY_ALIAS_PREFIX = "tempo_session_p256_v2_"
  private const val P256_SIGNATURE_ALGO = "SHA256withECDSA"

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // Session key validity: 7 days
  private const val SESSION_DURATION_SECS = 7 * 24 * 60 * 60L

  data class SessionKey(
    val keystoreAlias: String,
    val publicKeyX: ByteArray, // 32 bytes
    val publicKeyY: ByteArray, // 32 bytes
    val address: String, // key_id = last20bytes(keccak256(pubX || pubY))
    val ownerAddress: String?, // Tempo account this key is authorized for
    val keyAuthorization: ByteArray?, // signed key authorization RLP blob
    val expiresAt: Long, // unix timestamp
    val preHash: Boolean = true,
  )

  /** Generate a new non-extractable P256 session key in Android Keystore. */
  fun generate(activity: Activity, ownerAddress: String? = null): SessionKey =
    generate(activity as Context, ownerAddress)

  fun generate(context: Context, ownerAddress: String? = null): SessionKey {
    val alias = "$KEY_ALIAS_PREFIX${UUID.randomUUID()}"
    val keyPair = generateKeystoreP256(alias)
    val pub = keyPair.public as? ECPublicKey
      ?: throw IllegalStateException("Generated keystore key is not ECPublicKey")

    val pubX = padTo32(bigIntToUnsigned(pub.w.affineX.toByteArray()))
    val pubY = padTo32(bigIntToUnsigned(pub.w.affineY.toByteArray()))

    // key_id = keccak256(pubX || pubY)[12:]
    val hash = Keccak.Digest256().digest(pubX + pubY)
    val address = "0x" + P256Utils.bytesToHex(hash.copyOfRange(12, 32))

    val expiresAt = System.currentTimeMillis() / 1000 + SESSION_DURATION_SECS

    return SessionKey(
      keystoreAlias = alias,
      publicKeyX = pubX,
      publicKeyY = pubY,
      address = address,
      ownerAddress = ownerAddress?.trim()?.lowercase()?.ifBlank { null },
      keyAuthorization = null,
      expiresAt = expiresAt,
      preHash = true,
    )
  }

  /**
   * Build the KeyAuthorization digest that the root passkey signs.
   *
   * digest = keccak256(rlp(authorization_tuple))
   * where authorization_tuple = [chain_id, key_type, key_id, expiry?]
   */
  fun buildKeyAuthDigest(sessionKey: SessionKey): ByteArray {
    return Keccak.Digest256().digest(rlpEncodeList(buildKeyAuthorizationTuple(sessionKey)))
  }

  /**
   * Build SignedKeyAuthorization bytes to include in tx.key_authorization.
   *
   * Spec shape:
   * rlp([
   *   [chain_id, key_type, key_id, expiry?, limits?],
   *   signature
   * ])
   *
   * where signature is a PrimitiveSignature blob.
   */
  fun buildSignedKeyAuthorization(
    sessionKey: SessionKey,
    assertion: TempoPasskeyManager.PasskeyAssertion,
  ): ByteArray {
    // WebAuthn primitive signature bytes (type 0x02)
    val webauthnData = assertion.authenticatorData + assertion.clientDataJSON
    val signature = byteArrayOf(0x02) +
      webauthnData +
      assertion.signatureR +
      assertion.signatureS +
      assertion.pubKey.x +
      assertion.pubKey.y

    val authorizationTuple = buildKeyAuthorizationTuple(sessionKey)
    return rlpEncodeList(listOf(authorizationTuple, signature))
  }

  /**
   * Build SignedKeyAuthorization bytes signed by an EOA root key.
   *
   * Tempo secp256k1 primitive signatures are raw 65-byte values:
   * r(32) || s(32) || v(1), where v is yParity (0/1).
   */
  fun buildSignedKeyAuthorizationSecp256k1(
    sessionKey: SessionKey,
    r: ByteArray,
    s: ByteArray,
    v: Byte,
  ): ByteArray {
    val signature = buildSecp256k1Signature(r = r, s = s, v = v)
    val authorizationTuple = buildKeyAuthorizationTuple(sessionKey)
    return rlpEncodeList(listOf(authorizationTuple, signature))
  }

  private fun buildKeyAuthorizationTuple(sessionKey: SessionKey): List<Any> {
    val tuple = mutableListOf<Any>(
      TempoClient.CHAIN_ID, // chain_id
      1L, // key_type: 1 = P256
      P256Utils.hexToBytes(sessionKey.address), // key_id
    )
    if (sessionKey.expiresAt > 0L) {
      tuple.add(sessionKey.expiresAt) // expiry
    }
    return tuple
  }

  /**
   * Build the Tempo V2 keychain payload hash for a pre-hashed payload.
   *
   * inner_hash = keccak256(0x04 || payload_hash || user_address)
   */
  fun buildSessionKeyV2Digest(
    payloadHash: ByteArray,
    userAddress: String,
  ): ByteArray {
    require(payloadHash.size == 32) { "Tempo session key payload hash must be 32 bytes" }
    return Keccak.Digest256().digest(byteArrayOf(0x04) + payloadHash + requireUserAddressBytes(userAddress))
  }

  /**
   * Wrap a primitive session-key signature in the Tempo V2 keychain envelope.
   *
   * envelope = 0x04 || user_address(20) || inner_signature
   */
  fun wrapKeychainSignatureV2(
    userAddress: String,
    innerSignature: ByteArray,
  ): ByteArray = byteArrayOf(0x04) + requireUserAddressBytes(userAddress) + innerSignature

  /**
   * Sign a pre-hashed Tempo payload with the non-extractable P256 session key.
   * Returns KeychainSignature: 0x04 || user_address(20) || inner_signature.
   */
  fun signWithSessionKey(
    sessionKey: SessionKey,
    userAddress: String,
    txHash: ByteArray,
  ): ByteArray {
    require(sessionKey.preHash) { "Only preHash=true session keys are supported" }
    val innerPayload = buildSessionKeyV2Digest(
      payloadHash = txHash,
      userAddress = userAddress,
    )
    val der = signP256Digest(
      alias = sessionKey.keystoreAlias,
      digest = innerPayload,
    )
    val (r, s) = derToFixedRs(der)

    // P256 primitive signature bytes:
    // 0x01 || r(32) || s(32) || pubX(32) || pubY(32) || pre_hash(1)
    val innerSig = byteArrayOf(0x01) +
      r +
      s +
      padTo32(sessionKey.publicKeyX) +
      padTo32(sessionKey.publicKeyY) +
      byteArrayOf(0x01)

    return wrapKeychainSignatureV2(
      userAddress = userAddress,
      innerSignature = innerSig,
    )
  }

  private fun requireUserAddressBytes(userAddress: String): ByteArray {
    val normalized = userAddress.trim().lowercase()
    require(Regex("^0x[a-f0-9]{40}$").matches(normalized)) {
      "Tempo user address must be a 20-byte hex address"
    }
    return P256Utils.hexToBytes(normalized)
  }

  /** Check if session key is still valid and keystore alias is present. */
  fun isValid(sessionKey: SessionKey?, ownerAddress: String? = null): Boolean {
    if (sessionKey == null) return false
    if (ownerAddress != null) {
      val owner = sessionKey.ownerAddress?.trim()?.lowercase().orEmpty()
      val expected = ownerAddress.trim().lowercase()
      if (owner.isBlank() || owner != expected) return false
    }
    if (sessionKey.keystoreAlias.isBlank() || !keystoreAliasExists(sessionKey.keystoreAlias)) {
      return false
    }
    return System.currentTimeMillis() / 1000 < sessionKey.expiresAt
  }

  // -- persistence --

  fun save(activity: Activity, sessionKey: SessionKey) {
    save(activity as Context, sessionKey)
  }

  fun save(context: Context, sessionKey: SessionKey) {
    prefs(context).edit()
      .putString("keystore_alias", sessionKey.keystoreAlias)
      .putString("public_key_x", P256Utils.bytesToHex(sessionKey.publicKeyX))
      .putString("public_key_y", P256Utils.bytesToHex(sessionKey.publicKeyY))
      .putString("address", sessionKey.address)
      .putString("owner_address", sessionKey.ownerAddress)
      .putString("key_authorization", sessionKey.keyAuthorization?.let { P256Utils.bytesToHex(it) })
      .putLong("expires_at", sessionKey.expiresAt)
      .putBoolean("pre_hash", sessionKey.preHash)
      .apply()
  }

  fun load(activity: Activity): SessionKey? = load(activity as Context)

  fun load(context: Context): SessionKey? {
    val p = prefs(context)
    val alias = p.getString("keystore_alias", null)
    if (alias.isNullOrBlank()) return null

    val pubXHex = p.getString("public_key_x", null) ?: return null
    val pubYHex = p.getString("public_key_y", null) ?: return null
    val address = p.getString("address", null) ?: return null
    val ownerAddress = p.getString("owner_address", null)
    val keyAuthorizationHex = p.getString("key_authorization", null)
    val expiresAt = p.getLong("expires_at", 0)
    val preHash = p.getBoolean("pre_hash", true)
    if (expiresAt == 0L) return null

    if (!keystoreAliasExists(alias)) {
      clear(context)
      return null
    }

    return SessionKey(
      keystoreAlias = alias,
      publicKeyX = P256Utils.hexToBytes(pubXHex),
      publicKeyY = P256Utils.hexToBytes(pubYHex),
      address = address,
      ownerAddress = ownerAddress?.trim()?.lowercase()?.ifBlank { null },
      keyAuthorization = keyAuthorizationHex?.let { P256Utils.hexToBytes(it) },
      expiresAt = expiresAt,
      preHash = preHash,
    )
  }

  fun clear(activity: Activity) = clear(activity as Context)

  fun clear(context: Context) {
    val alias = prefs(context).getString("keystore_alias", null)
    if (!alias.isNullOrBlank()) {
      runCatching { deleteKeystoreAlias(alias) }
    }
    prefs(context).edit().clear().apply()
  }

  /**
   * Best-effort cleanup for a generated-but-never-persisted alias.
   * If the alias is currently persisted, clear persisted metadata as well.
   */
  fun deleteAlias(activity: Activity, alias: String) = deleteAlias(activity as Context, alias)

  fun deleteAlias(context: Context, alias: String) {
    if (alias.isBlank()) return
    runCatching { deleteKeystoreAlias(alias) }
    val persistedAlias = prefs(context).getString("keystore_alias", null)
    if (persistedAlias == alias) {
      prefs(context).edit().clear().apply()
    }
  }

  // -- Keystore helpers --

  private fun generateKeystoreP256(alias: String): KeyPair {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val strongBoxResult = runCatching { createKeystoreP256(alias = alias, strongBox = true) }
      if (strongBoxResult.isSuccess) return strongBoxResult.getOrThrow()
    }
    return createKeystoreP256(alias = alias, strongBox = false)
  }

  private fun createKeystoreP256(alias: String, strongBox: Boolean): KeyPair {
    val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
    val specBuilder =
      KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
      )
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBox) {
      specBuilder.setIsStrongBoxBacked(true)
    }

    generator.initialize(specBuilder.build())
    return generator.generateKeyPair()
  }

  private fun signP256Digest(alias: String, digest: ByteArray): ByteArray {
    val keyStore = keyStore()
    val privateKey = keyStore.getKey(alias, null)
      ?: throw IllegalStateException("Missing keystore private key for alias=$alias")

    val signer = Signature.getInstance(P256_SIGNATURE_ALGO)
    signer.initSign(privateKey as java.security.PrivateKey)
    signer.update(digest)
    return signer.sign()
  }

  private fun keystoreAliasExists(alias: String): Boolean {
    return runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)
  }

  private fun deleteKeystoreAlias(alias: String) {
    val ks = keyStore()
    if (ks.containsAlias(alias)) {
      ks.deleteEntry(alias)
    }
  }

  private fun keyStore(): KeyStore =
    KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
}
