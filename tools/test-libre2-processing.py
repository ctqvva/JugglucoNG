#!/usr/bin/env python3
"""Run the production Libre 2 history, sensor-error and frame rules on made-up values.

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
fields = source[start:source.index('/**', start)]
constants = '\n'.join(re.findall(r'^  static constexpr \w+ sensorError\w+ = .*;$', source, re.M))
methods = '\n'.join(extract(s) for s in [
    'void setSensorError(', 'void clearSensorError(', 'bool hasSensorError(',
])
sensor = '\n'.join([fields, constants, methods])
# From the JNI call down to the backend's verdict on the frame.
frames = '\n'.join([
    extract('const AlgorithmResults *  libre2stream::processTooth(', (cpp / 'libre2.cpp').read_text()),
    extract('extern "C" JNIEXPORT jlong JNICALL fromjava(processTooth)(', (cpp / 'g.cpp').read_text()),
])
with tempfile.TemporaryDirectory(prefix='libre2-processing-') as directory:
    for name in ['libre2-processing', 'libre2-frames']:
        fixture = (root / f'tools/fixtures/{name}.cpp').read_text()
        fixture = fixture.replace('/* SENSOR_METHODS */', sensor).replace('/* FRAME_METHODS */', frames)
        test = Path(directory) / f'{name}.cpp'
        binary = Path(directory) / name
        test.write_text(fixture)
        subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + [
            '-std=c++20', '-O1', '-DNOLOG', '-I', str(cpp), str(test), '-o', str(binary),
        ], check=True)
        subprocess.run([str(binary)], check=True)
