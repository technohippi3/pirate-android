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
 * Keystore-backed cache for the WebAuthn PRF root seed.
 *
 * The 32-byte root seed derived from the passkey PRF extension is stored
 * in SharedPreferences, encrypted with an AES-256-GCM key held in the
 * Android Keystore. This means the seed is device-bound but survives
 * app restarts — the user only has to assert the passkey once per device.
 *
 * Cache key: the credential ID (base64url or hex, trimmed to 128 chars).
 */
object PrfRootSeedStore {

    private const val PREFS_NAME = "pirate_prf_root_seed"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "pirate_prf_root_seed_v1"
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** Encrypt and cache the 32-byte root seed for the given credential. */
    fun store(context: Context, credentialId: String, rootSeed: ByteArray) {
        require(rootSeed.size == 32) { "root seed must be 32 bytes" }
        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(rootSeed)
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(prefsKey(credentialId), encoded)
            .apply()
    }

    /** Load the cached root seed for the given credential, or null if not present. */
    fun load(context: Context, credentialId: String): ByteArray? {
        val encoded = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(prefsKey(credentialId), null) ?: return null
        val parts = encoded.split(":", limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val result = cipher.doFinal(ciphertext)
            if (result.size == 32) result else null
        }.getOrNull()
    }

    /** Wipe all cached seeds (e.g. on sign-out). */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun prefsKey(credentialId: String): String =
        credentialId.trim().take(128)

    private fun getOrCreateKey(): SecretKey {
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
