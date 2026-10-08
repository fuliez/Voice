package voice.features.settings.views

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import voice.core.data.MAX_INTRO_OUTRO_SKIP_SECONDS
import voice.core.ui.icons.VoiceIcons
import voice.core.strings.R as StringsR

@Composable
internal fun SkipIntroRow(
  skipIntroInSeconds: Int,
  openSkipIntroDialog: () -> Unit,
) {
  IntroOutroSkipRow(
    titleRes = StringsR.string.settings_playback_skip_intro_title,
    seconds = skipIntroInSeconds,
    onClick = openSkipIntroDialog,
  )
}

@Composable
internal fun SkipOutroRow(
  skipOutroInSeconds: Int,
  openSkipOutroDialog: () -> Unit,
) {
  IntroOutroSkipRow(
    titleRes = StringsR.string.settings_playback_skip_outro_title,
    seconds = skipOutroInSeconds,
    onClick = openSkipOutroDialog,
  )
}

@Composable
private fun IntroOutroSkipRow(
  @StringRes titleRes: Int,
  seconds: Int,
  onClick: () -> Unit,
) {
  val title = stringResource(titleRes)
  ListItem(
    modifier = Modifier
      .clickable {
        onClick()
      }
      .fillMaxWidth(),
    leadingContent = {
      Icon(
        imageVector = VoiceIcons.Timelapse,
        contentDescription = title,
      )
    },
    supportingContent = {
      Text(
        text = LocalResources.current.getQuantityString(
          StringsR.plurals.duration_seconds,
          seconds,
          seconds,
        ),
      )
    },
  ) {
    Text(text = title)
  }
}

@Composable
internal fun SkipIntroAmountDialog(
  currentSeconds: Int,
  onSecondsConfirm: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  IntroOutroSkipAmountDialog(
    titleRes = StringsR.string.settings_playback_skip_intro_title,
    currentSeconds = currentSeconds,
    onSecondsConfirm = onSecondsConfirm,
    onDismiss = onDismiss,
  )
}

@Composable
internal fun SkipOutroAmountDialog(
  currentSeconds: Int,
  onSecondsConfirm: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  IntroOutroSkipAmountDialog(
    titleRes = StringsR.string.settings_playback_skip_outro_title,
    currentSeconds = currentSeconds,
    onSecondsConfirm = onSecondsConfirm,
    onDismiss = onDismiss,
  )
}

@Composable
private fun IntroOutroSkipAmountDialog(
  @StringRes titleRes: Int,
  currentSeconds: Int,
  onSecondsConfirm: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  TimeSettingDialog(
    title = stringResource(titleRes),
    currentSeconds = currentSeconds,
    minSeconds = 0,
    maxSeconds = MAX_INTRO_OUTRO_SKIP_SECONDS,
    textPluralRes = StringsR.plurals.duration_seconds,
    description = stringResource(StringsR.string.settings_playback_skip_intro_outro_summary),
    manualInput = true,
    onSecondsConfirm = onSecondsConfirm,
    onDismiss = onDismiss,
  )
}
