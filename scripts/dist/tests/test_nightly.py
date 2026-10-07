from datetime import date
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
import nightly

SHA = 'a' * 40
OLD_SHA = 'b' * 40
DAY = date(2026, 10, 5)


def release(sha=OLD_SHA, day=date(2026, 10, 4), **kwargs):
    names = ['JugglucoNG-1.2.3-Alpha' + suffix + '.apk' for suffix in ['', '-dub', '-wear', '-wear-dub']]
    value = {'tag_name': nightly.nightly_tag(sha, day), 'draft': False, 'prerelease': True,
             'published_at': str(day) + 'T01:00:00Z', 'body': f'<!-- juggluco-nightly:{sha} -->',
             'assets': [{'name': n, 'size': 123, 'state': 'uploaded'} for n in names + ['update-manifest.json']]}
    return dict(value, **kwargs)


def ci(sha=SHA, conclusion='success', status='completed'):
    return {'workflow_runs': [{'head_sha': sha, 'head_branch': 'main', 'event': 'push',
                              'status': status, 'conclusion': conclusion}]}


class Selection(unittest.TestCase):
    def decision(self, items, responses):
        with patch.object(nightly, 'api', side_effect=responses) as api:
            result = nightly.decision(SHA, DAY, items)
        return result, api

    def test_first_nightly_requires_passing_exact_main_ci(self):
        result, _ = self.decision([], [[], ci()])
        self.assertTrue(result[0])
        self.assertEqual(result[1], 'nightly-2026-10-05-aaaaaaaaaaaa')
        for status in [ci(conclusion='failure'), ci(conclusion=None, status='in_progress'), ci(sha=OLD_SHA), {'workflow_runs': []}]:
            result, _ = self.decision([], [[], status])
            self.assertFalse(result[0])

    def test_unchanged_main_skips_before_ci_or_build(self):
        result, api = self.decision([release(SHA)], [{'object': {'sha': SHA, 'type': 'commit'}}])
        self.assertFalse(result[0])
        self.assertIn('unchanged', result[3])
        self.assertEqual(api.call_count, 1)

    def test_new_changes_build_and_behind_or_divergent_history_does_not(self):
        ref = {'object': {'sha': OLD_SHA, 'type': 'commit'}}
        result, _ = self.decision([release()], [ref, {'status': 'ahead'}, [], ci()])
        self.assertTrue(result[0])
        result, _ = self.decision([release()], [ref, {'status': 'behind'}])
        self.assertFalse(result[0])
        with self.assertRaisesRegex(ValueError, 'diverged'):
            self.decision([release()], [ref, {'status': 'diverged'}])

    def test_drafts_never_count_as_success_and_reserved_tags_fail_closed(self):
        result, _ = self.decision([release(draft=True)], [[], ci()])
        self.assertTrue(result[0])
        with self.assertRaisesRegex(ValueError, 'including draft'):
            self.decision([release(SHA, DAY, draft=True)], [])
        with self.assertRaisesRegex(ValueError, 'tag already reserved'):
            self.decision([], [[{'ref': 'refs/tags/' + nightly.nightly_tag(SHA, DAY)}]])

    def test_incomplete_or_retagged_nightly_is_not_a_successful_baseline(self):
        for r, response in [(release(assets=[]), []), (release(), [{'object': {'sha': SHA, 'type': 'commit'}}])]:
            with self.assertRaises(ValueError):
                self.decision([r], response)

    def test_owned_nightly_identity_and_dates(self):
        self.assertTrue(nightly.is_nightly(release()))
        for r in [release(prerelease=False), release(body=''), release(tag_name='nightly-2026-02-30-bbbbbbbbbbbb'),
                  release(tag_name='nightly-2026-10-04-cccccccccccc'), release(tag_name='1.2.3-Alpha')]:
            self.assertFalse(nightly.is_nightly(r))
        with self.assertRaises(ValueError):
            nightly.nightly_tag('main', DAY)


