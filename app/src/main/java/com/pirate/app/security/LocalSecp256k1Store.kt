package com.pirate.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.math.BigInteger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local secp256k1 identity store keyed by user address.
 * Private keys are encrypted with an Android Keystore AES key before persistence.
 */
object LocalSecp256k1Store {
  private const val PREFS_NAME = "xmtp_prefs"
  private const val KEY_PREFIX = "xmtp_identity_key:"

  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val KEY_ALIAS = "pirate_local_secp256k1_v1"
  private const val CIPHER_ALGO = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128

  data class Identity(
    val keyPair: ECKeyPair,
    val signerAddress: String,
  )

  fun getOrCreateIdentity(context: Context, userAddress: String): Identity {
    val keyPair = getOrCreateKeyPair(context, userAddress)
    val signerAddress = ("0x" + Keys.getAddress(keyPair)).lowercase()
    return Identity(keyPair = keyPair, signerAddress = signerAddress)
  }

  private fun getOrCreateKeyPair(context: Context, userAddress: String): ECKeyPair {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val storageKey = "$KEY_PREFIX${userAddress.lowercase()}"
    val stored = prefs.getString(storageKey, null)

    if (!stored.isNullOrBlank()) {
      val privateKeyHex = decryptPrivateKeyHex(stored)
      return ECKeyPair.create(BigInteger(privateKeyHex, 16))
    }

    val keyPair = Keys.createEcKeyPair()
    val privateKeyHex = keyPair.privateKey.toString(16)
    prefs.edit().putString(storageKey, encryptPrivateKeyHex(privateKeyHex)).apply()
    return keyPair
  }

  private fun encryptPrivateKeyHex(privateKeyHex: String): String {
    val cipher = Cipher.getInstance(CIPHER_ALGO)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
    val cipherText = cipher.doFinal(privateKeyHex.toByteArray(Charsets.UTF_8))
    val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    val payload = Base64.encodeToString(cipherText, Base64.NO_WRAP)
    return "$iv:$payload"
  }

  private fun decryptPrivateKeyHex(encoded: String): String {
    val parts = encoded.split(":", limit = 2)
    require(parts.size == 2) { "Invalid encrypted key format" }

    val iv = Base64.decode(parts[0], Base64.NO_WRAP)
    val payload = Base64.decode(parts[1], Base64.NO_WRAP)
    val cipher = Cipher.getInstance(CIPHER_ALGO)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    val plain = cipher.doFinal(payload)
    return String(plain, Charsets.UTF_8)
  }

  private fun getOrCreateAesKey(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
    keyGenerator.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return keyGenerator.generateKey()
  }
}
