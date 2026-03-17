package com.pirate.app.util

fun shortAddress(
  address: String,
  prefixChars: Int = 6,
  suffixChars: Int = 4,
  minLengthToShorten: Int = prefixChars + suffixChars + 2,
): String {
  val raw = address.trim()
  if (raw.length <= minLengthToShorten) return raw
  return "${raw.take(prefixChars)}...${raw.takeLast(suffixChars)}"
}

fun abbreviateAddress(address: String): String {
  val raw = address.trim()
  if (raw.length <= 12) return raw
  return if (raw.startsWith("0x", ignoreCase = true) && raw.length >= 10) {
    shortAddress(raw, prefixChars = 6, suffixChars = 4, minLengthToShorten = 12)
  } else {
    shortAddress(raw, prefixChars = 8, suffixChars = 4, minLengthToShorten = 12)
  }
}

fun formatTimeAgoShort(timestampSec: Long, nowSec: Long = System.currentTimeMillis() / 1_000L): String {
  if (timestampSec <= 0L) return "unknown"
  val delta = (nowSec - timestampSec).coerceAtLeast(0L)
  return when {
    delta < 60L -> "${delta}s ago"
    delta < 3_600L -> "${delta / 60L}m ago"
    delta < 86_400L -> "${delta / 3_600L}h ago"
    delta < 2_592_000L -> "${delta / 86_400L}d ago"
    else -> "${delta / 2_592_000L}mo ago"
  }
}
