package sc.pirate.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PurchaseEnvelopeCryptoTest {
  private fun aesGcmEncrypt(
    key: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
  ): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return cipher.doFinal(plaintext)
  }

  @Test
  fun unwrapAndDecrypt_roundTripWorks() {
    val (privateKey, publicKey) = EciesContentCrypto.generateKeyPair()
    val dek = ByteArray(32) { index -> ((index * 7) and 0xFF).toByte() }
    val wrapped = EciesContentCrypto.eciesEncrypt(publicKey, dek)
    val envelope =
      PurchaseEnvelopeCrypto.PurchaseEnvelope(
        ephemeralPub = wrapped.ephemeralPub,
        iv = wrapped.iv,
        ciphertext = wrapped.ciphertext,
      )

    val unwrappedDek = PurchaseEnvelopeCrypto.unwrapDekFromEnvelope(privateKey, envelope)
    assertArrayEquals(dek, unwrappedDek)

    val plaintext = "pirate-android-purchase-envelope".toByteArray(Charsets.UTF_8)
    val artifactIv = ByteArray(12) { index -> index.toByte() }
    val artifactCiphertext = aesGcmEncrypt(unwrappedDek, artifactIv, plaintext)
    val ivPrependedArtifact = artifactIv + artifactCiphertext

    val decrypted = PurchaseEnvelopeCrypto.decryptIvPrependedArtifact(unwrappedDek, ivPrependedArtifact, 12)
    assertArrayEquals(plaintext, decrypted)
  }
}
