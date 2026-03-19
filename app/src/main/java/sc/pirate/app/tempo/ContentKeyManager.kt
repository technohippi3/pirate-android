package sc.pirate.app.tempo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages a persistent P256 keypair for ECIES content encryption/decryption.
 *
 * WebAuthn passkeys don't expose the private key, so we generate a separate
 * P256 keypair at first-upload time and store it in SharedPreferences.
 * The public key should be published on-chain (RecordsV1 "contentPubKey") so
 * others can ECIES-encrypt AES keys to it when sharing content.
 *
 * Also stores per-contentId wrapped AES keys (ECIES envelopes) so we can
 * decrypt content we've uploaded or received.
 */
object ContentKeyManager {

    private const val PREFS_NAME = "heaven_content_key"
    private const val WRAPPED_KEYS_PREFS = "heaven_wrapped_keys"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "heaven_content_privkey_v1"
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    data class ContentKeyPair(
        val privateKey: ByteArray, // 32 bytes
        val publicKey: ByteArray,  // 65 bytes (0x04 || x || y)
    )

    /** Get or generate the content encryption keypair. */
    fun getOrCreate(context: Context): ContentKeyPair {
        load(context)?.let { return it }
        val (priv, pub) = EciesContentCrypto.generateKeyPair()
        val kp = ContentKeyPair(privateKey = priv, publicKey = pub)
        save(context, kp)
        return kp
    }

    fun load(context: Context): ContentKeyPair? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedPriv = prefs.getString("private_key", null) ?: return null
        val privHex = decryptPrivateKeyHex(storedPriv)
        val pubHex = prefs.getString("public_key", null) ?: return null
        return ContentKeyPair(
            privateKey = P256Utils.hexToBytes(privHex),
            publicKey = P256Utils.hexToBytes(pubHex),
        )
    }

    private fun save(context: Context, kp: ContentKeyPair) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("private_key", encryptPrivateKeyHex(P256Utils.bytesToHex(kp.privateKey)))
            .putString("public_key", P256Utils.bytesToHex(kp.publicKey))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(WRAPPED_KEYS_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // -- Per-contentId wrapped key storage --

    /** Store an ECIES envelope (wrapped AES key) for a contentId. */
    fun saveWrappedKey(context: Context, contentId: String, envelope: EciesContentCrypto.EciesEnvelope) {
        val key = contentId.removePrefix("0x").trim().lowercase()
        val value = P256Utils.bytesToHex(envelope.ephemeralPub) + ":" +
            P256Utils.bytesToHex(envelope.iv) + ":" +
            P256Utils.bytesToHex(envelope.ciphertext)
        context.getSharedPreferences(WRAPPED_KEYS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, value)
            .apply()
    }

    /** Load an ECIES envelope for a contentId, or null if not found. */
    fun loadWrappedKey(context: Context, contentId: String): EciesContentCrypto.EciesEnvelope? {
        val key = contentId.removePrefix("0x").trim().lowercase()
        val value = context.getSharedPreferences(WRAPPED_KEYS_PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        val parts = value.split(":")
        if (parts.size != 3) return null
        return EciesContentCrypto.EciesEnvelope(
            ephemeralPub = P256Utils.hexToBytes(parts[0]),
            iv = P256Utils.hexToBytes(parts[1]),
            ciphertext = P256Utils.hexToBytes(parts[2]),
        )
    }

    private fun encryptPrivateKeyHex(privateKeyHex: String): String {
        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val ciphertext = cipher.doFinal(privateKeyHex.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$iv:$payload"
    }

    private fun decryptPrivateKeyHex(encoded: String): String {
        val parts = encoded.split(":", limit = 2)
        require(parts.size == 2) { "Invalid encrypted content key format" }
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
