#!/usr/bin/env python3
"""Run production v1/v3 serializers through real JNI and NightscoutCalibration.

The native store, selected-mode provider and calibration engine are fixtures.
Kotlin tests separately exercise the real mode provider and Sibionics lifecycle.
Requires a host C++20 compiler and JAVA_HOME pointing to a JDK.
"""
import argparse
import json
import os
from pathlib import Path
import runpy
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
extract = runpy.run_path(str(ROOT / "tools/test-resolved-glucose.py"))["extract"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-ref", help="Negative control for native export consumers")
    args = parser.parse_args()
    java_home = Path(os.environ["JAVA_HOME"])
    with tempfile.TemporaryDirectory(prefix="nightscout-mode-") as directory:
        work = Path(directory)
        sources = {
            "androidx/annotation/Keep.java": "package androidx.annotation; public @interface Keep {}",
            "tk/glucodata/Applic.java": "package tk.glucodata; public class Applic { public static int unit; public static Object app; }",
            "tk/glucodata/Natives.java": 'package tk.glucodata; public class Natives { public static String lastsensorname() { throw new AssertionError("unexpected current sensor lookup"); } }',
            "tk/glucodata/SensorIdentity.java": "package tk.glucodata; public class SensorIdentity { public static String resolveAppSensorId(String id) { return id; } }",
            "tk/glucodata/CurrentDisplaySource.java": """package tk.glucodata;
public class CurrentDisplaySource {
    public static int selectedMode = -1;
    public static int resolveViewModeForNativeExport(String id, int fallback) {
        if (!id.equals(CalibrationAccess.expectedId)) throw new AssertionError("truncated mode identity: " + id);
        return selectedMode < 0 ? fallback : selectedMode;
    }
}""",
            "tk/glucodata/DataSmoothing.java": """package tk.glucodata;
public class DataSmoothing {
    public static int exchangeSmoothingMinutes(Object app) { return 0; }
    public static boolean shouldCollapseExchangeOutputs(Object app) { return false; }
    public static int collapseIntervalMinutes(int minutes) { return minutes; }
    public static int getMinutes(Object app) { return 0; }
}""",
            "tk/glucodata/CalibrationAccess.java": """package tk.glucodata;
public class CalibrationAccess {
    public static String expectedId;
    public static boolean hasActiveCalibration(boolean raw, String id) {
        if (!id.equals(expectedId)) throw new AssertionError("truncated calibration identity: " + id);
        return true;
    }
    public static float getCalibratedValue(float value, long time, boolean raw, boolean diagnostics, String id) {
        return value + (raw ? -20f : 15f) / (Applic.unit == 1 ? 18.0182f : 1f);
    }
}""",
            "tk/glucodata/NightscoutCalibration.java": (ROOT / "Common/src/main/java/tk/glucodata/NightscoutCalibration.java").read_text(),
        }
        for name, source in sources.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        subprocess.run([str(java_home / "bin/javac"), "-d", str(work), *map(str, work.rglob("*.java"))], check=True)
        code = r'''
#include <jni.h>
#include <algorithm>
#include <array>
#include <cassert>
#include <charconv>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <span>
#include <string>
#include <string_view>
#include <system_error>
using namespace std;
#define LOGGER(...) ((void)0)
using sensorname_t = array<char,11>;
float gconvert(int value) { return value; }
constexpr float convfactordL = 180.182f;
constexpr int maxminutes = 100000;
'''
        code += extract("SensorGlucoseData.hpp", "struct ScanData") + ";\n"
        code += r'''
struct Info { int viewMode = 0; };
struct SensorGlucoseData {
    string id;
    Info info;
    ScanData polls[3]{};
    int raw[3]{};
    const Info *getinfo() const { return &info; }
    Info *getinfo() { return &info; }
    bool error() const { return false; }
    string_view sensid() const { return id; }
    int getRawForPoll(const ScanData *p) const {
        assert(p >= polls && p < polls+3); return raw[p-polls];
    }
};
struct Settings { bool DoCalibrate = false; Settings *data() { return this; } } setting;
Settings *settings = &setting;
double calibrateONE(const SensorGlucoseData *, uint32_t, double) { return NAN; }
double calibrateONEtest(const SensorGlucoseData *, const ScanData &) { return NAN; }
JNIEnv *javaEnv;
JNIEnv *getenv() { return javaEnv; }
jclass exportvalueclass, nightscoutcalibrationclass;
bool ensureexportvalueclass(JNIEnv *) { return true; }
static int uploadSmoothingSeconds = 0;
#define fromjava(name) name
struct SensorIndex {
    SensorGlucoseData *sensor = nullptr;
    int sensorindex(const char *id) { return sensor && sensor->id == id ? 0 : -1; }
    SensorGlucoseData *getSensorData(int index) { assert(index == 0); return sensor; }
} sensorIndex;
SensorIndex *sensors = &sensorIndex;
'''
        code += extract("g.cpp", 'extern "C" JNIEXPORT jboolean JNICALL fromjava(setSensorViewMode)')
        code += "template <typename T>\n" + extract("net/watchserver/common.hpp", "inline void\taddstrview(")
        code += "template <class T, size_t N>\n" + extract("net/watchserver/common.hpp", "inline static constexpr void addar(")
        for signature in ["inline double getdelta(", "inline std::string_view fixedsensorview(",
                          "inline void copyfixedsensorname(", "inline void addjsonuint(",
                          "inline void addjsonint(", "inline void addjsonpadded2(",
                          "inline void addjsonpadded3(", "inline void addjsonfixed3(",
                          "inline int addNightscoutDateString(", "inline void addjsonescaped("]:
            code += extract("net/watchserver/common.hpp", signature)
        trend = (ROOT / "Common/src/main/cpp/exchangetrend.hpp").read_text()
        code += trend[trend.index("static constexpr const uint32_t minderivegapsecs"):]
        code += '\n#define DOUBLETHRESHOLD 3.0f\n#define NOT_DETERMINED ""\n'
        for signature in ["int getdeltaindex(float rate) {", "std::string_view getdeltanamefromindex(int index) {", "std::string_view getdeltaname(float rate) {"]:
            code += extract("glucose.cpp", signature)
        for signature in ["int mksgvidentifier(", "int mkv3streamid(", "char * writev3entry("]:
            code += extract("net/watchserver/common.cpp", signature)
        code += extract("net/watchserver/common.cpp", "int resolveExportedMgdlAt(", args.consumer_ref)
        code += extract("net/watchserver/common.cpp", "int resolveExportedMgdl(")
        code += extract("net/watchserver/uploader.cpp", "static int getNightscoutCalibrationOverrideForItem(", args.consumer_ref)
        code += "template <class Getvalue>\n" + extract("net/watchserver/uploader.cpp", "static int smoothPollWindow(")
        for signature in ["static int smoothedUploadMgdl(", "static int smoothedUploadRaw("]:
            code += extract("net/watchserver/uploader.cpp", signature)
        code += "template <class T>\n" + extract("net/watchserver/uploader.cpp", "int mkuploaditem(")
        code += extract("net/watchserver/uploader.cpp", "static const char *writeNightscoutV3UploadEntry(")
        code += r'''
int main(int argc, char **argv) {
    JavaVM *vm;
    string classpath = string("-Djava.class.path=") + argv[1];
    JavaVMOption option{classpath.data(), nullptr};
    JavaVMInitArgs init{};
    init.version = JNI_VERSION_1_8; init.nOptions = 1; init.options = &option;
    assert(JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&javaEnv), &init) == JNI_OK);
    exportvalueclass = nightscoutcalibrationclass = javaEnv->FindClass("tk/glucodata/NightscoutCalibration");
    auto modeClass = javaEnv->FindClass("tk/glucodata/CurrentDisplaySource");
    auto modeField = javaEnv->GetStaticFieldID(modeClass, "selectedMode", "I");
    auto calibration = javaEnv->FindClass("tk/glucodata/CalibrationAccess");
    auto idField = javaEnv->GetStaticFieldID(calibration, "expectedId", "Ljava/lang/String;");
    auto app = javaEnv->FindClass("tk/glucodata/Applic");
    auto unitField = javaEnv->GetStaticFieldID(app, "unit", "I");
    assert(!javaEnv->ExceptionCheck());
    SensorGlucoseData mirror{"SIBI:0683013AQT9"};
    auto mirrorId = javaEnv->NewStringUTF(mirror.id.c_str());
    assert(!setSensorViewMode(javaEnv, nullptr, mirrorId, 3)); // no shell is created
    sensorIndex.sensor = &mirror;
    assert(setSensorViewMode(javaEnv, nullptr, mirrorId, 3) && mirror.info.viewMode == 3);
    assert(!setSensorViewMode(javaEnv, nullptr, mirrorId, 4) && mirror.info.viewMode == 3);
    auto otherId = javaEnv->NewStringUTF("LEGACY:0683013AQT9");
    assert(!setSensorViewMode(javaEnv, nullptr, otherId, 0) && mirror.info.viewMode == 3);
    javaEnv->DeleteLocalRef(otherId);
    javaEnv->DeleteLocalRef(mirrorId);
    sensorname_t alias; memcpy(alias.data(), "0683013AQT9", 11);
    for (int unit : {0, 1}) for (const char *id : {"SIBI:0683013AQT9", "LEGACY:0683013AQT9"}) {
        SensorGlucoseData sensor{id};
        sensor.polls[0] = {1760000000, 0, 110, 0, 0};
        auto series = span<const ScanData>(sensor.polls, 1);
        javaEnv->SetStaticIntField(app, unitField, unit);
        auto jid = javaEnv->NewStringUTF(id);
        javaEnv->SetStaticObjectField(calibration, idField, jid);
        javaEnv->DeleteLocalRef(jid);
        // A stale mirror must lose to every selected mode, including a switch
        // from Auto to Raw; absent managed state must preserve every native mode.
        for (int selected : {0, 1, 2, 3, -1}) for (int nativeMode : {0, 1, 2, 3}) for (bool missingRaw : {false, true}) {
            javaEnv->SetStaticIntField(modeClass, modeField, selected);
            sensor.info.viewMode = nativeMode;
            sensor.raw[0] = missingRaw ? 0 : 89; // ~160 mg/dL, Raw calibration -> ~140
            int mode = selected < 0 ? nativeMode : selected;
            int expected = (mode == 1 || mode == 3) ? (missingRaw ? 110 : 140) : 125;
            assert(resolveExportedMgdl(&sensor, &sensor.polls[0], &alias) == expected);
            char payload[2048]{};
            int size = mkuploaditem(&sensor, payload, &alias, sensor.polls[0], series, 0, false);
            printf("%d\t%.*s\n", expected, size, payload);
            auto end = writeNightscoutV3UploadEntry(payload, &sensor, &alias, &sensor.polls[0], series, 0);
            printf("%d\t%.*s\n", expected, int(end-payload), payload);
            // The value-level final marker must still bypass both calibration layers.
            sensor.polls[0].g = 130 | ScanData::resolvedGlucoseFlag;
            assert(resolveExportedMgdl(&sensor, &sensor.polls[0], &alias) == 130);
            size = mkuploaditem(&sensor, payload, &alias, sensor.polls[0], series, 0, false);
            printf("130\t%.*s\n", size, payload);
            end = writeNightscoutV3UploadEntry(payload, &sensor, &alias, &sensor.polls[0], series, 0);
            printf("130\t%.*s\n", int(end-payload), payload);
            sensor.polls[0].g = 110;
        }
    }
    assert(!javaEnv->ExceptionCheck());
    vm->DestroyJavaVM();
}
'''
        source = work / "test.cpp"
        source.write_text(code)
        platform = "darwin" if sys.platform == "darwin" else "linux"
        server = java_home / "lib/server"
        binary = work / "test"
        subprocess.run(shlex.split(os.environ.get("CXX", "c++")) + [
            "-std=c++20", str(source), "-o", str(binary),
            "-I" + str(java_home / "include"), "-I" + str(java_home / "include" / platform),
            "-L" + str(server), "-Wl,-rpath," + str(server), "-ljvm",
        ], check=True)
        result = subprocess.run([str(binary), str(work)], capture_output=True, text=True)
        if result.returncode:
            sys.stderr.write(result.stderr)
            result.check_returncode()
        count = 0
        for line in result.stdout.splitlines():
            expected, payload = line.split("\t", 1)
            document = json.loads(payload)
            assert document["sgv"] == int(expected), document
            assert document["device"] == "0683013AQT9", document
            count += 1
        assert count == 640, count
        print(f"Validated {count} serialized v1/v3 payloads through real JNI and the Java calibration bridge")


if __name__ == "__main__":
    main()
