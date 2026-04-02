package sc.pirate.app.scrobble

data class ScrobbleInput(
  val artist: String,
  val title: String,
  val album: String?,
  val durationSec: Int,
  val playedAtSec: Long,
)

data class ScrobbleSubmitResult(
  val success: Boolean,
  val txHash: String? = null,
  val trackId: String? = null,
  val usedRegisterPath: Boolean = false,
  val pendingConfirmation: Boolean = false,
  val error: String? = null,
)
