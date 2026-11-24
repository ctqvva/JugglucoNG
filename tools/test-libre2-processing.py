#!/usr/bin/env python3
"""Run the production Libre 2 history and sensor-error rules on made-up values.

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


def extract(signature):
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
fixture = (root / 'tools/fixtures/libre2-processing.cpp').read_text()
fixture = fixture.replace('/* SENSOR_METHODS */', '\n'.join([fields, constants, methods]))
with tempfile.TemporaryDirectory(prefix='libre2-processing-') as directory:
    test = Path(directory) / 'libre2-processing.cpp'
    binary = Path(directory) / 'libre2-processing'
    test.write_text(fixture)
    subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + [
        '-std=c++20', '-O1', '-DNOLOG', '-I', str(cpp), str(test), '-o', str(binary),
    ], check=True)
    subprocess.run([str(binary)], check=True)
