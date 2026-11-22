import importlib.util
from io import BytesIO
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
import dist


def load(name):
    spec = importlib.util.spec_from_file_location(name, BASE / f'{name}.py')
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


vendor = load('vendor-inputs')
publisher = load('publish-release')


class Lifecycle(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.inventory_path = self.root / 'scripts/dist/build-inputs.json'
        self.inventory_path.parent.mkdir(parents=True)
        self.initial = {f'Common/src/main/jniLibs/{abi}/libvendor.so': b'old-' + abi.encode() for abi in dist.ARM_ABIS}
        for path, content in self.initial.items():
            p = self.root / path
            p.parent.mkdir(parents=True)
            p.write_bytes(content)
        self.old_apk = self.root / 'old.apk'
        self.make_apk(self.old_apk, self.initial)
        data = {'schema': 1, 'source': {'url': 'https://example.invalid/old.apk', 'sha256': dist.digest(self.old_apk.read_bytes())},
                'files': [{'path': path, 'size': len(content), 'sha256': dist.digest(content)} for path, content in sorted(self.initial.items())]}
        self.inventory_path.write_text(json.dumps(data, indent=2) + '\n')
        for p in [patch.object(dist, 'ROOT', self.root), patch.object(dist, 'version', return_value=('1.2.3-Alpha', 1023)), patch.dict('os.environ', {'ORG_GRADLE_PROJECT_jugglucoAbi': ','.join(dist.ARM_ABIS)})]:
            p.start()
            self.addCleanup(p.stop)

    def make_apk(self, path, payload):
        with zipfile.ZipFile(path, 'w') as z:
            for name, content in payload.items():
                z.writestr(dist.input_entry(name), content)

    def published_rebaseline(self, apk, verify=None, draft=False):
        data = {'draft': draft, 'published_at': None if draft else '2026-10-04T00:00:00Z', 'assets': [{'browser_download_url': 'https://github.com/ctqvva/JugglucoNG/releases/download/1.2.3-Alpha/JugglucoNG-1.2.3-Alpha.apk'}]}
        responses = [BytesIO(json.dumps(data).encode()), BytesIO(apk.read_bytes())]
        with patch.object(vendor.urllib.request, 'urlopen', side_effect=responses), patch.object(dist, 'verify_apk', side_effect=verify):
            vendor.rebaseline('1.2.3-Alpha')

    def test_unchanged_regeneration_is_byte_identical(self):
        before = self.inventory_path.read_bytes()
        vendor.regenerate(self.old_apk)
        self.assertEqual(before, self.inventory_path.read_bytes())
        vendor.audit_and_restore(self.old_apk)

    def test_add_replace_bootstrap_then_release_then_clean_restore(self):
        changed = dict(self.initial)
        path = next(iter(changed))
        changed[path] = b'replaced-algorithm'
        added = 'Common/src/main/jniLibs/arm64-v8a/libnewvendor.so'
        changed[added] = b'new-previously-unpublished-bytes'
        for name, content in changed.items():
            (self.root / name).write_bytes(content)
        vendor.regenerate(self.old_apk)
        data = dist.inventory()
        self.assertTrue(data['bootstrapRequired'])
        dist.inputs_check()  # Local build can consume the exact new byte set.
        with self.assertRaisesRegex(ValueError, 'first published baseline'):
            dist.restore(self.old_apk)
        vendor.audit_and_restore(self.old_apk)  # Pending-state CI is honest, no partial restore.
        new_apk = self.root / 'new.apk'
        self.make_apk(new_apk, changed)
        def verify(apk, flavor, build):
            with zipfile.ZipFile(apk) as z:
                self.assertEqual(dist.unavailable_inputs(dist.inventory(), z), [])
        self.published_rebaseline(new_apk, verify)
        self.assertNotIn('bootstrapRequired', dist.inventory())
        self.assertEqual(dist.inventory()['source']['sha256'], dist.digest(new_apk.read_bytes()))
        for name in changed:
            (self.root / name).unlink()
        dist.restore(new_apk)
        self.assertEqual({name: (self.root / name).read_bytes() for name in changed}, changed)
        with self.assertRaisesRegex(ValueError, 'source APK checksum'):
            dist.restore(self.old_apk)

    def test_removal_keeps_old_baseline_and_readd_clears_retired_path(self):
        removed = next(iter(self.initial))
        (self.root / removed).unlink()
        vendor.regenerate(self.old_apk)
        self.assertNotIn('bootstrapRequired', dist.inventory())
        self.assertEqual(dist.inventory()['removedFiles'], [removed])
        vendor.audit_and_restore(self.old_apk)
        self.assertFalse((self.root / removed).exists())
        (self.root / removed).write_bytes(self.initial[removed])
        vendor.regenerate(self.old_apk)
        self.assertNotIn('removedFiles', dist.inventory())

    def test_clean_ci_restore_does_not_resurrect_retired_files_in_either_abi(self):
        retired = {f'Common/src/main/jniLibs/{abi}/libretired.so': b'retired'
                   for abi in dist.ARM_ABIS}
        original = dict(self.initial, **retired)
        self.make_apk(self.old_apk, original)
        data = dist.inventory()
        data['source']['sha256'] = dist.digest(self.old_apk.read_bytes())
        data['files'] += [dict(path=path, size=len(content), sha256=dist.digest(content))
                          for path, content in retired.items()]
        self.inventory_path.write_text(json.dumps(data))
        vendor.regenerate(self.old_apk)
        self.assertEqual(set(dist.inventory()['removedFiles']), set(retired))
        for path in self.initial:
            (self.root / path).unlink()
        vendor.audit_and_restore(self.old_apk)
        self.assertTrue(all((self.root / path).is_file() for path in self.initial))
        self.assertTrue(all(not (self.root / path).exists() for path in retired))
        # Stale caches/source-set copies must fail the canonical input check.
        for path in retired:
            with self.subTest(path=path):
                (self.root / path).write_bytes(b'retired')
                with self.assertRaisesRegex(ValueError, 'Uninventoried distribution input'):
                    dist.inputs_check()
                (self.root / path).unlink()

    def test_single_abi_readd_allows_only_the_inventoried_copy(self):
        # Keep another input in each ABI so retiring the target everywhere is
        # a valid inventory update rather than an empty-directory mistake.
        kept = {f'Common/src/main/jniLibs/{abi}/libkept.so': b'kept-' + abi.encode()
                for abi in dist.ARM_ABIS}
        for path, content in kept.items():
            (self.root / path).write_bytes(content)
        self.make_apk(self.old_apk, {**self.initial, **kept})
        data = dist.inventory()
        data['source']['sha256'] = dist.digest(self.old_apk.read_bytes())
        data['files'] += [dict(path=path, size=len(content), sha256=dist.digest(content))
                          for path, content in kept.items()]
        self.inventory_path.write_text(json.dumps(data))
        for path in self.initial:
            (self.root / path).unlink()
        vendor.regenerate(self.old_apk)

        def verify(apk):
            outputs = ['Signer #1 certificate SHA-256 digest: ' + 'a' * 64,
                       "package: name='tk.glucodata.ng' versionCode='1023' versionName='1.2.3-Alpha-phone'"]
            with patch.object(dist, 'sdk_tool', side_effect=lambda n: n), \
                 patch.object(dist.subprocess, 'check_output', side_effect=outputs):
                dist.verify_apk(apk, 'mobile', 'release', expected_certificate='a' * 64)

        for active in self.initial:
            with self.subTest(active=active):
                for path in self.initial:
                    (self.root / path).unlink(missing_ok=True)
                vendor.regenerate(self.old_apk)
                content = self.initial[active]
                (self.root / active).write_bytes(content)
                vendor.regenerate(self.old_apk)
                self.assertEqual(set(dist.inventory()['removedFiles']), set(self.initial) - {active})
                self.assertNotIn('bootstrapRequired', dist.inventory())
                dist.inputs_check()
                apk = self.root / 'readded.apk'
                self.make_apk(apk, {**kept, active: content})
                verify(apk)
                # Approval is still tied to the exact ABI/path and its hash.
                self.make_apk(apk, {**kept, active: b'tampered'})
                with self.assertRaisesRegex(ValueError, 'APK JNI checksum mismatch'):
                    verify(apk)
                self.make_apk(apk, {**kept, **self.initial})
                with self.assertRaisesRegex(ValueError, 'Removed vendor input still packaged'):
                    verify(apk)
                (self.root / active).unlink()
                vendor.regenerate(self.old_apk)

    def test_bad_bootstrap_declaration_and_source_tamper_rejected(self):
        data = dist.inventory()
        data['bootstrapRequired'] = True
        self.inventory_path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'Bootstrap state disagrees'):
            vendor.audit_and_restore(self.old_apk)
        before = self.inventory_path.read_bytes()
        self.old_apk.write_bytes(self.old_apk.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'source APK checksum'):
            vendor.regenerate(self.old_apk)
        self.assertEqual(before, self.inventory_path.read_bytes())

    def test_missing_marker_for_unavailable_bytes_rejected(self):
        data = dist.inventory()
        data['files'][0]['sha256'] = '0' * 64
        self.inventory_path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'Bootstrap state disagrees'):
            vendor.audit_and_restore(self.old_apk)

    def test_rebaseline_rejects_draft_wrong_tag_and_wrong_certificate(self):
        before = self.inventory_path.read_bytes()
        with self.assertRaisesRegex(ValueError, 'published release'):
            self.published_rebaseline(self.old_apk, draft=True)
        with self.assertRaisesRegex(ValueError, 'current appVersionName'):
            vendor.rebaseline('1.2.2-Alpha')
        with self.assertRaisesRegex(ValueError, 'certificate mismatch'):
            self.published_rebaseline(self.old_apk, verify=ValueError('certificate mismatch'))
        self.assertEqual(before, self.inventory_path.read_bytes())

    def test_empty_or_generated_input_regeneration_is_not_allowed(self):
        for path in self.initial:
            (self.root / path).unlink()
        with self.assertRaisesRegex(ValueError, 'No vendor libraries'):
            vendor.regenerate(self.old_apk)
        (self.root / 'Common/src/main/jniLibs/arm64-v8a/libg.so').write_bytes(b'compiled')
        with self.assertRaisesRegex(ValueError, 'Invalid vendor input'):
            vendor.regenerate(self.old_apk)

    def test_retired_library_in_signed_apk_rejected(self):
        path = next(iter(self.initial))
        (self.root / path).unlink()
        vendor.regenerate(self.old_apk)
        cert = self.root / 'scripts/dist/production-cert.sha256'
        cert.write_text('a' * 64)
        outputs = ['Signer #1 certificate SHA-256 digest: ' + 'a' * 64,
                   "package: name='tk.glucodata.ng' versionCode='1023' versionName='1.2.3-Alpha-phone'"]
        with patch.object(dist, 'sdk_tool', side_effect=lambda n: n), patch.object(dist.subprocess, 'check_output', side_effect=outputs):
            with self.assertRaisesRegex(ValueError, 'Removed vendor input still packaged'):
                dist.verify_apk(self.old_apk, 'mobile', 'release')


