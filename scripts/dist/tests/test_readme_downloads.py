import base64
import importlib.util
from pathlib import Path
import unittest
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


README = '# Original header\n' + helper.START + '\nold links\n' + helper.END + '\nUnrelated footer\n'


class Downloads(unittest.TestCase):
    def test_links_select_standard_phone_and_each_other_variant(self):
        links = helper.links(release())
        self.assertIn('[download-phone]: https://github.com/ctqvva/JugglucoNG/releases/download/1.2.4-Alpha/JugglucoNG-1.2.4-Alpha.apk\n', links)
        for key, suffix in [('wear', '-wear'), ('phone-dub', '-dub'), ('wear-dub', '-wear-dub')]:
            self.assertIn(f'[download-{key}]: https://github.com/{helper.REPO}/releases/download/1.2.4-Alpha/JugglucoNG-1.2.4-Alpha{suffix}.apk', links)
        self.assertIn('Latest_regular_release-1.2.4--Alpha-blue', links)

    def test_prerelease_draft_nightly_and_unpublished_never_generate_default_downloads(self):
        for changes in [{'prerelease': True}, {'draft': True}, {'published_at': None}, {'tag_name': 'nightly-2026-10-14-abc'}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                helper.links(release(**changes))

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
                helper.links(value)

    def test_rewrite_preserves_unrelated_content_and_is_idempotent(self):
        updated = helper.rewrite(README, release())
        self.assertTrue(updated.startswith('# Original header\n'))
        self.assertTrue(updated.endswith('\nUnrelated footer\n'))
        self.assertNotIn('old links', updated)
        self.assertEqual(helper.rewrite(updated, release()), updated)

    def test_bad_markers_never_truncate_readme(self):
        for value in ['plain README', README + helper.START, helper.END + helper.START]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                helper.rewrite(value, release())

    def test_remote_update_reads_current_main_and_uses_its_sha(self):
        current = {'sha': 'current-file-sha', 'content': base64.b64encode(README.encode()).decode()}
        with patch.dict('os.environ', {}, clear=True), patch.object(helper, 'api', side_effect=[release(), current, {}]) as api, \
             patch.object(helper, 'ensure_ci') as ci:
            helper.update()
            ci.assert_called_once()
        self.assertEqual(api.call_args_list[1].args[0], 'contents/README.md?ref=main')
        payload = api.call_args_list[2].args[1]
        self.assertEqual(payload['sha'], 'current-file-sha')
        self.assertEqual(payload['branch'], 'main')
        self.assertEqual(base64.b64decode(payload['content']).decode(), helper.rewrite(README, release()))

    def test_noop_and_superseded_release_do_not_commit(self):
        updated = helper.rewrite(README, release())
        current = {'sha': 'sha', 'content': base64.b64encode(updated.encode()).decode()}
        with patch.dict('os.environ', {}, clear=True), patch.object(helper, 'api', side_effect=[release(), current]) as api, \
             patch.object(helper, 'ensure_ci') as ci:
            helper.update()
            self.assertEqual(api.call_count, 2)
            ci.assert_called_once()
        with patch.dict('os.environ', {'TAG': '1.2.3-Alpha'}, clear=True), patch.object(helper, 'api', return_value=release()) as api:
            helper.update()
            self.assertEqual(api.call_count, 1)

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
