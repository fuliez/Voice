package voice.core.playback.player

import androidx.datastore.core.DataStore
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.data.repo.BookRepository
import voice.core.featureflag.MemoryFeatureFlag
import voice.core.logging.api.LogWriter
import voice.core.logging.api.Logger
import voice.core.playback.MemoryDataStore
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PositionUpdater
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.search.book
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerMode
import voice.core.sleeptimer.SleepTimerState
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Accepted shortfall when the position is expected at the end of a file.
 *
 * The last mark of a file ends at `duration - 1` and playback items are clipped in microseconds, so
 * the reported position can trail the file duration. It stays inside the margin the book overview
 * uses to classify a book as completed.
 */
private const val END_OF_FILE_TOLERANCE_MS = 5L

@RunWith(AndroidJUnit4::class)
class VoicePlayerIntroOutroSkipTest {

  init {
    Logger.install(
      object : LogWriter {
        override fun log(
          severity: Logger.Severity,
          message: String,
          throwable: Throwable?,
        ) {
          println("$severity: $message")
          throwable?.printStackTrace()
        }
      },
    )
  }

  private val bookId = BookId(Uuid.random().toString())

  @Test
  fun `starts playback at the configured intro`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()

    harness.play()

    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 30_000)
    assertTrue(harness.player.playWhenReady)
  }

  @Test
  fun `keeps a body position when resuming`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    val chapters = listOf(chapter(durationMs = 600_000))
    harness.setBook(chapters, positionInChapter = 300_000)
    harness.awaitReady()
    harness.loadSkipConfig()

    harness.play()

    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 300_000)
  }

  @Test
  fun `jumps to the next file when the outro is reached`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(
      listOf(
        chapter(durationMs = 600_000),
        chapter(durationMs = 600_000),
      ),
    )
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()

    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 30_000)
  }

  @Test
  fun `corrects a manual seek into the intro`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 5_000)
    harness.settlePlayback()

    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 30_000)
  }

  @Test
  fun `leaves files that are fully covered by the configuration untouched`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 40_000)))
    harness.awaitReady()
    harness.loadSkipConfig()

    harness.playForReal()
    // The covered file starts at its beginning instead of at its configured intro.
    assertTrue(
      actual = harness.player.currentPosition < 1_000,
      message = "expected the file to start at 0, but was at ${harness.player.currentPosition}",
    )

    harness.player.seekTo(0, 39_000)
    harness.settlePlayback()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 39_000)
  }

  @Test
  fun `does not repeat the intro for later marks of the same file`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(
      listOf(
        chapter(durationMs = 120_000, marks = listOf(0L, 30_000)),
      ),
    )
    harness.awaitReady()
    harness.loadSkipConfig()

    harness.play()
    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 0)

    // The transition into the second mark of the same file must not apply the intro again.
    harness.playForReal()
    harness.player.seekTo(1, 5_000)
    harness.settlePlayback()
    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 5_000)
  }

  @Test
  fun `skips an intro that spans multiple marks at once`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 120, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000, marks = listOf(0L, 30_000))))
    harness.awaitReady()
    harness.loadSkipConfig()

    harness.play()

    // The intro ends 120s into the file, which is 90s into the second mark.
    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 90_000)
    assertTrue(harness.player.playWhenReady)
  }

  @Test
  fun `skips an outro starting in the second to last mark to the next file`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(
      listOf(
        chapter(durationMs = 600_000, marks = listOf(0L, 300_000, 586_000)),
        chapter(durationMs = 600_000),
      ),
    )
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    // The outro threshold is at 585s, inside the second mark of the first file.
    harness.player.seekTo(1, 285_000)
    harness.settlePlayback()

    // The second file starts at index 3, not at the last mark of the first file.
    harness.player.shouldBeAt(mediaItemIndex = 3, positionMs = 30_000)
  }

  @Test
  fun `finishes the last file at its effective end`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()

    assertAtEndOfFile(harness.player)

    // Repeated checks must not seek again or fall back to the outro start.
    val seeksBefore = harness.instrumentedPlayer.seeks
    harness.player.advanceOutroChecks(testScheduler)
    assertAtEndOfFile(harness.player)
    assertEquals(expected = seeksBefore, actual = harness.instrumentedPlayer.seeks)
  }

  @Test
  fun `finishing the last file ends playback`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()
    TestPlayerRunHelper.runUntilPlaybackState(harness.internalPlayer, Player.STATE_ENDED)

    assertEquals(expected = Player.STATE_ENDED, actual = harness.internalPlayer.playbackState)
    assertFalse(harness.player.isPlaying)
    assertAtEndOfFile(harness.player)
  }

  @Test
  fun `applies a paused settings change on the next play`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 0, outroSeconds = 0)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()

    harness.play()
    harness.pause()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 0)

    harness.skipIntroStore.updateData { 30 }
    runCurrent()

    harness.player.seekTo(0, 5_000)
    harness.settlePlayback()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 5_000)

    harness.play()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 30_000)
  }

  @Test
  fun `waits for the skip configuration before starting playback`() = runTest {
    val harness = IntroOutroHarness(
      scope = backgroundScope,
      introSeconds = 30,
      outroSeconds = 0,
      delayConfigMs = 1_000,
    )
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()

    harness.play()

    assertFalse(harness.player.playWhenReady)
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 0)

    advanceTimeBy(1_000)
    runCurrent()

    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 30_000)
    assertTrue(harness.player.playWhenReady)
  }

  @Test
  fun `dropping a play request while the configuration loads keeps playback stopped`() = runTest {
    val harness = IntroOutroHarness(
      scope = backgroundScope,
      introSeconds = 30,
      outroSeconds = 0,
      delayConfigMs = 1_000,
    )
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()

    harness.play()
    harness.pause()

    advanceTimeBy(1_000)
    runCurrent()

    assertFalse(harness.player.playWhenReady)
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 0)
  }

  @Test
  fun `end of chapter sleep timer stops an intro correction that crosses a mark`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 120, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000, marks = listOf(0L, 30_000))))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.sleepTimer.enable(SleepTimerMode.EndOfChapter)

    harness.play()

    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 90_000)
    assertFalse(harness.player.playWhenReady)
    assertEquals(SleepTimerState.Disabled, harness.sleepTimer.state.value)
  }

  @Test
  fun `end of chapter sleep timer stops an outro jump to the next file`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(
      listOf(
        chapter(durationMs = 600_000),
        chapter(durationMs = 600_000),
      ),
    )
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()
    harness.sleepTimer.enable(SleepTimerMode.EndOfChapter)

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()

    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 30_000)
    assertFalse(harness.player.playWhenReady)
    assertEquals(SleepTimerState.Disabled, harness.sleepTimer.state.value)
  }

  @Test
  fun `finishing the last file persists the end of the file`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    val chapter = chapter(durationMs = 600_000)
    val positionUpdater = harness.attachPositionUpdater()
    harness.setBook(listOf(chapter))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()
    harness.player.advanceOutroChecks(testScheduler)
    positionUpdater.flushPositionNow()

    val saved = harness.savedContents.last()
    assertEquals(expected = chapter.id, actual = saved.currentChapter)
    assertTrue(
      actual = saved.positionInChapter >= chapter.duration - END_OF_FILE_TOLERANCE_MS,
      message = "expected the end of ${chapter.duration}ms but was ${saved.positionInChapter}ms",
    )
  }

  @Test
  fun `does not skip anything when both settings are zero`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 0, outroSeconds = 0)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()

    harness.playForReal()

    harness.player.seekTo(0, 5_000)
    harness.settlePlayback()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 5_000)

    harness.player.seekTo(0, 590_000)
    harness.settlePlayback()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 590_000)
  }

  @Test
  fun `skips the outro again when seeking back into it after finishing`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()
    assertAtEndOfFile(harness.player)

    // The book is not reloaded, so the finished marker is still set while the position is not at
    // the end anymore.
    harness.player.seekTo(0, 590_000)
    harness.settlePlayback()

    assertAtEndOfFile(harness.player)
    assertEquals(expected = 0, actual = harness.player.currentMediaItemIndex)
  }

  @Test
  fun `completes a finished file again when playing from its outro`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()
    assertAtEndOfFile(harness.player)

    harness.pause()
    harness.player.seekTo(0, 590_000)
    harness.settlePlayback()
    // While paused the position is kept as the user left it.
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 590_000)

    harness.play()

    assertAtEndOfFile(harness.player)
  }

  @Test
  fun `keeps playing the body when seeking back after finishing`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()
    assertAtEndOfFile(harness.player)

    harness.player.seekTo(0, 300_000)
    harness.settlePlayback()

    assertTrue(
      actual = harness.player.currentPosition < 585_000,
      message = "expected to stay in the body, but the position is ${harness.player.currentPosition}",
    )
  }

  @Test
  fun `completes the last file again from an earlier mark`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    // The outro threshold at 585s is inside the first mark, the file ends in the second one.
    harness.setBook(listOf(chapter(durationMs = 600_000, marks = listOf(0L, 590_000))))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()

    harness.player.seekTo(0, 585_000)
    harness.settlePlayback()
    assertAtEndOfItem(harness.player, mediaItemIndex = 1, endPositionMs = 9_999)

    // Back in the outro of the earlier mark of the same file.
    harness.player.seekTo(0, 586_000)
    harness.settlePlayback()

    assertAtEndOfItem(harness.player, mediaItemIndex = 1, endPositionMs = 9_999)
  }

  @Test
  fun `applies a settings change while playback is running`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 0, outroSeconds = 0)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()
    harness.player.seekTo(0, 5_000)
    harness.settlePlayback()

    harness.skipIntroStore.updateData { 30 }
    runCurrent()

    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 30_000)
  }

  @Test
  fun `stops checking while playback is suppressed`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()
    harness.player.seekTo(0, 300_000)
    harness.settlePlayback()

    harness.suppressPlayback()
    val seeks = harness.instrumentedPlayer.seeks
    val reads = harness.instrumentedPlayer.positionReads

    // Several intervals pass while the play intent and the ready state stay.
    harness.player.advanceOutroChecks(testScheduler)

    assertEquals(expected = reads, actual = harness.instrumentedPlayer.positionReads)
    assertEquals(expected = seeks, actual = harness.instrumentedPlayer.seeks)
  }

  @Test
  fun `does not apply an intro change while playback is suppressed`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()
    harness.player.seekTo(0, 60_000)
    harness.settlePlayback()
    harness.suppressPlayback()

    val position = harness.player.currentPosition
    harness.skipIntroStore.updateData { 120 }
    runCurrent()

    assertEquals(expected = position, actual = harness.player.currentPosition)
  }

  @Test
  fun `does not apply an outro change while playback is suppressed`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 0)
    harness.setBook(listOf(chapter(durationMs = 600_000), chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()
    harness.player.seekTo(0, 500_000)
    harness.settlePlayback()
    harness.suppressPlayback()

    // 500s is inside the outro once 120s are configured.
    harness.skipOutroStore.updateData { 120 }
    runCurrent()

    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 500_000)
  }

  @Test
  fun `checks with the latest settings when playback resumes`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 0)
    harness.setBook(listOf(chapter(durationMs = 600_000), chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()
    harness.player.seekTo(0, 500_000)
    harness.settlePlayback()
    harness.suppressPlayback()
    harness.skipOutroStore.updateData { 120 }
    runCurrent()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 500_000)

    harness.resumePlayback()

    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 30_000)
  }

  @Test
  fun `keeps a single check job when the playback state is reported repeatedly`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.playForReal()
    harness.player.seekTo(0, 300_000)
    harness.settlePlayback()
    // Align the interval with the coroutine clock before counting.
    harness.player.advanceOutroChecks(testScheduler)

    repeat(3) { harness.instrumentedPlayer.dispatchIsPlayingChanged(isPlaying = true) }
    val reads = harness.instrumentedPlayer.positionReads

    harness.player.advanceOutroChecks(testScheduler)

    // One position read per interval means exactly one check job is running.
    assertEquals(expected = 3, actual = harness.instrumentedPlayer.positionReads - reads)
  }

  @Test
  fun `applies the outro from the interval check when playing across the threshold`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 100_000), chapter(durationMs = 100_000)))
    harness.awaitReady()
    harness.loadSkipConfig()

    // Start before the 85s threshold, so no position event crosses it.
    harness.player.seekTo(0, 84_000)
    harness.player.playWhenReady = true
    harness.settlePlayback()

    // Playing on does not report a position discontinuity, so only the interval check can notice
    // that the threshold was passed.
    RobolectricUtil.runMainLooperUntil { harness.internalPlayer.currentPosition >= 85_100 }
    assertEquals(expected = 0, actual = harness.player.currentMediaItemIndex)

    harness.player.advanceOutroChecks(testScheduler)

    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 30_000)
  }

  private fun chapter(
    durationMs: Long,
    marks: List<Long> = listOf(0L),
    id: ChapterId = ChapterId(Uuid.random().toString()),
  ): Chapter {
    return Chapter(
      id = id,
      name = id.value,
      duration = durationMs,
      fileLastModified = Instant.EPOCH,
      markData = marks.mapIndexed { index, startMs -> MarkData(startMs = startMs, name = "mark$index") },
      fileSize = 0,
    )
  }

  private fun VoicePlayer.shouldBeAt(
    mediaItemIndex: Int,
    positionMs: Long,
  ) {
    assertEquals(expected = mediaItemIndex, actual = currentMediaItemIndex)
    assertEquals(expected = positionMs, actual = currentPosition)
  }

  private fun assertAtEndOfFile(
    player: VoicePlayer,
    fileDurationMs: Long = 600_000,
  ) {
    assertTrue(
      actual = player.currentPosition >= fileDurationMs - END_OF_FILE_TOLERANCE_MS,
      message = "expected the end of a ${fileDurationMs}ms file, but the position is ${player.currentPosition}",
    )
  }

  private fun assertAtEndOfItem(
    player: VoicePlayer,
    mediaItemIndex: Int,
    endPositionMs: Long,
  ) {
    assertEquals(expected = mediaItemIndex, actual = player.currentMediaItemIndex)
    assertTrue(
      actual = player.currentPosition >= endPositionMs - END_OF_FILE_TOLERANCE_MS,
      message = "expected the end of item $mediaItemIndex, but the position is ${player.currentPosition}",
    )
  }

  /** Advances the periodic outro checks by a few intervals. */
  private fun VoicePlayer.advanceOutroChecks(scheduler: TestCoroutineScheduler) {
    scheduler.advanceTimeBy(INTRO_OUTRO_CHECK_INTERVAL_MS * 3)
    scheduler.runCurrent()
  }

  private inner class IntroOutroHarness(
    private val scope: CoroutineScope,
    introSeconds: Int,
    outroSeconds: Int,
    delayConfigMs: Long = 0,
  ) {

    val skipIntroStore: DataStore<Int> = if (delayConfigMs == 0L) {
      MemoryDataStore(introSeconds)
    } else {
      DelayedDataStore(introSeconds, delayConfigMs)
    }
    val skipOutroStore: DataStore<Int> = if (delayConfigMs == 0L) {
      MemoryDataStore(outroSeconds)
    } else {
      DelayedDataStore(outroSeconds, delayConfigMs)
    }
    val sleepTimer = FakeSleepTimer()
    val savedContents = mutableListOf<BookContent>()

    var currentBook: Book = book(listOf(chapter(durationMs = 1_000)), bookId)

    private val mediaItemProvider = MediaItemProvider(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

    val internalPlayer: ExoPlayer = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
      .setMediaSourceFactory(
        mockk {
          every { createMediaSource(any()) } answers {
            val mediaItem = arg<MediaItem>(0)
            val durationMs = mediaItem.mediaMetadata.durationMs ?: 0L
            FakeMediaSource(
              FakeTimeline(
                FakeTimeline.TimelineWindowDefinition.Builder()
                  .setPeriodCount(1)
                  .setSeekable(true)
                  .setDurationUs(TimeUnit.MILLISECONDS.toMicros(durationMs))
                  .setMediaItem(mediaItem)
                  .build(),
              ),
            )
          }
        },
      )
      .build()

    private val repo: BookRepository = mockk {
      coEvery { get(bookId) } answers { currentBook }
      coEvery { updateBook(any(), any()) } answers {
        val update = arg<(BookContent) -> BookContent>(1)
        savedContents += update(currentBook.content)
      }
    }

    val instrumentedPlayer = InstrumentedPlayer(internalPlayer)

    val player = VoicePlayer(
      player = instrumentedPlayer,
      repo = repo,
      currentBookStoreId = mockk {
        every { data } returns MutableStateFlow(bookId)
      },
      seekTimeStore = MemoryDataStore(30),
      autoRewindAmountStore = MemoryDataStore(0),
      skipIntroSecondsStore = skipIntroStore,
      skipOutroSecondsStore = skipOutroStore,
      mediaItemProvider = mediaItemProvider,
      scope = scope,
      volumeGain = mockk(relaxed = true),
      sleepTimer = sleepTimer,
      analytics = mockk(relaxed = true),
    )

    fun setBook(
      chapters: List<Chapter>,
      positionInChapter: Long = 0,
    ) {
      currentBook = book(chapters, bookId).update {
        it.copy(
          currentChapter = chapters.first().id,
          positionInChapter = positionInChapter,
        )
      }
      player.setMediaItem(mediaItemProvider.mediaItem(currentBook))
      scheduler().runCurrent()
    }

    fun awaitReady() {
      player.prepare()
      TestPlayerRunHelper.runUntilPlaybackState(internalPlayer, Player.STATE_READY)
    }

    fun loadSkipConfig() {
      scheduler().runCurrent()
    }

    /** Only records the play intent, so this leaves the player not actually playing. */
    fun play() {
      player.playWhenReady = true
    }

    fun pause() {
      player.playWhenReady = false
    }

    /**
     * Starts playback through [VoicePlayer] so the correction before playback runs, and then waits
     * until the underlying player really plays. Ordinary playback events only apply skips while
     * audio is running.
     */
    fun playForReal() {
      player.playWhenReady = true
      playUntilPositionAfterCurrent(1)
      check(player.isPlaying) { "expected the player to be playing" }
    }

    /** Plays until the position advanced by [deltaMs] from the current one. */
    fun playUntilPositionAfterCurrent(deltaMs: Long) {
      val mediaItemIndex = internalPlayer.currentMediaItemIndex
      val target = internalPlayer.currentPosition + deltaMs
      TestPlayerRunHelper.play(internalPlayer).untilPositionAtLeast(mediaItemIndex, target)
    }

    /**
     * Processes the player's pending events after a seek.
     *
     * A seek can leave the player buffering, where it does not play yet. The skip is applied once
     * the player reports that it plays again, which is what this waits for.
     */
    fun settlePlayback() {
      TestPlayerRunHelper.advance(internalPlayer).untilState(Player.STATE_READY)
    }

    /** Keeps the play intent and the ready state but reports that audio is not playing. */
    fun suppressPlayback() {
      check(internalPlayer.isPlaying) { "can only suppress a player that really plays" }
      instrumentedPlayer.actuallyPlaying = false
      instrumentedPlayer.dispatchIsPlayingChanged(isPlaying = false)
    }

    fun resumePlayback() {
      instrumentedPlayer.actuallyPlaying = true
      instrumentedPlayer.dispatchIsPlayingChanged(isPlaying = true)
    }

    fun attachPositionUpdater(): PositionUpdater {
      val positionUpdater = PositionUpdater(
        bookRepo = repo,
        scope = scope,
        playStateManager = PlayStateManager(),
        experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(false),
      )
      positionUpdater.attachTo(internalPlayer)
      return positionUpdater
    }

    private fun scheduler(): TestCoroutineScheduler = scope.coroutineContext[TestCoroutineScheduler]!!
  }

  /**
   * Wraps the test player to make the reported playback state controllable and to count the calls
   * that decide whether a skip is applied.
   */
  private class InstrumentedPlayer(delegate: Player) : ForwardingPlayer(delegate) {

    private val listeners = mutableListOf<Player.Listener>()

    var actuallyPlaying = true
    var seeks = 0
    var positionReads = 0

    override fun isPlaying(): Boolean = actuallyPlaying && super.isPlaying()

    override fun getCurrentPosition(): Long {
      positionReads++
      return super.getCurrentPosition()
    }

    override fun seekTo(
      mediaItemIndex: Int,
      positionMs: Long,
    ) {
      seeks++
      super.seekTo(mediaItemIndex, positionMs)
    }

    override fun addListener(listener: Player.Listener) {
      listeners += listener
      super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
      listeners -= listener
      super.removeListener(listener)
    }

    fun dispatchIsPlayingChanged(isPlaying: Boolean) {
      listeners.toList().forEach { it.onIsPlayingChanged(isPlaying) }
    }
  }

  private class DelayedDataStore<T>(
    private val initial: T,
    private val delayMs: Long,
  ) : DataStore<T> {

    private val value = MutableStateFlow(initial)

    override val data: Flow<T> = flow {
      kotlinx.coroutines.delay(delayMs)
      emit(value.value)
    }

    override suspend fun updateData(transform: suspend (T) -> T): T {
      return value.updateAndGet { transform(it) }
    }
  }

  private class FakeSleepTimer : SleepTimer {
    override val state: StateFlow<SleepTimerState>
      get() = stateFlow

    private val stateFlow = MutableStateFlow<SleepTimerState>(SleepTimerState.Disabled)

    override fun enable(mode: SleepTimerMode) {
      stateFlow.value = when (mode) {
        is SleepTimerMode.TimedWithDuration -> SleepTimerState.Enabled.WithDuration(mode.duration)
        SleepTimerMode.TimedWithDefault -> error("TimedWithDefault is not used in these tests")
        SleepTimerMode.EndOfChapter -> SleepTimerState.Enabled.WithEndOfChapter
      }
    }

    override fun disable() {
      stateFlow.value = SleepTimerState.Disabled
    }
  }
}
