package sc.pirate.app.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FeedPostPreview(
  val id: String,
  val creator: String,
  val songTrackId: String,
  val songStoryIpId: String,
  val postStoryIpId: String?,
  val videoRef: String,
  val videoUrl: String?,
  val captionRef: String,
  val captionText: String?,
  val songTitle: String?,
  val songArtist: String?,
  val likeCount: Long,
  val createdAtSec: Long,
)

object HomeFeedApi {
  suspend fun fetchLatestPost(context: Context): FeedPostPreview? = withContext(Dispatchers.IO) {
    val post = FeedRepository.fetchFeedPage(context = context.applicationContext, limit = 1, cursor = null).posts.firstOrNull()
      ?: return@withContext null
    FeedPostPreview(
      id = post.id,
      creator = post.creator,
      songTrackId = post.songTrackId,
      songStoryIpId = post.songStoryIpId,
      postStoryIpId = post.postStoryIpId,
      videoRef = post.videoRef,
      videoUrl = post.videoUrl,
      captionRef = post.captionRef,
      captionText = post.captionText.ifBlank { null },
      songTitle = post.songTitle,
      songArtist = post.songArtist,
      likeCount = post.likeCount,
      createdAtSec = post.createdAtSec,
    )
  }
}
