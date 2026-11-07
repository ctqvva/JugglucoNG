#!/usr/bin/env python3
"""Exercise production native mirror classification, calibration and export bodies.

Uses the production on-disk ScanData layout, writer and reader bodies. JNI and
calibration arithmetic are stand-ins. Includes a legacy file reopened after a
partial page rewrite and a byte-for-byte Clone-style copy.
"""
import argparse
import os
from pathlib import Path
import re
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
    # Upload functions contain braces in JSON/raw strings and comments.
    masked = re.sub(
        r'R"([^ ()\\\t\r\n]{0,16})\(.*?\)\1"|"(?:\\.|[^"\\])*"|'
        r"'(?:\\.|[^'\\])*'|//[^\n]*|/\*.*?\*/",
        lambda match: " " * len(match.group()), source, flags=re.DOTALL,
    )
    start = source.index(signature)
    end = masked.index("{", start) + 1
    depth = 1
    while depth:
        depth += (masked[end] == "{") - (masked[end] == "}")
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-ref")
    parser.add_argument("--trend-ref", help="Negative control for native rate derivation")
    parser.add_argument("--smoothing-ref", help="Negative control for resolved upload smoothing")
    args = parser.parse_args()
    code = r'''
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <climits>
#include <charconv>
#include <span>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <mutex>
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
    static recursive_mutex &pollWriteMutex() { static recursive_mutex lock; return lock; }
    string id;
    Info info;
    string_view sensid() const { return id; }
    const Info *getinfo() const { return &info; }
    bool error() const { return false; }
    sensorname_t alias{};
    const sensorname_t *shortsensorname() const { return &alias; }
    ScanData polls[12]{};
    ScanData *getPollsData() { return polls; }
    const ScanData *getPollsData() const { return polls; }
    struct RawData { uint16_t raw; } rawpolls[12]{};
    uint16_t temppolls[12]{};
    const RawData *getRawPollsData() const { return rawpolls; }
    int getTempForPoll(int) const { return 0; }
    bool validPollIndex(int i) const { return i >= 0 && i < 12; }
    bool dex = false;
    int dexSlot = -1;
    bool isDexcom() const { return dex; }
    int dexSlotFor(uint32_t) const { return dexSlot; }
    bool hasStreamID(int i) const { return polls[i].getmgdL() > 0; }
    int getRawForPoll(const ScanData *) const { return 0; }
    size_t pollStorageCapacity() const { return 12; }
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
"""
    trend = (CPP / "exchangetrend.hpp").read_text() if not args.trend_ref else subprocess.check_output(
        ["git", "show", f"{args.trend_ref}:Common/src/main/cpp/exchangetrend.hpp"], cwd=ROOT, text=True,
    )
    trend = trend[trend.index("static constexpr const uint32_t minderivegapsecs"):]
    if args.trend_ref:
        # Keep an older helper callable from the new ingest path. Its original
        # predecessor selection still runs, ignoring the supplied representation.
        trend = trend.replace("const int curmgdl, const uint32_t curtime) {\n  if (!polls",
                              "const int curmgdl, const uint32_t curtime, bool resolved = false) {\n  if (!polls")
    code += trend
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
        "double     calibrateONEtest(",
        "std::pair<const ScanData*,const ScanData*>      makecalibrated(",
        "std::pair<const ScanData*,const ScanData*>      makecalibratedback(",
    ]:
        code += reader("calibrate/calculate.cpp", signature) + "\n"
    code += reader("net/watchserver/common.cpp", "int resolveExportedMgdlAt(")
    code += reader("net/watchserver/common.cpp", "int resolveExportedMgdl(")
    code += reader("net/watchserver/uploader.cpp", "static int getNightscoutCalibrationOverrideForItem(").replace("nightscoutcalibrationclass", "exportvalueclass")
    code += "\nstatic int uploadSmoothingSeconds=600;\n"
    code += "template <class Getvalue>\n" + extract("net/watchserver/uploader.cpp", "static int smoothPollWindow(")
    code += extract("net/watchserver/uploader.cpp", "static int smoothedUploadMgdl(", args.smoothing_ref)
    code += extract("net/watchserver/uploader.cpp", "static int smoothedUploadRaw(")
    code += "\ntemplate <class T, size_t N>\n" + extract("net/watchserver/common.hpp", "inline static constexpr void addar(")
    for signature in ["inline double getdelta(", "inline std::string_view fixedsensorview(",
                      "inline void addjsonuint(", "inline void addjsonint(",
                      "inline void addjsonpadded2(", "inline void addjsonpadded3(",
                      "inline void addjsonfixed3(", "inline int addNightscoutDateString(",
                      "inline void addjsonescaped("]:
        code += extract("net/watchserver/common.hpp", signature)
    code += extract("net/watchserver/common.cpp", "int mksgvidentifier(")
    code += '\n#define DOUBLETHRESHOLD 3.0f\n#define NOT_DETERMINED ""\n'
    for signature in ["int getdeltaindex(float rate) {", "std::string_view getdeltanamefromindex(int index) {",
                      "std::string_view getdeltaname(float rate) {"]:
        code += extract("glucose.cpp", signature)
    code += "\ntemplate <class T>\n" + extract("net/watchserver/uploader.cpp", "int mkuploaditem(")
    code += r'''
