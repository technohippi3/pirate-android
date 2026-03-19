package sc.pirate.app.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseId3TaggerTest {
  @Test
  fun injectPurchaseProvenanceId3_prependsId3ForRawMpegData() {
    val rawMp3 = byteArrayOf(
      0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte(),
      0x00, 0x00, 0x00, 0x00,
    )

    val tagged = injectPurchaseProvenanceId3(
      audioBytes = rawMp3,
      buyerWallet = "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      purchaseId = "purchase_123",
    )

    assertEquals('I'.code.toByte(), tagged[0])
    assertEquals('D'.code.toByte(), tagged[1])
    assertEquals('3'.code.toByte(), tagged[2])

    val taggedAscii = String(tagged, Charsets.ISO_8859_1)
    assertTrue(taggedAscii.contains("buyer_wallet"))
    assertTrue(taggedAscii.contains("purchase_id"))
    assertTrue(taggedAscii.contains("purchase_receipt"))
    assertTrue(taggedAscii.contains("purchase_123"))
  }

  @Test
  fun injectPurchaseProvenanceId3_appendsToExistingId3Payload() {
    val existingPayload = byteArrayOf(
      0x54, 0x49, 0x54, 0x32, // TIT2
      0x00, 0x00, 0x00, 0x01, // size
      0x00, 0x00, // flags
      0x00, // body
    )
    val header = byteArrayOf(
      'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
      0x03, 0x00, // v2.3
      0x00, // flags
      0x00, 0x00, 0x00, existingPayload.size.toByte(), // syncsafe tag size for 11 bytes
    )
    val tail = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
    val input = ByteArray(header.size + existingPayload.size + tail.size)
    System.arraycopy(header, 0, input, 0, header.size)
    System.arraycopy(existingPayload, 0, input, header.size, existingPayload.size)
    System.arraycopy(tail, 0, input, header.size + existingPayload.size, tail.size)

    val tagged = injectPurchaseProvenanceId3(
      audioBytes = input,
      buyerWallet = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      purchaseId = "purchase_abc",
    )

    val newTagSize = ((tagged[6].toInt() and 0x7F) shl 21) or
      ((tagged[7].toInt() and 0x7F) shl 14) or
      ((tagged[8].toInt() and 0x7F) shl 7) or
      (tagged[9].toInt() and 0x7F)
    assertTrue(newTagSize > existingPayload.size)

    val taggedAscii = String(tagged, Charsets.ISO_8859_1)
    assertTrue(taggedAscii.contains("purchase_abc"))
    assertTrue(taggedAscii.contains("buyer_wallet"))
  }
}

