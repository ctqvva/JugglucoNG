#!/usr/bin/env python3
"""Exercise production sender/JNI bodies with transport and native-index stubs."""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-ref', help='Run against a git revision as a negative control')
    args = parser.parse_args()

    def source(path):
        if args.source_ref:
            return subprocess.check_output(['git', 'show', f'{args.source_ref}:{path}'], cwd=ROOT, text=True)
        return (ROOT / path).read_text()

    sender = source('Common/src/main/cpp/sensoren.hpp')
    sender = sender[sender.index('  int sendCalibrates('):sender.index('  int update(', sender.index('  int sendCalibrates('))]
    request = source('Common/src/main/cpp/calibrate/java.cpp')
    request = request[request.index('extern "C" JNIEXPORT void JNICALL fromjava(requestMirrorCalibrationSync)'):]
    code = r'''
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>
#define LOGGER(...) ((void)0)
using senddata_t = unsigned char;
using crypt_t = int;
std::mutex caliMutex;
std::vector<std::string> roomIds;
std::vector<std::string> javaMirrorCalibrationSensorIds() { return roomIds; }
std::string unexportableId;
std::string javaExportCalibrationProfile(const char *id) {
    return id == unexportableId ? std::string{} : std::string("profile:") + id;
}
struct Connect {
    std::vector<std::string> paths;
    std::vector<std::string> pulled;
    bool fail = false;
    int pullMirrorCalibrationProfile(crypt_t *, std::string_view id) {
        pulled.emplace_back(id);
        return 1;
    }
    bool senddata(crypt_t *, int, const senddata_t *, int, const std::string &path) {
        if (fail) return false;
        paths.push_back(path);
        return true;
    }
};
struct SensorGlucoseData {
    bool fail = false;
    int updateCali(crypt_t *, Connect *, int, int) { return fail ? 0 : 2; }
};
struct Sensor {
    std::string id;
    const char *shortsensorname_chars() { return id.c_str(); }
};
struct Sensoren {
    std::vector<Sensor> native;
    SensorGlucoseData data;
    int last() { return static_cast<int>(native.size()) - 1; }
    SensorGlucoseData *getSensorData(int) { return &data; }
    Sensor *getsensor(int index) { return &native.at(index); }
    int sensorindex(const std::string &id) {
        for (int i = 0; i <= last(); ++i) if (id == "FULL-" + native[i].id) return i;
        return -1;
    }
    int sensorindexshort(const std::string &id) {
        for (int i = 0; i <= last(); ++i) if (id == native[i].id) return i;
        return -1;
    }
    void alignMirrorRemoteBase(SensorGlucoseData *, int) {}
'''
    code += sender + r'''
};
#define JNIEXPORT
#define JNICALL
#define fromjava(name) name
using jclass = void *;
using jstring = const char *;
struct JNIEnv {
    const char *GetStringUTFChars(jstring id, void *) { return id; }
    void ReleaseStringUTFChars(jstring, const char *) {}
};
struct Index {
    int found = -1;
    int sensorindexshort(const char *) { return found; }
    int sensorindex(const char *) { return found; }
};
Index *sensors = nullptr;
int wakes = 0, marked = -1;
void setCalibrates(uint16_t index) { marked = index; }
void wakeupall() { ++wakes; }
'''
    code += request + r'''
int main() {
    Sensoren store;
    Connect con;
    uint16_t cursor = 0;
    roomIds = {"AA:BB:CC:DD:EE:FF"};
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) & 1);
    assert(con.paths == std::vector<std::string>{"mirror/calibration/AA:BB:CC:DD:EE:FF.json"});
    assert(con.pulled == std::vector<std::string>{"AA:BB:CC:DD:EE:FF"});
    assert(cursor == 0); // no -1 wrapping when the native index is empty

    store.native = {{"OLD"}, {"LATEST"}};
    con.paths.clear();
    cursor = 1;
    roomIds = {"DELETED", "LATEST"};
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) & 1);
    assert(con.paths == (std::vector<std::string>{"mirror/calibration/DELETED.json", "mirror/calibration/LATEST.json", "mirror/calibration/OLD.json"}));
    assert(cursor == 1);

    con.paths.clear();
    roomIds = {"FULL-LATEST"};
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) & 1);
    assert(con.paths == (std::vector<std::string>{"mirror/calibration/FULL-LATEST.json", "mirror/calibration/OLD.json"}));

    con.paths.clear();
    roomIds.clear();
    cursor = 0;
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) & 1);
    assert(con.paths == (std::vector<std::string>{"mirror/calibration/OLD.json", "mirror/calibration/LATEST.json"}));
    assert(cursor == 1); // legacy native profiles remain supported

    con.fail = true;
    cursor = 0;
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) == 0);
    assert(cursor == 0); // retries must still include earlier native profiles
    con.fail = false;
    con.paths.clear();
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) & 1);
    assert(con.paths.size() == 2);

    con.paths.clear();
    con.pulled.clear();
    roomIds.clear();
    cursor = 1;
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) & 1);
    assert(con.pulled == (std::vector<std::string>{"OLD", "LATEST"}));

    con.paths.clear();
    con.pulled.clear();
    unexportableId = "OLD";
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) & 1);
    assert(con.paths == std::vector<std::string>{"mirror/calibration/LATEST.json"});
    assert(con.pulled == (std::vector<std::string>{"OLD", "LATEST"}));

    con.paths.clear();
    store.native.clear();
    roomIds = {"../escape", "nested/id", "..", "bad\\id"};
    cursor = 9;
    assert(store.sendCalibrates(nullptr, &con, 0, cursor) == 2);
    assert(con.paths.empty());

    JNIEnv env;
    requestMirrorCalibrationSync(&env, nullptr, "ROOM-ONLY");
    assert(wakes == 1 && marked == -1);
    Index index;
    sensors = &index;
    requestMirrorCalibrationSync(&env, nullptr, "ROOM-ONLY");
    assert(wakes == 2 && marked == -1);
    index.found = 3;
    requestMirrorCalibrationSync(&env, nullptr, "NATIVE");
    assert(wakes == 3 && marked == 3);
    requestMirrorCalibrationSync(&env, nullptr, nullptr);
    assert(wakes == 3);
}
'''
    with tempfile.TemporaryDirectory(prefix='mirror-calibration-test-') as tmp:
        path = Path(tmp)
        (path / 'test.cpp').write_text(code)
        subprocess.run(['c++', '-std=c++20', str(path / 'test.cpp'), '-o', str(path / 'test')], check=True)
        subprocess.run([str(path / 'test')], check=True)
    print('Mirror calibration native transfer tests passed')


if __name__ == '__main__':
    main()
