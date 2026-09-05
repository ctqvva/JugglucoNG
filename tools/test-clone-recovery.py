#!/usr/bin/env python3
"""Compile and exercise the production native recovery frame/path validators."""
import os
from pathlib import Path
import subprocess
import tempfile


def between(source, start, end):
    return source[source.index(start):source.index(end, source.index(start))]


def main():
    root = Path(__file__).resolve().parents[1]
    sender = (root / 'Common/src/main/cpp/datbackup.cpp').read_text()
    receiver = (root / 'Common/src/main/cpp/net/getcommand.cpp').read_text()
    code = '#include <algorithm>\n#include <cassert>\n#include <cstdint>\n#include <limits>\n#include <string>\n#include <string_view>\n#include <vector>\n#include <iostream>\n'
    code += 'namespace sender {\n'
    code += between(sender, 'constexpr uint8_t cloneRecoveryActionVersion', 'struct CloneRecoveryHostBinding')
    code += between(sender, 'uint32_t readUint32LittleEndian', 'bool validCloneRecoveryIceLabel')
    code += '}\nnamespace receiver {\n'
    code += between(receiver, 'static constexpr std::string_view cloneRecoveryPrefix', 'struct ValidatedFileOnce')
    code += between(receiver, 'static bool isCloneRecoveryNamespace', 'static bool isAuthenticatedCloneRecovery')
    code += between(receiver, 'static bool validateCloneRecoveryRead', 'static bool validateCloneRecoveryWrite')
    code += '}\n'
    code += r'''
std::vector<uint8_t> frame(uint8_t kind, uint64_t offset, const std::string &path, std::vector<uint8_t> payload) {
    std::vector<uint8_t> raw = {1, kind, 0, 0};
    sender::appendUint64LittleEndian(raw, offset);
    sender::appendUint32LittleEndian(raw, path.size());
    sender::appendUint32LittleEndian(raw, payload.size());
    raw.insert(raw.end(),path.begin(),path.end()); raw.insert(raw.end(),payload.begin(),payload.end());
    return raw;
}
int main() {
    const std::string prefix = "mirror/backfill/jobs/0123456789abcdef0123456789abcdef/";
    const std::vector<uint8_t> read64k = {0,0,1,0};
    sender::CloneRecoveryActionView action;
    auto valid = [&](uint8_t kind, uint64_t offset, const std::string &path, const std::vector<uint8_t> &payload) {
        auto raw=frame(kind,offset,path,payload); return sender::parseCloneRecoveryAction(raw,action);
    };
    assert(valid(1,0,"mirror/backfill/capabilities-v1",read64k));
    assert(valid(2,0,prefix+"manifest.json",{1}));
    assert(valid(3,0,prefix+"package.jsonl.gz",{1}));
    assert(valid(4,0,prefix+"commit.json",{1}));
    assert(valid(5,0,prefix+"status.json",read64k));
    assert(valid(6,0,prefix+"cancel.json",{1}));
    assert(valid(7,0,prefix+"request.json",{1}));
    assert(valid(8,0,prefix+"manifest.json",read64k));
    assert(valid(9,1024*1024,prefix+"package.jsonl.gz",read64k));
    assert(!valid(7,1,prefix+"request.json",{1}));
    assert(!valid(7,0,prefix+"../request.json",{1}));
    assert(!valid(8,256*1024,prefix+"manifest.json",read64k));
    assert(!valid(9,512ULL*1024*1024,prefix+"package.jsonl.gz",read64k));
    assert(!valid(9,0,prefix+"package.jsonl.gz",{1,0,1,0}));
    assert(!valid(9,0,prefix+"package.jsonl.gz",{}));
    assert(!valid(10,0,prefix+"package.jsonl.gz",read64k));
    auto raw=frame(9,0,prefix+"package.jsonl.gz",read64k);
    raw.pop_back(); assert(!sender::parseCloneRecoveryAction(raw,action));
    using K=receiver::CloneRecoveryPathKind;
    assert(receiver::classifyCloneRecoveryPath(prefix+"request.json")==K::request);
    assert(receiver::classifyCloneRecoveryPath(prefix+"manifest.json")==K::manifest);
    assert(receiver::classifyCloneRecoveryPath(prefix+"../request.json")==K::invalid);
    assert(receiver::validateCloneRecoveryRead(K::manifest,0,65536));
    assert(!receiver::validateCloneRecoveryRead(K::manifest,256*1024,1));
    assert(receiver::validateCloneRecoveryRead(K::package,1024*1024,65536));
    assert(!receiver::validateCloneRecoveryRead(K::package,0,65537));
    assert(!receiver::validateCloneRecoveryRead(K::package,512U*1024*1024,1));
    assert(!receiver::validateCloneRecoveryRead(K::request,0,1));
    std::cout << "26 native recovery frame/path checks passed\n";
}
'''
    with tempfile.TemporaryDirectory(prefix='juggluco-recovery-native-') as temp:
        source = Path(temp) / 'recovery.cpp'
        binary = Path(temp) / 'recovery-tests'
        source.write_text(code)
        subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++20', '-O1', '-g',
                        '-fsanitize=address,undefined', str(source), '-o', str(binary)], check=True)
        return subprocess.run([str(binary)], timeout=30).returncode


if __name__ == '__main__':
    raise SystemExit(main())
