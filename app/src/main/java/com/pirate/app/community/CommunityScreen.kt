package com.pirate.app.community

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import com.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pirate.app.resolvePublicProfileIdentityWithRetry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

@Composable
fun CommunityScreen(
  viewerAddress: String?,
  onMemberClick: (String) -> Unit = {},
) {
  var searchText by remember { mutableStateOf("") }
  var filters by remember { mutableStateOf(CommunityFilters(nativeLanguage = "en")) }
  var viewerLatE6 by remember { mutableStateOf<Int?>(null) }
  var viewerLngE6 by remember { mutableStateOf<Int?>(null) }
  var members by remember { mutableStateOf<List<CommunityMemberPreview>>(emptyList()) }
  var resolvedPrimaryNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var showFilterSheet by remember { mutableStateOf(false) }
  var defaultsLoadedFor by remember { mutableStateOf<String?>(null) }
  var preferredNativeLanguage by remember { mutableStateOf("en") }
  var reloadKey by remember { mutableStateOf(0) }

  LaunchedEffect(viewerAddress) {
    val normalizedViewer = viewerAddress?.trim()?.lowercase()
    if (defaultsLoadedFor == normalizedViewer) return@LaunchedEffect
    defaultsLoadedFor = normalizedViewer

    if (normalizedViewer.isNullOrBlank()) {
      viewerLatE6 = null
      viewerLngE6 = null
      preferredNativeLanguage = "en"
      filters = filters.copy(
        nativeLanguage = filters.nativeLanguage ?: preferredNativeLanguage,
        radiusKm = null,
      )
      return@LaunchedEffect
    }

    val defaults = runCatching {
      withContext(Dispatchers.IO) { CommunityApi.fetchViewerDefaults(normalizedViewer) }
    }.onFailure { throwable ->
      if (throwable is CancellationException) throw throwable
    }.getOrNull()

    viewerLatE6 = defaults?.locationLatE6
    viewerLngE6 = defaults?.locationLngE6
    val hasViewerCoords = defaults?.locationLatE6 != null && defaults?.locationLngE6 != null
    val defaultNative = defaults?.nativeSpeakerTargetLanguage ?: "en"
    preferredNativeLanguage = defaultNative
    filters = filters.copy(
      nativeLanguage = defaultNative,
      radiusKm = if (hasViewerCoords) filters.radiusKm else null,
    )
  }

  LaunchedEffect(filters, viewerLatE6, viewerLngE6, viewerAddress, reloadKey) {
    loading = true
    error = null
    runCatching {
      withContext(Dispatchers.IO) {
        CommunityApi.fetchCommunityMembers(
          viewerAddress = viewerAddress,
          filters = filters,
          viewerLatE6 = viewerLatE6,
          viewerLngE6 = viewerLngE6,
        )
      }
    }.onSuccess {
      val fetchedMembers = it
      val unresolvedAddresses =
        fetchedMembers
          .map { member -> member.address }
          .filter { address -> !resolvedPrimaryNames.containsKey(address) }

      val resolvedBatch =
        withContext(Dispatchers.IO) {
          resolvePrimaryNames(unresolvedAddresses)
        }
      if (resolvedBatch.isNotEmpty()) {
        resolvedPrimaryNames = resolvedPrimaryNames + resolvedBatch
      }

      members = fetchedMembers
      loading = false
    }.onFailure { throwable ->
      if (throwable is CancellationException) throw throwable
      error = throwable.message ?: "Failed to load community"
      loading = false
    }
  }

  val activeFilterCount = CommunityApi.activeFilterCount(filters)
  val hasViewerCoords = viewerLatE6 != null && viewerLngE6 != null
  val visibleMembers = remember(members, resolvedPrimaryNames, searchText) {
    val query = searchText.trim().lowercase()
    if (query.isBlank()) {
      members
    } else {
      members.filter { member ->
        val name = member.displayName.lowercase()
        val primary = resolvedPrimaryNames[member.address]?.lowercase().orEmpty()
        val addr = member.address.lowercase()
        name.contains(query) || primary.contains(query) || addr.contains(query)
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .statusBarsPadding(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = searchText,
        onValueChange = { searchText = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Search people") },
        leadingIcon = { Icon(PhosphorIcons.Regular.MagnifyingGlass, contentDescription = null) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(),
      )
      Spacer(Modifier.width(10.dp))
      BadgedBox(
        badge = {
          if (activeFilterCount > 0) {
            Badge {
              Text(activeFilterCount.toString())
            }
          }
        },
      ) {
        PirateIconButton(
          onClick = { showFilterSheet = true },
          modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) {
          Icon(PhosphorIcons.Regular.SlidersHorizontal, contentDescription = "More filters")
        }
      }
    }

    when {
      loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
      !error.isNullOrBlank() -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            PirateOutlinedButton(onClick = { reloadKey++ }) {
              Text("Retry")
            }
          }
        }
      }
      visibleMembers.isEmpty() -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("No users found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      else -> {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          items(visibleMembers, key = { it.address }) { member ->
            CommunityPreviewRow(
              member = member,
              resolvedPrimaryName = resolvedPrimaryNames[member.address],
              onClick = { onMemberClick(member.address) },
            )
            HorizontalDivider(
              modifier = Modifier.padding(horizontal = 16.dp),
              color = MaterialTheme.colorScheme.outlineVariant,
            )
          }
        }
      }
    }
  }

  if (showFilterSheet) {
    CommunityFilterSheet(
      filters = filters,
      defaultNativeLanguage = preferredNativeLanguage,
      hasViewerCoords = hasViewerCoords,
      onDismiss = { showFilterSheet = false },
      onApply = {
        filters = it
        showFilterSheet = false
      },
    )
  }
}

private suspend fun resolvePrimaryNames(
  addresses: List<String>,
  maxLookups: Int = 36,
): Map<String, String> = coroutineScope {
  val limited = addresses.distinct().take(maxLookups)
  limited
    .map { address ->
      async {
        val fullName =
          runCatching {
            resolvePublicProfileIdentityWithRetry(address, attempts = 1).first
              ?.trim()
              ?.takeIf { it.isNotBlank() }
          }.getOrNull()
        if (fullName.isNullOrBlank()) null else address to fullName
      }
    }
    .awaitAll()
    .filterNotNull()
    .toMap()
}
