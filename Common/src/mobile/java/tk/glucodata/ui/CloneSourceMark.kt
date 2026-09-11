package tk.glucodata.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.CloneTransport
import tk.glucodata.R

@Composable
fun CloneSourceMark(
    transport: CloneTransport?,
    showLabel: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp,
    textStyle: TextStyle = TextStyle.Default,
) {
    val description = stringResource(
        when (transport) {
            CloneTransport.TURN -> R.string.clone_source_turn_description
            CloneTransport.LOCAL_ICE -> R.string.clone_source_local_ice_description
            CloneTransport.UNKNOWN, null -> R.string.clone_source_label
        }
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (transport == CloneTransport.TURN) Icons.Default.Hub else Icons.Default.SwapHoriz,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.clone_source_label),
                style = textStyle,
                color = tint,
                maxLines = 1,
            )
        }
    }
}
