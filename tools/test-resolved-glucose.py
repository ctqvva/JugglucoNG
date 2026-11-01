#!/usr/bin/env python3
"""Exercise production native mirror classification, calibration and export bodies.

JNI and calibration arithmetic are stand-ins; the reader decisions are extracted
from production. --consumer-ref tests an older reader against today's mirror IDs.
"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "Common/src/main/cpp"


def extract(path, signature, ref=None):
    if ref:
        source = subprocess.check_output(
            ["git", "show", f"{ref}:Common/src/main/cpp/{path}"], cwd=ROOT, text=True,
        )
    else:
        source = (CPP / path).read_text()
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-ref")
    args = parser.parse_args()
    code = r'''
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>
#include <string_view>
#include <vector>
using namespace std;
#define LOGGER(...) ((void)0)
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE true
#define JNI_FALSE false
#define fromjava(name) name
using jboolean = bool;
using jclass = void*;
using jmethodID = void*;
using jlong = int64_t;
using jstring = const char*;
using sensorname_t = array<char, 11>;
struct CaliPara { uint32_t time = 1; };
struct ScanData {
    uint32_t t;
    int g;
    uint32_t gettime() const { return t; }
    int getmgdL() const { return g; }
    bool valid() const { return g > 0; }
};
struct Info { uint32_t caliNr = 1, viewMode = 0; CaliPara caliPara[1]; };
struct SensorGlucoseData {
    string id;
    Info info;
    string_view sensid() const { return id; }
    const Info *getinfo() const { return &info; }
    bool error() const { return false; }
    sensorname_t alias{};
    const sensorname_t *shortsensorname() const { return &alias; }
    /* SEMANTICS */
};
struct Sensoren {
    vector<SensorGlucoseData> entries;
    const SensorGlucoseData *gethistshort(const char *id) const {
        for (const auto &entry : entries)
            if (entry.id == id || entry.id.substr(5) == id) return &entry;
        return nullptr;
    }
};
Sensoren registry;
Sensoren *sensors = &registry;
struct Settings {
    bool DoCalibrate = false;
    bool CalibratePast = false;
    Settings *data() { return this; }
} storage;
Settings *settings = &storage;
int nativeCalls = 0;
double calibrateValue(const CaliPara &, uint32_t, double value) {
    ++nativeCalls;
    return value + 15;
}
struct JNIEnv {
    int javaCalls = 0;
    int javaOffset = 15;
    bool ExceptionCheck() { return false; }
    void ExceptionClear() {}
    jmethodID GetStaticMethodID(jclass, const char *, const char *) { return (void*)1; }
    jstring NewStringUTF(const char *id) { return id; }
    void DeleteLocalRef(jstring) {}
    const char *GetStringUTFChars(jstring id, void *) { return id; }
    void ReleaseStringUTFChars(jstring, const char *) {}
    int CallStaticIntMethod(jclass, jmethodID, jstring, int, int value, int, jlong) {
        ++javaCalls;
        return value + javaOffset;
    }
} env;
JNIEnv *getenv() { return &env; }
jclass exportvalueclass = (void*)1;
bool ensureexportvalueclass(JNIEnv *) { return true; }
void copyfixedsensorname(char *out, size_t size, const sensorname_t *name) {
    snprintf(out, size, "%.*s", 11, name->data());
}
'''
    code = code.replace("/* SEMANTICS */", extract("SensorGlucoseData.hpp", "bool hasResolvedGlucose()"))
    code += extract("g.cpp", 'extern "C" JNIEXPORT jboolean JNICALL fromjava(isGlucoseValueResolved)')
    for signature in [
        "double calibrateValue(const CaliPara &cali ,const ScanData &el)",
        "const CaliPara *getCaliBefore(",
        "double     calibrateNowNoCheck(const SensorGlucoseData *sens,const uint32_t time,",
        "double     calibrateONE(const SensorGlucoseData *sens,const uint32_t time,",
        "std::pair<const ScanData*,const ScanData*>      makecalibrated(",
        "std::pair<const ScanData*,const ScanData*>      makecalibratedback(",
    ]:
        code += extract("calibrate/calculate.cpp", signature, args.consumer_ref) + "\n"
    code += extract("net/watchserver/common.cpp", "int resolveExportedMgdlAt(", args.consumer_ref)
    code += r'''
int main() {
    for (const char *id : {"API-123456789ABC", "NSF-23456789ABCD", "MQF-3456789ABCDE"}) {
        registry.entries.push_back({id});
        const auto &source = registry.entries.back();
        assert(isGlucoseValueResolved(&env, nullptr, id));
        const auto alias = string(id).substr(5);
        assert(isGlucoseValueResolved(&env, nullptr, alias.c_str()));
        for (bool nativeEnabled : {false, true}) {
            storage.DoCalibrate = nativeEnabled;
            env.javaCalls = nativeCalls = 0;
            assert(resolveExportedMgdlAt(&source, source.shortsensorname(), 100, 130, 0) == 130);
            assert(nativeCalls == 0 && env.javaCalls == 0);
        }
        // Watch/lastGlucose and historical native calibration must leave it alone too.
        assert(isnan(calibrateNowNoCheck(&source, 100, 130)));
        assert(isnan(calibrateONE(&source, 100, 130)));
        ScanData input{100, 130}, output{};
        assert(makecalibrated(&source, &input, &output, 1, true).first == &output);
        assert(output.g == 130);
        assert(makecalibratedback(&source, &input, &output, 1, true).first == &output);
        assert(output.g == 130);
    }
    for (const char *id : {"1234567890", "X-222227KUFY", "70D07EDCB416"}) {
        registry.entries.push_back({id});
        const auto &source = registry.entries.back();
        assert(!isGlucoseValueResolved(&env, nullptr, id));
        // Automatic Java correction, and then the independent native correction.
        storage.DoCalibrate = false;
        env.javaOffset = 15;
        assert(resolveExportedMgdlAt(&source, source.shortsensorname(), 100, 115, 0) == 130);
        storage.DoCalibrate = true;
        env.javaOffset = 0;
        assert(resolveExportedMgdlAt(&source, source.shortsensorname(), 100, 115, 0) == 130);
        assert(calibrateNowNoCheck(&source, 100, 115) == 130);
        assert(calibrateONE(&source, 100, 115) == 130);
        ScanData input{100, 115}, output{};
        assert(makecalibrated(&source, &input, &output, 1, true).first == &output);
        assert(output.g == 130);
        assert(makecalibratedback(&source, &input, &output, 1, true).first == &output);
        assert(output.g == 130);
        env.javaOffset = 15;
    }
    assert(!isGlucoseValueResolved(&env, nullptr, "missing"));
    assert(!isGlucoseValueResolved(&env, nullptr, nullptr));
    puts("Resolved mirror export, alias and native watch calibration checks passed");
}
'''
    with tempfile.TemporaryDirectory(prefix="resolved-glucose-") as directory:
        source = Path(directory) / "test.cpp"
        binary = Path(directory) / "test"
        source.write_text(code)
        subprocess.run(shlex.split(os.environ.get("CXX", "c++")) + [
            "-std=c++20", str(source), "-o", str(binary),
        ], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
