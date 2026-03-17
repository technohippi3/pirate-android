package com.pirate.app.home

data class FeedPostCore(
  val id: String,
  val creator: String,
  val songTrackId: String,
  val songStoryIpId: String,
  val postStoryIpId: String?,
  val videoRef: String,
  val captionRef: String,
  val translationRef: String?,
  val likeCount: Long,
  val createdAtSec: Long,
)

data class FeedTaggedItem(
  val requestedUrl: String,
  val canonicalUrl: String,
  val merchant: String,
  val title: String,
  val brand: String? = null,
  val price: Double? = null,
  val currency: String? = null,
  val size: String? = null,
  val condition: String? = null,
  val imageUrl: String? = null,
  val images: List<String> = emptyList(),
)

data class FeedPostResolved(
  val id: String,
  val creator: String,
  val creatorHandle: String? = null,
  val creatorDisplayName: String? = null,
  val creatorAvatarRef: String? = null,
  val creatorAvatarUrl: String? = null,
  val songTrackId: String,
  val songStoryIpId: String,
  val postStoryIpId: String?,
  val videoRef: String,
  val videoUrl: String?,
  val captionRef: String,
  val previewRef: String?,
  val previewUrl: String?,
  val previewAtMs: Long?,
  val translationRef: String?,
  val likeCount: Long,
  val createdAtSec: Long,
  val captionText: String,
  val captionLanguage: String? = null,
  val songTitle: String?,
  val songArtist: String?,
  val songCoverRef: String? = null,
  val songCoverUrl: String? = null,
  val taggedItems: List<FeedTaggedItem> = emptyList(),
  val translationText: String? = null,
  val translationSourceLanguage: String? = null,
)

data class FeedPageCursor(
  val createdAtSec: Long,
  val postId: String,
)

data class FeedPage(
  val posts: List<FeedPostResolved>,
  val nextCursor: FeedPageCursor?,
  val hasMore: Boolean,
)