ScanData serializedV3;
char *writev3entry(char *out, const ScanData *value, const sensorname_t *, bool,
                   const std::string_view &) {
    serializedV3 = *value;
    return out;
}
'''
    code += extract("net/watchserver/uploader.cpp", "static const char *writeNightscoutV3UploadEntry(")
    code += r'''
int main() {
    static_assert(sizeof(ScanData) == 20);
    {
        // Dexcom: the writer stores at the slot the store picks, on the
        // 5-minute grid, and never replaces a slot the sensor already filled.
        const uint32_t t0 = 1760000000;
        SensorGlucoseData dexcom{"DEX-123456789ABC"};
        dexcom.dex = true;
        dexcom.polls[0] = {t0, 0, 100, 0, 0};
        dexcom.polls[1] = {t0 + 300, 1, 105, 0, 0};
        dexcom.info.pollcount = 2;
        dexcom.dexSlot = 1;
        assert(!storeGlucoseStreamSample(&dexcom, dexcom.id.c_str(), t0 + 420, 13, 0, 0,
                                        true, true, true, nullptr, true));
        assert(dexcom.polls[1].getmgdL() == 105 && dexcom.pollcount() == 2);
        dexcom.dexSlot = 4;
        assert(storeGlucoseStreamSample(&dexcom, dexcom.id.c_str(), t0 + 1210, 13, 0, 0,
                                       true, true, true, nullptr, true));
        assert(dexcom.pollcount() == 5);
        assert(dexcom.polls[4].id == 4 && dexcom.polls[4].t == t0 + 1210 && dexcom.polls[4].getmgdL() == 130);
        assert(dexcom.polls[3].id == 3 && !dexcom.polls[3].g && dexcom.polls[3].t == t0 + 910);
        assert(dexcom.polls[2].id == 2 && !dexcom.polls[2].g && dexcom.polls[2].t == t0 + 610);
        dexcom.dexSlot = 3; // a missed slot below the end is filled
        assert(storeGlucoseStreamSample(&dexcom, dexcom.id.c_str(), t0 + 905, 12, 0, 0,
                                       true, true, true, nullptr, true));
        assert(dexcom.polls[3].getmgdL() == 120 && dexcom.pollcount() == 5);
    }
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
    for (const auto &sample : span<const ScanData>(receiver.polls, 3))
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

    // A partial API page changes representation, not glucose. Never save +3/min
    // by comparing final 130 to the legacy automatic 115 five minutes earlier.
    SensorGlucoseData rateSource{"API-rate"};
    rateSource.info.pollcount = 1;
    rateSource.polls[0] = {start, 0, 115, 0, NAN};
    assert(storeGlucoseStreamSample(&rateSource, rateSource.id.c_str(), start+300, 13, 0, 0,
                                   true, true, true, nullptr, true));
    assert(isnan(rateSource.polls[5].getchange()));
    auto rateData = span<const ScanData>(rateSource.polls, 12);
    assert(isnan(derivechangefrompolls(rateData, 5)));
    assert(isnan(effectivechange(rateData, 5, rateSource.polls[5])));
    char payload[2048]{};
    auto size = mkuploaditem(&rateSource, payload, rateSource.shortsensorname(), rateSource.polls[5], rateData, 5, false);
    string firstPayload(payload, size);
    assert(firstPayload.find("\"direction\":\"\"") != string::npos);
    assert(firstPayload.find("\"delta\":0.000") != string::npos);
    // Once a final predecessor is old enough, a stable final feed is flat.
    assert(storeGlucoseStreamSample(&rateSource, rateSource.id.c_str(), start+600, 13, 0, 0,
                                   true, true, true, nullptr, true));
    assert(rateSource.polls[10].getchange() == 0);
    assert(derivechangefrompolls(rateData, 10) == 0);
    // The same rule applies when stock values arrive after resolved values.
    assert(storeGlucoseStreamSample(&rateSource, rateSource.id.c_str(), start+660, 11.5, 0, 0,
                                   true, true, true, nullptr, false));
    assert(rateSource.polls[11].getchange() == 0);
    SensorGlucoseData stockSource{"BLE-stock-rate"};
    stockSource.info.pollcount = 1;
    stockSource.polls[0] = {start, 0, 115, 0, NAN};
    assert(storeGlucoseStreamSample(&stockSource, stockSource.id.c_str(), start+300, 13, 0, 0,
                                   true, true, true, nullptr, false));
    assert(stockSource.polls[5].getchange() == 3);
    // A nearer point of the wrong representation cannot hide a matching one.
    rateSource.polls[0].g = 130 | ScanData::resolvedGlucoseFlag;
    rateSource.polls[5].g = 115;
    rateSource.polls[10].ch = NAN;
    assert(derivechangefrompolls(rateData, 10) == 0);

    // Supplied final glucose must stay 130 even when its 100/130/100
    // neighbourhood would smooth to 110. Exercise both production upload paths.
    SensorGlucoseData uploadSource{"API-upload"};
    for (int i = 0; i < 3; ++i)
        uploadSource.polls[i] = {start + uint32_t(i)*300, i,
            (i == 1 ? 130 : 100) | ScanData::resolvedGlucoseFlag, 0, NAN};
    auto uploadData = span<const ScanData>(uploadSource.polls, 3);
    assert(smoothedUploadMgdl(uploadData, 1) == 0);
    for (bool nativeEnabled : {false, true}) {
        storage.DoCalibrate = nativeEnabled;
        env.javaCalls = nativeCalls = 0;
        size = mkuploaditem(&uploadSource, payload, uploadSource.shortsensorname(), uploadSource.polls[1], uploadData, 1, false);
        assert(string(payload, size).find("\"sgv\":130,") != string::npos);
        writeNightscoutV3UploadEntry(payload, &uploadSource, uploadSource.shortsensorname(), &uploadSource.polls[1], uploadData, 1);
        assert(serializedV3.getmgdL() == 130);
        assert(env.javaCalls == 0 && nativeCalls == 0);
    }
    // Stock readings still use the configured exchange smoother.
    for (auto &sample : uploadSource.polls) sample.g &= 0xffff;
    assert(smoothedUploadMgdl(uploadData, 1) == 110);
    for (bool nativeEnabled : {false, true}) {
        storage.DoCalibrate = nativeEnabled;
        env.javaOffset = nativeEnabled ? 0 : 15;
        size = mkuploaditem(&uploadSource, payload, uploadSource.shortsensorname(), uploadSource.polls[1], uploadData, 1, false);
        assert(string(payload, size).find("\"sgv\":125,") != string::npos);
        writeNightscoutV3UploadEntry(payload, &uploadSource, uploadSource.shortsensorname(), &uploadSource.polls[1], uploadData, 1);
        assert(serializedV3.getmgdL() == 125);
    }
    remove(filename);
    puts("Persisted mirror, representation-safe trend, final V1/V3 export and calibration checks passed");
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
