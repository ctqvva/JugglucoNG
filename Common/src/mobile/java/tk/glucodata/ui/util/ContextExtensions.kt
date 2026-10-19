package tk.glucodata.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import kotlin.system.exitProcess

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun Activity.hardRestart() {
    finish()
    overridePendingTransition(0, 0)
    startActivity(intent)
    overridePendingTransition(0, 0)
}

/**
 * Full app restart that kills the process to clear native C++ memory state.
 * Use after data clearing operations since native code retains state in memory.
 */
fun Activity.fullRestart() {
    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    finishAffinity()
    if (intent != null) {
        startActivity(intent)
    }
    // Kill process to clear native memory
    exitProcess(0)
}
