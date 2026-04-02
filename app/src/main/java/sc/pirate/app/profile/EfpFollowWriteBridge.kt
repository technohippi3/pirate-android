package sc.pirate.app.profile

import android.content.Context
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.privy.PrivyRelayClient

internal data class EfpFollowWriteResult(
  val success: Boolean,
  val txHash: String? = null,
  val error: String? = null,
)

internal object EfpFollowWriteBridge {
  suspend fun follow(
    context: Context,
    viewerAddress: String,
    targetAddress: String,
  ): EfpFollowWriteResult = submit(context, viewerAddress, targetAddress, followed = true)

  suspend fun unfollow(
    context: Context,
    viewerAddress: String,
    targetAddress: String,
  ): EfpFollowWriteResult = submit(context, viewerAddress, targetAddress, followed = false)

  private suspend fun submit(
    context: Context,
    viewerAddress: String,
    targetAddress: String,
    followed: Boolean,
  ): EfpFollowWriteResult {
    return runCatching {
      val viewer = viewerAddress.trim().lowercase()
      val target = targetAddress.trim().lowercase()
      require(viewer.isNotBlank()) { "Pirate wallet is unavailable. Sign in again." }
      require(target.isNotBlank()) { "Invalid target address." }
      require(!viewer.equals(target, ignoreCase = true)) { "Cannot follow yourself." }

      val lists = EfpFollowApi.fetchProfileLists(viewer)
      val existingStorage =
        lists.primaryList
          ?.takeIf { it.isNotBlank() }
          ?.let { EfpFollowApi.getListStorageLocation(it) }
      val transactions = EfpFollowApi.buildFollowTransactions(viewer, target, existingStorage, followed)
      var lastTxHash: String? = null
      for (transaction in transactions) {
        val txHash =
          PrivyRelayClient.submitContractCall(
            context = context.applicationContext,
            chainId = PirateChainConfig.BASE_SEPOLIA_CHAIN_ID,
            to = transaction.to,
            data = transaction.data,
            intentType = transaction.intentType,
            intentArgs = transaction.intentArgs,
          )
        val mined = EfpFollowApi.waitForTransactionReceipt(txHash)
        check(mined) { "EFP transaction reverted on-chain." }
        lastTxHash = txHash
      }
      lastTxHash ?: error("Follow transaction submission failed.")
    }.fold(
      onSuccess = { txHash -> EfpFollowWriteResult(success = true, txHash = txHash) },
      onFailure = { error ->
        EfpFollowWriteResult(success = false, error = error.message ?: "Follow transaction failed")
      },
    )
  }
}
