#!/usr/bin/env python3
"""Exercise production native mirror classification, calibration and export bodies.

Uses the production on-disk ScanData layout, writer and reader bodies. JNI and
calibration arithmetic are stand-ins. Includes a legacy file reopened after a
partial page rewrite and a byte-for-byte Clone-style copy.
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
#include <climits>
#include <span>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
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
constexpr float convfactordL = 180.182f;
constexpr int maxminutes = 100000;
float gconvert(int value) { return value; }
/* SCANDATA */;
struct Info { uint32_t caliNr = 1, viewMode = 0, starttime = 1760000000, pollcount = 0, pollstart = 0; uint16_t nightiter = 0; CaliPara caliPara[1]; };
struct SensorGlucoseData {
    string id;
    Info info;
    string_view sensid() const { return id; }
    const Info *getinfo() const { return &info; }
    bool error() const { return false; }
    sensorname_t alias{};
    const sensorname_t *shortsensorname() const { return &alias; }
    ScanData polls[3]{};
    ScanData *getPollsData() { return polls; }
    const ScanData *getPollsData() const { return polls; }
    struct RawData { uint16_t raw; } rawpolls[3]{};
    uint16_t temppolls[3]{};
    const RawData *getRawPollsData() const { return rawpolls; }
    int getTempForPoll(int) const { return 0; }
    bool validPollIndex(int i) const { return i >= 0 && i < 3; }
    bool hasStreamID(int i) const { return polls[i].getmgdL() > 0; }
    int getRawForPoll(const ScanData *) const { return 0; }
    size_t pollStorageCapacity() const { return 3; }
    int pollcount() const { return info.pollcount; }
    void backstream(int) {}
    void backhistory(int) {}
    Info *getinfo() { return &info; }
    /* CLASSIFICATION */
    /* WRITERS */
};
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
    code = code.replace("/* SCANDATA */", extract("SensorGlucoseData.hpp", "struct ScanData"))
    code = code.replace("/* WRITERS */", "\n".join(
        "template <int secs>\n" + extract("SensorGlucoseData.hpp", signature)
        for signature in ["int savepollallIDsonlyQuiet(", "bool savepollallIDsQuiet("]
    ) + r"""
    template<int secs, class... Args> bool savepollallIDs(Args... args) {
        return savepollallIDsQuiet<secs>(args...);
    }
    """)
    code += r"""
