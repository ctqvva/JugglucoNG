#!/usr/bin/env python3
"""Run production Dexcom parsing, lifecycle and rescan paths with native fakes.

No sensor, BLE stack or wire-format oracle is involved. The C++ fixture executes
methods extracted from the app, so changing a cutoff or rescan path changes the
test's behavior too. Requires a host C++20 compiler (CXX or c++).
"""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
cpp = root / 'Common/src/main/cpp'


def extract(file, signature):
    source = (cpp / file).read_text()
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]


def methods(file, signatures):
    return '\n'.join(extract(file, signature) for signature in signatures)


fixture = (root / 'tools/fixtures/dexcom-native.cpp').read_text()
fixture = fixture.replace('/* SENSOR_METHODS */', methods('SensorGlucoseData.hpp', [
    'int getDexWearMinutes()', 'int getmaxdexcount()', 'int getDexMaxSecs()',
    'const int historybytes(', 'int32_t maxstreampos(', 'int32_t pollStorageSize(',
    'int initialHistoryBytes(', 'static bool mkdatabaseDex(',
    '[[nodiscard]] const std::array<uint8_t, 4> getDexPin()',
    'bool hasData(', 'int getweardurationMIN()', 'int getweardurationSEC()',
    'uint32_t officialendtime()', 'int expectedWearDuration()', 'uint32_t expectedEndTime()',
]))
fixture = fixture.replace('/* REGISTRY_METHODS */', methods('sensoren.hpp', [
    'bool knownDex(',
    'sensor *findUnboundManualDexcom(',
    'std::pair<int, SensorGlucoseData *>\n  makeDexComSensorindex(',
]))
fixture = fixture.replace('/* DRIVER_METHODS */',
    extract('dexcom/java.cpp', 'struct glucoseinput {') + ' __attribute__((packed));\n' +
    extract('dexcom/java.cpp', 'struct dexbackfill {') + ' __attribute__((packed));\n' +
    'jbyteArray ' + extract('dexcom/java.cpp', 'fromjava(getDexbackfillcmd)(JNIEnv *envin, jclass _cl, jlong dataptr)')
        .replace('fromjava(getDexbackfillcmd)', 'getDexbackfillcmd') + '\n' +
    'jboolean ' + extract('dexcom/java.cpp', 'fromjava(dexbackfill)(')
        .replace('fromjava(dexbackfill)', 'dexbackfillInput') + '\n' +
    extract('dexcom/java.cpp', 'static bool isG7(') + '\n' +
    'jboolean ' + extract('dexcom/java.cpp', 'fromjava(dexCandidate)(')
        .replace('fromjava(dexCandidate)', 'dexCandidate'))
with tempfile.TemporaryDirectory(prefix='dexcom-native-') as directory:
    directory = Path(directory)
    source = directory / 'dexcom.cpp'
    binary = directory / 'dexcom'
    source.write_text(fixture)
    subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + [
        '-std=c++20', '-DNOLOG', '-I', str(cpp), str(source), '-o', str(binary),
    ], check=True)
    subprocess.run([str(binary)], check=True)
