package com.pirate.app.music

import java.io.ByteArrayOutputStream

private const val ID3_HEADER_SIZE = 10
private const val ID3V23_MAJOR_VERSION = 3
private const val PURCHASE_RECEIPT_URL_BASE = "https://tempo.pirate.sc/receipt/"

private fun isLikelyMp3Bytes(bytes: ByteArray): Boolean {
  if (bytes.size >= 3 &&
    bytes[0] == 'I'.code.toByte() &&
    bytes[1] == 'D'.code.toByte() &&
    bytes[2] == '3'.code.toByte()
  ) {
    return true
  }
  if (bytes.size >= 2) {
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xE0
    if (b0 == 0xFF && b1 == 0xE0) return true
  }
  return false
}

private fun decodeSyncsafeInt(input: ByteArray, offset: Int): Int {
  return ((input[offset].toInt() and 0x7F) shl 21) or
    ((input[offset + 1].toInt() and 0x7F) shl 14) or
    ((input[offset + 2].toInt() and 0x7F) shl 7) or
    (input[offset + 3].toInt() and 0x7F)
}

private fun encodeSyncsafeInt(value: Int): ByteArray = byteArrayOf(
  ((value ushr 21) and 0x7F).toByte(),
  ((value ushr 14) and 0x7F).toByte(),
  ((value ushr 7) and 0x7F).toByte(),
  (value and 0x7F).toByte(),
)

private fun encodeFrameSize(value: Int, id3MajorVersion: Int): ByteArray {
  if (id3MajorVersion == 4) return encodeSyncsafeInt(value)
  return byteArrayOf(
    ((value ushr 24) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
  )
}

private fun buildTxxxFrame(description: String, value: String, id3MajorVersion: Int): ByteArray {
  val descriptionBytes = description.toByteArray(Charsets.ISO_8859_1)
  val valueBytes = value.toByteArray(Charsets.ISO_8859_1)
  val body = ByteArrayOutputStream(descriptionBytes.size + valueBytes.size + 2).apply {
    write(0x00) // ISO-8859-1 encoding byte.
    write(descriptionBytes)
    write(0x00)
    write(valueBytes)
  }.toByteArray()

  return ByteArrayOutputStream(10 + body.size).apply {
    write("TXXX".toByteArray(Charsets.US_ASCII))
    write(encodeFrameSize(body.size, id3MajorVersion))
    write(0x00)
    write(0x00)
    write(body)
  }.toByteArray()
}

private fun prependNewId3Tag(audioBytes: ByteArray, frames: ByteArray): ByteArray {
  val header = ByteArrayOutputStream(ID3_HEADER_SIZE).apply {
    write('I'.code)
    write('D'.code)
    write('3'.code)
    write(ID3V23_MAJOR_VERSION)
    write(0x00)
    write(0x00)
    write(encodeSyncsafeInt(frames.size))
  }.toByteArray()

  return ByteArrayOutputStream(header.size + frames.size + audioBytes.size).apply {
    write(header)
    write(frames)
    write(audioBytes)
  }.toByteArray()
}

internal fun injectPurchaseProvenanceId3(
  audioBytes: ByteArray,
  buyerWallet: String,
  purchaseId: String,
  receiptUrlBase: String = PURCHASE_RECEIPT_URL_BASE,
): ByteArray {
  val normalizedBuyer = buyerWallet.trim().lowercase()
  val normalizedPurchaseId = purchaseId.trim()
  if (normalizedBuyer.isBlank() || normalizedPurchaseId.isBlank()) return audioBytes
  if (!isLikelyMp3Bytes(audioBytes)) return audioBytes

  val receiptUrl = receiptUrlBase.trim().trimEnd('/') + "/" + normalizedPurchaseId

  val currentTagMajor = if (
    audioBytes.size >= 4 &&
    audioBytes[0] == 'I'.code.toByte() &&
    audioBytes[1] == 'D'.code.toByte() &&
    audioBytes[2] == '3'.code.toByte()
  ) {
    audioBytes[3].toInt() and 0xFF
  } else {
    ID3V23_MAJOR_VERSION
  }
  val frameVersion = if (currentTagMajor == 4) 4 else ID3V23_MAJOR_VERSION

  val frames = ByteArrayOutputStream().apply {
    write(buildTxxxFrame("buyer_wallet", normalizedBuyer, frameVersion))
    write(buildTxxxFrame("purchase_id", normalizedPurchaseId, frameVersion))
    write(buildTxxxFrame("purchase_receipt", receiptUrl, frameVersion))
  }.toByteArray()

  if (
    audioBytes.size >= ID3_HEADER_SIZE &&
    audioBytes[0] == 'I'.code.toByte() &&
    audioBytes[1] == 'D'.code.toByte() &&
    audioBytes[2] == '3'.code.toByte()
  ) {
    val major = audioBytes[3].toInt() and 0xFF
    val flags = audioBytes[5].toInt() and 0xFF
    val hasV24Footer = major == 4 && (flags and 0x10) != 0
    val existingPayloadSize = decodeSyncsafeInt(audioBytes, 6)
    val existingTagTotalSize = ID3_HEADER_SIZE + existingPayloadSize + if (hasV24Footer) 10 else 0

    if (!hasV24Footer && existingTagTotalSize <= audioBytes.size) {
      val existingPayload = audioBytes.copyOfRange(ID3_HEADER_SIZE, existingTagTotalSize)
      val mergedPayload = ByteArrayOutputStream(existingPayload.size + frames.size).apply {
        write(existingPayload)
        write(frames)
      }.toByteArray()
      val output = ByteArrayOutputStream(audioBytes.size + frames.size).apply {
        write(audioBytes, 0, 6)
        write(encodeSyncsafeInt(mergedPayload.size))
        write(mergedPayload)
        write(audioBytes, existingTagTotalSize, audioBytes.size - existingTagTotalSize)
      }.toByteArray()
      return output
    }
  }

  return prependNewId3Tag(audioBytes, frames)
}
