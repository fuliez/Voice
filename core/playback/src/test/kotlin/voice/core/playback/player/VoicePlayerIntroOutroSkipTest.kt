package voice.core.playback.player

import androidx.datastore.core.DataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
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
    harness.play()

    harness.player.seekTo(0, 585_000)

    harness.player.shouldBeAt(mediaItemIndex = 1, positionMs = 30_000)
  }

  @Test
  fun `corrects a manual seek into the intro`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.play()

    harness.player.seekTo(0, 5_000)

    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 30_000)
  }

  @Test
  fun `leaves files that are fully covered by the configuration untouched`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 40_000)))
    harness.awaitReady()
    harness.loadSkipConfig()

    harness.play()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 0)

    harness.player.seekTo(0, 39_000)
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
    harness.player.seekTo(1, 5_000)
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
    harness.play()

    // The outro threshold is at 585s, inside the second mark of the first file.
    harness.player.seekTo(1, 285_000)

    // The second file starts at index 3, not at the last mark of the first file.
    harness.player.shouldBeAt(mediaItemIndex = 3, positionMs = 30_000)
  }

  @Test
  fun `finishes the last file at its effective end`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.play()

    harness.player.seekTo(0, 585_000)

    assertAtEndOfFile(harness.player)

    // Repeated checks must not seek again or fall back to the outro start.
    harness.player.advanceOutroChecks(testScheduler)
    assertAtEndOfFile(harness.player)
  }

  @Test
  fun `finishing the last file ends playback`() = runTest {
    val harness = IntroOutroHarness(scope = backgroundScope, introSeconds = 30, outroSeconds = 15)
    harness.setBook(listOf(chapter(durationMs = 600_000)))
    harness.awaitReady()
    harness.loadSkipConfig()
    harness.play()

    harness.player.seekTo(0, 585_000)
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
    harness.play()
    harness.sleepTimer.enable(SleepTimerMode.EndOfChapter)

    harness.player.seekTo(0, 585_000)

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
    harness.play()

    harness.player.seekTo(0, 585_000)
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

    harness.play()
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 0)

    harness.player.seekTo(0, 5_000)
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 5_000)

    harness.player.seekTo(0, 590_000)
    harness.player.shouldBeAt(mediaItemIndex = 0, positionMs = 590_000)
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

    val player = VoicePlayer(
      player = internalPlayer,
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

    fun play() {
      player.playWhenReady = true
    }

    fun pause() {
      player.playWhenReady = false
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
