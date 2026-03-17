package com.pirate.app.song

import java.text.Normalizer

private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")
private val GRAPHQL_CONTROL_CHARS_REGEX = Regex("[\\u0000-\\u001F\\u007F]")

internal fun normalizeAddress(raw: String): String? {
  val trimmed = raw.trim()
  if (!ADDRESS_REGEX.matches(trimmed)) return null
  return trimmed.lowercase()
}

internal fun normalizeBytes32(raw: String): String? {
  val trimmed = raw.trim().removePrefix("0x").removePrefix("0X")
  if (trimmed.length != 64) return null
  if (!trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
  return "0x${trimmed.lowercase()}"
}

internal fun escapeGraphQL(value: String): String {
  val sanitized = GRAPHQL_CONTROL_CHARS_REGEX.replace(value, " ")
  return sanitized.replace("\\", "\\\\").replace("\"", "\\\"")
}

internal fun normalizeArtistName(name: String): String {
  val folded =
    Normalizer.normalize(name, Normalizer.Form.NFKD)
      .replace(Regex("[\\u0300-\\u036f]"), "")
  return folded
    .lowercase()
    .replace("$", "s")
    .replace("&", " and ")
    .replace(Regex("\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b"), " feat ")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
}

private fun splitArtistNames(name: String): List<String> {
  val unified =
    name
      .lowercase()
      .replace(Regex("\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b"), "|")
      .replace(Regex("\\bstarring\\b"), "|")
      .replace("&", "|")
      .replace("+", "|")
      .replace(Regex("\\bx\\b"), "|")
      .replace(Regex("\\band\\b"), "|")
      .replace(Regex("\\bwith\\b"), "|")
      .replace("/", "|")
      .replace(",", "|")
  return unified
    .split("|")
    .map { normalizeArtistName(it) }
    .filter { it.isNotBlank() }
}

private fun normalizeArtistVariants(name: String): Set<String> {
  val base = normalizeArtistName(name)
  if (base.isBlank()) return emptySet()
  val variants = linkedSetOf(base)

  val noParens = base.replace(Regex("\\s*\\([^)]*\\)\\s*"), " ").replace(Regex("\\s+"), " ").trim()
  if (noParens.isNotBlank() && noParens != base) variants.add(noParens)

  if (base.startsWith("the ")) {
    variants.add(base.removePrefix("the ").trim())
  }
  if (base.endsWith(" the")) {
    val noTrail = base.removeSuffix(" the").trim()
    if (noTrail.isNotBlank()) {
      variants.add(noTrail)
      variants.add("the $noTrail")
    }
  }
  return variants
}

private fun wordContains(haystack: String, needle: String): Boolean {
  if (haystack.isBlank() || needle.isBlank()) return false
  return " $haystack ".contains(" $needle ")
}

internal fun artistMatchesTarget(artistField: String, targetNorm: String): Boolean {
  if (targetNorm.isBlank()) return false
  val targetVariants = normalizeArtistVariants(targetNorm)
  val fieldVariants = normalizeArtistVariants(artistField)

  for (fieldVariant in fieldVariants) {
    for (targetVariant in targetVariants) {
      if (fieldVariant == targetVariant) return true
      if (wordContains(fieldVariant, targetVariant)) return true
      if (wordContains(targetVariant, fieldVariant)) return true
    }
  }

  for (part in splitArtistNames(artistField)) {
    for (targetVariant in targetVariants) {
      if (part == targetVariant) return true
      if (wordContains(part, targetVariant)) return true
    }
  }
  return false
}

/**
 * Returns the primary artist (before any feat./ft./featuring/,/&) for display and navigation.
 * "Sub Focus feat. Kelli-Leigh" → "Sub Focus"
 * "Sub Focus, Kelli-Leigh" → "Sub Focus"
 */
internal fun primaryArtist(raw: String): String {
  return raw
    .split(Regex("\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+", RegexOption.IGNORE_CASE))
    .first()
    .split(Regex("\\s*,\\s*|\\s*&\\s*"))
    .first()
    .trim()
}

/**
 * Parses all individual artists out of a compound artist string for the artist-picker drawer.
 * "Sub Focus feat. Kelli-Leigh" → ["Sub Focus", "Kelli-Leigh"]
 */
internal fun parseAllArtists(raw: String): List<String> {
  return raw
    .split(Regex("\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s*,\\s*|\\s*&\\s*|\\s*/\\s*", RegexOption.IGNORE_CASE))
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()
}

internal fun hexToBytes(hex0x: String): ByteArray {
  val hex = hex0x.removePrefix("0x")
  if (hex.length % 2 != 0) throw IllegalArgumentException("Odd hex length")
  val out = ByteArray(hex.length / 2)
  var i = 0
  while (i < hex.length) {
    out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
    i += 2
  }
  return out
}

internal fun encodeUrlComponent(value: String): String {
  return java.net.URLEncoder.encode(value, "UTF-8")
}
