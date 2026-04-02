package sc.pirate.app.post

import android.content.Context
import android.net.Uri
import android.util.Log
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.identity.SelfVerificationService
import sc.pirate.app.auth.privy.PrivyRelayClient
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.crypto.P256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256

data class PostCreateTxResult(
  val success: Boolean,
  val txHash: String? = null,
  val postId: String? = null,
  val videoRef: String? = null,
  val captionRef: String? = null,
  val error: String? = null,
)

data class PostActionTxResult(
  val success: Boolean,
  val txHash: String? = null,
  val error: String? = null,
)

data class PostViewerState(
  val likedByViewer: Boolean,
  val likeCount: Long,
)

object PostTxRepository {
  private const val TAG = "PostTxRepository"
  private const val ZERO_BYTES32 = "0x0000000000000000000000000000000000000000000000000000000000000000"
  private const val MIN_GAS_LIMIT_CREATE = 500_000L
  private const val MIN_GAS_LIMIT_ACTION = 240_000L

  fun isConfigured(): Boolean = PostTxInternals.feedContractOrNull() != null

  suspend fun createPost(
    context: Context,
    ownerAddress: String,
    songTrackId: String?,
    songStoryIpId: String? = null,
    videoUri: Uri,
    captionText: String = "",
    taggedItems: List<PostTaggedItem> = emptyList(),
    previewAtMs: Long? = null,
  ): PostCreateTxResult {
    return runCatching {
      val sender = PostTxInternals.normalizeAddress(ownerAddress)
      val feed = PostTxInternals.feedContractOrNull() ?: throw IllegalStateException("Feed contract is not configured")

      val isVerified = withContext(Dispatchers.IO) { PostTxInternals.isVerifiedForFeed(feed = feed, userAddress = sender) }
      if (!isVerified) {
        throw IllegalStateException("Self verification required before posting")
      }

      val videoUpload = withContext(Dispatchers.IO) { PostTxInternals.uploadVideo(context = context, userAddress = sender, uri = videoUri) }
      val previewUpload = withContext(Dispatchers.IO) {
        PostTxInternals.uploadPreviewImage(
          context = context,
          userAddress = sender,
          uri = videoUri,
          previewAtMs = previewAtMs,
        )
      }
      val captionUpload = withContext(Dispatchers.IO) {
        PostTxInternals.uploadCaption(
          userAddress = sender,
          captionText = captionText,
          taggedItems = taggedItems,
          previewAtMs = previewAtMs,
          previewRef = previewUpload.ref,
        )
      }
      val resolvedTrackId =
        songTrackId
          ?.trim()
          .orEmpty()
          .ifBlank { ZERO_BYTES32 }
      val trackId = PostTxInternals.normalizeBytes32(resolvedTrackId, "song track id")
      val requestedSongStoryIpId = songStoryIpId?.trim().orEmpty()
      val calldata =
        if (requestedSongStoryIpId.isBlank()) {
          PostTxInternals.encodeCreatePostCalldata(
            songTrackId = trackId,
            videoRef = videoUpload.ref,
            captionRef = captionUpload.ref,
          )
        } else {
          PostTxInternals.encodeCreatePostCalldata(
            songTrackId = trackId,
            songStoryIpId = requestedSongStoryIpId,
            videoRef = videoUpload.ref,
            captionRef = captionUpload.ref,
          )
        }

      val txHash =
        PrivyRelayClient.submitContractCall(
          context = context,
          chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
          to = feed,
          data = calldata,
          intentType = "pirate.post.create",
          intentArgs =
            JSONObject()
              .put("songTrackId", trackId)
              .put("songStoryIpId", requestedSongStoryIpId.ifBlank { JSONObject.NULL })
              .put("videoRef", videoUpload.ref)
              .put("captionRef", captionUpload.ref),
        )
      val receipt = withContext(Dispatchers.IO) { PostTxInternals.awaitPostReceipt(txHash) }
      if (!receipt.isSuccess) {
        throw IllegalStateException("Post transaction reverted on-chain: $txHash")
      }

      val postId = withContext(Dispatchers.IO) { PostTxInternals.extractPostIdFromReceipt(txHash = txHash, feedAddress = feed) }
      runCatching {
        PostStoryEnqueueSync.enqueueOrPersist(
          context = context,
          ownerAddress = sender,
          chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
          txHash = txHash,
          postId = postId,
        )
      }.onFailure { error ->
        Log.w(TAG, "post-story enqueue deferred postId=$postId txHash=$txHash err=${error.message}")
      }
      PostCreateTxResult(
        success = true,
        txHash = txHash,
        postId = postId,
        videoRef = videoUpload.ref,
        captionRef = captionUpload.ref,
      )
    }.getOrElse { error ->
      PostCreateTxResult(
        success = false,
        error = error.message ?: "Create post failed",
      )
    }
  }

