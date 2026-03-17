package com.pirate.app.chat

import android.content.Context
import android.util.Log
import org.xmtp.android.library.Client
import org.xmtp.android.library.ClientOptions
import java.io.File
import java.security.SecureRandom

private const val TAG = "XmtpStorage"
private const val PREFS_NAME = "xmtp_prefs"
private const val KEY_DB_KEY = "db_encryption_key"

internal fun getOrCreateXmtpDbKey(
  appContext: Context,
  identity: String,
): ByteArray {
  val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  val storageKey = "${KEY_DB_KEY}:${identity.lowercase()}"
  val scoped = prefs.getString(storageKey, null)
  if (scoped != null) {
    return hexToBytes(scoped)
  }
  val key = ByteArray(32)
  SecureRandom().nextBytes(key)
  prefs.edit().putString(storageKey, bytesToHex(key)).apply()
  return key
}

internal suspend fun createXmtpClientWithDbRecovery(
  appContext: Context,
  signer: LocalSigningKey,
  options: ClientOptions,
): Client {
  return try {
    Client.create(account = signer, options = options)
  } catch (e: Exception) {
    if (!isDbKeyOrSaltMismatch(e)) throw e
    Log.w(TAG, "XMTP DB key/salt mismatch detected; resetting local XMTP DB and retrying once", e)
    resetLocalXmtpDbFiles(appContext)
    Client.create(account = signer, options = options)
  }
}

private fun isDbKeyOrSaltMismatch(error: Throwable): Boolean {
  var cursor: Throwable? = error
  while (cursor != null) {
    val message = cursor.message.orEmpty().lowercase()
    if (
      message.contains("pragma key or salt has incorrect value") ||
      message.contains("error decrypting page") ||
      message.contains("hmac check failed") ||
      message.contains("file is not a database")
    ) {
      return true
    }
    cursor = cursor.cause
  }
  return false
}

private fun resetLocalXmtpDbFiles(appContext: Context) {
  val dbDir = File(appContext.filesDir, "xmtp_db")
  if (!dbDir.exists()) return
  dbDir.listFiles()?.forEach { f ->
    runCatching { f.delete() }.onFailure {
      runCatching { f.deleteRecursively() }
    }
  }
}

private fun hexToBytes(hex: String): ByteArray {
  val clean = hex.lowercase()
  val out = ByteArray(clean.length / 2)
  for (i in out.indices) {
    val hi = clean[2 * i].digitToInt(16)
    val lo = clean[2 * i + 1].digitToInt(16)
    out[i] = ((hi shl 4) or lo).toByte()
  }
  return out
}

private fun bytesToHex(bytes: ByteArray): String {
  val sb = StringBuilder(bytes.size * 2)
  for (b in bytes) {
    sb.append(((b.toInt() ushr 4) and 0x0f).toString(16))
    sb.append((b.toInt() and 0x0f).toString(16))
  }
  return sb.toString()
}
