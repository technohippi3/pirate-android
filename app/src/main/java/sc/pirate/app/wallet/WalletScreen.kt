package sc.pirate.app.wallet

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.Copy
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.assistant.AssistantQuotaApi
import sc.pirate.app.assistant.AssistantQuotaStatus
import sc.pirate.app.assistant.formatCallSeconds
import sc.pirate.app.store.StudyCreditsApi
import sc.pirate.app.store.StudyCreditsQuote
import sc.pirate.app.store.formatStoreTokenAmount
import sc.pirate.app.store.readErc20BalanceRaw
import sc.pirate.app.store.tokenSymbolForAddress
import sc.pirate.app.ui.PirateIconButton
import sc.pirate.app.ui.PirateMobileHeader
import sc.pirate.app.ui.PiratePrimaryButton
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CREDIT_PACKS = listOf(5, 10, 25)

private fun formatUsd(
  rawAmount: BigInteger,
  decimals: Int,
): String {
  if (rawAmount <= BigInteger.ZERO) return "0.00"
  val safeDecimals = decimals.coerceAtLeast(0)
  val divisor = BigDecimal.TEN.pow(safeDecimals)
  val value = rawAmount.toBigDecimal().divide(divisor, 2, RoundingMode.DOWN)
  return value.setScale(2, RoundingMode.DOWN).toPlainString()
}

private data class WalletAssetBalance(
  val id: String,
  val symbol: String,
  val network: String,
  val rawAmount: BigInteger,
  val decimals: Int,
)

