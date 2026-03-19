package sc.pirate.app.music

import java.util.Locale

@JvmInline
internal value class CanonicalTrackId private constructor(
  val value: String,
) {
  companion object {
    private val bytes32Pattern = Regex("^[a-f0-9]{64}$")

    fun parse(raw: String?): CanonicalTrackId? {
      val clean = raw?.trim().orEmpty().removePrefix("0x").removePrefix("0X").lowercase(Locale.US)
      if (!bytes32Pattern.matches(clean)) return null
      return CanonicalTrackId("0x$clean")
    }
  }

  override fun toString(): String = value
}
