#!/usr/bin/env python3
"""Parse event data as data; comments never supply shell code or a checkout ref."""
import json
import os
from pathlib import Path
import re

TARGETS = {'phone', 'phone-all', 'wear', 'wear-all', 'all'}
# Immutable GitHub account IDs: ctqvva, JetFoxy. Account renames do not transfer trust.
REQUESTERS = {3178592, 65550090}


def requested_target(event_name, event):
    if event_name == 'workflow_dispatch':
        target = event.get('inputs', {}).get('target', 'phone')
        return target if target in TARGETS else None
    if event_name != 'issue_comment':
        return None
    comment = event.get('comment', {})
    if comment.get('user', {}).get('id') not in REQUESTERS:
        return None
    match = re.fullmatch(r'/build-dist (phone|phone-all|wear|wear-all|all)', comment.get('body', '').strip())
    return match[1] if match else None


def requested_pr(event_name, event):
    """Only a PR number is accepted; never a caller-selected branch/ref."""
    if event_name == 'workflow_dispatch':
        value = event.get('inputs', {}).get('pr_number', '').strip()
    elif event_name == 'issue_comment' and event.get('issue', {}).get('pull_request'):
        value = str(event['issue']['number'])
    else:
        value = ''
    if not value:
        return None
    if not re.fullmatch(r'[1-9][0-9]*', value):
        raise ValueError('pr_number must be a positive PR number, not a source ref')
    return int(value)


if __name__ == '__main__':
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    target = requested_target(os.environ['GITHUB_EVENT_NAME'], event)
    print('accepted=' + ('true' if target else 'false'))
    if target:
        print('target=' + target)
        pr = requested_pr(os.environ['GITHUB_EVENT_NAME'], event)
        print('pr_number=' + (str(pr) if pr else ''))