  suspend fun likePost(
    context: Context,
    ownerAddress: String,
    postId: String,
  ): PostActionTxResult = submitPostAction(
    context = context,
    ownerAddress = ownerAddress,
    postId = postId,
    functionName = "likePost",
  )

  suspend fun unlikePost(
    context: Context,
    ownerAddress: String,
    postId: String,
  ): PostActionTxResult = submitPostAction(
    context = context,
    ownerAddress = ownerAddress,
    postId = postId,
    functionName = "unlikePost",
  )

  private suspend fun submitPostAction(
    context: Context,
    ownerAddress: String,
    postId: String,
    functionName: String,
  ): PostActionTxResult {
    return runCatching {
      val sender = PostTxInternals.normalizeAddress(ownerAddress)
      val feed = PostTxInternals.feedContractOrNull() ?: throw IllegalStateException("Feed contract is not configured")

      if (functionName != "unlikePost") {
        val isVerified = withContext(Dispatchers.IO) { PostTxInternals.isVerifiedForFeed(feed = feed, userAddress = sender) }
        if (!isVerified) {
          when (withContext(Dispatchers.IO) {
            SelfVerificationService.checkIdentity(SongPublishService.API_CORE_URL, sender)
          }) {
            is SelfVerificationService.IdentityResult.Verified -> Unit
            is SelfVerificationService.IdentityResult.NotVerified -> {
              throw IllegalStateException("Self verification required")
            }
            is SelfVerificationService.IdentityResult.Error -> {
              throw IllegalStateException("Self verification check failed")
            }
          }
        }
      }

      val normalizedPostId = PostTxInternals.normalizeBytes32(postId, "postId")
      val calldata = PostTxInternals.encodePostActionCalldata(functionName = functionName, postId = normalizedPostId)
      val txHash =
        PrivyRelayClient.submitContractCall(
          context = context,
          chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
          to = feed,
          data = calldata,
          intentType = if (functionName == "likePost") "pirate.post.like" else "pirate.post.unlike",
          intentArgs = JSONObject().put("postId", normalizedPostId),
        )
      val receipt = withContext(Dispatchers.IO) { PostTxInternals.awaitPostReceipt(txHash) }
      if (!receipt.isSuccess) {
        throw IllegalStateException("$functionName transaction reverted on-chain: $txHash")
      }
      PostActionTxResult(success = true, txHash = txHash)
    }.getOrElse { error ->
      PostActionTxResult(success = false, error = error.message ?: "$functionName failed")
    }
  }

  suspend fun fetchViewerState(
    ownerAddress: String,
    postId: String,
  ): PostViewerState {
    val feed = PostTxInternals.feedContractOrNull() ?: throw IllegalStateException("Feed contract is not configured")
    val viewer = PostTxInternals.normalizeAddress(ownerAddress)
    val normalizedPostId = PostTxInternals.normalizeBytes32(postId, "postId")

    return withContext(Dispatchers.IO) {
      val likeCountFunction = Function(
        "likeCounts",
        listOf(Bytes32(P256Utils.hexToBytes(normalizedPostId))),
        listOf(object : TypeReference<Uint256>() {}),
      )
      val likeCountHex = PostTxInternals.ethCall(to = feed, data = FunctionEncoder.encode(likeCountFunction))
      val likeCountDecoded = FunctionReturnDecoder.decode(likeCountHex, likeCountFunction.outputParameters)
      val likeCountBig = (likeCountDecoded.firstOrNull() as? Uint256)?.value
      val likeCount = likeCountBig?.toLong() ?: 0L

      val likedFunction = Function(
        "isLiked",
        listOf(Bytes32(P256Utils.hexToBytes(normalizedPostId)), Address(viewer)),
        listOf(object : TypeReference<Bool>() {}),
      )
      val likedHex = PostTxInternals.ethCall(to = feed, data = FunctionEncoder.encode(likedFunction))
      val likedDecoded = FunctionReturnDecoder.decode(likedHex, likedFunction.outputParameters)
      val liked = (likedDecoded.firstOrNull() as? Bool)?.value ?: false

      PostViewerState(
        likedByViewer = liked,
        likeCount = if (likeCount < 0L) 0L else likeCount,
      )
    }
  }

}
