import copy
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE))


def module(name):
    spec = importlib.util.spec_from_file_location(name, BASE / f'{name}.py')
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


pr_build = module('pr-build')
request = module('request')
SHA = 'e' * 40
EXPECTED = {'pr_number': '547', 'source_sha': SHA, 'head_repo': 'JetFoxy/JugglucoNG'}
PR = {'state': 'open', 'merged': False, 'mergeable': True,
      'base': {'ref': 'main', 'repo': {'full_name': pr_build.REPOSITORY}},
      'head': {'sha': SHA, 'repo': {'full_name': EXPECTED['head_repo']}}}


class PrRequests(unittest.TestCase):
    def test_pr_comments_select_the_pr_and_issues_select_main(self):
        event = {'issue': {'number': 547, 'pull_request': {'url': 'ignored'}}}
        self.assertEqual(request.requested_pr('issue_comment', event), 547)
        del event['issue']['pull_request']
        self.assertIsNone(request.requested_pr('issue_comment', event))

    def test_dispatch_accepts_only_positive_number_or_empty(self):
        self.assertEqual(request.requested_pr('workflow_dispatch', {'inputs': {'pr_number': '547'}}), 547)
        self.assertIsNone(request.requested_pr('workflow_dispatch', {'inputs': {}}))
        for value in ['0', '-1', 'main', SHA, '547; echo secret', '547\n1', '../../547']:
            with self.assertRaises(ValueError):
                request.requested_pr('workflow_dispatch', {'inputs': {'pr_number': value}})


class PrSnapshot(unittest.TestCase):
    def test_open_fork_pr_is_immutable_source_not_an_arbitrary_ref(self):
        with patch.object(pr_build, 'api', return_value=PR):
            self.assertEqual(pr_build.snapshot(547), EXPECTED)

    def test_closed_wrong_base_conflict_deleted_fork_and_invalid_sha_rejected(self):
        for mutate in [lambda p: p.update(state='closed'), lambda p: p.update(merged=True),
                       lambda p: p['base'].update(ref='develop'),
                       lambda p: p['base']['repo'].update(full_name='evil/JugglucoNG'),
                       lambda p: p.update(mergeable=False), lambda p: p['head'].update(repo=None),
                       lambda p: p['head'].update(sha='main;echo secret')]:
            pr = copy.deepcopy(PR)
            mutate(pr)
            with patch.object(pr_build, 'api', return_value=pr), self.assertRaises(ValueError):
                pr_build.snapshot(547)

    def test_new_head_or_changed_repository_rejects_old_approval(self):
        with patch.object(pr_build, 'api', return_value=PR):
            pr_build.check_current(EXPECTED)
            for changed in [dict(EXPECTED, source_sha='f' * 40), dict(EXPECTED, head_repo='evil/repo')]:
                with self.assertRaisesRegex(ValueError, 'PR changed'):
                    pr_build.check_current(changed)

    def test_controller_rejects_branch_tag_and_foreign_repo(self):
        for repo, ref in [(pr_build.REPOSITORY, 'refs/heads/pr'), (pr_build.REPOSITORY, 'refs/tags/main'), ('evil/repo', 'refs/heads/main')]:
            with patch.dict(os.environ, {'GITHUB_REPOSITORY': repo, 'GITHUB_REF': ref}), self.assertRaises(ValueError):
                pr_build.trusted_context()
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': pr_build.REPOSITORY, 'GITHUB_REF': 'refs/heads/main'}):
            pr_build.trusted_context()

    def test_missing_wrong_reviewer_environment_and_rejection_fail(self):
        good = {'state': 'approved', 'user': {'id': pr_build.OWNER_ID},
                'environments': [{'name': pr_build.REVIEW_ENVIRONMENT}]}
        reviews = [[], [dict(good, state='rejected')], [dict(good, user={'id': 65550090})],
                   [dict(good, environments=[{'name': 'production-signing'}])],
                   [good, dict(good, state='rejected')]]
        with patch.dict(os.environ, {'GITHUB_RUN_ID': '123'}):
            for history in reviews:
                with patch.object(pr_build, 'api', return_value=history), self.assertRaisesRegex(ValueError, 'owner approval'):
                    pr_build.check_approval()
            with patch.object(pr_build, 'api', return_value=[good]):
                pr_build.check_approval()

    def test_no_restore_or_verification_before_owner_approval(self):
        env = {'GITHUB_REPOSITORY': pr_build.REPOSITORY, 'GITHUB_REF': 'refs/heads/main',
               'GITHUB_RUN_ID': '123', **{k.upper(): v for k, v in EXPECTED.items()}}
        for command in ['restore', 'verify']:
            with patch.dict(os.environ, env), patch.object(sys, 'argv', ['pr-build.py', command]), \
                 patch.object(pr_build, 'api', side_effect=[PR, []]), \
                 patch.object(pr_build.dist, 'restore') as restore, \
                 patch.object(pr_build, 'verify') as verify:
                with self.assertRaisesRegex(ValueError, 'owner approval'):
                    pr_build.main()
                restore.assert_not_called()
                verify.assert_not_called()


