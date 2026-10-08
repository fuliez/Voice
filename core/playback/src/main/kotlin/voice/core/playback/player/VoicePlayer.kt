package voice.core.playback.player

import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import voice.core.analytics.api.Analytics
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.durationMs
import voice.core.data.repo.BookRepository
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.SeekTimeStore
import voice.core.data.store.SkipIntroSecondsStore
import voice.core.data.store.SkipOutroSecondsStore
import voice.core.logging.api.Logger
import voice.core.playback.misc.Decibel
import voice.core.playback.misc.VolumeGain
import voice.core.playback.session.MediaId
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.playbackItemForPosition
import voice.core.playback.session.playbackItems
import voice.core.playback.session.positionInChapter
import voice.core.playback.session.positionInMediaItem
import voice.core.playback.session.realChapterId
import voice.core.playback.session.toMediaIdOrNull
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerState
import java.time.Instant
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Inject
class VoicePlayer(
  private val player: Player,
  private val repo: BookRepository,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  @AutoRewindAmountStore
  private val autoRewindAmountStore: DataStore<Int>,
  @SkipIntroSecondsStore
  private val skipIntroSecondsStore: DataStore<Int>,
  @SkipOutroSecondsStore
  private val skipOutroSecondsStore: DataStore<Int>,
  private val mediaItemProvider: MediaItemProvider,
  private val scope: CoroutineScope,
  private val volumeGain: VolumeGain,
  private val sleepTimer: SleepTimer,
  private val analytics: Analytics,
) : ForwardingPlayer(player) {

  private val skipConfig = MutableStateFlow<IntroOutroSkipConfig?>(null)
  private var currentBook: Book? = null
  private var pendingPlay = false
  private var outroCheckJob: Job? = null
  private var skipConfigJob: Job? = null
  private var isApplyingSkip = false

  /**
   * The file whose outro was already skipped for good.
   *
   * Seeking to the very end of the last playback item can land a millisecond before the file end,
   * so the position alone cannot tell that the file is already done.
   */
  private var outroSkippedChapterId: ChapterId? = null

  private val endOfChapterSleepTimerListener = object : Player.Listener {
    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
        pauseAndDisableSleepTimerIfEndOfChapter()
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == STATE_ENDED) {
        pauseAndDisableSleepTimerIfEndOfChapter()
      }
    }
  }

  private val introOutroSkipListener = object : Player.Listener {
    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      checkIntroOutroSkipWhilePlaying()
    }

    override fun onMediaItemTransition(
      mediaItem: MediaItem?,
      reason: Int,
    ) {
      checkIntroOutroSkipWhilePlaying()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      updateOutroCheckJob()
      if (playbackState == STATE_READY) {
        checkIntroOutroSkipWhilePlaying()
      }
    }

    override fun onPlayWhenReadyChanged(
      playWhenReady: Boolean,
      reason: Int,
    ) {
      updateOutroCheckJob()
    }
  }

  init {
    player.addListener(endOfChapterSleepTimerListener)
    player.addListener(introOutroSkipListener)
    skipConfigJob = scope.launch {
      combine(
        skipIntroSecondsStore.data,
        skipOutroSecondsStore.data,
      ) { introSeconds, outroSeconds ->
        IntroOutroSkipConfig(introSeconds = introSeconds, outroSeconds = outroSeconds)
      }.collect { config ->
        skipConfig.value = config
        onSkipConfigChanged()
      }
    }
  }

  private fun pauseAndDisableSleepTimerIfEndOfChapter() {
    if (sleepTimer.state.value !is SleepTimerState.Enabled.WithEndOfChapter) return
    Logger.v("Pausing due to EndOfChapter")
    sleepTimer.disable()
    player.pause()
  }

  private fun onSkipConfigChanged() {
    updateOutroCheckJob()
    checkIntroOutroSkipWhilePlaying()
  }

  private fun updateOutroCheckJob() {
    val outroMs = skipConfig.value?.outroMs ?: 0L
    val shouldRun = outroMs > 0L &&
      player.playWhenReady &&
      player.playbackState == Player.STATE_READY &&
      player.currentMediaItemIndex != C.INDEX_UNSET
    val running = outroCheckJob?.isActive == true
    if (!shouldRun) {
      if (running) {
        outroCheckJob?.cancel()
        outroCheckJob = null
      }
      return
    }
    if (running) return
    outroCheckJob = scope.launch {
      while (true) {
        delay(INTRO_OUTRO_CHECK_INTERVAL_MS)
        if (!player.playWhenReady || player.playbackState != Player.STATE_READY) break
        applyIntroOutroSkip()
      }
    }
  }

  private fun checkIntroOutroSkipWhilePlaying() {
    if (!player.playWhenReady) return
    applyIntroOutroSkip()
  }

  /**
   * Re-applies the configured intro and outro skip to the current position.
   *
   * Never changes the play intent on its own, except for the end-of-chapter sleep timer, which stops
   * playback when an automatic jump leaves the current chapter mark.
   *
   * @return `true` when playback must stay stopped after this call.
   */
  @IgnorableReturnValue
  private fun applyIntroOutroSkip(): Boolean {
    if (isApplyingSkip) return false
    val config = skipConfig.value ?: return false
    val location = currentFileLocation() ?: return false
    isApplyingSkip = true
    return try {
      val decision = decideIntroOutroSkip(
        filePositionMs = location.filePositionMs,
        fileDurationMs = location.chapter.duration,
        config = config,
      )
      if (decision !is IntroOutroSkipDecision.SkipOutro) {
        outroSkippedChapterId = null
      }
      when (decision) {
        IntroOutroSkipDecision.None -> false
        is IntroOutroSkipDecision.SeekWithinFile -> {
          seekToFilePosition(location.chapter.id, decision.filePositionMs)
        }
        IntroOutroSkipDecision.SkipOutro -> skipOutro(location.chapter, config)
      }
    } finally {
      isApplyingSkip = false
    }
  }

  private fun currentFileLocation(): FileLocation? {
    val book = currentBook ?: return null
    val mediaId = player.currentMediaItem?.mediaId?.toMediaIdOrNull() ?: return null
    val chapterId = mediaId.realChapterId ?: return null
    val chapter = book.chapters.firstOrNull { it.id == chapterId } ?: return null
    val positionInMediaItem = player.currentPosition.takeUnless { it == C.TIME_UNSET } ?: return null
    val filePositionMs = mediaId.positionInChapter(positionInMediaItem) ?: return null
    if (filePositionMs < 0) return null
    return FileLocation(chapter = chapter, filePositionMs = filePositionMs)
  }

  private fun seekToFilePosition(
    chapterId: ChapterId,
    filePositionMs: Long,
  ): Boolean {
    val target = skipTarget(chapterId, filePositionMs) ?: return false
    return seekToSkipTarget(target)
  }

  private fun skipOutro(
    chapter: Chapter,
    config: IntroOutroSkipConfig,
  ): Boolean {
    val book = currentBook ?: return false
    val nextChapter = book.nextChapterOrNull(chapter.id)
    if (nextChapter == null) {
      finishLastFile(book, chapter)
      return false
    }
    val target = skipTarget(nextChapter.id, config.startPositionMs(nextChapter.duration)) ?: return false
    return seekToSkipTarget(target)
  }

  private fun skipTarget(
    chapterId: ChapterId,
    filePositionMs: Long,
  ): SkipTarget? {
    val book = currentBook ?: return null
    val playbackItem = book.playbackItemForPosition(chapterId, filePositionMs) ?: return null
    return SkipTarget(
      mediaItemIndex = playbackItem.index,
      positionMs = playbackItem.positionInMediaItem(filePositionMs),
    )
  }

  /** @return `true` when the end-of-chapter sleep timer must keep playback stopped. */
  private fun seekToSkipTarget(target: SkipTarget): Boolean {
    val stopped = target.mediaItemIndex != player.currentMediaItemIndex &&
      sleepTimer.state.value is SleepTimerState.Enabled.WithEndOfChapter
    if (stopped) {
      pauseAndDisableSleepTimerIfEndOfChapter()
    }
    if (!isAt(target)) {
      player.seekTo(target.mediaItemIndex, target.positionMs)
    }
    return stopped
  }

  /**
   * Moves the position to the end of the last playback item so the existing end-of-stream handling
   * stops playback and persists the finished book.
   */
  private fun finishLastFile(
    book: Book,
    chapter: Chapter,
  ) {
    if (outroSkippedChapterId == chapter.id) return
    val last = book.playbackItems().lastOrNull() ?: return
    outroSkippedChapterId = chapter.id
    player.seekTo(last.index, last.mark.durationMs)
  }

  private fun isAt(target: SkipTarget): Boolean {
    return player.currentMediaItemIndex == target.mediaItemIndex &&
      abs(player.currentPosition - target.positionMs) <= END_POSITION_TOLERANCE_MS
  }

  private data class FileLocation(
    val chapter: Chapter,
    val filePositionMs: Long,
  )

  private data class SkipTarget(
    val mediaItemIndex: Int,
    val positionMs: Long,
  )

  fun forceSeekToNext() {
    scope.launch {
      val nextMediaItemIndex = player.nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
        ?: return@launch
      player.seekTo(nextMediaItemIndex, 0)
    }
  }

  fun forceSeekToPrevious() {
    scope.launch {
      val currentPosition = player.currentPosition
      if (currentPosition > THRESHOLD_FOR_BACK_SEEK_MS) {
        player.seekTo(0)
      } else {
        val previousMediaItemIndex = player.previousMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
        if (previousMediaItemIndex != null) {
          player.seekTo(previousMediaItemIndex, 0)
        } else {
          player.seekTo(0)
        }
      }
    }
  }

  override fun getAvailableCommands(): Player.Commands {
    // On Android 13, the notification always shows the "skip to next" and "skip to previous"
    // actions.
    // However these are also used internally when seeking for example through a bluetooth headset
    // We use these and delegate them to fast forward / rewind.
    // The player however only advertises the seek to next and previous item in the case
    // that it's not the first or last track. Therefore we manually advertise that these
    // are available.
    return super.getAvailableCommands()
      .buildUpon()
      .addAll(
        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_NEXT,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
      )
      .build()
  }

  override fun seekToPreviousMediaItem() {
    seekBack()
  }

  override fun seekToNextMediaItem() {
    seekForward()
  }

  override fun seekToPrevious() {
    seekBack()
  }

  override fun seekToNext() {
    seekForward()
  }

  override fun seekBack() {
    scope.launch {
      seekBackBy(seekTimeStore.data.first().seconds)
    }
  }

  private suspend fun seekBackBy(skipAmount: Duration) {
    seekBackBy(
      skipAmount = skipAmount,
      crossMediaItems = true,
    )
  }

  private suspend fun seekBackBy(
    skipAmount: Duration,
    crossMediaItems: Boolean,
  ) {
    var currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?.coerceAtLeast(ZERO)
      ?: return
    var remaining = skipAmount
    var mediaItemIndex = player.currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: return

    while (remaining > currentPosition) {
      if (!crossMediaItems) {
        player.seekTo(mediaItemIndex, 0)
        return
      }
      remaining -= currentPosition
      val previousMediaItemIndex = mediaItemIndex - 1
      if (previousMediaItemIndex < 0) {
        player.seekTo(0)
        return
      }
      val previousMediaItem = player.getMediaItemAt(previousMediaItemIndex)
      currentPosition = previousMediaItem.mediaMetadata.durationMs?.milliseconds ?: return
      mediaItemIndex = previousMediaItemIndex
    }

    player.seekTo(mediaItemIndex, (currentPosition - remaining).inWholeMilliseconds)
  }

  override fun seekForward() {
    scope.launch {
      val skipAmount = seekTimeStore.data.first().seconds

      val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?.coerceAtLeast(ZERO)
        ?: return@launch
      val newPosition = currentPosition + skipAmount

      val duration = player.duration.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?: return@launch

      if (newPosition > duration) {
        val nextMediaItemIndex = nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
          ?: return@launch
        player.seekTo(nextMediaItemIndex, (duration - newPosition).absoluteValue.inWholeMilliseconds)
      } else {
        player.seekTo(newPosition.inWholeMilliseconds)
      }
    }
  }

  override fun play() {
    playWhenReady = true
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    Logger.d("setPlayWhenReady=$playWhenReady")

    if (playWhenReady) {
      updateLastPlayedAt()
      // The configured skip times decide where the first sample comes from, so playback has to wait
      // for them instead of starting at the default of zero.
      if (skipConfig.value == null) {
        schedulePlayAfterSkipConfigLoaded()
        return
      }
      if (applyIntroOutroSkip()) return
    } else {
      pendingPlay = false
      val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }?.milliseconds ?: ZERO
      if (currentPosition > ZERO) {
        scope.launch {
          seekBackBy(
            skipAmount = autoRewindAmountStore.data.first().seconds,
            crossMediaItems = false,
          )
        }
      }
    }
    analytics.event(if (playWhenReady) "play" else "pause")
    super.setPlayWhenReady(playWhenReady)
  }

  private fun schedulePlayAfterSkipConfigLoaded() {
    if (pendingPlay) return
    pendingPlay = true
    scope.launch {
      skipConfig.first { it != null }
      if (!pendingPlay) return@launch
      pendingPlay = false
      if (applyIntroOutroSkip()) return@launch
      analytics.event("play")
      super.setPlayWhenReady(true)
    }
  }

  override fun pause() {
    playWhenReady = false
  }

  private fun updateLastPlayedAt() {
    scope.launch {
      currentBookStoreId.data.first()?.let { bookId ->
        repo.updateBook(bookId) {
          val lastPlayedAt = Instant.now()
          Logger.v("Update ${it.name}: lastPlayedAt to $lastPlayedAt")
          it.copy(lastPlayedAt = lastPlayedAt)
        }
      }
    }
  }

  override fun getPlaybackState(): Int = when (val state = super.getPlaybackState()) {
    // redirect buffering to ready to prevent visual artifacts on seeking
    STATE_BUFFERING -> STATE_READY
    else -> state
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    startPositionMs: Long,
  ) {
    setBook(mediaItem)
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    resetPosition: Boolean,
  ) {
    setBook(mediaItem)
  }

  override fun setMediaItems(mediaItems: List<MediaItem>) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    resetPosition: Boolean,
  ) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  override fun setMediaItem(mediaItem: MediaItem) {
    setBook(mediaItem)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  private fun setBook(mediaItem: MediaItem) {
    Logger.v("setBook(${mediaItem.mediaId})")
    // A book change invalidates any play request that was still waiting for the skip settings.
    pendingPlay = false
    currentBook = null
    outroSkippedChapterId = null
    val mediaId = mediaItem.mediaId.toMediaIdOrNull()
    if (mediaId != null) {
      if (mediaId is MediaId.Book) {
        val book = runBlocking {
          repo.get(mediaId.id)
        }
        if (book != null) {
          currentBook = book
          player.setPlaybackSpeed(book.content.playbackSpeed)
          setSkipSilenceEnabled(book.content.skipSilence)
          volumeGain.gain = Decibel(book.content.gain)
          val currentPlaybackItem = book.playbackItemForPosition(
            chapterId = book.content.currentChapter,
            positionInChapterMs = book.content.positionInChapter,
          ) ?: return
          val mediaItems = mediaItemProvider.playbackItems(book)
          player.setMediaItems(
            mediaItems,
            currentPlaybackItem.index,
            currentPlaybackItem.positionInMediaItem(book.content.positionInChapter),
          )
        }
      } else {
        Logger.w("Unexpected mediaId=$mediaId")
      }
    }
  }

  override fun setPlaybackSpeed(speed: Float) {
    super.setPlaybackSpeed(speed)
    scope.launch {
      updateBook { it.copy(playbackSpeed = speed) }
    }
  }

  fun setSkipSilenceEnabled(enabled: Boolean) {
    scope.launch {
      updateBook { it.copy(skipSilence = enabled) }
    }
    if (player is ExoPlayer) {
      player.skipSilenceEnabled = enabled
    }
  }

  fun setGain(gain: Decibel) {
    volumeGain.gain = gain
    scope.launch {
      updateBook { it.copy(gain = gain.value) }
    }
  }

  private suspend fun updateBook(update: (BookContent) -> BookContent) {
    val bookId = currentBookStoreId.data.first() ?: return
    repo.updateBook(bookId, update)
  }

  override fun release() {
    pendingPlay = false
    outroCheckJob?.cancel()
    outroCheckJob = null
    skipConfigJob?.cancel()
    skipConfigJob = null
    player.removeListener(endOfChapterSleepTimerListener)
    player.removeListener(introOutroSkipListener)
    super.release()
  }
}

private const val THRESHOLD_FOR_BACK_SEEK_MS = 2000

/** Endpoint positions can differ by this much because playback items are clipped to whole microseconds. */
private const val END_POSITION_TOLERANCE_MS = 1L
