package com.pirate.app.chat

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pirate.app.ui.PiratePrimaryButton
import com.pirate.app.ui.PirateSheetTitle
import org.xmtp.android.library.libxmtp.PermissionOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSettingsSheet(
  isGroupConversation: Boolean,
  groupMetaName: String,
  onGroupMetaNameChange: (String) -> Unit,
  groupMetaDescription: String,
  onGroupMetaDescriptionChange: (String) -> Unit,
  groupMetaImageUrl: String,
  onGroupMetaImageUrlChange: (String) -> Unit,
  groupMetaAppData: String,
  onGroupMetaAppDataChange: (String) -> Unit,
  groupMetaBusy: Boolean,
  groupMetaError: String?,
  onSaveGroupMetadata: () -> Unit,
  groupPermissionAddMembers: PermissionOption,
  onGroupPermissionAddMembersChange: (PermissionOption) -> Unit,
  groupPermissionMetadata: PermissionOption,
  onGroupPermissionMetadataChange: (PermissionOption) -> Unit,
  groupPermissionBusy: Boolean,
  groupPermissionError: String?,
  onSaveGroupPermissions: () -> Unit,
  disappearingRetentionSeconds: Long?,
  disappearingBusy: Boolean,
  onSelectDisappearing: (Long?) -> Unit,
  onDismiss: () -> Unit,
) {
  val options =
    listOf(
      "Off" to null,
      "5 minutes" to 5L * 60L,
      "1 hour" to 60L * 60L,
      "1 day" to 24L * 60L * 60L,
      "7 days" to 7L * 24L * 60L * 60L,
    )

  ModalBottomSheet(
    onDismissRequest = onDismiss,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
      PirateSheetTitle(
        text = "Conversation settings",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
      if (isGroupConversation) {
        OutlinedTextField(
          value = groupMetaName,
          onValueChange = onGroupMetaNameChange,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp),
          label = { Text("Group name") },
          enabled = !groupMetaBusy,
        )
        OutlinedTextField(
          value = groupMetaDescription,
          onValueChange = onGroupMetaDescriptionChange,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp),
          label = { Text("Description") },
          enabled = !groupMetaBusy,
        )
        OutlinedTextField(
          value = groupMetaImageUrl,
          onValueChange = onGroupMetaImageUrlChange,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp),
          label = { Text("Image URL") },
          enabled = !groupMetaBusy,
        )
        OutlinedTextField(
          value = groupMetaAppData,
          onValueChange = onGroupMetaAppDataChange,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp),
          label = { Text("App data") },
          enabled = !groupMetaBusy,
        )
        PiratePrimaryButton(
          text = "Save group details",
          onClick = onSaveGroupMetadata,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
          enabled = !groupMetaBusy,
        )
        if (!groupMetaError.isNullOrBlank()) {
          Text(
            text = groupMetaError,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
        }
        Text(
          text = "Group permissions",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PermissionOptionSelector(
          title = "Who can add members?",
          selected = groupPermissionAddMembers,
          onSelect = onGroupPermissionAddMembersChange,
          enabled = !groupPermissionBusy,
        )
        PermissionOptionSelector(
          title = "Who can edit metadata?",
          selected = groupPermissionMetadata,
          onSelect = onGroupPermissionMetadataChange,
          enabled = !groupPermissionBusy,
        )
        PiratePrimaryButton(
          text = "Save group permissions",
          onClick = onSaveGroupPermissions,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
          enabled = !groupPermissionBusy,
        )
        if (!groupPermissionError.isNullOrBlank()) {
          Text(
            text = groupPermissionError,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
        }
        HorizontalDivider(
          modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
          color = MaterialTheme.colorScheme.outlineVariant,
        )
      }
      Text(
        text = "Disappearing messages",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
      )
      options.forEach { (label, seconds) ->
        val selected = disappearingRetentionSeconds == seconds
        ListItem(
          headlineContent = { Text(label) },
          leadingContent = { Icon(PhosphorIcons.Regular.Timer, contentDescription = null) },
          trailingContent = {
            RadioButton(
              selected = selected,
              onClick = null,
              enabled = !disappearingBusy,
            )
          },
          modifier =
            Modifier
              .fillMaxWidth()
              .clickable(enabled = !disappearingBusy) { onSelectDisappearing(seconds) },
        )
      }
    }
  }
}
