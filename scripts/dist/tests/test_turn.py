import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from test_dist import module

turn = module('turn')
ROOT = Path(__file__).resolve().parents[3]
SERVER = {'host': 'relay.example.invalid', 'port': 3478, 'username': 'test-user', 'password': 'test-password'}


class DistributionTurn(unittest.TestCase):
    def test_missing_empty_and_partial_inputs_fail_without_echoing_values(self):
        invalid = [None, '', 'secret-malformed-json', '{}', '[]', '[null]',
                   json.dumps([dict(SERVER, password='')]), json.dumps([dict(SERVER, username=' ')]),
                   json.dumps([dict(SERVER, host='turn:relay.invalid')]),
                   json.dumps([dict(SERVER, port=True)]), json.dumps([dict(SERVER, port=0)]),
                   json.dumps([dict(SERVER, port=65536)]), json.dumps([dict(SERVER, port='3478')]),
                   json.dumps([dict(SERVER, accountToken='master-token')]),
                   json.dumps([dict(SERVER, password='bad\0secret')]),
                   json.dumps([dict(SERVER, password='x' * 1025)]), json.dumps([SERVER] * 3)]
        invalid.extend([json.dumps([dict(SERVER, host='turn://relay.invalid')]),
                        json.dumps([dict(SERVER, password='bad\ud800secret')])])
        for value in invalid:
            with self.subTest(value_type=type(value).__name__):
                with self.assertRaises(ValueError) as error:
                    turn.configuration(value)
                self.assertNotIn('secret-malformed-json', str(error.exception))
                self.assertNotIn('master-token', str(error.exception))
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'requires JUGGLUCO_TURN_SERVERS'):
            turn.load()

    def test_all_abis_and_every_entry_must_survive_packaging(self):
        servers = [SERVER, dict(SERVER, username='other-user', password='other-password')]
        payload = b'\0'.join(s[field].encode() for s in servers for field in ('host', 'username', 'password')) + b'\0'
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / 'example.apk'
            for missing in (None, 'arm64-v8a', 'armeabi-v7a'):
                with zipfile.ZipFile(apk, 'w') as archive:
                    for abi in ('arm64-v8a', 'armeabi-v7a'):
                        archive.writestr(f'lib/{abi}/libg.so', payload if missing != abi else b'stale no-relay library')
                if missing:
                    with self.assertRaisesRegex(ValueError, 'missing from') as error:
                        turn.verify_apk(apk, servers, ['arm64-v8a', 'armeabi-v7a'])
                    self.assertNotIn('other-password', str(error.exception))
                else:
                    turn.verify_apk(apk, servers, ['arm64-v8a', 'armeabi-v7a'])
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libg.so', payload)
            with self.assertRaisesRegex(ValueError, 'native library missing'):
                turn.verify_apk(apk, servers, ['arm64-v8a', 'armeabi-v7a'])

    @unittest.skipUnless(shutil.which('c++'), 'requires a C++ compiler')
    def test_production_header_selection_and_escaping_in_real_cpp(self):
        with tempfile.TemporaryDirectory(prefix='clone turn ') as tmp:
            directory = Path(tmp)
            special = dict(SERVER, username='u"\\\n??/é', password='p\t\\\r\n"')
            turn.generate(turn.configuration(json.dumps([special])), directory)
            self.assertEqual((directory / turn.HEADER).stat().st_mode & 0o777, 0o600)
            source = directory / 'test.cpp'
            source.write_text('''#include "net/ICE/BundledTurnServers.hpp"
#include <cstring>
static_assert(std::size(bundledTurnServers) == 1);
static_assert(!hasTurnCredentials({.host="x", .username="", .password="p", .port=1}));
static_assert(!hasTurnCredentials({.host="x", .username="u", .password=nullptr, .port=1}));
int main() {
    const auto &server = bundledTurnServers[0];
    return strcmp(server.username, EXPECTED_USER) || strcmp(server.password, EXPECTED_PASSWORD);
}
''')
            executable = directory / 'test'
            command = ['c++', '-std=c++20', '-DJUGGLUCO_DISTRIBUTION=1', '-I', str(directory),
                       '-I', str(ROOT / 'Common/src/main/cpp'),
                       '-DEXPECTED_USER=' + turn.literal(special['username']),
                       '-DEXPECTED_PASSWORD=' + turn.literal(special['password']),
                       str(source), '-o', str(executable)]
            subprocess.run(command, check=True, capture_output=True)
            subprocess.run([str(executable)], check=True)
            # Even a present legacy header cannot rescue a missing release input.
            (directory / 'turnservers.local.hpp').write_text('{.host="local",.username="u",.password="p",.port=1},')
            (directory / turn.HEADER).unlink()
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            # Empty/malformed credentials fail at native compile time as well.
            for header in ('', '{.host="x",.username="",.password="p",.port=1},'):
                (directory / turn.HEADER).write_text(header)
                self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)

    def test_wrapper_cleans_private_input_and_does_not_stage_failed_build(self):
        with tempfile.TemporaryDirectory(prefix='clone dist ') as tmp:
            root = Path(tmp)
            (root / 'scripts/dist').mkdir(parents=True)
            shutil.copy(ROOT / 'scripts/build-dist.sh', root / 'scripts/build-dist.sh')
            shutil.copy(ROOT / 'scripts/dist/turn.py', root / 'scripts/dist/turn.py')
            (root / 'scripts/dist/dist.py').write_text('import sys\nTARGETS={"phone": []}\nprint(":Common:assembleMobileRelease")\n')
            gradle = root / 'gradlew'
            gradle.write_text('#!/bin/sh\ntest -s "$ORG_GRADLE_PROJECT_distributionTurnDirectory/distribution-turn.hpp" || exit 22\nexit 23\n')
            gradle.chmod(0o755)
            staged = root / 'build/dist/phone'
            staged.mkdir(parents=True)
            (staged / 'stale.apk').write_text('old')
            for value in ('', json.dumps([SERVER])):
                with patch.dict(os.environ, {turn.ENVIRONMENT: value}):
                    result = subprocess.run(['bash', str(root / 'scripts/build-dist.sh'), 'phone'], capture_output=True, text=True)
                self.assertEqual(result.returncode, 1 if not value else 23)
                self.assertFalse(staged.exists())
                self.assertFalse(list((root / 'build/private').iterdir()))
                self.assertNotIn(SERVER['password'], result.stdout + result.stderr)

    def test_workflow_inputs_cover_both_signing_paths(self):
        for name in ('signed-build.yml', 'dev-build.yml'):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            self.assertEqual(workflow.count('JUGGLUCO_TURN_SERVERS: ${{ secrets.CLONE_TURN_SERVERS_JSON }}'), 2)


if __name__ == '__main__':
    unittest.main()
