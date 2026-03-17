package com.pirate.app.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pirate.app.theme.PiratePalette
import com.pirate.app.ui.PirateMobileHeader
import com.pirate.app.ui.PiratePrimaryButton

@Composable
internal fun NewGroupMembersScreen(
  query: String,
  members: List<String>,
  recents: List<DmSuggestion>,
  directorySuggestions: List<DmSuggestion>,
  directoryBusy: Boolean,
  directoryError: String?,
  busy: Boolean,
  error: String?,
  onBack: () -> Unit,
  onQueryChange: (String) -> Unit,
  onAddQuery: () -> Unit,
  onAddSuggestion: (String) -> Unit,
  onRemoveMember: (String) -> Unit,
  onNext: () -> Unit,
) {
  val queryTrimmed = query.trim()
  val showDirectorySection = shouldSearchDirectory(queryTrimmed)
  Column(modifier = Modifier.fillMaxSize()) {
    PirateMobileHeader(
      title = "New group",
      onBackPress = onBack,
      isAuthenticated = true,
    )
    Text(
      text = "Add members",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.weight(1f),
        placeholder = { Text("Search users") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions =
          androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Send,
          ),
        keyboardActions =
          androidx.compose.foundation.text.KeyboardActions(
            onSend = { onAddQuery() },
          ),
      )
      PiratePrimaryButton(
        text = "Add",
        onClick = onAddQuery,
        enabled = query.trim().isNotBlank() && !busy,
      )
    }
    if (!error.isNullOrBlank()) {
      Text(
        text = error,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
    if (!directoryError.isNullOrBlank()) {
      Text(
        text = directoryError,
        color = PiratePalette.TextMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
      )
    }
    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
      if (members.isEmpty()) {
        item(key = "members-empty") {
          Text(
            text = "No members selected yet",
            color = PiratePalette.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
      } else {
        item(key = "members-title") {
          Text(
            text = "Selected (${members.size})",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
        items(
          items = members,
          key = { "member-${it.lowercase()}" },
        ) { member ->
          ListItem(
            headlineContent = { Text(memberDisplayName(member)) },
            supportingContent = { Text(member, style = MaterialTheme.typography.bodySmall) },
            trailingContent = { Text("Remove", color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(enabled = !busy) { onRemoveMember(member) },
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
      }
      item(key = "suggestions-title") {
        Text(
          text = "Recents",
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
      if (recents.isEmpty()) {
        item(key = "suggestions-empty") {
          Text(
            text = "No recent suggestions",
            color = PiratePalette.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
        }
      } else {
        items(
          items = recents,
          key = { "recent-suggest-${it.inputValue.lowercase()}" },
        ) { suggestion ->
          DmSuggestionRow(
            suggestion = suggestion,
            enabled = !busy,
            onClick = { onAddSuggestion(suggestion.inputValue) },
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
      }
      if (showDirectorySection) {
        when {
          directoryBusy -> {
            item(key = "directory-loading") {
              Text(
                text = "Searching directory...",
                color = PiratePalette.TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
              )
            }
          }
          directorySuggestions.isNotEmpty() -> {
            items(
              items = directorySuggestions,
              key = { "dir-suggest-${it.inputValue.lowercase()}" },
            ) { suggestion ->
              DmSuggestionRow(
                suggestion = suggestion,
                enabled = !busy,
                onClick = { onAddSuggestion(suggestion.inputValue) },
              )
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
          }
        }
      }
      item(key = "spacer-end") {
        Spacer(modifier = Modifier.height(16.dp))
      }
    }
    PiratePrimaryButton(
      text = "Next",
      onClick = onNext,
      enabled = members.isNotEmpty() && !busy,
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
}

@Composable
internal fun NewGroupDetailsScreen(
  groupName: String,
  description: String,
  memberCount: Int,
  busy: Boolean,
  error: String?,
  onBack: () -> Unit,
  onNameChange: (String) -> Unit,
  onDescriptionChange: (String) -> Unit,
  onCreate: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    PirateMobileHeader(
      title = "Group details",
      onBackPress = onBack,
      isAuthenticated = true,
    )
    Text(
      text = "$memberCount member${if (memberCount == 1) "" else "s"} selected",
      color = PiratePalette.TextMuted,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    OutlinedTextField(
      value = groupName,
      onValueChange = onNameChange,
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
      placeholder = { Text("Group name (optional)") },
      singleLine = true,
      enabled = !busy,
    )
    OutlinedTextField(
      value = description,
      onValueChange = onDescriptionChange,
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
      placeholder = { Text("Description (optional)") },
      enabled = !busy,
      minLines = 2,
    )
    if (!error.isNullOrBlank()) {
      Text(
        text = error,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
    Spacer(modifier = Modifier.weight(1f))
    PiratePrimaryButton(
      text = if (busy) "Creating..." else "Create group",
      onClick = onCreate,
      enabled = memberCount > 0 && !busy,
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 16.dp, vertical = 12.dp),
    )
  }
}
