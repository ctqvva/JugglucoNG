#!/usr/bin/env python3
"""Run the production calibration pull/response code over an in-memory wire."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def between(path, start, end):
    text = (ROOT / path).read_text()
    offset = text.index(start)
    return text[offset:text.index(end, offset)]


def main():
    sender = between('Common/src/main/cpp/net/sendcommands.cpp',
                     'int Connect::pullMirrorCalibrationProfile(', 'bool Connect::sendBlueWatch(')
    receiver = between('Common/src/main/cpp/net/getcommand.cpp',
                       'static bool sendMirrorCalibrationProfile(', 'static std::string collectFileOncePayload(')
    code = r'''
#include <cassert>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>
#include "net/MirrorCalibrationExchange.hpp"
using senddata_t = uint8_t;
using crypt_t = int;
constexpr uint16_t saskfile = 15;
struct askfile { uint16_t com, namelen; uint32_t off, len; char name[]; };
struct dataonly { int32_t len; uint8_t data[]; };
template<int alignment> int aligner(int n) { return (n + alignment - 1) & ~(alignment - 1); }
template<int alignment, class T> struct ardeleter {
    void operator()(T *p) const { ::operator delete[](p, std::align_val_t(alignment)); }
};
struct FrameDelete { void operator()(dataonly *p) const { ::operator delete(p); } };
using dataonlyptr = std::unique_ptr<dataonly, FrameDelete>;
std::string receiverProfile, replacement, imported, importedId;
std::string javaExportCalibrationProfile(const char *) { return receiverProfile; }
void javaImportMirrorCalibrationProfile(const char *id, const char *json) {
    importedId = id; imported = json;
}
struct Connect {
    std::vector<uint8_t> wire;
    int requests = 0;
    bool oldPeer = false, failWrite = false, failRead = false;
    bool retireDuringRead = false, reconnectDuringRead = false;
    std::atomic_bool receiverCommandsEnabled{true};
    std::mutex receiverCommandsMutex;
    uint64_t generation = 1;
    uint64_t senderConnectionGeneration() const { return generation; }
    bool isConnectedSender() const { return true; }
    int pullMirrorCalibrationProfile(crypt_t *, std::string_view);
    bool s_noacksendcommand(crypt_t *, const uint8_t *, int);
    bool r_noacksendcommand(crypt_t *, const uint8_t *frame, int bytes) {
        wire.assign(frame, frame + bytes); return true;
    }
    dataonlyptr receivedataonly_s(crypt_t *, int length) {
        if (failRead) return dataonlyptr(nullptr);
        if (length > 0 && retireDuringRead) receiverCommandsEnabled.store(false);
        if (length > 0 && reconnectDuringRead) ++generation;
        assert(wire.size() == static_cast<size_t>(aligner<4>(sizeof(dataonly) + length)));
        auto *frame = static_cast<dataonly *>(::operator new(wire.size()));
        memcpy(frame, wire.data(), wire.size());
        return dataonlyptr(frame);
    }
};
'''
    code += receiver + sender + r'''
bool Connect::s_noacksendcommand(crypt_t *pass, const uint8_t *command, int bytes) {
    if (failWrite) return false;
    ++requests;
    auto *ask = reinterpret_cast<const askfile *>(command);
    assert(ask->com == saskfile && ask->off == 0);
    assert(bytes == aligner<4>(sizeof(askfile) + ask->namelen));
    assert(ask->name[ask->namelen - 1] == 0);
    if (oldPeer) {
        wire.assign(aligner<4>(sizeof(dataonly) + ask->len), 0);
        return true;
    }
    if (requests == 2 && !replacement.empty()) receiverProfile = replacement;
    return sendMirrorCalibrationProfile(this, pass, ask->name, ask->off, ask->len);
}
int main() {
    const std::string id = "SIBI:0683013AQT9";
    const std::string path = "mirror/calibration/" + id + ".json";
    receiverProfile = R"({"revision":20,"calibrations":[{"userValue":7}]})";
    Connect con;
    assert(con.pullMirrorCalibrationProfile(nullptr, id) == 1);
    assert(con.requests == 2 && importedId == id && imported == receiverProfile);

    imported.clear();
    Connect old; old.oldPeer = true;
    assert(old.pullMirrorCalibrationProfile(nullptr, id) == 2);
    assert(old.requests == 1 && imported.empty());

    Connect failed; failed.failWrite = true;
    assert(failed.pullMirrorCalibrationProfile(nullptr, id) == 0);
    failed.failWrite = false; failed.failRead = true;
    assert(failed.pullMirrorCalibrationProfile(nullptr, id) == 0);
    failed.failRead = false;
    assert(failed.pullMirrorCalibrationProfile(nullptr, id) == 1);

    imported.clear();
    Connect retired; retired.retireDuringRead = true;
    assert(retired.pullMirrorCalibrationProfile(nullptr, id) == 2);
    assert(imported.empty());
    Connect reconnected; reconnected.reconnectDuringRead = true;
    assert(reconnected.pullMirrorCalibrationProfile(nullptr, id) == 2);
    assert(imported.empty());

    imported.clear();
    receiverProfile = R"({"revision":20,"calibrations":[]})";
    replacement = R"({"revision":21,"calibrations":[{"userValue":8}]})";
    Connect grows;
    assert(grows.pullMirrorCalibrationProfile(nullptr, id) == 2);
    assert(imported.empty()); // never import a truncated, concurrently edited profile
    replacement.clear();
    assert(grows.pullMirrorCalibrationProfile(nullptr, id) == 1);
    assert(imported == receiverProfile);

    imported.clear();
    receiverProfile = std::string(mirrorCalibrationMaximumBytes + 1, 'x');
    Connect huge;
    assert(huge.pullMirrorCalibrationProfile(nullptr, id) == 2);
    assert(huge.requests == 1 && imported.empty());
    Connect invalid;
    assert(invalid.pullMirrorCalibrationProfile(nullptr, "../other") == 2);
    assert(invalid.requests == 0);
    assert(!sendMirrorCalibrationProfile(&invalid, nullptr, path, 1, 0));
    assert(!sendMirrorCalibrationProfile(&invalid, nullptr, path, 0, mirrorCalibrationMaximumBytes + 1));

    assert(isMirrorCalibrationProfilePath(path));
    assert(!isMirrorCalibrationProfilePath("mirror/calibration/.json"));
    assert(!isMirrorCalibrationProfilePath("mirror/calibration/../outside.json"));
    assert(!isMirrorCalibrationProfilePath("mirror/calibration/..json"));
    assert(mirrorCalibrationResponseLength(100, 0, 0) == 100);
    assert(mirrorCalibrationResponseLength(100, 0, 99) == -1);
    assert(mirrorCalibrationResponseLength(100, 0, 120) == 100);
}
'''
    with tempfile.TemporaryDirectory(prefix='mirror-calibration-return-') as tmp:
        path = Path(tmp)
        (path / 'test.cpp').write_text(code)
        subprocess.run(['c++', '-std=c++20', '-I', str(ROOT / 'Common/src/main/cpp'),
                        str(path / 'test.cpp'), '-o', str(path / 'test')], check=True)
        subprocess.run([str(path / 'test')], check=True)
    print('Mirror calibration receiver-to-master protocol tests passed')


if __name__ == '__main__':
    main()
