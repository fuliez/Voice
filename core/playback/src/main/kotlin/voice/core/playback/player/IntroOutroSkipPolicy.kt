package voice.core.playback.player

import voice.core.data.Book
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MAX_INTRO_OUTRO_SKIP_SECONDS

/** How often playback re-checks whether the current file reached its outro. */
internal const val INTRO_OUTRO_CHECK_INTERVAL_MS = 200L

/**
 * The globally configured intro and outro skip durations.
 *
 * [introSeconds] and [outroSeconds] are normalized to `0..MAX_INTRO_OUTRO_SKIP_SECONDS` before they
 * are converted to milliseconds, so out of range values can never produce a negative or unbounded
 * target.
 */
internal data class IntroOutroSkipConfig(
  val introSeconds: Int,
  val outroSeconds: Int,
) {
  val introMs: Long = introSeconds
    .coerceIn(0, MAX_INTRO_OUTRO_SKIP_SECONDS)
    .toLong() * 1000L

  val outroMs: Long = outroSeconds
    .coerceIn(0, MAX_INTRO_OUTRO_SKIP_SECONDS)
    .toLong() * 1000L

  /**
   * Whether this configuration can be applied to a file of [fileDurationMs].
   *
   * A file that is fully covered by the configured intro and outro plays untouched instead of being
   * discarded.
   */
  fun isEnabledFor(fileDurationMs: Long): Boolean {
    return fileDurationMs > 0 && introMs + outroMs < fileDurationMs
  }
}

/** What playback should do with the file position it is currently at. */
internal sealed interface IntroOutroSkipDecision {
  /** The position is inside the body of the file and stays as it is. */
  data object None : IntroOutroSkipDecision

  /** Seek to [filePositionMs] within the current file, which is where its body starts. */
  data class SeekWithinFile(val filePositionMs: Long) : IntroOutroSkipDecision

  /** The position is at or past the outro threshold, so the current file is done. */
  data object SkipOutro : IntroOutroSkipDecision
}

/**
 * Decides what to do with [filePositionMs] inside a file of [fileDurationMs].
 *
 * All arguments are milliseconds in the coordinate system of the original audio file, not of the
 * playback item that currently plays part of it.
 */
internal fun decideIntroOutroSkip(
  filePositionMs: Long,
  fileDurationMs: Long,
  config: IntroOutroSkipConfig,
): IntroOutroSkipDecision {
  if (filePositionMs < 0 || !config.isEnabledFor(fileDurationMs)) {
    return IntroOutroSkipDecision.None
  }
  if (filePositionMs < config.introMs) {
    return IntroOutroSkipDecision.SeekWithinFile(config.introMs)
  }
  if (config.outroMs > 0 && filePositionMs >= fileDurationMs - config.outroMs) {
    return IntroOutroSkipDecision.SkipOutro
  }
  return IntroOutroSkipDecision.None
}

/**
 * The position a file should start playing at: its configured intro, or `0` when the configuration
 * does not apply to this file.
 */
internal fun IntroOutroSkipConfig.startPositionMs(fileDurationMs: Long): Long {
  return if (isEnabledFor(fileDurationMs)) introMs else 0L
}

/** The file that plays after [chapterId], or `null` when [chapterId] is the last one. */
internal fun Book.nextChapterOrNull(chapterId: ChapterId): Chapter? {
  val index = chapters.indexOfFirst { it.id == chapterId }
  if (index < 0) return null
  return chapters.getOrNull(index + 1)
}
