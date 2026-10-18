#!/usr/bin/env python3
"""Build and exercise the bundled TURN path against a local authenticated relay."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/dist'))
import turn


def main():
    with tempfile.TemporaryDirectory(prefix='clone-turn-test-') as tmp:
        directory = Path(tmp)
        turn.generate(turn.configuration(json.dumps([
            {'host': '127.0.0.1', 'port': 3478, 'username': 'invalid-user', 'password': 'invalid-password'},
            {'host': '127.0.0.1', 'port': 3478, 'username': 'loopback-user', 'password': 'loopback-password'},
        ])), directory)
        subprocess.run(['cmake', '-S', str(ROOT / 'Common/src/main/cpp/libjuice'), '-B', str(directory / 'juice'),
                        '-DBUILD_SHARED_LIBS=OFF', '-DNO_TESTS=ON', '-DNO_SERVER=OFF',
                        '-DCMAKE_C_FLAGS=-Dsendto=clone_turn_test_sendto',
                        '-DENABLE_LOCALHOST_ADDRESS=ON'], check=True)
        subprocess.run(['cmake', '--build', str(directory / 'juice'), '--parallel', '2'], check=True)
        executable = directory / 'clone-turn-test'
        subprocess.run(['c++', '-std=c++20', '-pthread', '-DJUGGLUCO_DISTRIBUTION=1',
                        '-I', str(ROOT / 'Common/src/main/cpp'), '-I', str(directory),
                        str(ROOT / 'Common/src/test/cpp/CloneTurnRelayTests.cpp'),
                        str(directory / 'juice/libjuice.a'), '-o', str(executable)], check=True)
        subprocess.run([str(executable)], check=True, timeout=25)
        subprocess.run([str(executable), '--reject-all'], check=True, timeout=10)


if __name__ == '__main__':
    main()
