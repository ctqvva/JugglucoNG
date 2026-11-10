#!/usr/bin/env python3
"""Trusted-main controller for explicitly owner-approved PR distribution builds.

Import this helper and dist.py from the controller, never from the PR checkout.
Approval covers the entire exact source snapshot, including its build logic.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

import dist

REPOSITORY = 'ctqvva/JugglucoNG'
OWNER_ID = 3178592
REVIEW_ENVIRONMENT = 'production-pr-review'
CONTROLLER_ROOT = dist.ROOT


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPOSITORY}/{path}'], text=True))


def trusted_context():
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY or os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('PR signing controller must run from the official main branch')


def snapshot(number):
    if not re.fullmatch(r'[1-9][0-9]*', str(number)):
        raise ValueError('Invalid PR number')
    pr = api(f'pulls/{number}')
    if (pr['state'] != 'open' or pr.get('merged') or pr['base']['ref'] != 'main'
            or pr['base']['repo']['full_name'] != REPOSITORY):
        raise ValueError('Only an open PR targeting official main can request signing')
    if pr.get('mergeable') is False:
        raise ValueError('PR has merge conflicts; rebase before requesting a test APK')
    sha = pr['head']['sha']
    repo = (pr['head'].get('repo') or {}).get('full_name', '')
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Invalid PR source identity')
    return {'pr_number': str(number), 'source_sha': sha, 'head_repo': repo}


def expected_snapshot():
    return {name: os.environ[name.upper()] for name in ['pr_number', 'source_sha', 'head_repo']}


def check_current(expected):
    if snapshot(expected['pr_number']) != expected:
        raise ValueError('PR changed since this request. Start a new run and approve its new snapshot.')


def check_approval():
    run = os.environ['GITHUB_RUN_ID']
    if not run.isdecimal():
        raise ValueError('Invalid workflow run ID')
    reviews = api(f'actions/runs/{run}/approvals')
    relevant = [r for r in reviews if any(e['name'] == REVIEW_ENVIRONMENT for e in r['environments'])]
    # The job dependency enforces the environment gate. Also reject missing,
    # bypassed or non-owner review history before executing any approved source.
    if not relevant or any(r['state'] != 'approved' or r['user']['id'] != OWNER_ID for r in relevant):
        raise ValueError('This run needs explicit owner approval of production-pr-review')


def git(source, *args):
    return subprocess.check_output(['git', '-C', str(source), *args], text=True).strip()


def check_checkout(source, sha):
    if git(source, 'rev-parse', 'HEAD') != sha:
        raise ValueError('Checkout does not match the approved PR SHA')
    if git(source, 'status', '--porcelain', '--untracked-files=no'):
        raise ValueError('Approved source has tracked modifications')
    # A PR cannot replace the public certificate pin and bless a test-signed APK.
    cert = (CONTROLLER_ROOT / 'scripts/dist/production-cert.sha256').read_text().strip()
    if (source / 'scripts/dist/production-cert.sha256').read_text().strip() != cert:
        raise ValueError('PR production certificate differs from trusted main')
    return cert


def verify(source, expected, target):
    cert = check_checkout(source, expected['source_sha'])
    dist.ROOT = source
    if dist.abis() != dist.ARM_ABIS:
        raise ValueError('Remote PR APKs require both ARM ABIs')
    out = dist.stage_test_assets(source / 'build/dist' / target, target, expected['source_sha'],
                                 pr_number=expected['pr_number'], expected_certificate=cert)
    info = dict(expected, target=target, controller_sha=os.environ['GITHUB_SHA'],
                certificate_sha256=cert, abis=dist.ARM_ABIS, source_kind='owner-approved-pr-head',
                run_url=f'https://github.com/{REPOSITORY}/actions/runs/{os.environ["GITHUB_RUN_ID"]}')
    dist.write_build_info(out, info)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['capture', 'check', 'restore', 'verify'])
    args = parser.parse_args()
    trusted_context()
    if args.command == 'capture':
        expected = snapshot(os.environ['PR_NUMBER'])
        for k, v in expected.items():
            print(f'{k}={v}')
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(f'PR **#{expected["pr_number"]}** test source: '
                          f'[{expected["source_sha"]}](https://github.com/{expected["head_repo"]}/commit/{expected["source_sha"]}).\n\n'
                          'The owner must review this entire snapshot, including build scripts and dependencies, '
                          'before approving **production-pr-review**. This builds the PR tip, not a merge with newer main.\n')
        return
    expected = expected_snapshot()
    check_current(expected)
    check_approval()
    if args.command == 'check':
        return
    source = Path(os.environ['SOURCE_DIR']).resolve()
    check_checkout(source, expected['source_sha'])
    if args.command == 'restore':
        dist.ROOT = source
        dist.restore()
    else:
        target = os.environ['TARGET']
        if target not in dist.TARGETS:
            raise ValueError('Invalid distribution target')
        verify(source, expected, target)


if __name__ == '__main__':
    main()
