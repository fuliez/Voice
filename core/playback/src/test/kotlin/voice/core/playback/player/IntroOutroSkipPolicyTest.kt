package voice.core.playback.player

import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.playback.session.search.book
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class IntroOutroSkipPolicyTest {

  private val twelveMinutes = 600_000L
  private val defaultConfig = IntroOutroSkipConfig(introSeconds = 30, outroSeconds = 15)

  @Test
  fun `P01 no skip when both are zero`() {
    val config = IntroOutroSkipConfig(introSeconds = 0, outroSeconds = 0)

    assertEquals(IntroOutroSkipDecision.None, decide(0, config))
    assertEquals(IntroOutroSkipDecision.None, decide(300_000, config))
    assertEquals(IntroOutroSkipDecision.None, decide(599_999, config))
  }

  @Test
  fun `P02 seeks to the intro when starting at the beginning`() {
    assertEquals(
      expected = IntroOutroSkipDecision.SeekWithinFile(30_000),
      actual = decide(0, defaultConfig),
    )
  }

  @Test
  fun `P03 to P05 leaves positions inside the body untouched`() {
    assertEquals(IntroOutroSkipDecision.None, decide(30_000, defaultConfig))
    assertEquals(IntroOutroSkipDecision.None, decide(300_000, defaultConfig))
    assertEquals(IntroOutroSkipDecision.None, decide(584_999, defaultConfig))
  }

  @Test
  fun `P06 skips the outro exactly at the threshold`() {
    assertEquals(IntroOutroSkipDecision.SkipOutro, decide(585_000, defaultConfig))
    assertEquals(IntroOutroSkipDecision.SkipOutro, decide(599_999, defaultConfig))
  }

  @Test
  fun `P07 intro and outro work independently`() {
    val introOnly = IntroOutroSkipConfig(introSeconds = 30, outroSeconds = 0)
    val outroOnly = IntroOutroSkipConfig(introSeconds = 0, outroSeconds = 15)

    assertEquals(IntroOutroSkipDecision.SeekWithinFile(30_000), decide(0, introOnly))
    assertEquals(IntroOutroSkipDecision.None, decide(599_999, introOnly))
    assertEquals(IntroOutroSkipDecision.None, decide(0, outroOnly))
    assertEquals(IntroOutroSkipDecision.SkipOutro, decide(585_000, outroOnly))
  }

  @Test
  fun `P08 files covered by the configuration play completely`() {
    // The maximum configurable total is 2 * 120s, so a two minute file can be covered completely.
    val exactlyCovered = IntroOutroSkipConfig(introSeconds = 60, outroSeconds = 60)
    val overCovered = IntroOutroSkipConfig(introSeconds = 120, outroSeconds = 120)

    for (position in listOf(0L, 1L, 119_999L, 120_000L)) {
      assertEquals(IntroOutroSkipDecision.None, decide(position, exactlyCovered, durationMs = 120_000))
      assertEquals(IntroOutroSkipDecision.None, decide(position, overCovered, durationMs = 120_000))
    }
    assertEquals(
      IntroOutroSkipDecision.None,
      decide(filePositionMs = 0, durationMs = 30_000, config = IntroOutroSkipConfig(30, 0)),
    )
    assertEquals(
      IntroOutroSkipDecision.None,
      decide(filePositionMs = 0, durationMs = 10_000, config = IntroOutroSkipConfig(0, 15)),
    )
  }

  @Test
  fun `P09 invalid durations and positions are not touched`() {
    assertEquals(IntroOutroSkipDecision.None, decide(0, defaultConfig, durationMs = 0))
    assertEquals(IntroOutroSkipDecision.None, decide(0, defaultConfig, durationMs = -1))
    assertEquals(IntroOutroSkipDecision.None, decide(-1, defaultConfig))
  }

  @Test
  fun `P10 out of range configuration is normalized`() {
    val config = IntroOutroSkipConfig(introSeconds = -5, outroSeconds = 400)

    assertEquals(0L, config.introMs)
    assertEquals(120_000L, config.outroMs)
    assertEquals(IntroOutroSkipDecision.None, decide(479_999, config))
    assertEquals(IntroOutroSkipDecision.SkipOutro, decide(480_000, config))
    assertEquals(120_000L, IntroOutroSkipConfig(999, 0).introMs)
  }

  @Test
  fun `start position is the intro unless the file is covered`() {
    assertEquals(30_000L, defaultConfig.startPositionMs(twelveMinutes))
    assertEquals(0L, defaultConfig.startPositionMs(fileDurationMs = 45_000))
    assertEquals(0L, IntroOutroSkipConfig(0, 0).startPositionMs(twelveMinutes))
    assertEquals(0L, IntroOutroSkipConfig(-5, -5).startPositionMs(twelveMinutes))
  }

  @Test
  fun `P12 to P14 resolve the following file`() {
    val first = chapter(durationMs = 600_000, id = "first")
    val second = chapter(durationMs = 40_000, id = "second")
    val last = chapter(durationMs = 600_000, id = "last")
    val book = book(chapters = listOf(first, second, last), id = BookId("book"))

    assertEquals(second, book.nextChapterOrNull(first.id))
    assertEquals(last, book.nextChapterOrNull(second.id))
    assertNull(book.nextChapterOrNull(last.id))
    assertNull(book.nextChapterOrNull(ChapterId("unknown")))

    // The next file is covered by the configuration, so it starts at zero instead of its intro.
    assertEquals(0L, defaultConfig.startPositionMs(second.duration))
    assertEquals(30_000L, defaultConfig.startPositionMs(last.duration))
  }

  @Test
  fun `infers the decision type from the file position`() {
    assertIs<IntroOutroSkipDecision.SeekWithinFile>(decide(0, defaultConfig))
    assertIs<IntroOutroSkipDecision.SkipOutro>(decide(590_000, defaultConfig))
    assertIs<IntroOutroSkipDecision.None>(decide(60_000, defaultConfig))
  }

  private fun decide(
    filePositionMs: Long,
    config: IntroOutroSkipConfig,
    durationMs: Long = twelveMinutes,
  ): IntroOutroSkipDecision {
    return decideIntroOutroSkip(
      filePositionMs = filePositionMs,
      fileDurationMs = durationMs,
      config = config,
    )
  }

  private fun chapter(
    durationMs: Long,
    id: String = Uuid.random().toString(),
  ): Chapter {
    return Chapter(
      id = ChapterId(id),
      name = id,
      duration = durationMs,
      fileLastModified = Instant.EPOCH,
      markData = listOf(MarkData(startMs = 0, name = "mark")),
      fileSize = 0,
    )
  }
}
