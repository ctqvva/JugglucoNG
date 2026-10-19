import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

BASE = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, BASE / f'{name}.py')
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


dist = module('dist')
request = module('request')


class Requests(unittest.TestCase):
    def test_only_exact_allowlisted_commands(self):
        for target in dist.TARGETS:
            event = {'comment': {'user': {'id': 65550090}, 'body': '/build-dist ' + target}}
            self.assertEqual(request.requested_target('issue_comment', event), target)
        for body in ['/build-dist all; echo leak', '/build-dist $(id)', '/build-dist main', '/build-dist all\nmalicious', 'quoted /build-dist all']:
            self.assertIsNone(request.requested_target('issue_comment', {'comment': {'user': {'id': 3178592}, 'body': body}}))
        self.assertIsNone(request.requested_target('issue_comment', {'comment': {'user': {'id': 1, 'login': 'JetFoxy'}, 'body': '/build-dist all'}}))
        self.assertIsNone(request.requested_target('pull_request', {}))
        self.assertIsNone(request.requested_target('workflow_dispatch', {'inputs': {'target': 'untrusted'}}))


class TestApkStaging(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.sha = '123456789abc' + 'd' * 28
        patcher = patch.object(dist, 'version', return_value=('1.2.3-Alpha', 1023))
        patcher.start()
        self.addCleanup(patcher.stop)

    def fixture(self, target):
        directory = self.root / 'build/dist' / target
        directory.mkdir(parents=True, exist_ok=True)
        for flavor, build_type in dist.TARGETS[target]:
            (directory / dist.filename(flavor, build_type)).write_bytes(f'{flavor}:{build_type}'.encode())
        return directory

    def test_all_targets_and_sources_preserve_each_variant_and_bytes(self):
        for target in dist.TARGETS:
            directory = self.fixture(target)
            original = {p.name: p.read_bytes() for p in directory.glob('*.apk')}
            for pr_number in [None, '547']:
                with self.subTest(target=target, pr_number=pr_number), patch.object(dist, 'verify_apk') as verify:
                    out = dist.stage_test_assets(directory, target, self.sha, pr_number, expected_certificate='trusted pin')
                    self.assertEqual(verify.call_count, len(dist.TARGETS[target]))
                    self.assertTrue(all(call.args[3] == 'trusted pin' for call in verify.call_args_list))
                    source = 'main' if pr_number is None else 'pr547'
                    expected = {}
                    for flavor, build_type in dist.TARGETS[target]:
                        variant = ('phone' if flavor == 'mobile' else 'wear') + ('-dub' if build_type == 'releasedub' else '')
                        expected[f'JugglucoNG-test-{source}-123456789abc-{variant}.apk'] = f'{flavor}:{build_type}'.encode()
                    self.assertEqual({p.name: p.read_bytes() for p in out.iterdir()}, expected)
                    self.assertEqual({p.name: p.read_bytes() for p in directory.iterdir()}, original)

    def test_new_snapshot_replaces_old_files_and_metadata(self):
        directory = self.fixture('phone')
        with patch.object(dist, 'verify_apk'):
            out = dist.stage_test_assets(directory, 'phone', self.sha, '547')
            dist.write_build_info(out, {'source_sha': self.sha})
            dist.stage_test_assets(directory, 'phone', 'e' * 40, '547')
        self.assertEqual([p.name for p in out.iterdir()], ['JugglucoNG-test-pr547-eeeeeeeeeeee-phone.apk'])

    def test_bad_identity_rejected_before_verification_or_writes(self):
        directory = self.fixture('phone')
        for sha, number, target in [(None, None, 'phone'), ('main', None, 'phone'),
                                    ('a' * 39, None, 'phone'), ('A' * 40, None, 'phone'),
                                    (self.sha, '0', 'phone'), (self.sha, '../547', 'phone'),
                                    (self.sha, None, '../phone')]:
            with self.subTest(sha=sha, number=number, target=target), patch.object(dist, 'verify_set') as verify:
                with self.assertRaises(ValueError):
                    dist.stage_test_assets(directory, target, sha, number)
                verify.assert_not_called()
        self.assertFalse((directory.parent / 'test').exists())

    def test_incomplete_or_unverified_set_never_staged(self):
        directory = self.fixture('phone')
        with self.assertRaisesRegex(ValueError, 'set mismatch'):
            dist.stage_test_assets(directory, 'all', self.sha)
        with patch.object(dist, 'verify_apk', side_effect=ValueError('certificate mismatch')):
            with self.assertRaisesRegex(ValueError, 'certificate mismatch'):
                dist.stage_test_assets(directory, 'phone', self.sha)
        self.assertFalse((directory.parent / 'test').exists())

    def test_copy_corruption_cannot_replace_previous_staged_set(self):
        directory = self.fixture('phone')
        with patch.object(dist, 'verify_apk'):
            out = dist.stage_test_assets(directory, 'phone', self.sha)
            before = {p.name: p.read_bytes() for p in out.iterdir()}
            with patch.object(dist.shutil, 'copyfile', side_effect=lambda src, dest: dest.write_bytes(b'corrupt')):
                with self.assertRaisesRegex(ValueError, 'bytes changed'):
                    dist.stage_test_assets(directory, 'phone', 'e' * 40)
            self.assertEqual({p.name: p.read_bytes() for p in out.iterdir()}, before)

    def test_main_cli_metadata_matches_uploaded_names_and_hashes(self):
        directory = self.fixture('all')
        with patch.object(dist, 'verify_apk'), \
             patch.dict('os.environ', {'GITHUB_REPOSITORY': 'ctqvva/JugglucoNG', 'GITHUB_RUN_ID': '123'}), \
             patch('sys.argv', ['dist.py', 'test-stage', '--dir', str(directory), '--target', 'all', '--sha', self.sha]):
            dist.main()
        out = directory.parent / 'test/all'
        info = json.loads((out / 'build-info.json').read_text())
        self.assertEqual(info['source_sha'], self.sha)
        self.assertEqual(info['source_kind'], 'trusted-main')
        self.assertEqual(info['run_url'], 'https://github.com/ctqvva/JugglucoNG/actions/runs/123')
        self.assertEqual(len(info['apks']), 4)
        for item in info['apks']:
            apk = out / item['file']
            self.assertEqual(item['sha256'], dist.digest(apk.read_bytes()))
            self.assertEqual(item['size'], apk.stat().st_size)


class InputRestore(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.payload = b'known native input'
        self.spec = {'path': 'Common/src/main/jniLibs/arm64-v8a/libexample.so', 'size': len(self.payload), 'sha256': dist.digest(self.payload)}
        self.apk = self.root / 'baseline.apk'
        with zipfile.ZipFile(self.apk, 'w') as z:
            z.writestr('lib/arm64-v8a/libexample.so', self.payload)
            z.writestr('../../must-not-extract', b'not allowed')
        self.data = {'source': {'sha256': dist.digest(self.apk.read_bytes())}, 'files': [self.spec]}
        for p in [patch.object(dist, 'ROOT', self.root), patch.object(dist, 'inventory', return_value=self.data), patch.object(dist, 'abis', return_value=['arm64-v8a'])]:
            p.start()
            self.addCleanup(p.stop)

    def test_restore_allowlist_and_idempotence(self):
        dist.restore(self.apk)
        dist.restore(self.apk)
        self.assertEqual((self.root / self.spec['path']).read_bytes(), self.payload)
        self.assertEqual(list(self.root.rglob('*.so')), [self.root / self.spec['path']])

    def test_source_tamper_fails_before_writes(self):
        self.apk.write_bytes(self.apk.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'source APK checksum'):
            dist.restore(self.apk)
        self.assertFalse((self.root / 'Common').exists())

    def test_input_tamper_fails_before_writes(self):
        self.spec['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Input checksum'):
            dist.restore(self.apk)
        self.assertFalse((self.root / 'Common').exists())

    def test_refuses_overwrite_and_extra_libraries(self):
        dest = self.root / self.spec['path']
        dest.parent.mkdir(parents=True)
        dest.write_bytes(b'local changes')
        with self.assertRaisesRegex(ValueError, 'overwrite'):
            dist.restore(self.apk)
        self.assertEqual(dest.read_bytes(), b'local changes')
        dest.write_bytes(self.payload)
        (dest.parent / 'libunexpected.so').write_bytes(b'unreviewed')
        with self.assertRaisesRegex(ValueError, 'Uninventoried'):
            dist.inputs_check()


class ApkVerification(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'scripts/dist').mkdir(parents=True)
        self.cert = 'a' * 64
        (self.root / 'scripts/dist/production-cert.sha256').write_text(self.cert)
        self.apk = self.root / 'example.apk'
        with zipfile.ZipFile(self.apk, 'w') as z:
            z.writestr('lib/arm64-v8a/libg.so', b'compiled')
        for p in [patch.object(dist, 'ROOT', self.root), patch.object(dist, 'version', return_value=('1.2.3-Alpha', 1023)), patch.object(dist, 'inventory', return_value={'files': []}), patch.object(dist, 'abis', return_value=['arm64-v8a']), patch.object(dist, 'sdk_tool', side_effect=lambda name: name)]:
            p.start()
            self.addCleanup(p.stop)
        self.badging = "package: name='tk.glucodata.ng' versionCode='1023' versionName='1.2.3-Alpha-phone'"

    def verify(self, certificate, badging=None):
        with patch.object(dist.subprocess, 'check_output', side_effect=[certificate, badging or self.badging]):
            dist.verify_apk(self.apk, 'mobile', 'release')

    def test_both_apksigner_output_formats(self):
        for prefix in ['Signer #1', 'V2 Signer:']:
            self.verify(prefix + ' certificate SHA-256 digest: ' + self.cert)

    def test_fallback_or_missing_certificate_rejected(self):
        for certificate in ['Signer #1 certificate SHA-256 digest: ' + 'b' * 64, 'no certificate']:
            with self.assertRaisesRegex(ValueError, 'certificate mismatch'):
                self.verify(certificate)

    def test_controller_pin_overrides_untrusted_certificate_file(self):
        (self.root / 'scripts/dist/production-cert.sha256').write_text('b' * 64)
        with patch.object(dist.subprocess, 'check_output', return_value='Signer #1 certificate SHA-256 digest: ' + 'b' * 64):
            with self.assertRaisesRegex(ValueError, 'certificate mismatch'):
                dist.verify_apk(self.apk, 'mobile', 'release', expected_certificate=self.cert)

    def test_wrong_version_package_debuggable_rejected(self):
        for badging in [self.badging.replace('1023', '1022'), self.badging.replace('tk.glucodata.ng', 'tk.glucodata.ng.dub'), self.badging + '\napplication-debuggable']:
            with self.assertRaises(ValueError):
                self.verify('Signer #1 certificate SHA-256 digest: ' + self.cert, badging)

    def test_missing_abi_and_incomplete_set_rejected(self):
        with patch.object(dist, 'abis', return_value=['arm64-v8a', 'armeabi-v7a']):
            with self.assertRaisesRegex(ValueError, 'ABI mismatch'):
                self.verify('Signer #1 certificate SHA-256 digest: ' + self.cert)
        with self.assertRaisesRegex(ValueError, 'APK set mismatch'):
            dist.verify_set(self.root, 'all')

    def test_release_tag_mismatch_rejected(self):
        with patch('sys.argv', ['dist.py', 'release-check', '--tag', '1.2.2-Alpha']):
            with self.assertRaisesRegex(ValueError, 'exactly equal'):
                dist.main()


# Import release helper through its normal sibling-module lookup.
import sys
sys.path.insert(0, str(BASE))
release = module('release-preflight')


class ReleasePreflight(unittest.TestCase):
    def run_preflight(self, tag='1.2.3-Alpha', refs=None, releases=None, previous_code=1022):
        from io import BytesIO
        with patch.dict('os.environ', {'TAG': tag, 'GITHUB_REPOSITORY': 'ctqvva/JugglucoNG'}), \
             patch.object(release, 'version', return_value=('1.2.3-Alpha', 1023)), \
             patch.object(release.subprocess, 'check_output', side_effect=[json.dumps([refs or []]), json.dumps([releases or []])]), \
             patch.object(release.urllib.request, 'urlopen', return_value=BytesIO(json.dumps({'versionCode': previous_code}).encode())):
            release.main()

    def test_existing_tag_and_draft_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Tag already exists'):
            self.run_preflight(refs=[{'ref': 'refs/tags/1.2.3-Alpha'}])
        with self.assertRaisesRegex(ValueError, 'including draft'):
            self.run_preflight(releases=[{'tag_name': '1.2.3-Alpha', 'draft': True}])

    def test_version_mismatch_and_regression_rejected(self):
        with self.assertRaisesRegex(ValueError, 'exactly equal'):
            self.run_preflight(tag='v1.2.3-Alpha')
        for previous in [1023, 1024]:
            with self.assertRaisesRegex(ValueError, 'exceed every'):
                self.run_preflight(releases=[{'tag_name': '1.2.2-Alpha', 'draft': False, 'assets': [{'name': 'update-manifest.json', 'browser_download_url': 'https://example.invalid/manifest'}]}], previous_code=previous)

    def test_new_increasing_version_accepted(self):
        self.run_preflight(releases=[{'tag_name': '1.2.2-Alpha', 'draft': False, 'assets': [{'name': 'update-manifest.json', 'browser_download_url': 'https://example.invalid/manifest'}]}])


if __name__ == '__main__':
    unittest.main()
