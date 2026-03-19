package sc.pirate.app.music

import sc.pirate.app.BuildConfig
import sc.pirate.app.arweave.ArweaveTurboConfig

/**
 * Resolve on-chain/off-chain cover refs to a fetchable URL.
 *
 * Supported:
 * - IPFS CID (Qm..., bafy...) via configured IPFS gateway
 * - ipfs://<cid> via configured IPFS gateway
 * - ls3://<id> and load-s3://<id> via Load gateway
 * - ar://<id> via Arweave gateway
 */
object CoverRef {
  private const val DEFAULT_LOAD_GATEWAY = "https://gateway.s3-node-1.load.network"
  private val API_CORE_BASE_URL = BuildConfig.API_CORE_URL.trim().trimEnd('/')
  private val IPFS_GATEWAY = BuildConfig.IPFS_GATEWAY_URL.trim().trimEnd('/')
  private val LOAD_GATEWAY = DEFAULT_LOAD_GATEWAY.trim().trimEnd('/')
  private val ARWEAVE_GATEWAY = ArweaveTurboConfig.GATEWAY_URL.trim().trimEnd('/')

  private fun isIpfsCid(value: String): Boolean {
    val v = value.trim()
    if (v.isEmpty()) return false
    if (v.startsWith("Qm") && v.length >= 46) return true
    // CIDv1 in base32 is lowercase and always starts with "b".
    return Regex("^b[a-z2-7]{20,}$").matches(v)
  }

  private fun isDataItemId(value: String): Boolean {
    return Regex("^[A-Za-z0-9_-]{32,}$").matches(value.trim())
  }

  private fun isSha256Hex(value: String): Boolean {
    return Regex("^[a-fA-F0-9]{64}$").matches(value.trim())
  }

  private fun resolveStagedCoverUrl(coverId: String): String {
    return "$API_CORE_BASE_URL/api/music/cover/${coverId.trim().lowercase()}"
  }

  private fun resolveLoadGatewayUrl(dataItemId: String): String {
    return "$LOAD_GATEWAY/resolve/${dataItemId.trim()}"
  }

  private fun buildImageTransformQuery(
    width: Int?,
    height: Int?,
    format: String?,
    quality: Int?,
  ): String {
    val parts = ArrayList<String>(4)
    if (width != null && width > 0) parts.add("img-width=$width")
    if (height != null && height > 0) parts.add("img-height=$height")
    if (!format.isNullOrBlank()) parts.add("img-format=${format.trim()}")
    if (quality != null && quality > 0) parts.add("img-quality=$quality")
    return if (parts.isEmpty()) "" else "?${parts.joinToString("&")}"
  }

  fun resolveCoverUrl(
    ref: String?,
    width: Int? = null,
    height: Int? = null,
    format: String? = "webp",
    quality: Int? = 80,
  ): String? {
    val raw = ref?.trim().orEmpty()
    if (raw.isEmpty()) return null

    if (raw.startsWith("content://") || raw.startsWith("file://")) {
      return raw
    }
    if (raw.startsWith("https://") || raw.startsWith("http://")) {
      return raw
    }

    if (raw.startsWith("ipfs://")) {
      val cid = raw.removePrefix("ipfs://").trim()
      if (cid.isEmpty()) return null
      if (isSha256Hex(cid)) {
        return resolveStagedCoverUrl(cid)
      }
      // Some refs are mislabeled as ipfs:// but carry Load data-item IDs.
      if (isDataItemId(cid) && !isIpfsCid(cid)) {
        return resolveLoadGatewayUrl(cid)
      }
      return "$IPFS_GATEWAY/$cid${buildImageTransformQuery(width, height, format, quality)}"
    }

    if (raw.startsWith("ls3://")) {
      val id = raw.removePrefix("ls3://").trim()
      if (id.isEmpty()) return null
      return resolveLoadGatewayUrl(id)
    }

    if (raw.startsWith("load-s3://")) {
      val id = raw.removePrefix("load-s3://").trim()
      if (id.isEmpty()) return null
      return resolveLoadGatewayUrl(id)
    }

    if (raw.startsWith("ar://")) {
      val id = raw.removePrefix("ar://").trim()
      if (id.isEmpty()) return null
      return "$ARWEAVE_GATEWAY/$id"
    }

    if (isIpfsCid(raw)) {
      return "$IPFS_GATEWAY/$raw${buildImageTransformQuery(width, height, format, quality)}"
    }

    if (isSha256Hex(raw)) {
      return resolveStagedCoverUrl(raw)
    }

    // Fallback: treat bare base64url-ish IDs as Load dataitem refs.
    if (isDataItemId(raw)) {
      return resolveLoadGatewayUrl(raw)
    }

    return null
  }
}
