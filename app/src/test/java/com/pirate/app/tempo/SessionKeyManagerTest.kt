package com.pirate.app.tempo

import org.bouncycastle.jcajce.provider.digest.Keccak
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionKeyManagerTest {

  private fun hexToBytes(hex: String): ByteArray {
    val normalized = hex.removePrefix("0x")
    return ByteArray(normalized.length / 2) { index ->
      normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
  }

  @Test
  fun buildTempoTransactionSessionKeyV2Digest_matchesSpec() {
    val txHash = hexToBytes("0x${"11".repeat(32)}")
    val userAddress = "0x${"22".repeat(20)}"
    val expected = Keccak.Digest256().digest(byteArrayOf(0x04) + txHash + hexToBytes(userAddress))

    val actual = SessionKeyManager.buildSessionKeyV2Digest(
      payloadHash = txHash,
      userAddress = userAddress,
    )

    assertArrayEquals(expected, actual)
    assertFalse(actual.contentEquals(txHash))
  }

  @Test
  fun wrapKeychainSignatureV2_usesType04Envelope() {
    val userAddress = "0x${"ab".repeat(20)}"
    val innerSignature = byteArrayOf(0x01) + ByteArray(129) { index -> (index and 0xFF).toByte() }

    val wrapped = SessionKeyManager.wrapKeychainSignatureV2(
      userAddress = userAddress,
      innerSignature = innerSignature,
    )

    assertEquals(0x04.toByte(), wrapped.first())
    assertArrayEquals(hexToBytes(userAddress), wrapped.copyOfRange(1, 21))
    assertArrayEquals(innerSignature, wrapped.copyOfRange(21, wrapped.size))
  }
}
