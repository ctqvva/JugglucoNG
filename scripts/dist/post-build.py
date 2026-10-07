#!/usr/bin/env python3
"""Reply to an accepted build request with its verified Actions artifact link.

Run only in the trusted controller's separate notification job; no PR checkout,
artifact execution, signing material, or contents-write permission is needed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

import request

REPOSITORY = 'ctqvva/JugglucoNG'


def api(path, method=None, payload=None, paginate=False):
    args = ['gh', 'api', f'repos/{REPOSITORY}/{path}']
    if paginate:
        args += ['--paginate', '--slurp']
    if method:
        args += ['--method', method, '--input', '-']
    return json.loads(subprocess.check_output(args, input=json.dumps(payload) if method else None, text=True))


def reply(event):
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY or os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Build replies require the official main controller')
    if os.environ.get('GITHUB_EVENT_NAME') != 'issue_comment':
        raise ValueError('Build replies require an issue/PR comment request')
    target = request.requested_target('issue_comment', event)
    if target is None or target != os.environ['TARGET']:
        raise ValueError('Not an accepted build request')
    issue, comment, run = str(event['issue']['number']), str(event['comment']['id']), os.environ['GITHUB_RUN_ID']
    if not all(re.fullmatch(r'[1-9][0-9]*', n) for n in [issue, comment, run]):
        raise ValueError('Invalid request/run identity')
    pr = request.requested_pr('issue_comment', event)
    sha = os.environ['SOURCE_SHA'] if pr else os.environ['GITHUB_SHA']
    head_repo = os.environ['HEAD_REPO'] if pr else REPOSITORY
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', head_repo):
        raise ValueError('Invalid build source identity')
    source = f'pr{pr}' if pr else 'main'
    name = f'distribution-{source}-{target}-{sha}'
    pages = api(f'actions/runs/{run}/artifacts?per_page=100', paginate=True)
    artifacts = [a for page in pages for a in page['artifacts'] if a['name'] == name and not a['expired']]
    if len(artifacts) != 1:
        raise ValueError('Expected exactly one unexpired verified build artifact')
    artifact = artifacts[0]
    if type(artifact['id']) is not int or artifact['id'] <= 0:
        raise ValueError('Invalid artifact identity')
    url = f'https://github.com/{REPOSITORY}/actions/runs/{run}'
    marker = f'<!-- build-dist-reply:{comment}:{run} -->'
    body = (f'{marker}\n'
            f'Your `/build-dist {target}` test build is ready.\n\n'
            f'**[Download the APK ZIP]({url}/artifacts/{artifact["id"]})** '
            f'(GitHub sign-in required; expires {artifact["expires_at"]}). '
            f'[Build logs]({url}).\n\n'
            f'Source: {"PR #" + str(pr) if pr else "main"} at '
            f'[`{sha[:12]}`](https://github.com/{head_repo}/commit/{sha}). '
            'The ZIP contains the requested production-signed APKs and `build-info.json`.\n\n'
            '**Test build:** updates the matching existing app and retains its data. '
            'Back up settings/data first; Android may reject a lower versionCode. '
            'Sensor/device behavior still needs testing. This is not an updater release.')
    return issue, marker, body


def post(issue, marker, body):
    pages = api(f'issues/{issue}/comments?per_page=100', paginate=True)
    # Reruns update our own reply; a user cannot plant a marker to redirect edits.
    existing = [c for page in pages for c in page
                if c['user']['login'] == 'github-actions[bot]' and c['body'].startswith(marker + '\n')]
    if existing:
        api(f'issues/comments/{existing[-1]["id"]}', 'PATCH', {'body': body})
    else:
        api(f'issues/{issue}/comments', 'POST', {'body': body})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true', help='Validate the real request/artifact and print the reply without posting')
    args = parser.parse_args()
    issue, marker, body = reply(json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text()))
    if args.dry_run:
        print(body)
    else:
        post(issue, marker, body)


if __name__ == '__main__':
    main()
