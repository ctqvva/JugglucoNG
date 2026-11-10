import json
import base64
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
    def test_optional_twilio_build_input_is_private_and_validated(self):
        account = {'sid': 'AC' + 'a' * 32, 'auth_token': 'b' * 32}
        with patch.dict(os.environ, {turn.TWILIO_ENVIRONMENT: json.dumps(account)}):
            self.assertEqual(turn.load_twilio(), account)
        for value in ('broken-secret', '{}', json.dumps(dict(account, auth_token='bad secret'))):
            with patch.dict(os.environ, {turn.TWILIO_ENVIRONMENT: value}):
                with self.assertRaises(ValueError) as error:
                    turn.load_twilio()
                self.assertNotIn('broken-secret', str(error.exception))
                self.assertNotIn('bad secret', str(error.exception))
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            turn.generate([SERVER], directory, account)
            path = directory / turn.TWILIO_HEADER
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertIn('TWILIOACCOUNT', path.read_text())
            self.assertNotIn(account['auth_token'], path.read_text())
            basic = base64.b64encode((account['sid'] + ':' + account['auth_token']).encode())
            payload = b'\0'.join(SERVER[field].encode() for field in ('host', 'username', 'password')) + b'\0'
            apk = directory / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libg.so', payload)
            with self.assertRaisesRegex(ValueError, 'Optional Twilio input missing'):
                turn.verify_apk(apk, [SERVER], ['arm64-v8a'], account)
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libg.so', payload + account['sid'].encode() + b'\0' + basic + b'\0')
            turn.verify_apk(apk, [SERVER], ['arm64-v8a'], account)

    def test_extended_pool_secret_takes_priority_without_replacing_legacy_input(self):
        pool = [dict(SERVER, transport='tls', port=443)]
        with patch.dict(os.environ, {turn.ENVIRONMENT: json.dumps([SERVER]),
                                    turn.POOL_ENVIRONMENT: json.dumps(pool)}):
            self.assertEqual(turn.load(), pool)

    def test_missing_empty_and_partial_inputs_fail_without_echoing_values(self):
        invalid = [None, '', 'secret-malformed-json', '{}', '[]', '[null]',
                   json.dumps([dict(SERVER, password='')]), json.dumps([dict(SERVER, username=' ')]),
                   json.dumps([dict(SERVER, host='turn:relay.invalid')]),
                   json.dumps([dict(SERVER, port=True)]), json.dumps([dict(SERVER, port=0)]),
                   json.dumps([dict(SERVER, port=65536)]), json.dumps([dict(SERVER, port='3478')]),
                   json.dumps([dict(SERVER, accountToken='master-token')]),
                   json.dumps([dict(SERVER, password='bad\0secret')]),
                   json.dumps([dict(SERVER, password='x' * 1025)]), json.dumps([SERVER] * 17),
                   json.dumps([dict(SERVER, transport='https')]),
                   json.dumps([dict(SERVER, provider='bad provider')]),
                   json.dumps([dict(SERVER, region='x' * 65)])]
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

    def test_pool_accepts_sixteen_relays_and_generates_transport_metadata(self):
        servers = [dict(SERVER, host=f'relay{i}.invalid', transport=('udp', 'tcp', 'tls')[i % 3],
                        provider=f'provider{i % 3}', region='asia') for i in range(16)]
        self.assertEqual(len(turn.configuration(json.dumps(servers))), 16)
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            turn.generate(servers, directory)
            options = (directory / turn.OPTIONS_HEADER).read_text()
            self.assertIn('TurnRelayTransport::Tls', options)
            self.assertIn('TurnRelayTransport::Tcp', options)
            self.assertEqual((directory / turn.OPTIONS_HEADER).stat().st_mode & 0o777, 0o600)
            self.assertNotIn(SERVER['password'], options)

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
            self.assertEqual(workflow.count('JUGGLUCO_TURN_POOL: ${{ secrets.CLONE_TURN_POOL_JSON }}'), 2)
            self.assertEqual(workflow.count('JUGGLUCO_TWILIO_ACCOUNT: ${{ secrets.CLONE_TWILIO_ACCOUNT_JSON }}'), 2)


if __name__ == '__main__':
    unittest.main()
