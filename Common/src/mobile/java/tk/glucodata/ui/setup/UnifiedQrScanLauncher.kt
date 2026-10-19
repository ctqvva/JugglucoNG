package tk.glucodata.ui.setup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import tk.glucodata.MainActivity
import tk.glucodata.PhotoScan
import tk.glucodata.UnifiedScanActivity

@Composable
fun rememberUnifiedQrScanLauncher(
    requestCode: Int,
    title: String? = null,
    onScanResult: (String) -> Unit,
    onCancelled: (() -> Unit)? = null
): () -> Unit {
    val context = LocalContext.current
    val onScanResultState = rememberUpdatedState(onScanResult)
    val onCancelledState = rememberUpdatedState(onCancelled)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val scanText = if (result.resultCode == Activity.RESULT_OK) {
            PhotoScan.trimOuterScannerWhitespace(data?.getStringExtra(UnifiedScanActivity.EXTRA_SCAN_TEXT))
        } else {
            null
        }
        if (!scanText.isNullOrEmpty()) {
            onScanResultState.value(scanText)
        } else {
            onCancelledState.value?.invoke()
        }
    }

    return remember(context, launcher, requestCode, title) {
        {
            val intent = PhotoScan.createUnifiedScanIntent(context, requestCode, 0L, title)
            if (intent != null) {
                launcher.launch(intent)
            } else {
                if (title.isNullOrEmpty()) {
                    MainActivity.launchQrScan(requestCode)
                } else {
                    MainActivity.launchQrScan(requestCode, title)
                }
            }
        }
    }
}