class RetiredNativeLibraries(unittest.TestCase):
    """Exercise the real retirement inventory through distribution APK validation."""

    RETIRED = {
        'libalgorithm-jni.so', 'libcgat-lib.so', 'libobjectbox-jni.so',
        'libscannative.so', 'libjniPdfium.so', 'libmodpdfium.so',
        'libmodft2.so', 'libmodpng.so', 'libmarsxlog.so', 'libmmkv.so',
    }

    def setUp(self):
        self.data = dist.inventory()
        self.expected = {
            f'Common/src/main/jniLibs/{abi}/{name}'
            for abi in dist.ARM_ABIS for name in self.RETIRED
        } | {'Common/src/main/jniLibs/armeabi-v7a/libgetuiext3.so'}
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / 'distribution.apk'
        self.payloads = {spec['path']: b'fixture-' + spec['path'].encode()
                         for spec in self.data['files']}
        self.data['files'] = [dict(path=path, size=len(content), sha256=dist.digest(content))
                              for path, content in self.payloads.items()]

    def make_apk(self, retired=None):
        with zipfile.ZipFile(self.apk, 'w') as z:
            for path, content in self.payloads.items():
                z.writestr(dist.input_entry(path), content)
            for abi in dist.ARM_ABIS:
                z.writestr(f'lib/{abi}/libg.so', b'production-target-fixture')
                z.writestr(f'lib/{abi}/libnative.so', b'active-libre-recovery-fixture')
            if retired:
                z.writestr(dist.input_entry(retired), b'accidentally-repackaged')

    def verify(self, flavor, build_type):
        name, code = dist.version()
        code += 1000000 if flavor == 'wear' else 0
        appid = 'tk.glucodata.ng' + ('.dub' if build_type == 'releasedub' else '')
        vn = name + ('-wear' if flavor == 'wear' else '-phone') + ('DUB' if build_type == 'releasedub' else '')
        outputs = ['Signer #1 certificate SHA-256 digest: ' + 'a' * 64,
                   f"package: name='{appid}' versionCode='{code}' versionName='{vn}'"]
        with patch.object(dist, 'inventory', return_value=self.data), \
             patch.object(dist, 'abis', return_value=dist.ARM_ABIS), \
             patch.object(dist, 'sdk_tool', side_effect=lambda n: n), \
             patch.object(dist.subprocess, 'check_output', side_effect=outputs):
            dist.verify_apk(self.apk, flavor, build_type, expected_certificate='a' * 64)

    def test_all_retired_candidates_are_protected_for_each_existing_abi(self):
        self.assertTrue(self.expected <= set(self.data['removedFiles']))
        self.assertFalse(self.expected & {spec['path'] for spec in self.data['files']})

    def test_phone_wear_and_dub_apks_reject_each_retired_payload_in_either_abi(self):
        for flavor, build_type in dist.TARGETS['all']:
            with self.subTest(flavor=flavor, build_type=build_type):
                self.make_apk()
                self.verify(flavor, build_type)
            for retired in sorted(self.expected):
                with self.subTest(flavor=flavor, build_type=build_type, retired=retired):
                    self.make_apk(retired)
                    with self.assertRaisesRegex(ValueError, 'Removed vendor input still packaged'):
                        self.verify(flavor, build_type)

    def test_arm32_only_retired_library_cannot_return_via_an_arm64_aar(self):
        self.make_apk('Common/src/main/jniLibs/arm64-v8a/libgetuiext3.so')
        with self.assertRaisesRegex(ValueError, 'Removed vendor input still packaged'):
            self.verify('mobile', 'release')