class Publication(unittest.TestCase):
    def env(self):
        return patch.dict(os.environ, {'GITHUB_REPOSITORY': nightly.REPO,
                                      'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': SHA})

    def test_controller_rejects_foreign_repo_and_non_main(self):
        for repo, ref in [('evil/repo', 'refs/heads/main'), (nightly.REPO, 'refs/heads/branch'),
                          (nightly.REPO, 'refs/tags/main')]:
            with patch.dict(os.environ, {'GITHUB_REPOSITORY': repo, 'GITHUB_REF': ref}), self.assertRaises(ValueError):
                nightly.trusted_context()

    def test_wrong_sha_tag_or_non_nightly_tag_never_publishes(self):
        with self.env(), patch.object(nightly, 'publication_helper') as helper:
            for tag in ['1.2.3-Alpha', nightly.nightly_tag(OLD_SHA, DAY), 'nightly-2026-99-99-aaaaaaaaaaaa']:
                with self.assertRaises(ValueError):
                    nightly.publish(tag)
            helper.assert_not_called()

    def test_publication_rechecks_then_uses_shared_verified_five_asset_publisher(self):
        tag = nightly.nightly_tag(SHA, DAY)
        helper = Mock()
        seen = {}
        def publish_assets(actual_tag, sha, title, **kwargs):
            self.assertEqual((actual_tag, sha, title), (tag, SHA, 'Nightly 2026-10-05'))
            self.assertTrue(kwargs['prerelease'])
            self.assertEqual(kwargs['directory'], Path('nightly-assets'))
            seen['notes'] = kwargs['notes_file'].read_text()
        helper.publish_assets.side_effect = publish_assets
        with self.env(), patch.object(nightly, 'releases', return_value=[]), \
             patch.object(nightly, 'decision', return_value=(True, tag, None, 'new')), \
             patch.object(nightly, 'publication_helper', return_value=helper), \
             patch.object(nightly, 'stage_assets', return_value=Path('nightly-assets')) as stage, \
             patch.object(nightly, 'prune') as prune:
            nightly.publish(tag)
        helper.tracked_source.assert_called_once_with(SHA)
        helper.verified_assets.assert_called_once()
        stage.assert_called_once_with(helper.verified_assets.return_value, unittest.mock.ANY, tag)
        self.assertIn('Experimental nightly', seen['notes'])
        self.assertIn('Do not rely on this build for treatment decisions or critical alarms', seen['notes'])
        self.assertIn(SHA, seen['notes'])
        prune.assert_called_once_with([])

    def test_unchanged_source_skips_publication_and_failed_verification_cannot_publish(self):
        tag = nightly.nightly_tag(SHA, DAY)
        with self.env(), patch.object(nightly, 'releases', return_value=[]), \
             patch.object(nightly, 'decision', return_value=(False, tag, None, 'unchanged')), \
             patch.object(nightly, 'publication_helper') as helper:
            nightly.publish(tag)
            helper.assert_not_called()
        helper = Mock()
        helper.verified_assets.side_effect = ValueError('wrong signing key')
        with self.env(), patch.object(nightly, 'releases', return_value=[]), \
             patch.object(nightly, 'decision', return_value=(True, tag, None, 'new')), \
             patch.object(nightly, 'publication_helper', return_value=helper), self.assertRaises(ValueError):
            nightly.publish(tag)
        helper.publish_assets.assert_not_called()

    def test_keep_three_deletes_only_owned_published_nightlies_and_protects_pinned_baseline(self):
        items = [release(day=date(2026, 10, d)) for d in range(1, 11)]
        items += [release(tag_name='1.2.3-Alpha'), release(body='other automation'), release(draft=True)]
        pinned = f'https://github.com/{nightly.REPO}/releases/download/{items[0]["tag_name"]}/source.apk'
        with patch.object(nightly.dist, 'inventory', return_value={'source': {'url': pinned}}), \
             patch.object(nightly, 'api', return_value={'object': {'type': 'commit', 'sha': OLD_SHA}}), \
             patch.object(nightly.subprocess, 'run') as run:
            nightly.prune(items)
        deleted = [call.args[0][3] for call in run.call_args_list]
        self.assertEqual(set(deleted), {items[i]['tag_name'] for i in range(1, 7)})
        self.assertTrue(all('--cleanup-tag' in call.args[0] for call in run.call_args_list))


