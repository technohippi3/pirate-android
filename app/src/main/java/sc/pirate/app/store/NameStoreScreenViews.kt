package sc.pirate.app.store

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.ui.PirateToggleChip
import sc.pirate.app.ui.PirateMobileHeader
import sc.pirate.app.ui.PiratePrimaryButton

@Composable
internal fun NameStoreContent(
  isAuthenticated: Boolean,
  hasAccount: Boolean,
  selectedTld: String,
  customInput: String,
  checkingCustom: Boolean,
  customResult: SelectableName?,
  customError: String?,
  customValid: Boolean,
  customNormalized: String,
  loadingNames: Boolean,
  availableNames: List<SelectableName>,
  selectedName: SelectableName?,
  buying: Boolean,
  canBuy: Boolean,
  onClose: () -> Unit,
  onSelectTld: (String) -> Unit,
  onCustomInputChange: (String) -> Unit,
  onSelectName: (SelectableName) -> Unit,
  onBuy: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    PirateMobileHeader(
      title = "Domains",
      onClosePress = onClose,
    )

    if (!isAuthenticated || !hasAccount) {
      Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Sign in to buy premium names.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      return@Column
    }

    Column(
      modifier =
        Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp),
    ) {
      Spacer(Modifier.height(8.dp))

      Text(
        "Get a name",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Spacer(Modifier.height(8.dp))
      Text(
        "Choose a domain for your identity on Pirate",
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(24.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        PirateToggleChip(selected = selectedTld == "pirate", onClick = { onSelectTld("pirate") }, label = ".pirate")
        PirateToggleChip(selected = selectedTld == "heaven", onClick = { onSelectTld("heaven") }, label = ".heaven")
      }
      Spacer(Modifier.height(20.dp))

      OutlinedTextField(
        value = customInput,
        onValueChange = onCustomInputChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Search custom name") },
        suffix = { Text(".${selectedTld.lowercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = RoundedCornerShape(50),
      )
      Spacer(Modifier.height(8.dp))

      if (customInput.isNotBlank()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          when {
            checkingCustom -> {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
              Text("  Checking...", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            customResult != null -> {
              Text(
                "✓ Ready — ${customResult.price}",
                fontSize = 16.sp,
                color = PirateTokens.colors.accentSuccess,
              )
            }
            customError != null -> {
              Text(customError, fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
            }
            !customValid && customNormalized.isNotBlank() -> {
              Text("Use lowercase letters, numbers, or hyphens", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
            }
          }
        }
      }

      Spacer(Modifier.height(28.dp))

      if (loadingNames) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Text("  Loading available names...", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else if (availableNames.isNotEmpty()) {
        val premiumNames = availableNames.filter { it.tier == "premium" }
        val standardNames = availableNames.filter { it.tier == "standard" }

        if (premiumNames.isNotEmpty()) {
          Text(
            "Premium",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(8.dp))

          premiumNames.forEach { name ->
            NameListRow(
              label = name.label,
              tld = selectedTld,
              price = name.price,
              isSelected = selectedName?.label == name.label && selectedName.isPremiumListing,
              onClick = { onSelectName(name) },
            )
          }
          Spacer(Modifier.height(20.dp))
        }

        if (standardNames.isNotEmpty()) {
          Text(
            "Standard",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(8.dp))

          standardNames.forEach { name ->
            NameListRow(
              label = name.label,
              tld = selectedTld,
              price = name.price,
              isSelected = selectedName?.label == name.label && selectedName?.tier == "standard",
              onClick = { onSelectName(name) },
            )
          }
        }
      }

      Spacer(Modifier.height(24.dp))
    }

    Surface(
      modifier = Modifier.fillMaxWidth(),
      tonalElevation = 3.dp,
      shadowElevation = 8.dp,
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
      ) {
        if (selectedName != null) {
          Text(
            "${selectedName.label}.$selectedTld — ${selectedName.price}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        PiratePrimaryButton(
          text = "Buy",
          onClick = onBuy,
          enabled = canBuy,
          modifier = Modifier.fillMaxWidth(),
          loading = buying,
        )
      }
    }
  }
}

@Composable
internal fun NameListRow(
  label: String,
  tld: String,
  price: String,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
        .clickable(onClick = onClick),
    shape = MaterialTheme.shapes.medium,
    color =
      if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        Color.Transparent
      },
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "$label.$tld",
        style = MaterialTheme.typography.bodyLarge,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onBackground
          },
      )
      Text(
        price,
        style = MaterialTheme.typography.bodyMedium,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
  }
}