class ApprovedCheckout(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.control = self.root / 'control'
        self.source = self.root / 'source'
        self.cert = 'a' * 64
        for root in [self.control, self.source]:
            (root / 'scripts/dist').mkdir(parents=True)
            (root / 'scripts/dist/production-cert.sha256').write_text(self.cert)
        subprocess.run(['git', 'init', '-q', str(self.source)], check=True)
        subprocess.run(['git', '-C', str(self.source), 'config', 'user.name', 'Test'], check=True)
        subprocess.run(['git', '-C', str(self.source), 'config', 'user.email', 'test@example.invalid'], check=True)
        subprocess.run(['git', '-C', str(self.source), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.source), 'commit', '-qm', 'fixture'], check=True)
        self.sha = pr_build.git(self.source, 'rev-parse', 'HEAD')
        patcher = patch.object(pr_build, 'CONTROLLER_ROOT', self.control)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_wrong_checkout_and_changed_tracked_files_fail(self):
        self.assertEqual(pr_build.check_checkout(self.source, self.sha), self.cert)
        with self.assertRaisesRegex(ValueError, 'approved PR SHA'):
            pr_build.check_checkout(self.source, SHA)
        (self.source / 'scripts/dist/production-cert.sha256').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'tracked modifications'):
            pr_build.check_checkout(self.source, self.sha)

    def test_committed_pr_certificate_change_cannot_override_control_pin(self):
        cert = self.source / 'scripts/dist/production-cert.sha256'
        cert.write_text('b' * 64)
        subprocess.run(['git', '-C', str(self.source), 'commit', '-qam', 'wrong pin'], check=True)
        sha = pr_build.git(self.source, 'rev-parse', 'HEAD')
        with self.assertRaisesRegex(ValueError, 'differs from trusted main'):
            pr_build.check_checkout(self.source, sha)

    def test_independent_verifier_receives_controller_pin_and_writes_provenance(self):
        out = self.source / 'build/dist/phone'
        out.mkdir(parents=True)
        (out / 'JugglucoNG-1.2.3-Alpha.apk').write_bytes(b'fixture')
        expected = dict(EXPECTED, source_sha=self.sha)
        with patch.dict(os.environ, {'GITHUB_SHA': SHA, 'GITHUB_RUN_ID': '123'}), \
             patch.object(pr_build.dist, 'ROOT', self.control), \
             patch.object(pr_build.dist, 'version', return_value=('1.2.3-Alpha', 1023)), \
             patch.object(pr_build.dist, 'verify_set') as verify:
            pr_build.verify(self.source, expected, 'phone')
            verify.assert_called_once_with(out, 'phone', expected_certificate=self.cert)
        import json
        staged = self.source / 'build/dist/test/phone'
        info = json.loads((staged / 'build-info.json').read_text())
        self.assertEqual(info['source_sha'], self.sha)
        self.assertEqual(info['certificate_sha256'], self.cert)
        self.assertEqual(info['controller_sha'], SHA)
        self.assertEqual(info['apks'][0]['file'], f'JugglucoNG-test-pr547-{self.sha[:12]}-phone.apk')
        self.assertEqual(info['apks'][0]['sha256'], pr_build.dist.digest(b'fixture'))
        self.assertEqual((staged / info['apks'][0]['file']).read_bytes(), b'fixture')
        self.assertEqual((out / 'JugglucoNG-1.2.3-Alpha.apk').read_bytes(), b'fixture')


if __name__ == '__main__':
    unittest.main()
