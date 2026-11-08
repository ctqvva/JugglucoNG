package tk.glucodata.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tk.glucodata.R
import tk.glucodata.ui.components.*

@Composable
internal fun LumiflexPhoneField(account: String, onAccount: (String) -> Unit, enabled: Boolean) {
    AccountPhoneField(
        value = russianSubscriberFromCanonicalAccount(account),
        onValueChange = { raw ->
            val digits = russianSubscriberInput(raw)
            onAccount(if (digits.isBlank()) "" else "7$digits")
        },
        prefix = "+7", label = stringResource(R.string.lumiflex_phone_mode), enabled = enabled,
        placeholder = stringResource(R.string.lumiflex_phone_example),
        normalizeInput = ::russianSubscriberInput,
        outputTransformation = RussianPhoneOutputTransformation,
    )
}

internal fun lumiflexAccountLabel(account: String): String {
    if (account.contains('@')) return account
    val digits = russianSubscriberFromAccount(account)
    return if (digits.length == 10) "+7 (${digits.take(3)}) ${digits.substring(3, 6)}-${digits.substring(6, 8)}-${digits.substring(8)}" else account
}
