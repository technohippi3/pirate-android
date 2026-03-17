package com.pirate.app.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.assistant.AssistantQuotaApi
import com.pirate.app.assistant.AssistantQuotaStatus
import com.pirate.app.assistant.formatCallSeconds
import com.pirate.app.ui.PirateMobileHeader
import com.pirate.app.ui.PiratePrimaryButton
import java.math.BigInteger
import kotlinx.coroutines.launch

private val CREDIT_PACKS = listOf(1, 5, 10, 25)

@Composable
fun StudyCreditsScreen(
  activity: FragmentActivity,
  isAuthenticated: Boolean,
  account: TempoPasskeyManager.PasskeyAccount?,
  onClose: () -> Unit,
  onPurchased: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var selectedCredits by remember { mutableIntStateOf(5) }
  var loadingQuote by remember { mutableStateOf(false) }
  var quote by remember { mutableStateOf<StudyCreditsQuote?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var buying by remember { mutableStateOf(false) }
  var refreshNonce by remember { mutableIntStateOf(0) }
  var assistantQuota by remember { mutableStateOf<AssistantQuotaStatus?>(null) }

  LaunchedEffect(isAuthenticated, account?.address, refreshNonce) {
    if (!isAuthenticated || account == null) {
      quote = null
      loadingQuote = false
      error = null
      return@LaunchedEffect
    }
    loadingQuote = true
    error = null
    quote =
      runCatching { StudyCreditsApi.quote(account.address) }
        .onFailure { err -> error = err.message ?: "Unable to load credits." }
        .getOrNull()
    assistantQuota =
      runCatching { AssistantQuotaApi.fetchQuota(activity, account.address) }
        .getOrNull()
    loadingQuote = false
  }

  val paymentTokenSymbol = quote?.paymentToken?.let(::tokenSymbolForAddress) ?: "αUSD"
  val creditPriceDisplay = quote?.creditPrice?.let(::formatTokenAmount)?.let { "$it $paymentTokenSymbol" } ?: "--"
  val totalCostRaw = quote?.creditPrice?.multiply(BigInteger.valueOf(selectedCredits.toLong())) ?: BigInteger.ZERO
  val totalCostDisplay = quote?.let { "${formatTokenAmount(totalCostRaw)} $paymentTokenSymbol" } ?: "--"
  val walletBalanceDisplay = quote?.let { "${formatTokenAmount(it.tokenBalance)} $paymentTokenSymbol" } ?: "--"
  val hasSufficientTokenBalance = quote?.tokenBalance?.let { it >= totalCostRaw } ?: false
  val canBuy = isAuthenticated && account != null && quote != null && !loadingQuote && !buying && hasSufficientTokenBalance

  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    PirateMobileHeader(
      title = "Credits",
      onClosePress = onClose,
    )

    if (!isAuthenticated || account == null) {
      Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Sign in to buy credits.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      return@Column
    }

    Box(
      modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 24.dp),
      contentAlignment = Alignment.Center,
    ) {
      when {
        loadingQuote -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(
              text = "  Loading credits...",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        quote != null -> {
          val quoteState = quote
          Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = quoteState?.walletCredits?.toString() ?: "0",
              style = MaterialTheme.typography.displayLarge,
              fontWeight = FontWeight.Bold,
              textAlign = TextAlign.Center,
            )
            Text(
              text = "available",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              CREDIT_PACKS.forEach { credits ->
                FilterChip(
                  modifier = Modifier.weight(1f),
                  selected = selectedCredits == credits,
                  onClick = { selectedCredits = credits },
                  label = {
                    Text(
                      text = "$credits",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = if (selectedCredits == credits) FontWeight.SemiBold else FontWeight.Medium,
                    )
                  },
                  enabled = !buying,
                )
              }
            }

            CreditsInfoRow(
              label = "Price",
              value = "$creditPriceDisplay / credit",
            )
            CreditsInfoRow(
              label = "Total",
              value = totalCostDisplay,
            )
            CreditsInfoRow(
              label = "Wallet",
              value = walletBalanceDisplay,
            )
            val quota = assistantQuota?.quota
            if (quota != null) {
              val verified = quota.verificationTier.equals("verified", ignoreCase = true)
              CreditsInfoRow(
                label = "Violet tier",
                value = if (verified) "Verified" else "Unverified",
              )
              CreditsInfoRow(
                label = "Violet messages",
                value = "${quota.freeChatMessagesRemaining}/${quota.freeChatMessagesLimit} free left",
              )
              CreditsInfoRow(
                label = "Violet call",
                value = "${formatCallSeconds(quota.freeCallSecondsRemaining)} / ${formatCallSeconds(quota.freeCallSecondsLimit)} free left",
              )
              if (!verified) {
                Text(
                  text = "Verify with Self.xyz to unlock 30 free messages/day and 3 free call minutes/day.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            if (!hasSufficientTokenBalance) {
              Text(
                text = "Not enough $paymentTokenSymbol",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
              )
            }
          }
        }
        else -> {
          Text(
            text = error ?: "Credits unavailable.",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    }

    Surface(
      modifier = Modifier.fillMaxWidth(),
      tonalElevation = 3.dp,
      shadowElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        PiratePrimaryButton(
          text = "Buy",
          onClick = {
            val passkeyAccount = account ?: return@PiratePrimaryButton
            if (!canBuy) return@PiratePrimaryButton
            scope.launch {
              buying = true
              val result =
                StudyCreditsApi.buy(
                  activity = activity,
                  account = passkeyAccount,
                  creditCount = selectedCredits,
                  sessionKey =
                    SessionKeyManager.load(activity)?.takeIf {
                      SessionKeyManager.isValid(it, ownerAddress = passkeyAccount.address)
                    },
                )
              buying = false
              if (!result.success) {
                onShowMessage(result.error ?: "Credit purchase failed.")
                return@launch
              }
              onShowMessage("Purchased ${result.creditsPurchased} credits")
              onPurchased()
            }
          },
          enabled = canBuy,
          modifier = Modifier.fillMaxWidth(),
          loading = buying,
        )
      }
    }
  }
}

@Composable
private fun CreditsInfoRow(
  label: String,
  value: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

private fun tokenSymbolForAddress(address: String): String {
  return if (address.equals(TempoClient.ALPHA_USD, ignoreCase = true)) "αUSD" else "Token"
}

private fun formatTokenAmount(rawAmount: BigInteger): String {
  return TempoClient.formatUnits(rawAmount = rawAmount, decimals = 6, maxFractionDigits = 2)
}
