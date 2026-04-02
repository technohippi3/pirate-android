package sc.pirate.app.song

data class SongStats(
  val trackId: String,
  val title: String,
  val artistLabel: String,
  val album: String,
  val coverCid: String?,
  val lyricsRef: String? = null,
  val scrobbleCountTotal: Long,
  val scrobbleCountVerified: Long,
  val durationSec: Int = 0,
  val registeredAtSec: Long,
  val publisherAddress: String? = null,
)

data class SongListenerRow(
  val userAddress: String,
  val scrobbleCount: Int,
  val lastScrobbleAtSec: Long,
)

data class SongScrobbleRow(
  val userAddress: String,
  val playedAtSec: Long,
)

data class ArtistTrackRow(
  val trackId: String,
  val title: String,
  val artistLabel: String,
  val album: String,
  val coverCid: String?,
  val lyricsRef: String? = null,
  val recordingMbid: String? = null,
  val scrobbleCountTotal: Long,
  val scrobbleCountVerified: Long,
  val publisherAddress: String? = null,
)

data class ArtistCatalogSongRow(
  val trackId: String,
  val title: String,
  val artistLabel: String,
  val album: String,
  val coverUrl: String?,
  val artworkUrl: String?,
  val lyricsRef: String? = null,
)

data class ArtistListenerRow(
  val userAddress: String,
  val scrobbleCount: Long,
  val lastScrobbleAtSec: Long,
)

data class StudySetStatus(
  val ready: Boolean,
  val studySetRef: String?,
  val studySetHash: String?,
  val errorCode: String?,
  val error: String?,
)

data class StudySetGenerateResult(
  val success: Boolean,
  val cached: Boolean,
  val studySetRef: String?,
  val studySetHash: String?,
  val errorCode: String?,
  val error: String?,
  val lineCount: Int? = null,
  val minLineCount: Int? = null,
)

data class GeniusAnnotationCoverage(
  val insufficientLyricLines: Boolean,
  val lineCount: Int?,
  val minLineCount: Int,
  val geniusSongId: Int?,
  val geniusSongUrl: String?,
)
