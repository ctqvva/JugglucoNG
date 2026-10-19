package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import tk.glucodata.R

/**
 * What the phone shows when a reading is tapped and the journal is on: add a
 * journal item against that reading, or calibrate it. With the journal off the
 * caller skips this and goes straight to calibration.
 */
@Composable
fun ReadingActionChooser(
    hasCalibration: Boolean,
    onAddJournal: () -> Unit,
    onCalibrate: () -> Unit,
) {
    ScreenScaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.wear_reading_actions_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Button(
                onClick = onAddJournal,
                label = { Text(stringResource(R.string.wear_journal_add)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onCalibrate,
                label = {
                    Text(
                        stringResource(
                            if (hasCalibration) R.string.wear_calibration_edit
                            else R.string.calibrate_action,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}
