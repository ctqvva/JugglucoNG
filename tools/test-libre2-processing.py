#!/usr/bin/env python3
"""Run the production Libre 2 history, sensor-error, frame and wear rules on made-up values.

Requires a host C++20 compiler (CXX or c++).
"""
from pathlib import Path
import os
import re
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
cpp = root / 'Common/src/main/cpp'
source = (cpp / 'SensorGlucoseData.hpp').read_text()


def extract(signature, source=source):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]


# The error fields and constants, up to the first method that uses them.
start = source.index('  bool sensorerror = false;')
fields = source[start:source.index('  bool receivingPackets(', start)]
constants = '\n'.join(re.findall(r'^  static constexpr \w+ sensorError\w+ = .*;$', source, re.M))
methods = '\n'.join(extract(s) for s in [
    'void setSensorError(', 'void clearSensorError(', 'bool hasSensorError(',
    'bool receivingPackets(', 'bool isScanNeeded(',
])
sensor = '\n'.join([fields, constants, methods])
# From the JNI call down to the backend's verdict on the frame.
frames = '\n'.join([
    extract('const AlgorithmResults *  libre2stream::processTooth(', (cpp / 'libre2.cpp').read_text()),
    extract('extern "C" JNIEXPORT jlong JNICALL fromjava(processTooth)(', (cpp / 'g.cpp').read_text()),
])
# The app's own ends: the data record, the sensor list and the loop that feeds other devices.
wear = '\n'.join(extract(s) for s in [
    'bool isNfcLibre2(', 'int getweardurationMIN(', 'int getweardurationSEC(', 'uint32_t officialendtime(',
    'uint32_t getmaxtime(', 'bool isLibre2(', 'bool hasLibrePatchInfo(',
])
sensoren = (cpp / 'sensoren.hpp').read_text()
entry = extract('struct sensor {', sensoren) + ';'
roster = '\n'.join(extract(s, sensoren) for s in [
    'static void coverLibre2Grace(', 'template <typename F> bool checkinfo(const int ind, uint32_t nu, F dont) {',
    'int update(crypt_t *pass, Connect *connect, const int ind, int &firstsensor,',
])
with tempfile.TemporaryDirectory(prefix='libre2-processing-') as directory:
    for name in ['libre2-processing', 'libre2-frames', 'libre2-wear', 'libre2-roster']:
        fixture = (root / f'tools/fixtures/{name}.cpp').read_text()
        fixture = fixture.replace('/* SENSOR_METHODS */', sensor).replace('/* FRAME_METHODS */', frames)
        fixture = fixture.replace('/* WEAR_METHODS */', wear).replace('/* LIST_ENTRY */', entry)
        fixture = fixture.replace('/* ROSTER_METHODS */', roster)
        test = Path(directory) / f'{name}.cpp'
        binary = Path(directory) / name
        test.write_text(fixture)
        subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + [
            '-std=c++20', '-O1', '-DNOLOG', '-I', str(root / 'tools/fixtures/libre2-stubs'), '-I', str(cpp), str(test), '-o', str(binary),
        ], check=True)
        subprocess.run([str(binary)], check=True)