using jfloat = float;
using RawData = SensorGlucoseData::RawData;
bool backup = false;
void syncListStarttime(SensorGlucoseData *, uint32_t) {}
int compactRawMgdl(float) { return 0; }
float derivechangeforsample(const ScanData *, int, int, int, jlong) { return 0; }
"""
    code = code.replace("/* CLASSIFICATION */",
        extract("SensorGlucoseData.hpp", "bool hasResolvedGlucose()", args.consumer_ref)
        if args.consumer_ref else "")
    def reader(path, signature):
        body = extract(path, signature, args.consumer_ref)
        # Adapt old scalar entry-point arity; their original decisions remain intact.
        if args.consumer_ref and signature in (
                "int resolveExportedMgdlAt(", "static int getNightscoutCalibrationOverrideForItem("):
            brace = body.index("{")
            head, tail = body[:brace].rstrip(), body[brace:]
            body = head[:-1] + ",bool primaryIsResolved=false) " + tail
        return body
    code += extract("g.cpp", "static bool storeGlucoseStreamSample(")
    for signature in [
        "double calibrateValue(const CaliPara &cali ,const ScanData &el)",
        "const CaliPara *getCaliBefore(",
        "double     calibrateNowNoCheck(const SensorGlucoseData *sens,const uint32_t time,",
        "double     calibrateONE(const SensorGlucoseData *sens,const uint32_t time,",
        "double     calibrateNowNoCheck(const SensorGlucoseData *sens,const ScanData &value) {",
        "double     calibrateONE(const SensorGlucoseData *sens,const ScanData &value) {",
        "std::pair<const ScanData*,const ScanData*>      makecalibrated(",
        "std::pair<const ScanData*,const ScanData*>      makecalibratedback(",
    ]:
        code += reader("calibrate/calculate.cpp", signature) + "\n"
    code += reader("net/watchserver/common.cpp", "int resolveExportedMgdlAt(")
    code += reader("net/watchserver/common.cpp", "int resolveExportedMgdl(")
    code += reader("net/watchserver/uploader.cpp", "static int getNightscoutCalibrationOverrideForItem(").replace("nightscoutcalibrationclass", "exportvalueclass")
    code += "\nstatic int uploadSmoothingSeconds=600;\n"
    code += "template <class Getvalue>\n" + extract("net/watchserver/uploader.cpp", "static int smoothPollWindow(")
    code += extract("net/watchserver/uploader.cpp", "static int smoothedUploadMgdl(")
    code += r'''
int main() {
    static_assert(sizeof(ScanData) == 20);
    // Old storage contains automatic glucose, with no representation marker.
    struct LegacyScan { uint32_t t; int32_t id, g, tr; float ch; };
    static_assert(sizeof(LegacyScan) == sizeof(ScanData));
    const uint32_t start = 1760000000;
    LegacyScan old[]{{start, 0, 115, 0, 0}, {start+60, 1, 115, 0, 0}, {start+120, 2, 115, 0, 0}};
    const char *filename = "polls.dat";
    { ofstream file(filename, ios::binary); file.write((char*)old, sizeof(old)); }
    SensorGlucoseData source{"API-123456789ABC"};
    { ifstream file(filename, ios::binary); file.read((char*)source.polls, sizeof(old)); }
    source.info.pollcount = 3;
    assert(!source.polls[0].hasResolvedGlucose());
    // Only the recent page is rewritten. The historical automatic value remains.
    assert(storeGlucoseStreamSample(&source, source.id.c_str(), start+120, 13, 0, 0,
                                   true, true, true, nullptr, true));
    assert(source.polls[2].hasResolvedGlucose());
    { ofstream file(filename, ios::binary); file.write((char*)source.polls, sizeof(old)); }
    memset(source.polls, 0, sizeof(source.polls));
    { ifstream file(filename, ios::binary); file.read((char*)source.polls, sizeof(old)); }
    assert(!source.polls[0].hasResolvedGlucose() && source.polls[0].getmgdL() == 115);
    assert(source.polls[2].hasResolvedGlucose() && source.polls[2].getmgdL() == 130);
    for (const char *id : {"API-123456789ABC", "NSF-23456789ABCD", "MQF-3456789ABCDE", "BLE-stock"}) {
        source.id = id; // IDs never determine a supplied value's representation.
        for (bool nativeEnabled : {false, true}) {
            storage.DoCalibrate = nativeEnabled;
            env.javaOffset = nativeEnabled ? 0 : 15;
            for (int index = 0; index < 3; ++index) {
                env.javaCalls = nativeCalls = 0;
                assert(resolveExportedMgdl(&source, &source.polls[index], source.shortsensorname()) == 130);
                assert(index == 2 ? (env.javaCalls == 0 && nativeCalls == 0) :
                                   (env.javaCalls == 1 && nativeCalls == (nativeEnabled ? 1 : 0)));
            }
        }
        // Same sensor, different representations supplied to calibration.
        assert(calibrateNowNoCheck(&source, source.polls[0]) == 130);
        assert(calibrateONE(&source, source.polls[0]) == 130);
        assert(isnan(calibrateNowNoCheck(&source, source.polls[2])));
        assert(isnan(calibrateONE(&source, source.polls[2])));
        assert(calibrateONE(&source, start, 115) == 130);
        ScanData output[3]{};
        assert(makecalibrated(&source, source.polls, output, 3, true).first == output);
        for (const auto &sample : output) assert(sample.getmgdL() == 130);
        assert(makecalibratedback(&source, source.polls, output, 3, true).first == output);
        for (const auto &sample : output) assert(sample.getmgdL() == 130);
    }
    // Clone transports the same bytes, not a sensor-wide flag.
    SensorGlucoseData receiver{"API-123456789ABC"};
    memcpy(receiver.polls, source.polls, sizeof(source.polls));
    assert(!receiver.polls[0].hasResolvedGlucose() && receiver.polls[2].hasResolvedGlucose());
    env.javaOffset = 15;
    storage.DoCalibrate = false;
    for (const auto &sample : receiver.polls)
        assert(resolveExportedMgdl(&receiver, &sample, receiver.shortsensorname()) == 130);
    assert(smoothedUploadMgdl(span<const ScanData>(source.polls), 0) == 115);
    assert(smoothedUploadMgdl(span<const ScanData>(source.polls), 2) == 0);
    auto smoothed = source.polls[2];
    smoothed.setmgdL(132);
    assert(smoothed.valid(0) && smoothed.hasResolvedGlucose() && smoothed.getmgdL() == 132);
    assert(smoothed.getsputnik() == 1320);
    assert(getNightscoutCalibrationOverrideForItem(&source, "alias", 130, 80, start*1000LL, true) == 130);
    assert(getNightscoutCalibrationOverrideForItem(&source, "alias", 115, 0, start*1000LL, false) == 130);
    // An automatic rewrite explicitly clears the final marker on just that slot.
    assert(storeGlucoseStreamSample(&source, source.id.c_str(), start+120, 11.5, 0, 0,
                                   true, true, true, nullptr, false));
    assert(!source.polls[2].hasResolvedGlucose() && source.polls[2].getmgdL() == 115);
    remove(filename);
    puts("Persisted legacy/final mirror, partial rewrite, Clone bytes, export and calibration checks passed");
}
'''
    with tempfile.TemporaryDirectory(prefix="resolved-glucose-") as directory:
        source = Path(directory) / "test.cpp"
        binary = Path(directory) / "test"
        source.write_text(code)
        subprocess.run(shlex.split(os.environ.get("CXX", "c++")) + [
            "-std=c++20", str(source), "-o", str(binary),
        ], check=True)
        subprocess.run([str(binary)], cwd=directory, check=True)


if __name__ == "__main__":
    main()
