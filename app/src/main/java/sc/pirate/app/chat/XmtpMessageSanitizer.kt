package sc.pirate.app.chat

import org.xmtp.android.library.libxmtp.DecodedMessage
import uniffi.xmtpv3.FfiConversationMessageKind

internal fun sanitizeXmtpBody(message: DecodedMessage): String {
  val kind = runCatching { message.kind }.getOrNull()
  if (kind != null && kind != FfiConversationMessageKind.APPLICATION) return ""

  val fallback = runCatching { message.fallback }.getOrDefault("").trim()
  val body = runCatching { message.body }.getOrDefault("").trim()
  val text =
    when {
      fallback.isNotBlank() -> fallback
      body.isNotBlank() -> body
      else -> ""
    }
  if (text.isBlank()) return ""
  if (looksLikeProtocolPrefixedPayload(text)) return ""
  return text
}

private fun looksLikeProtocolPrefixedPayload(value: String): Boolean {
  if (!value.startsWith("@")) return false
  val firstWhitespace = value.indexOfFirst { it.isWhitespace() }
  val token =
    if (firstWhitespace == -1) value.drop(1)
    else value.substring(1, firstWhitespace)
  if (token.length < 20) return false
  if (token.contains('.')) return false
  if (token.any { it.isWhitespace() }) return false
  if (!token.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '=' || it == '+' || it == '/' }) {
    return false
  }
  if (firstWhitespace == -1) return true
  val rest = value.substring(firstWhitespace).trim()
  if (rest.isBlank()) return true
  return looksLikeEncodedPayloadRemainder(rest)
}

private fun looksLikeEncodedPayloadRemainder(value: String): Boolean {
  val trimmed = value.trim()
  if (trimmed.isBlank()) return false
  if (trimmed.contains(' ')) {
    val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return false
    return tokens.size <= 3 && tokens.all { looksLikeBlobToken(it) }
  }
  return looksLikeBlobToken(trimmed)
}

private fun looksLikeBlobToken(value: String): Boolean {
  if (value.length < 16) return false
  if (value.endsWith(".pirate", ignoreCase = true) || value.endsWith(".heaven", ignoreCase = true)) {
    return false
  }
  val allowedCount =
    value.count {
      it.isLetterOrDigit() ||
        it == '-' ||
        it == '_' ||
        it == '=' ||
        it == '+' ||
        it == '/' ||
        it == '.'
    }
  if (allowedCount != value.length) return false
  val alphaNumericRatio = value.count { it.isLetterOrDigit() }.toDouble() / value.length.toDouble()
  return alphaNumericRatio >= 0.85
}