class AssetNaming(unittest.TestCase):
    def test_each_snapshot_and_variant_has_a_distinct_filename(self):
        tag = nightly.nightly_tag(SHA, DAY)
        expected = {f'JugglucoNG-nightly-2026-10-05-aaaaaaaaaaaa-{variant}.apk'
                    for variant in ['phone', 'phone-dub', 'wear', 'wear-dub']}
        self.assertEqual(set(nightly.asset_names(tag).values()), expected)
        other = nightly.asset_names(nightly.nightly_tag(OLD_SHA, DAY))
        self.assertFalse(expected.intersection(other.values()))
        for invalid in ['1.2.3-Alpha', 'nightly-2026-02-30-aaaaaaaaaaaa', '../assets']:
            with self.assertRaises(ValueError):
                nightly.asset_names(invalid)

    def test_named_nightlies_and_legacy_names_both_remain_valid_baselines(self):
        named = release(SHA, DAY)
        for asset, name in zip(named['assets'][:4], nightly.asset_names(named['tag_name']).values()):
            asset['name'] = name
        with patch.object(nightly, 'api', return_value={'object': {'sha': SHA, 'type': 'commit'}}):
            self.assertEqual(nightly.source_sha(named), SHA)
            self.assertEqual(nightly.source_sha(release(SHA, DAY)), SHA)
            named['assets'][0]['name'] = named['assets'][0]['name'].replace('2026-10-05', '2026-10-06')
            with self.assertRaises(ValueError):
                nightly.source_sha(named)

    def source(self, root):
        source = root / 'canonical'
        source.mkdir()
        for flavor, build in nightly.dist.TARGETS['all']:
            (source / nightly.dist.filename(flavor, build)).write_bytes(f'signed-{flavor}-{build}'.encode())
        artifacts = []
        for flavor, build in nightly.dist.TARGETS['phone-all']:
            apk = source / nightly.dist.filename(flavor, build)
            artifacts.append({'file': apk.name, 'size': apk.stat().st_size,
                              'sha256': nightly.dist.digest(apk.read_bytes()),
                              'applicationId': 'tk.glucodata.ng' + ('.dub' if build == 'releasedub' else '')})
        manifest = {'schema': 1, 'versionName': '1.2.3-Alpha', 'versionCode': 1023, 'minSdk': 26,
                    'artifacts': sorted(artifacts, key=lambda a: a['file'])}
        (source / 'update-manifest.json').write_text(json.dumps(manifest))
        return source, manifest

    def test_staging_preserves_apk_bytes_hashes_and_internal_versions_and_rewrites_manifest(self):
        tag = nightly.nightly_tag(SHA, DAY)
        with tempfile.TemporaryDirectory() as tmp:
            source, original = self.source(Path(tmp))
            with patch.object(nightly.dist, 'manifest', return_value=original):
                out = nightly.stage_assets(source, Path(tmp) / 'published', tag)
            names = nightly.asset_names(tag)
            self.assertEqual({p.name for p in out.iterdir()}, set(names.values()) | {'update-manifest.json'})
            for old, new in names.items():
                self.assertEqual((source / old).read_bytes(), (out / new).read_bytes())
            manifest = json.loads((out / 'update-manifest.json').read_text())
            for key in ['schema', 'versionName', 'versionCode', 'minSdk']:
                self.assertEqual(manifest[key], original[key])
            for entry in manifest['artifacts']:
                apk = out / entry['file']
                self.assertEqual(entry['size'], apk.stat().st_size)
                self.assertEqual(entry['sha256'], nightly.dist.digest(apk.read_bytes()))
            self.assertEqual(json.loads((source / 'update-manifest.json').read_text()), original)

    def test_unverified_manifest_or_extra_input_cannot_be_staged(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, original = self.source(Path(tmp))
            destination = Path(tmp) / 'published'
            with patch.object(nightly.dist, 'manifest', return_value=dict(original, versionCode=9999)):
                with self.assertRaisesRegex(ValueError, 'manifest differs'):
                    nightly.stage_assets(source, destination, nightly.nightly_tag(SHA, DAY))
            self.assertFalse(destination.exists())
            (source / 'unexpected.apk').write_bytes(b'extra')
            with self.assertRaisesRegex(ValueError, 'asset set mismatch'):
                nightly.stage_assets(source, destination, nightly.nightly_tag(SHA, DAY))
            self.assertFalse(destination.exists())


class RegularReleaseCompatibility(unittest.TestCase):
    def test_nightly_with_same_code_does_not_block_regular_release(self):
        spec = importlib.util.spec_from_file_location('preflight', BASE / 'release-preflight.py')
        preflight = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(preflight)
        pages = [json.dumps([[]]), json.dumps([[release()]])]
        with patch.dict(os.environ, {'TAG': '1.2.3-Alpha', 'GITHUB_REPOSITORY': nightly.REPO}), \
             patch.object(preflight, 'version', return_value=('1.2.3-Alpha', 1023)), \
             patch.object(preflight.subprocess, 'check_output', side_effect=pages), \
             patch.object(preflight.urllib.request, 'urlopen') as download:
            preflight.main()
            download.assert_not_called()


if __name__ == '__main__':
    unittest.main()
