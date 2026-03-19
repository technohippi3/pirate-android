package sc.pirate.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sc.pirate.app.util.abbreviateAddress
import org.xmtp.android.library.libxmtp.PermissionOption

internal fun buildDmSuggestions(
  conversations: List<ConversationItem>,
  query: String,
  excludeConversationId: String? = null,
  limit: Int = 6,
): List<DmSuggestion> {
  val needle = query.trim().lowercase()
  return conversations
    .asSequence()
    .filter { convo ->
      if (convo.type != ConversationType.DM) return@filter false
      if (!excludeConversationId.isNullOrBlank() && convo.id == excludeConversationId) return@filter false
      val address = convo.peerAddress.orEmpty()
      val inboxId = convo.peerInboxId.orEmpty()
      if (needle.isBlank()) {
        true
      } else {
        convo.displayName.lowercase().contains(needle) ||
          address.lowercase().contains(needle) ||
          inboxId.lowercase().contains(needle)
      }
    }
    .sortedByDescending { it.lastMessageTimestampMs }
    .map { convo ->
      val address = convo.peerAddress.orEmpty()
      val inboxId = convo.peerInboxId.orEmpty()
      val hasResolvedName =
        convo.displayName.isNotBlank() &&
          !convo.displayName.equals(address, ignoreCase = true)
      val title =
        if (hasResolvedName) convo.displayName else abbreviateAddress(address.ifBlank { inboxId })
      val subtitle =
        if (hasResolvedName) abbreviateAddress(address.ifBlank { inboxId }) else abbreviateAddress(inboxId)
      val inputValue =
        if (looksLikeEthereumAddress(address)) address else inboxId
      DmSuggestion(
        title = title,
        subtitle = subtitle,
        inputValue = inputValue,
        avatarUri = convo.avatarUri,
        verificationAddress = address.ifBlank { null },
      )
    }
    .filter { it.inputValue.isNotBlank() }
    .distinctBy { it.inputValue.lowercase() }
    .take(limit)
    .toList()
}

internal suspend fun refreshDirectorySuggestions(
  currentView: ChatView,
  expectedView: ChatView,
  rawQuery: String,
  onSuggestions: (List<DmSuggestion>) -> Unit,
  onBusy: (Boolean) -> Unit,
  onError: (String?) -> Unit,
) {
  if (currentView != expectedView || !shouldSearchDirectory(rawQuery)) {
    onSuggestions(emptyList())
    onBusy(false)
    onError(null)
    return
  }

  val query = rawQuery.trim()
  delay(280)
  onBusy(true)
  onError(null)
  try {
    val profiles = ChatDirectoryApi.searchProfilesByDisplayNamePrefix(query, first = 12)
    onSuggestions(profiles.mapNotNull(::directoryProfileToSuggestion))
    onError(null)
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    onSuggestions(emptyList())
    onError(error.message ?: "Directory search unavailable")
  } finally {
    onBusy(false)
  }
}

internal fun parseGroupMembersInput(input: String): List<String> {
  return input
    .split(',', '\n', '\t', ' ')
    .map { it.trim() }
    .filter { it.isNotBlank() }
}

internal fun shouldSearchDirectory(query: String): Boolean {
  val normalized = query.trim()
  if (normalized.length < 2) return false
  if (looksLikeEthereumAddress(normalized)) return false
  return true
}

internal fun looksLikeDirectDmTarget(value: String): Boolean {
  val normalized = value.trim()
  if (normalized.isBlank()) return false
  if (looksLikeEthereumAddress(normalized)) return true
  if (looksLikeTempoName(normalized)) return true
  return false
}

internal fun looksLikeTempoName(value: String): Boolean {
  val normalized = value.trim().lowercase().removePrefix("@")
  if (normalized.isBlank()) return false
  val dotIndex = normalized.lastIndexOf('.')
  if (dotIndex <= 0 || dotIndex >= normalized.lastIndex) return false
  val label = normalized.substring(0, dotIndex)
  val tld = normalized.substring(dotIndex + 1)
  if (tld != "heaven" && tld != "pirate") return false
  if (label.isBlank()) return false
  return label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

internal fun directoryProfileToSuggestion(profile: ChatDirectoryProfile): DmSuggestion? {
  val address = profile.address.trim().lowercase()
  if (!looksLikeEthereumAddress(address)) return null
  val title = profile.displayName.ifBlank { abbreviateAddress(address) }
  return DmSuggestion(
    title = title,
    subtitle = abbreviateAddress(address),
    inputValue = address,
    avatarUri = profile.photoUri,
    verificationAddress = address,
  )
}

internal fun dropKnownSuggestions(
  directorySuggestions: List<DmSuggestion>,
  existingSuggestions: List<DmSuggestion>,
): List<DmSuggestion> {
  if (directorySuggestions.isEmpty()) return emptyList()
  if (existingSuggestions.isEmpty()) return directorySuggestions
  val existingInputs =
    existingSuggestions
      .asSequence()
      .map { it.inputValue.trim().lowercase() }
      .filter { it.isNotBlank() }
      .toSet()
  if (existingInputs.isEmpty()) return directorySuggestions
  return directorySuggestions.filterNot { it.inputValue.trim().lowercase() in existingInputs }
}

internal fun memberDisplayName(value: String): String {
  val trimmed = value.trim()
  if (trimmed.isBlank()) return ""
  return if (looksLikeEthereumAddress(trimmed) || trimmed.length > 22) {
    abbreviateAddress(trimmed)
  } else {
    trimmed
  }
}

internal fun permissionOptionLabel(option: PermissionOption): String {
  return when (option) {
    PermissionOption.Allow -> "All members"
    PermissionOption.Admin -> "Admins only"
    PermissionOption.SuperAdmin -> "Super admin only"
    PermissionOption.Deny -> "Deny all"
    PermissionOption.Unknown -> "Unknown"
  }
}

internal fun looksLikeEthereumAddress(value: String): Boolean {
  val trimmed = value.trim()
  if (!trimmed.startsWith("0x") || trimmed.length != 42) return false
  return trimmed.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}

internal fun avatarInitial(displayName: String): String {
  val normalized = displayName.trim()
  if (normalized.isBlank()) return "?"
  return normalized.take(1).uppercase()
}

internal fun formatTimestamp(ms: Long): String {
  val now = System.currentTimeMillis()
  val diff = now - ms
  return when {
    diff < 60_000 -> "now"
    diff < 3600_000 -> "${diff / 60_000}m"
    diff < 86400_000 -> "${diff / 3600_000}h"
    else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
  }
}

internal fun formatTime(ms: Long): String {
  return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
}
