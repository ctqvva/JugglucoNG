#!/usr/bin/env python3
"""Build a JNI-free UI harness for the ACTUAL AiDex sheet on a named emulator.

Temporary mobileDebug sources override Application only in this harness APK.
They are always removed afterwards. The harness does not test BLE or native
storage, and must not be installed on a physical phone or distributed as the app.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

REPO = Path(__file__).resolve().parents[2]
ACTIVITY = '''@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package tk.glucodata.ui
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class AiDexMenuQaActivity: ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        val locale = java.util.Locale.forLanguageTag(base.getSharedPreferences("aidex-qa",0).getString("locale","en")!!)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selection by remember { mutableIntStateOf(intent.getIntExtra("mode", 0)) }
            MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.padding(24.dp)) { Text("AiDex Auto mode", color=MaterialTheme.colorScheme.onBackground) }
                    AiDexAlgorithmSheet("X-UI-PREVIEW",selection,
                        onSelect={selection=it; android.util.Log.i("AiDexUiQa","mode="+it)},onDismiss={finish()})
                }
            }
        }
    }
}
'''
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
<application android:name="android.app.Application" tools:replace="android:name">
<activity android:name="tk.glucodata.ui.AiDexMenuQaActivity" android:exported="true" />
</application></manifest>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emulator", required=True)
    parser.add_argument("--output", type=Path, default=REPO / ".local/aidex-evaluation/menu-harness.apk")
    args = parser.parse_args()
    if not args.emulator.startswith("emulator-"):
        parser.error("Only an explicitly named emulator is supported")
    sdk = Path(os.environ["ANDROID_HOME"])
    adb = str(sdk / "platform-tools/adb")
    hardware = subprocess.check_output([adb, "-s", args.emulator, "shell", "getprop", "ro.kernel.qemu"], text=True).strip()
    if hardware != "1":
        parser.error("Target is not an emulator")
    root = REPO / "Common/src/mobileDebug"
    if root.exists():
        parser.error("Temporary source set already exists; refusing to overwrite it")
    try:
        source = root / "java/tk/glucodata/ui/AiDexMenuQaActivity.kt"
        source.parent.mkdir(parents=True)
        source.write_text(ACTIVITY)
        (root / "AndroidManifest.xml").write_text(MANIFEST)
        subprocess.run([str(REPO / "gradlew"), "--offline", "--no-daemon", ":Common:assembleMobileDebug",
            "-x", ":Common:externalNativeBuildMobileDebug", "-PjugglucoAbi=arm64-v8a"], cwd=REPO, check=True)
        apks = list((REPO / "Common/build/outputs/apk/mobile/debug").glob("*.apk"))
        if len(apks) != 1:
            raise RuntimeError(f"Expected one harness APK, got {len(apks)}")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apks[0], args.output)
        subprocess.run([adb, "-s", args.emulator, "install", "-r", str(args.output)], check=True)
        metadata = json.loads((REPO / "Common/build/outputs/apk/mobile/debug/output-metadata.json").read_text())
        component = metadata["applicationId"] + "/tk.glucodata.ui.AiDexMenuQaActivity"
        subprocess.run([adb, "-s", args.emulator, "shell", "am", "start", "-n", component, "--ei", "mode", "0"], check=True)
    finally:
        shutil.rmtree(root)


if __name__ == "__main__":
    main()
