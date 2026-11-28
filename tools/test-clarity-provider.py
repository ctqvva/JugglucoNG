#!/usr/bin/env python3
"""Test the Clarity provider locally using synthetic inputs and no transport."""

import argparse
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'Common/src/main/cpp'
TESTS = ROOT / 'Common/src/test/cpp'
SOURCES = ('bytes', 'cipher', 'engine', 'logging', 'provider', 'material', 'block_decoder')


def compile_runner(compiler, output, crypto_flags, link_flags):
    subprocess.run([
        compiler, '-std=c++20', '-O1', '-pthread', '-I', str(CPP), *crypto_flags,
        *[str(CPP / 'net/clarity' / f'{name}.cpp') for name in SOURCES],
        str(TESTS / 'ClarityProviderTests.cpp'), '-lz', *link_flags, '-o', str(output),
    ], check=True)


def compile_fixture(compiler, output, *definitions):
    subprocess.run([
        compiler, '-std=c++20', '-shared', '-fPIC', '-I', str(CPP), *definitions,
        str(TESTS / 'ClarityProviderFixture.cpp'), '-o', str(output),
    ], check=True)


def run(binary, env, *providers, expect_mismatch=False):
    result = subprocess.run([str(binary), *map(str, providers)], env=env, text=True,
                            capture_output=True, timeout=30)
    if expect_mismatch and result.returncode == 1 and 'decoder mismatch at operation' in result.stderr:
        return
    if result.returncode or expect_mismatch:
        print(result.stdout, end='')
        print(result.stderr, end='', file=sys.stderr)
        raise SystemExit('Decoder comparison or fixture check failed')
    print(result.stdout, end='')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--compare', nargs=2, type=Path, metavar=('REFERENCE', 'CANDIDATE'),
                      help='compare two trusted provider libraries for the host ABI')
    mode.add_argument('--android-compiler', help='build with an NDK target clang++ wrapper')
    parser.add_argument('--output', type=Path, help='output executable for the Android build')
    args = parser.parse_args()
    if bool(args.android_compiler) != bool(args.output):
        parser.error('--android-compiler and --output must be used together')
    if args.android_compiler:
        compile_runner(args.android_compiler, args.output, ['-I', str(CPP / 'include')],
                       ['-ldl', '-static-libstdc++'])
        return

    compiler = os.environ.get('CXX', 'c++')
    cflags = shlex.split(subprocess.check_output(['pkg-config', '--cflags', 'libcrypto'], text=True))
    crypto_dir = subprocess.check_output(['pkg-config', '--variable=libdir', 'libcrypto'], text=True).strip()
    mac = sys.platform == 'darwin'
    env = os.environ.copy()
    library_path = 'DYLD_LIBRARY_PATH' if mac else 'LD_LIBRARY_PATH'
    env[library_path] = os.pathsep.join(filter(None, (crypto_dir, env.get(library_path))))
    with tempfile.TemporaryDirectory(prefix='clarity-provider-') as temporary:
        directory = Path(temporary).resolve()
        binary = directory / 'clarity-provider-test'
        compile_runner(compiler, binary, cflags, [] if mac else ['-ldl'])
        if args.compare:
            run(binary, env, *[path.resolve(strict=True) for path in args.compare])
            return

        fixture = directory / 'fixture.so'
        different = directory / 'different.so'
        compile_fixture(compiler, fixture)
        compile_fixture(compiler, different, '-DCLARITY_TEST_DIFFERENT_DECODER')
        run(binary, env, fixture)
        run(binary, env, fixture, fixture)
        run(binary, env, fixture, different, expect_mismatch=True)


if __name__ == '__main__':
    main()