class Publication(unittest.TestCase):
    def test_owner_license_and_source_guards_precede_build(self):
        with patch.object(publisher, 'api', return_value={'id': 1}), patch.object(publisher.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'owner'):
                publisher.bootstrap()
            run.assert_not_called()
        with patch.object(publisher, 'api', side_effect=[{'id': publisher.OWNER_ID}, {'value': 'false'}]), patch.object(publisher.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'redistribution rights'):
                publisher.bootstrap()
            run.assert_not_called()
        with patch.object(publisher.subprocess, 'check_output', side_effect=['head\n', ' M build.gradle\n']):
            with self.assertRaisesRegex(ValueError, 'clean committed source'):
                publisher.tracked_source('head')
        with patch.object(publisher.subprocess, 'check_output', side_effect=['branch\n', '']):
            with self.assertRaisesRegex(ValueError, 'trusted main SHA'):
                publisher.tracked_source('main')

    def test_bootstrap_uses_canonical_all_build_and_rechecks_source(self):
        with patch.object(publisher, 'api', side_effect=[{'id': publisher.OWNER_ID}, {'value': 'true'}, {'sha': 'main'}]), \
             patch.object(publisher, 'tracked_source') as check, patch.object(publisher, 'preflight'), \
             patch.object(publisher.dist, 'version', return_value=('1.2.3-Alpha', 1023)), \
             patch.object(publisher.dist, 'inputs_check') as inputs, patch.object(publisher.subprocess, 'run') as run, \
             patch.dict('os.environ', {}, clear=False):
            publisher.bootstrap()
            args = run.call_args.args[0]
            self.assertEqual(args[:2], [str(dist.ROOT / 'scripts/build-dist.sh'), 'all'])
            self.assertIn('--no-configuration-cache', args)
            self.assertEqual(check.call_count, 2)
            self.assertEqual(inputs.call_count, 2)

    def exercise_publish(self, fail_upload=False, fail_tag=False):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        directory = root / 'build/dist/all'
        directory.mkdir(parents=True)
        names = ['JugglucoNG-1.2.3-Alpha.apk', 'JugglucoNG-1.2.3-Alpha-dub.apk', 'JugglucoNG-1.2.3-Alpha-wear.apk', 'JugglucoNG-1.2.3-Alpha-wear-dub.apk']
        for name in names:
            (directory / name).write_bytes(b'validated APK fixture')
        calls = []
        def run(args, **kwargs):
            calls.append((args, kwargs))
            if (fail_tag and args[:2] == ['gh', 'api']) or (fail_upload and args[:3] == ['gh', 'release', 'upload']):
                raise subprocess.CalledProcessError(1, args)
        with patch.object(dist, 'ROOT', root), patch.object(dist, 'version', return_value=('1.2.3-Alpha', 1023)), \
             patch.object(dist, 'verify_set'), patch.object(publisher, 'tracked_source'), patch.object(publisher, 'preflight'), \
             patch.object(publisher, 'api', return_value={'tag_name': '1.2.2-Alpha', 'draft': False, 'prerelease': False, 'published_at': '2026-09-26T00:00:00Z'}), \
             patch.object(publisher.subprocess, 'check_output', return_value='{}'), patch.object(publisher.subprocess, 'run', side_effect=run), \
             patch.dict('os.environ', {'TAG': '1.2.3-Alpha', 'GITHUB_REPOSITORY': publisher.REPO, 'GITHUB_SHA': 'main'}, clear=True):
            if fail_upload or fail_tag:
                with self.assertRaises(subprocess.CalledProcessError):
                    publisher.publish()
            else:
                publisher.publish()
        return calls

    def test_shared_publisher_reserves_tag_uploads_five_assets_then_publishes(self):
        calls = self.exercise_publish()
        gh = [(args, kwargs) for args, kwargs in calls if args[0] == 'gh']
        self.assertEqual([args[1] for args, _ in gh], ['api', 'release', 'release', 'release'])
        self.assertEqual(json.loads(gh[0][1]['input']), {'ref': 'refs/tags/1.2.3-Alpha', 'sha': 'main'})
        self.assertIn('--draft', gh[1][0])
        self.assertEqual(gh[1][0][-3:], ['--generate-notes', '--notes-start-tag', '1.2.2-Alpha'])
        self.assertEqual(len([p for p in gh[2][0] if p.endswith(('.apk', '.json'))]), 5)
        self.assertIn('--draft=false', gh[3][0])

    def test_failed_upload_or_tag_reservation_never_publishes(self):
        for kwargs in [{'fail_upload': True}, {'fail_tag': True}]:
            calls = self.exercise_publish(**kwargs)
            self.assertFalse(any(args[:3] == ['gh', 'release', 'edit'] for args, _ in calls))


