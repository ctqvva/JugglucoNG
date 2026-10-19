import copy
import importlib.util
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))
spec = importlib.util.spec_from_file_location('post_build', BASE / 'post-build.py')
poster = importlib.util.module_from_spec(spec)
spec.loader.exec_module(poster)

SHA = 'a' * 40
EVENT = {'issue': {'number': 572, 'pull_request': {'url': 'https://api.github.com/repos/ctqvva/JugglucoNG/pulls/572'}},
         'comment': {'id': 123, 'user': {'id': 65550090}, 'body': '/build-dist phone'}}
ENV = {'GITHUB_REPOSITORY': poster.REPOSITORY, 'GITHUB_REF': 'refs/heads/main',
       'GITHUB_EVENT_NAME': 'issue_comment', 'GITHUB_RUN_ID': '456',
       'GITHUB_SHA': 'b' * 40, 'SOURCE_SHA': SHA, 'HEAD_REPO': 'JetFoxy/JugglucoNG', 'TARGET': 'phone'}
ARTIFACT = {'id': 789, 'name': f'distribution-pr572-phone-{SHA}', 'expired': False,
            'expires_at': '2026-10-12T21:00:00Z'}


class BuildReplies(unittest.TestCase):
    def test_pr_reply_uses_exact_source_and_artifact_not_controller_main(self):
        with patch.dict(os.environ, ENV, clear=True), patch.object(poster, 'api', return_value=[{'artifacts': [ARTIFACT]}]):
            issue, marker, body = poster.reply(EVENT)
        self.assertEqual(issue, '572')
        self.assertEqual(marker, '<!-- build-dist-reply:123:456 -->')
        self.assertIn('/actions/runs/456/artifacts/789', body)
        self.assertIn('JetFoxy/JugglucoNG/commit/' + SHA, body)
        self.assertIn('expires 2026-10-12T21:00:00Z', body)
        self.assertIn('GitHub sign-in required', body)
        self.assertIn('**Test build:**', body)

    def test_issue_reply_selects_main_and_finds_artifact_after_first_page(self):
        event = copy.deepcopy(EVENT)
        del event['issue']['pull_request']
        artifact = dict(ARTIFACT, name=f'distribution-main-phone-{ENV["GITHUB_SHA"]}')
        with patch.dict(os.environ, ENV, clear=True), patch.object(poster, 'api', return_value=[{'artifacts': []}, {'artifacts': [artifact]}]):
            _, _, body = poster.reply(event)
        self.assertIn('Source: main', body)
        self.assertIn(poster.REPOSITORY + '/commit/' + ENV['GITHUB_SHA'], body)

    def test_missing_expired_duplicate_or_wrong_source_artifact_never_posts(self):
        for artifacts in [[], [dict(ARTIFACT, expired=True)], [ARTIFACT, ARTIFACT],
                          [dict(ARTIFACT, name=ARTIFACT['name'].replace(SHA, 'c' * 40))]]:
            with patch.dict(os.environ, ENV, clear=True), patch.object(poster, 'api', return_value=[{'artifacts': artifacts}]):
                with self.assertRaisesRegex(ValueError, 'exactly one unexpired'):
                    poster.reply(EVENT)

    def test_wrong_context_or_untrusted_command_rejected_before_api_access(self):
        for changes in [{'GITHUB_REPOSITORY': 'evil/repo'}, {'GITHUB_REF': 'refs/heads/pr'},
                        {'GITHUB_EVENT_NAME': 'pull_request'}, {'TARGET': 'all'}]:
            with patch.dict(os.environ, dict(ENV, **changes), clear=True), patch.object(poster, 'api') as api:
                with self.assertRaises(ValueError):
                    poster.reply(EVENT)
                api.assert_not_called()
        for edit in [lambda e: e['comment']['user'].update(id=1),
                     lambda e: e['comment'].update(body='/build-dist phone; echo leak')]:
            event = copy.deepcopy(EVENT)
            edit(event)
            with patch.dict(os.environ, ENV, clear=True), patch.object(poster, 'api') as api:
                with self.assertRaises(ValueError):
                    poster.reply(event)
                api.assert_not_called()

    def test_rerun_updates_only_bot_reply_and_paginates_comments(self):
        marker = '<!-- build-dist-reply:123:456 -->'
        forged = {'id': 1, 'user': {'login': 'contributor'}, 'body': marker + '\nforged'}
        bot = {'id': 2, 'user': {'login': 'github-actions[bot]'}, 'body': marker + '\nold'}
        with patch.object(poster, 'api', side_effect=[[[forged], [bot]], {}]) as api:
            poster.post('572', marker, 'updated')
            self.assertEqual(api.call_args.args, ('issues/comments/2', 'PATCH', {'body': 'updated'}))
        with patch.object(poster, 'api', side_effect=[[[forged]], {}]) as api:
            poster.post('572', marker, 'new')
            self.assertEqual(api.call_args.args, ('issues/572/comments', 'POST', {'body': 'new'}))

    def test_comment_body_passed_as_json_stdin_without_shell(self):
        body = 'literal `$(echo nope)`\nsecond line'
        with patch.object(poster.subprocess, 'check_output', return_value='{}') as call:
            poster.api('issues/572/comments', 'POST', {'body': body})
        import json
        self.assertEqual(json.loads(call.call_args.kwargs['input']), {'body': body})
        self.assertNotIn('shell', call.call_args.kwargs)


if __name__ == '__main__':
    unittest.main()
