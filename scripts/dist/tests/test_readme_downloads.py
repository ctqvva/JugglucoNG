import base64
import importlib.util
from pathlib import Path
import unittest
from subprocess import CalledProcessError
from tempfile import TemporaryDirectory
import xml.etree.ElementTree as ET
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('readme_downloads', Path(__file__).parents[1] / 'update-readme-downloads.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def release(tag='1.2.4-Alpha', **changes):
    value = {'tag_name': tag, 'draft': False, 'prerelease': False, 'published_at': '2026-10-14T00:00:00Z',
             'assets': [{'name': f'JugglucoNG-{tag}{suffix}.apk', 'size': 100, 'state': 'uploaded',
                         'browser_download_url': f'https://github.com/{helper.REPO}/releases/download/{tag}/JugglucoNG-{tag}{suffix}.apk'}
                        for suffix in ['', '-dub', '-wear', '-wear-dub']]}
    return dict(value, **changes)


AUTOMATIC = release('1.2.2-Alpha', published_at='2026-09-26T00:00:00Z')
PREVIEW = release(prerelease=True)
PAGES = [[AUTOMATIC, PREVIEW]]
README = ('# Original header\n' + helper.INFO_START + '\nold version\n' + helper.INFO_END
          + '\nUnrelated explanation\n' + helper.START + '\nold links\n' + helper.END + '\nUnrelated footer\n')
BUTTON = (helper.ROOT / helper.BUTTON_PATH).read_text()


def encoded(text):
    return {'content': base64.b64encode(text.encode()).decode()}


def remote_responses(readme=README, button=BUTTON):
    return [PAGES, {'object': {'sha': 'current-head'}}, encoded(readme), encoded(button),
            {'tree': {'sha': 'current-tree'}}, {'sha': 'new-tree'}, {'sha': 'new-commit'}, {}]


class Downloads(unittest.TestCase):
    def test_manual_download_includes_preview_but_update_channel_excludes_it(self):
        items = [AUTOMATIC, PREVIEW, release('1.2.3-Alpha', prerelease=True, published_at='2026-10-07T00:00:00Z'),
                 release('1.2.5-Alpha', draft=True, published_at='2026-10-15T00:00:00Z'),
                 release('nightly-2026-10-15-abc', prerelease=True, published_at='2026-10-15T00:00:00Z')]
        self.assertEqual(helper.newest_versioned(items)['tag_name'], '1.2.4-Alpha')
        self.assertEqual(helper.newest_versioned(items, include_previews=False)['tag_name'], '1.2.2-Alpha')
        with self.assertRaises(ValueError):
            helper.newest_versioned([PREVIEW], include_previews=False)

    def test_promotion_changes_channel_label_without_changing_apk_link(self):
        promoted = release()
        before = helper.rewrite(README, PREVIEW, AUTOMATIC)
        after = helper.rewrite(README, promoted, promoted)
        for text in [before, after]:
            self.assertNotIn('Preview release', text)
            self.assertNotIn('Released build', text)
            self.assertNotIn('held back', text)
            self.assertIn('<sub>Auto-update channel:', text)
            self.assertIn('Download JugglucoNG 1.2.4-Alpha for Android', text)
            self.assertIn('[![Nightly builds]', text)
        phone = '[download-phone]: https://github.com/ctqvva/JugglucoNG/releases/download/1.2.4-Alpha/JugglucoNG-1.2.4-Alpha.apk'
        self.assertIn(phone, before)
        self.assertIn(phone, after)
        self.assertIn('[1.2.4-Alpha][download-update-channel]', after)

    def test_links_select_standard_phone_and_each_other_variant(self):
        links = helper.links(PREVIEW, AUTOMATIC)
        self.assertIn('[download-phone]: https://github.com/ctqvva/JugglucoNG/releases/download/1.2.4-Alpha/JugglucoNG-1.2.4-Alpha.apk\n', links)
        for key, suffix in [('wear', '-wear'), ('phone-dub', '-dub'), ('wear-dub', '-wear-dub')]:
            self.assertIn(f'[download-{key}]: https://github.com/{helper.REPO}/releases/download/1.2.4-Alpha/JugglucoNG-1.2.4-Alpha{suffix}.apk', links)
        self.assertIn('[download-update-channel]: https://github.com/ctqvva/JugglucoNG/releases/tag/1.2.2-Alpha', links)

    def test_draft_nightly_and_unpublished_never_generate_default_downloads(self):
        for changes in [{'draft': True}, {'published_at': None}, {'tag_name': 'nightly-2026-10-14-abc'}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                helper.links(release(**changes), AUTOMATIC)

    def test_missing_duplicate_or_untrusted_asset_fails_closed(self):
        for kind in ['missing', 'duplicate', 'url', 'state', 'empty']:
            value = release()
            if kind == 'missing':
                value['assets'].pop(0)
            elif kind == 'duplicate':
                value['assets'].append(value['assets'][0])
            else:
                key, wrong = {'url': ('browser_download_url', 'https://example.com/app.apk'),
                              'state': ('state', 'new'), 'empty': ('size', 0)}[kind]
                value['assets'][0][key] = wrong
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                helper.links(value, AUTOMATIC)

    def test_rewrite_preserves_unrelated_content_and_is_idempotent(self):
        updated = helper.rewrite(README, PREVIEW, AUTOMATIC)
        self.assertTrue(updated.startswith('# Original header\n'))
        self.assertTrue(updated.endswith('\nUnrelated footer\n'))
        self.assertNotIn('old links', updated)
        self.assertIn('Unrelated explanation', updated)
        self.assertNotIn('**Preview release**', updated)
        self.assertIn('[1.2.2-Alpha][download-update-channel]', updated)
        self.assertEqual(helper.rewrite(updated, PREVIEW, AUTOMATIC), updated)

    def test_bad_markers_never_truncate_readme(self):
        for value in ['plain README', README + helper.START, helper.END + helper.START]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                helper.rewrite(value, PREVIEW, AUTOMATIC)

    def test_remote_update_publishes_readme_and_version_button_at_one_current_head(self):
        with patch.dict('os.environ', {}, clear=True), patch.object(helper, 'api', side_effect=remote_responses()) as api, \
             patch.object(helper, 'ensure_ci') as ci:
            helper.update()
            ci.assert_called_once()
        self.assertEqual(api.call_args_list[2].args[0], 'contents/README.md?ref=current-head')
        self.assertEqual(api.call_args_list[3].args[0], f'contents/{helper.BUTTON_PATH}?ref=current-head')
        tree = api.call_args_list[5].args[1]
        self.assertEqual(tree['base_tree'], 'current-tree')
        files = {f['path']: f['content'] for f in tree['tree']}
        self.assertEqual(files['README.md'], helper.rewrite(README, PREVIEW, AUTOMATIC))
        self.assertEqual(files[helper.BUTTON_PATH], helper.rewrite_button(BUTTON, PREVIEW['tag_name']))
        self.assertEqual(api.call_args_list[6].args[1]['parents'], ['current-head'])
        self.assertEqual(api.call_args_list[7].args, ('git/refs/heads/main', {'sha': 'new-commit', 'force': False}))
        self.assertEqual(api.call_args_list[7].kwargs['method'], 'PATCH')

    def test_concurrent_main_update_is_rejected_without_force_or_ci_dispatch(self):
        replies = remote_responses()
        replies[-1] = CalledProcessError(1, ['gh', 'api'])
        with patch.dict('os.environ', {}, clear=True), patch.object(helper, 'api', side_effect=replies) as api, \
             patch.object(helper, 'ensure_ci') as ci:
            with self.assertRaises(CalledProcessError):
                helper.update()
            self.assertFalse(api.call_args.args[1]['force'])
            ci.assert_not_called()

    def test_local_refresh_keeps_the_visible_button_version_and_apk_links_together(self):
        newer = release('1.2.5-Alpha')
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / helper.BUTTON_PATH).parent.mkdir(parents=True)
            (root / helper.BUTTON_PATH).write_text(BUTTON)
            (root / 'README.md').write_text(README)
            with patch.object(helper, 'ROOT', root), patch.object(helper, 'api', return_value=[[AUTOMATIC, newer]]):
                helper.update(local=True)
            text = (root / 'README.md').read_text()
            self.assertIn('Download JugglucoNG 1.2.5-Alpha for Android', text)
            self.assertIn('download-android.svg?version=1.2.5-Alpha', text)
            self.assertIn('/1.2.5-Alpha/JugglucoNG-1.2.5-Alpha.apk', text)
            version = ET.parse(root / helper.BUTTON_PATH).find('.//{http://www.w3.org/2000/svg}text[@id="download-version"]')
            self.assertEqual(version.text, 'Download 1.2.5-Alpha')

    def test_invalid_button_marker_never_writes_either_file(self):
        for svg in ['<svg/>', BUTTON + BUTTON]:
            with patch.dict('os.environ', {}, clear=True), \
                 patch.object(helper, 'api', side_effect=remote_responses(button=svg)) as api:
                with self.assertRaises(ValueError):
                    helper.update()
                self.assertEqual(api.call_count, 4)

    def test_noop_and_superseded_release_do_not_commit(self):
        updated = helper.rewrite(README, PREVIEW, AUTOMATIC)
        replies = remote_responses(readme=updated)[:4]
        with patch.dict('os.environ', {}, clear=True), patch.object(helper, 'api', side_effect=replies) as api, \
             patch.object(helper, 'ensure_ci') as ci:
            helper.update()
            self.assertEqual(api.call_count, 4)
            ci.assert_called_once()
        with patch.dict('os.environ', {'TAG': '1.2.3-Alpha'}, clear=True), patch.object(helper, 'api', side_effect=replies) as api, patch.object(helper, 'ensure_ci'):
            helper.update()
            self.assertEqual(api.call_count, 4)

    def test_foreign_repository_never_reads_or_writes_github(self):
        with patch.dict('os.environ', {'GITHUB_REPOSITORY': 'someone/fork'}, clear=True), patch.object(helper, 'api') as api:
            with self.assertRaises(ValueError):
                helper.update()
            api.assert_not_called()

    def test_pending_or_passing_exact_main_ci_avoids_duplicate_dispatch(self):
        for event, status, conclusion in [('push', 'in_progress', None), ('workflow_dispatch', 'completed', 'success')]:
            run = {'head_sha': 'head', 'head_branch': 'main', 'event': event, 'status': status, 'conclusion': conclusion}
            with patch.object(helper, 'api', side_effect=[{'sha': 'head'}, {'workflow_runs': [run]}]), \
                 patch.object(helper.subprocess, 'run') as dispatch:
                helper.ensure_ci()
                dispatch.assert_not_called()

    def test_missing_or_failed_exact_main_ci_is_dispatched_for_bot_commit(self):
        failed = {'head_sha': 'head', 'head_branch': 'main', 'event': 'workflow_dispatch', 'status': 'completed', 'conclusion': 'failure'}
        for runs in [[], [failed], [dict(failed, event='pull_request', conclusion='success')], [dict(failed, head_sha='old', conclusion='success')]]:
            with patch.object(helper, 'api', side_effect=[{'sha': 'head'}, {'workflow_runs': runs}]), \
                 patch.object(helper.subprocess, 'run') as dispatch:
                helper.ensure_ci()
                dispatch.assert_called_once_with(['gh', 'workflow', 'run', 'ci.yml', '--repo', helper.REPO, '--ref', 'main'], check=True)
