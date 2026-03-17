package com.pirate.app.songpicker

data class SongPickerSong(
  val trackId: String,
  val songStoryIpId: String?,
  val title: String,
  val artist: String,
  val coverCid: String?,
  val durationSec: Int,
  val pieceCid: String?,
  val commercialUse: Boolean = true,
  val commercialRevSharePpm8: Int = 0,
  val approvalMode: String = "auto",
  val approvalSlaSec: Int = 259200,
  val remixable: Boolean = false,
)
