#!/bin/sh
set -eu
cd "$(dirname "$0")"
probe_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
probe_java="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
probe_tools="$probe_sdk/build-tools/37.0.0"
probe_android="$probe_sdk/platforms/android-37.0/android.jar"
mkdir -p out
probe_build="$(mktemp -d out/build.XXXXXX)"
mkdir -p "$probe_build/classes" "$probe_build/dex" "$probe_build/generated" "$probe_build/res/font"
cp -R res/. "$probe_build/res/"
cp ../../Common/src/main/res/font/ibm_plex_sans_var.ttf "$probe_build/res/font/"
cp ../../Common/src/main/res/layout/notification_phone*.xml "$probe_build/res/layout/"
cp ../../Common/src/main/res/drawable/notification_trend*.xml "$probe_build/res/drawable/"
mkdir -p "$probe_build/res/values"
cp ../../Common/src/main/res/values/notification_native_settings.xml "$probe_build/res/values/"
probe_java_classes=../../Common/build/intermediates/javac/mobileDebug/compileMobileDebugJavaWithJavac/classes
probe_kotlin_classes=../../Common/build/intermediates/built_in_kotlinc/mobileDebug/compileMobileDebugKotlin/classes
probe_stdlib="${PROBE_KOTLIN_STDLIB:-}"
if [ -z "$probe_stdlib" ]; then
    probe_stdlib="$(rg --files "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.10" -g '*.jar')"
fi
test -f "$probe_stdlib"
test -f "$probe_java_classes/tk/glucodata/NotificationChartDrawer\$ValueItem.class"
test -f "$probe_kotlin_classes/tk/glucodata/SensorVisuals.class"
"$probe_tools/aapt2" compile --dir "$probe_build/res" -o "$probe_build/resources.zip"
"$probe_tools/aapt2" link -I "$probe_android" --manifest AndroidManifest.xml --java "$probe_build/generated" --extra-packages tk.glucodata -o "$probe_build/resources.apk" "$probe_build/resources.zip"
"$probe_java/bin/javac" -source 8 -target 8 -classpath "$probe_android:$probe_java_classes:$probe_kotlin_classes:$probe_stdlib" -d "$probe_build/classes" src/tk/glucodata/nativeviewprobe/*.java src/tk/glucodata/ProductionPreview.java ../../Common/src/main/java/tk/glucodata/CustomGlucoseNotification.java ../../Common/src/main/java/tk/glucodata/NotificationValueBitmap.java ../../Common/src/main/java/tk/glucodata/TrendArrowAngle.java "$probe_build/generated/tk/glucodata/nativeviewprobe/R.java" "$probe_build/generated/tk/glucodata/R.java"
mkdir -p "$probe_build/nest-host/tk/glucodata"
cp "$probe_java_classes"/tk/glucodata/NotificationChartDrawer*.class "$probe_build/nest-host/tk/glucodata/"
"$probe_java/bin/jar" --create --file "$probe_build/nest-host.jar" -C "$probe_build/nest-host" .
"$probe_tools/d8" --lib "$probe_android" --classpath "$probe_build/nest-host.jar" --min-api 26 --output "$probe_build/dex" "$probe_build"/classes/tk/glucodata/nativeviewprobe/*.class "$probe_build"/classes/tk/glucodata/*.class "$probe_java_classes/tk/glucodata/NotificationChartDrawer\$ValueItem.class" "$probe_kotlin_classes"/tk/glucodata/SensorVisuals*.class "$probe_stdlib"
cp "$probe_build/resources.apk" "$probe_build/probe-unsigned.apk"
(cd "$probe_build/dex" && zip -q -u ../probe-unsigned.apk classes.dex)
"$probe_tools/zipalign" -f 4 "$probe_build/probe-unsigned.apk" "$probe_build/probe-aligned.apk"
if [ ! -f out/probe.keystore ]; then
    "$probe_java/bin/keytool" -genkeypair -keystore out/probe.keystore -storepass android -keypass android -alias probe -keyalg RSA -validity 3650 -dname 'CN=Native Notification View Probe'
fi
"$probe_tools/apksigner" sign --ks out/probe.keystore --ks-pass pass:android --out out/native-view-probe.apk "$probe_build/probe-aligned.apk"
"$probe_tools/apksigner" verify out/native-view-probe.apk
