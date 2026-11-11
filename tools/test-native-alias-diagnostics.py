#!/usr/bin/env python3
"""Execute production registry lookup and verify the read-only poll-file layout."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'Common/src/main/cpp'


def extract(file, signature):
    source = (CPP / file).read_text()
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


code = r'''
#include <algorithm>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>
using namespace std;
float gconvert(int value) { return value; }
constexpr float convfactordL = 180.182f;
constexpr int maxminutes = 46 * 24 * 60;
/* SCANDATA */;
/* RAWDATA */;
struct sensor {
    char name[17]{};
    bool finished = false;
    sensor(const char *id, bool retired = false): finished(retired) { strncpy(name, id, 16); }
    static constexpr int sensornamelen = 16;
    /* NAME_METHODS */
};
struct Sensoren {
    vector<sensor> records;
    int last() const { return records.size() - 1; }
    const sensor *sensorlist() const { return records.data(); }
    const sensor *getsensor(int index) const { return &records[index]; }
    /* INDICES */
    // Deliberately no getSensorData/ensure method: a diagnostic must not open a store.
} storage;
Sensoren *sensors = &storage;
using jstring = const char *;
using jclass = void *;
using jobjectArray = const vector<string> *;
struct JNIEnv {
    const char *GetStringUTFChars(jstring value, void *) { return value; }
    void ReleaseStringUTFChars(jstring, const char *) {}
} env;
vector<string> output;
jobjectArray sensorNamesToJavaArray(JNIEnv *, const vector<string> &names) { output = names; return &output; }
#define JNIEXPORT
#define JNICALL
#define fromjava(name) name
/* LOOKUP */
int main() {
    static_assert(sizeof(ScanData) == 20 && sizeof(RawData) == 2);
    static_assert(offsetof(ScanData, t) == 0 && offsetof(ScanData, id) == 4);
    static_assert(offsetof(ScanData, g) == 8 && offsetof(ScanData, tr) == 12 && offsetof(ScanData, ch) == 16);
    static_assert(ScanData::resolvedGlucoseFlag == 0x10000);
    storage.records.emplace_back("240600111801JGS9", true); // legacy directory survives retirement
    assert(getNativeHistoryRecordNames(&env, nullptr, "SIBI:0111801JGS9")->empty());
    storage.records.emplace_back("SIBI:0111801JGS9");
    auto names = getNativeHistoryRecordNames(&env, nullptr, "SIBI:0111801JGS9");
    assert(*names == vector<string>{"SIBI:0111801JGS9"});
    names = getNativeHistoryRecordNames(&env, nullptr, "0111801JGS9");
    assert((*names == vector<string>{"240600111801JGS9", "SIBI:0111801JGS9"}));
    names = getNativeHistoryRecordNames(&env, nullptr, "240600111801JGS9");
    assert(*names == vector<string>{"240600111801JGS9"});
    assert(getNativeHistoryRecordNames(&env, nullptr, "unknown")->empty());
    assert(getNativeHistoryRecordNames(&env, nullptr, nullptr) == nullptr);
    assert(storage.records[0].finished); // no reactivation
    puts("PASS: retired/ambiguous aliases enumerate distinct physical records without opening history; poll layout verified");
}
'''
code = code.replace('/* SCANDATA */', extract('SensorGlucoseData.hpp', 'struct ScanData {'))
code = code.replace('/* RAWDATA */', extract('SensorGlucoseData.hpp', 'struct RawData {'))
code = code.replace('/* NAME_METHODS */', '\n'.join(extract('sensoren.hpp', s) for s in [
    'size_t fulllen() const', 'std::string_view fullname() const', 'std::string_view shortsensorname_view() const',
]))
code = code.replace('/* INDICES */', extract('sensoren.hpp', 'vector<int> sensorindices('))
code = code.replace('/* LOOKUP */', extract('g.cpp', 'extern "C" JNIEXPORT jobjectArray JNICALL fromjava(getNativeHistoryRecordNames)('))
with tempfile.TemporaryDirectory(prefix='native-alias-diagnostic-') as temporary:
    source = Path(temporary) / 'test.cpp'
    binary = Path(temporary) / 'test'
    source.write_text(code)
    subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + [
        '-std=c++20', str(source), '-o', str(binary),
    ], check=True)
    subprocess.run([str(binary)], check=True)