class ReleaseNotes(unittest.TestCase):
    def test_normal_and_prerelease_notes_use_latest_non_prerelease_endpoint(self):
        stable = {'tag_name': '1.2.2-Alpha', 'draft': False, 'prerelease': False,
                  'published_at': '2026-09-26T00:00:00Z'}
        for prerelease in (False, True):
            with self.subTest(prerelease=prerelease), \
                 patch.dict('os.environ', {'GITHUB_REPOSITORY': publisher.REPO}, clear=True), \
                 patch.object(publisher, 'api', return_value=stable) as api, \
                 patch.object(publisher.subprocess, 'run') as run:
                publisher.publish_assets('1.2.4-Alpha', 'head', '1.2.4-Alpha', prerelease=prerelease)
                api.assert_called_once_with(f'repos/{publisher.REPO}/releases/latest')
                create = run.call_args_list[1].args[0]
                self.assertEqual(create[-3:], ['--generate-notes', '--notes-start-tag', '1.2.2-Alpha'])

    def test_custom_nightly_notes_skip_generated_notes_and_baseline_lookup(self):
        with patch.dict('os.environ', {'GITHUB_REPOSITORY': publisher.REPO}, clear=True), \
             patch.object(publisher, 'api') as api, patch.object(publisher.subprocess, 'run') as run:
            publisher.publish_assets('nightly-date-sha', 'head', 'Nightly', prerelease=True,
                                     notes_file=Path('nightly-notes.md'))
            api.assert_not_called()
            self.assertEqual(run.call_args_list[1].args[0][-2:], ['--notes-file', 'nightly-notes.md'])

    def test_invalid_or_unavailable_baseline_fails_before_reserving_tag(self):
        invalid = {'tag_name': 'nightly-date-sha', 'draft': False, 'prerelease': True,
                   'published_at': '2026-10-13T00:00:00Z'}
        for value, error in [(invalid, ValueError),
                             (dict(invalid, prerelease=False, draft=True), ValueError),
                             (dict(invalid, prerelease=False, published_at=None), ValueError),
                             (subprocess.CalledProcessError(1, ['gh', 'api']), subprocess.CalledProcessError)]:
            with self.subTest(value=value), \
                 patch.dict('os.environ', {'GITHUB_REPOSITORY': publisher.REPO}, clear=True), \
                 patch.object(publisher, 'api', side_effect=lambda *args: value) as api, \
                 patch.object(publisher.subprocess, 'run') as run:
                if isinstance(value, Exception):
                    api.side_effect = value
                with self.assertRaises(error):
                    publisher.publish_assets('1.2.4-Alpha', 'head', '1.2.4-Alpha')
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
