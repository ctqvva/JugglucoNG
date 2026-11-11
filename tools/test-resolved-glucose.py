#!/usr/bin/env python3
"""Exercise production native mirror classification, calibration and export bodies.

Uses the production on-disk ScanData layout, writer and reader bodies. JNI and
calibration arithmetic are stand-ins. Includes a legacy file reopened after a
partial page rewrite and a byte-for-byte Clone-style copy.
Owner backfill executes the production JNI batch body, preserves local samples
and races with live writes using the production poll-write lock.
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
#include <thread>
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
using jint = int;
using jsize = int;
using jlongArray = vector<jlong>*;
using jfloatArray = vector<float>*;
#define JNI_ABORT 2
using sensorname_t = array<char, 11>;
struct CaliPara { uint32_t time = 1; };
constexpr float convfactordL = 180.182f;
constexpr int maxminutes = 100000;
float gconvert(int value) { return value; }
/* SCANDATA */;
struct Info { uint32_t caliNr = 1, viewMode = 0, starttime = 1760000000, pollcount = 0, pollstart = 0; uint16_t nightiter = 0; CaliPara caliPara[1]; };
struct SensorGlucoseData {
    static recursive_mutex &pollWriteMutex() { static recursive_mutex lock; return lock; }
    // Whether the calling thread holds the poll write lock: it is a recursive mutex,
    // so only another thread's failed try_lock can tell.
    static bool lockHeld() {
        bool taken = false;
        thread([&] { taken = pollWriteMutex().try_lock(); if (taken) pollWriteMutex().unlock(); }).join();
        return !taken;
    }
    string id;
    Info info;
    // While watching, every read of the poll state counts as made with or without the lock.
    mutable bool watching = false;
    mutable int readsHeld = 0, readsUnheld = 0;
    void noteRead() const {
        if (watching) ++(lockHeld() ? readsHeld : readsUnheld);
    }
    string_view sensid() const { return id; }
    const Info *getinfo() const { return &info; }
    bool error() const { return false; }
    uint32_t getstarttime() const { return info.starttime; }
    sensorname_t alias{};
    const sensorname_t *shortsensorname() const { return &alias; }
    ScanData polls[12]{};
    ScanData *getPollsData() { noteRead(); return polls; }
    const ScanData *getPollsData() const { noteRead(); return polls; }
    struct RawData { uint16_t raw; } rawpolls[12]{};
    uint16_t temppolls[12]{};
    const RawData *getRawPollsData() const { noteRead(); return rawpolls; }
    int getTempForPoll(int) const { noteRead(); return 0; }
    bool validPollIndex(int i) const { return i >= 0 && i < 12; }
    bool dex = false;
    int dexSlot = -1;
    bool isDexcom() const { return dex; }
    int dexSlotFor(uint32_t) const { noteRead(); return dexSlot; }
    bool hasStreamID(int i) const { noteRead(); return polls[i].getmgdL() > 0; }
    int getRawForPoll(const ScanData *) const { return 0; }
    size_t pollStorageCapacity() const { return 12; }
    int pollcount() const { noteRead(); return info.pollcount; }
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
    template<class T> int GetArrayLength(vector<T> *array) { return array->size(); }
    jlong *GetLongArrayElements(jlongArray array, void *) { return array->data(); }
    float *GetFloatArrayElements(jfloatArray array, void *) { return array->data(); }
    void ReleaseLongArrayElements(jlongArray, jlong *, int) {}
    void ReleaseFloatArrayElements(jfloatArray, float *, int) {}
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
SensorGlucoseData *batchSensor = nullptr;
struct Sensors {
    SensorGlucoseData *getSensorData(const char *id) {
        return batchSensor && batchSensor->id == id ? batchSensor : nullptr;
    }
    SensorGlucoseData *gethistshort(const char *id) {
        return string_view(id) == "legacy-alias" ? batchSensor : nullptr;
    }
} sensorStore;
Sensors *sensors = &sensorStore;
int shellEnsures = 0, seeds = 0, streamings = 0;
SensorGlucoseData *ensureDirectStreamShellForId(const char *, uint32_t) {
    ++shellEnsures;
    return batchSensor;
}
void seedDirectStreamStateIfMissing(SensorGlucoseData *, time_t) { ++seeds; }
void setstreaming(SensorGlucoseData *) { ++streamings; }
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
    code += extract("g.cpp", "static jint addGlucoseStreamBatchWithRawTempInternal(")
    code += extract("g.cpp", 'extern "C" JNIEXPORT jint JNICALL fromjava(backfillGlucoseStreamBatchWithRawTemp)(')
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
        SensorGlucoseData managed{"SIBI:0111801JGS9"};
        batchSensor = &managed;
        const uint32_t start = managed.getstarttime();
        // The watch already received its own live reading in slot 8. A phone
        // chunk overlaps it, fills older gaps and carries an out-of-window row.
        assert(storeGlucoseStreamSample(&managed, managed.id.c_str(), start + 8*60,
                                        14.5f, 150, 36, true, true, true, nullptr));
        managed.rawpolls[8].raw = 83;
        managed.temppolls[8] = 360;
        const ScanData local = managed.polls[8];
        vector<jlong> times{start - 60, start + 2*60, start + 8*60, start + 13*60};
        vector<float> values{9, 10, 20, 21}, raws{90, 100, 200, 210}, temps{0, 0, 0, 0};
        // The reader must not observe a stale slot check outside the live lock.
        managed.watching = true;
        assert(backfillGlucoseStreamBatchWithRawTemp(&env, nullptr, &times, &values,
                                                     &raws, &temps, managed.id.c_str()) == 1);
        managed.watching = false;
        assert(managed.readsHeld > 0 && managed.readsUnheld == 0);
        assert(managed.polls[2].getmgdL() == 100);
        assert(!memcmp(&local, &managed.polls[8], sizeof(local)));
        assert(managed.rawpolls[8].raw == 83 && managed.temppolls[8] == 360);
        assert(managed.getstarttime() == start && managed.pollcount() == 9);
        assert(managed.polls[0].getmgdL() == 0);
        assert(shellEnsures == 0 && seeds == 0 && streamings == 0);
        // Replay, including a native alias, is a no-op.
        assert(backfillGlucoseStreamBatchWithRawTemp(&env, nullptr, &times, &values,
                                                     &raws, &temps, "legacy-alias") == 0);
        assert(backfillGlucoseStreamBatchWithRawTemp(&env, nullptr, &times, &values,
                                                     &raws, &temps, "unknown") == 0);
        assert(shellEnsures == 0);
        // A subsequent local sample/algorithm rebuild can replace a filled gap.
        assert(storeGlucoseStreamSample(&managed, managed.id.c_str(), start + 2*60,
                                        12.3f, 120, 35, true, true, true, nullptr));
        assert(managed.polls[2].getmgdL() == 123);
        assert(backfillGlucoseStreamBatchWithRawTemp(&env, nullptr, &times, &values,
                                                     &raws, &temps, managed.id.c_str()) == 0);
        assert(managed.polls[2].getmgdL() == 123);
        // Whichever thread gets the slot first, the local driver wins. Both
        // use the production writer and the same recursive poll-write lock.
        vector<jlong> racingTimes{start + 3*60};
        vector<float> racingValues{10}, racingRaws{100}, racingTemps{0};
        for (int attempt = 0; attempt < 100; ++attempt) {
            managed.polls[3] = {};
            thread peer([&] {
                backfillGlucoseStreamBatchWithRawTemp(&env, nullptr, &racingTimes,
                    &racingValues, &racingRaws, &racingTemps, managed.id.c_str());
            });
            thread live([&] {
                assert(storeGlucoseStreamSample(&managed, managed.id.c_str(), start + 3*60,
                                               20.f, 200, 36, true, true, true, nullptr));
            });
            peer.join(); live.join();
            assert(managed.polls[3].getmgdL() == 200);
        }
        managed.info.starttime = 0;
        assert(backfillGlucoseStreamBatchWithRawTemp(&env, nullptr, &times, &values,
                                                     &raws, &temps, managed.id.c_str()) == 0);
        assert(managed.getstarttime() == 0);
        batchSensor = nullptr;
    }
    {
        // Dexcom: the writer stores at the slot the store picks, on the
        // 5-minute grid, and never replaces a slot the sensor already filled.
        const uint32_t t0 = 1760000000;
        SensorGlucoseData dexcom{"DEX-123456789ABC"};
        dexcom.dex = true;
        dexcom.polls[0] = {t0, 0, 100, 0, 0};
        dexcom.polls[1] = {t0 + 300, 1, 105, 0, 0};
        dexcom.info.pollcount = 2;
        // The writer reads the store (slot, what the slot holds, the end) and then
        // writes it. All of that has to happen under the poll write lock, or a slot
        // repair on another instance runs between the read and the write.
        const auto store = [&](uint32_t t, float glucose) {
            dexcom.watching = true;
            dexcom.readsHeld = dexcom.readsUnheld = 0;
            const bool stored = storeGlucoseStreamSample(&dexcom, dexcom.id.c_str(), t, glucose, 0, 0,
                                                         true, true, true, nullptr, true);
            dexcom.watching = false;
            assert(dexcom.readsHeld >= 3 && dexcom.readsUnheld == 0);
            assert(!SensorGlucoseData::lockHeld());
            return stored;
        };
        dexcom.dexSlot = 1;
        assert(!store(t0 + 420, 13));
        assert(dexcom.polls[1].getmgdL() == 105 && dexcom.pollcount() == 2);
        dexcom.dexSlot = 4;
        assert(store(t0 + 1210, 13));
        assert(dexcom.pollcount() == 5);
        assert(dexcom.polls[4].id == 4 && dexcom.polls[4].t == t0 + 1210 && dexcom.polls[4].getmgdL() == 130);
        assert(dexcom.polls[3].id == 3 && !dexcom.polls[3].g && dexcom.polls[3].t == t0 + 910);
        assert(dexcom.polls[2].id == 2 && !dexcom.polls[2].g && dexcom.polls[2].t == t0 + 610);
        dexcom.dexSlot = 3; // a missed slot below the end is filled
        assert(store(t0 + 905, 12));
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
    puts("Owner backfill, persisted mirror, representation-safe trend, final V1/V3 export and calibration checks passed");
}
'''
    with tempfile.TemporaryDirectory(prefix="resolved-glucose-") as directory:
        source = Path(directory) / "test.cpp"
        binary = Path(directory) / "test"
        source.write_text(code)
        subprocess.run(shlex.split(os.environ.get("CXX", "c++")) + [
            "-std=c++20", "-pthread", str(source), "-o", str(binary),
        ], check=True)
        subprocess.run([str(binary)], cwd=directory, check=True)


if __name__ == "__main__":
    main()
