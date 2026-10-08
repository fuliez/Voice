package voice.features.settings.views

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import voice.core.strings.R as StringsR

/** Longest value that can be typed, in decimal digits. */
private const val MAX_INPUT_DIGITS = 3

@Composable
fun TimeSettingDialog(
  title: String,
  currentSeconds: Int,
  @PluralsRes textPluralRes: Int,
  minSeconds: Int,
  maxSeconds: Int,
  onSecondsConfirm: (Int) -> Unit,
  onDismiss: () -> Unit,
  description: String? = null,
  manualInput: Boolean = false,
) {
  var sliderValue by remember { mutableFloatStateOf(currentSeconds.toFloat()) }
  var inputValue by remember { mutableStateOf(currentSeconds.toString()) }

  fun setSecondsFromInput(raw: String) {
    val digits = raw.filter { it.isDigit() }.take(MAX_INPUT_DIGITS)
    val parsed = digits.toIntOrNull()
    inputValue = if (parsed != null && parsed > maxSeconds) maxSeconds.toString() else digits
    if (parsed != null) {
      sliderValue = parsed.coerceIn(minSeconds, maxSeconds).toFloat()
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(text = title)
    },
    text = {
      Column {
        if (description != null) {
          Text(text = description)
        }
        if (manualInput) {
          OutlinedTextField(
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 8.dp),
            value = inputValue,
            onValueChange = { setSecondsFromInput(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = {
              Text(stringResource(StringsR.string.settings_playback_skip_intro_outro_input_label))
            },
          )
        } else {
          Text(
            LocalResources.current.getQuantityString(
              textPluralRes,
              sliderValue.roundToInt(),
              sliderValue.roundToInt(),
            ),
          )
        }
        Slider(
          valueRange = minSeconds.toFloat()..maxSeconds.toFloat(),
          value = sliderValue,
          onValueChange = {
            sliderValue = it
            inputValue = it.roundToInt().toString()
          },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val seconds = inputValue.toIntOrNull() ?: sliderValue.roundToInt()
          onSecondsConfirm(seconds.coerceIn(minSeconds, maxSeconds))
          onDismiss()
        },
      ) {
        Text(stringResource(StringsR.string.common_dialog_confirm))
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          onDismiss()
        },
      ) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}
