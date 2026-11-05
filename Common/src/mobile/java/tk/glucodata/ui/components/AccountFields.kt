package tk.glucodata.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import tk.glucodata.R

/** The password control shared with the MQ account form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
    onDone: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(stringResource(R.string.password)) },
        singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled && value.isNotBlank()) onDone() }),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = {
            val description = stringResource(if (visible) R.string.hide_password else R.string.show_password)
            IconButtonTooltip(description) {
                IconButton(onClick = { visible = !visible }, shapes = IconButtonDefaults.shapes()) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, description)
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** The fixed/pickable country-prefix field used by SMS account setup. */
@Composable
fun AccountPhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    prefix: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    placeholder: String? = null,
    onCountryClick: (() -> Unit)? = null,
    countryPickerLabel: String? = null,
    normalizeInput: (String) -> String = { it },
    outputTransformation: OutputTransformation? = null,
) {
    val field = key(prefix) { rememberTextFieldState(initialText = value) }
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestNormalize by rememberUpdatedState(normalizeInput)
    LaunchedEffect(value, field) {
        if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { text ->
            if (text != latestValue) latestChange(text)
        }
    }
    OutlinedTextField(
        state = field, enabled = enabled, isError = isError,
        label = { Text(label) }, lineLimits = TextFieldLineLimits.SingleLine,
        placeholder = placeholder?.let { { Text(it) } },
        inputTransformation = InputTransformation {
            val normalized = latestNormalize(asCharSequence().toString())
            if (normalized != asCharSequence().toString()) replace(0, length, normalized)
        },
        outputTransformation = outputTransformation,
        leadingIcon = {
            Row(
                modifier = Modifier.heightIn(min = 48.dp)
                    .then(if (onCountryClick != null) Modifier.clickable(enabled = enabled,
                        role = Role.Button, onClickLabel = countryPickerLabel, onClick = onCountryClick) else Modifier)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(prefix)
                if (onCountryClick != null) Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth(),
    )
}

internal fun russianSubscriberInput(raw: String): String {
    val digits = raw.filter { it in '0'..'9' }
    return (if (raw.trimStart().startsWith("+7") || (digits.length > 10 && digits.firstOrNull() in listOf('7', '8')))
        digits.drop(1) else digits).take(10)
}

internal fun russianSubscriberFromAccount(account: String): String =
    account.filter { it in '0'..'9' }.let { if (it.startsWith('7') || (it.length == 11 && it.startsWith('8'))) it.drop(1) else it }.take(10)

internal val RussianPhoneOutputTransformation = OutputTransformation {
    if (length > 8) insert(8, "-")
    if (length > 6) insert(6, "-")
    if (length > 3) insert(3, ") ")
    if (length > 0) insert(0, "(")
}