@Composable
fun WalletScreen(
  activity: FragmentActivity,
  isAuthenticated: Boolean,
  walletAddress: String?,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
  onCreditsPurchased: (() -> Unit)? = null,
) {
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()

  var balances by remember(walletAddress) { mutableStateOf<List<WalletAssetBalance>>(emptyList()) }
  var loadingBalances by remember(walletAddress) { mutableStateOf(false) }
  var balanceError by remember(walletAddress) { mutableStateOf<String?>(null) }
  var balanceRefreshNonce by remember { mutableIntStateOf(0) }

  var selectedCredits by remember { mutableIntStateOf(5) }
  var creditsQuote by remember { mutableStateOf<StudyCreditsQuote?>(null) }
  var loadingCredits by remember { mutableStateOf(false) }
  var creditsError by remember { mutableStateOf<String?>(null) }
  var buyingCredits by remember { mutableStateOf(false) }
  var creditsRefreshNonce by remember { mutableIntStateOf(0) }
  var assistantQuota by remember { mutableStateOf<AssistantQuotaStatus?>(null) }

  val paymentTokenSymbol = creditsQuote?.paymentToken?.let(::tokenSymbolForAddress) ?: tokenSymbolForAddress(PirateChainConfig.STORY_STABLE_TOKEN)
  val totalCostRaw = creditsQuote?.creditPrice?.multiply(BigInteger.valueOf(selectedCredits.toLong())) ?: BigInteger.ZERO
  val totalCostDisplay = creditsQuote?.let { "${formatStoreTokenAmount(totalCostRaw)} $paymentTokenSymbol" } ?: "--"
  val creditPriceDisplay = creditsQuote?.let { "${formatStoreTokenAmount(it.creditPrice)} $paymentTokenSymbol" } ?: "--"
  val walletBalanceDisplay = creditsQuote?.let { "${formatStoreTokenAmount(it.tokenBalance)} $paymentTokenSymbol" } ?: "--"
  val hasSufficientTokenBalance = creditsQuote?.tokenBalance?.let { it >= totalCostRaw } ?: false
  val canBuyCredits = !walletAddress.isNullOrBlank() && creditsQuote != null && !loadingCredits && !buyingCredits && hasSufficientTokenBalance

  LaunchedEffect(isAuthenticated, walletAddress, balanceRefreshNonce, creditsRefreshNonce) {
    val address = walletAddress?.trim()
    if (!isAuthenticated || address.isNullOrBlank()) {
      balances = emptyList()
      balanceError = null
      loadingBalances = false
      return@LaunchedEffect
    }

    loadingBalances = true
    balanceError = null
    runCatching {
      withContext(Dispatchers.IO) {
        val stableToken = creditsQuote?.paymentToken ?: PirateChainConfig.STORY_STABLE_TOKEN
        val stableRaw = readErc20BalanceRaw(address = address, token = stableToken)
        listOf(
          WalletAssetBalance(
            id = "story:${stableToken.lowercase()}",
            symbol = tokenSymbolForAddress(stableToken),
            network = "Story",
            rawAmount = stableRaw,
            decimals = 6,
          ),
        )
      }
    }.onSuccess {
      balances = it
    }.onFailure { err ->
      balanceError = err.message ?: "Failed to load wallet balances."
      Log.e("WalletScreen", "Failed to refresh wallet balances for address=$address", err)
      onShowMessage("Couldn't refresh wallet balances.")
    }
    loadingBalances = false
  }

  LaunchedEffect(isAuthenticated, walletAddress, creditsRefreshNonce) {
    val address = walletAddress?.trim()
    if (!isAuthenticated || address.isNullOrBlank()) {
      creditsQuote = null
      loadingCredits = false
      creditsError = null
      return@LaunchedEffect
    }
    loadingCredits = true
    creditsError = null
    creditsQuote =
      runCatching { StudyCreditsApi.quote(address) }
        .onFailure { err ->
          creditsError = err.message ?: "Unable to load credits."
          Log.e("WalletScreen", "Failed to refresh credits quote for address=$address", err)
        }
        .getOrNull()
    assistantQuota =
      runCatching { AssistantQuotaApi.fetchQuota(activity, address) }
        .getOrNull()
    loadingCredits = false
  }

  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    PirateMobileHeader(
      title = "Wallet",
      onClosePress = onClose,
      rightSlot = {
        PirateIconButton(
          onClick = {
            balanceRefreshNonce += 1
            creditsRefreshNonce += 1
          },
          enabled = isAuthenticated && !walletAddress.isNullOrBlank() && !loadingBalances && !loadingCredits,
        ) {
          Icon(PhosphorIcons.Regular.ArrowClockwise, contentDescription = "Refresh wallet")
        }
      },
    )

    if (!isAuthenticated || walletAddress.isNullOrBlank()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Sign in to view your wallet",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      return@Column
    }

    val address = walletAddress.trim()

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        OutlinedTextField(
          value = address,
          onValueChange = {},
          modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
          readOnly = true,
          singleLine = true,
          label = { Text("Address") },
          trailingIcon = {
            PirateIconButton(
              onClick = {
                clipboard.setText(AnnotatedString(address))
                onShowMessage("Wallet address copied.")
              },
            ) {
              Icon(PhosphorIcons.Regular.Copy, contentDescription = "Copy address")
            }
          },
        )
      }

      item {
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = "Credits",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold,
            )

            when {
              loadingCredits -> {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.Center,
                ) {
                  CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                  Text(
                    text = "  Loading credits...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              creditsQuote != null -> {
                Text(
                  text = creditsQuote?.walletCredits?.toString() ?: "0",
                  style = MaterialTheme.typography.displayLarge,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text = "Amount",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  items(CREDIT_PACKS, key = { it }) { credits ->
                    FilterChip(
                      selected = selectedCredits == credits,
                      onClick = { selectedCredits = credits },
                      label = {
                        Text(
                          text = "$credits",
                          style = MaterialTheme.typography.titleMedium,
                          fontWeight = if (selectedCredits == credits) FontWeight.SemiBold else FontWeight.Medium,
                        )
                      },
                      enabled = !buyingCredits,
                    )
                  }
                }

                CreditsInfoRow(label = "Price", value = "$creditPriceDisplay / credit")
                CreditsInfoRow(label = "Total", value = totalCostDisplay)
                CreditsInfoRow(label = "Wallet", value = walletBalanceDisplay)
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

                PiratePrimaryButton(
                  text = "Buy",
                  loading = buyingCredits,
                  onClick = {
                    if (!canBuyCredits) return@PiratePrimaryButton
                    scope.launch {
                      buyingCredits = true
                      val result =
                        StudyCreditsApi.buy(
                          context = activity.applicationContext,
                          ownerAddress = address,
                          creditCount = selectedCredits,
                        )
                      buyingCredits = false
                      if (!result.success) {
                        onShowMessage(result.error ?: "Credit purchase failed.")
                        return@launch
                      }
                      onShowMessage("Purchased ${result.creditsPurchased} credits")
                      creditsRefreshNonce += 1
                      balanceRefreshNonce += 1
                      onCreditsPurchased?.invoke()
                    }
                  },
                  enabled = canBuyCredits,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
              else -> {
                Text(
                  text = creditsError ?: "Credits unavailable.",
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
          }
        }
      }

      item {
        Text(
          text = "Balances",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
      }

      if (loadingBalances && balances.isEmpty()) {
        item {
          Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
          }
        }
      }

      if (!balanceError.isNullOrBlank()) {
        item {
          Text(
            balanceError!!,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      items(
        items = balances,
        key = { it.id },
      ) { balance ->
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
          Column(
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  balance.symbol,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  balance.network,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Text(
                "\$${formatUsd(balance.rawAmount, decimals = balance.decimals)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
            }
            HorizontalDivider()
          }
        }
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
