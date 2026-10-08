package voice.core.data

/**
 * Upper bound for the globally configured intro and outro skip duration.
 *
 * Shared between the settings UI, which limits what can be saved, and playback, which normalizes
 * whatever it reads from the store.
 */
public const val MAX_INTRO_OUTRO_SKIP_SECONDS: Int = 60

public fun Int.coerceToIntroOutroSkipSeconds(): Int = coerceIn(0, MAX_INTRO_OUTRO_SKIP_SECONDS)
