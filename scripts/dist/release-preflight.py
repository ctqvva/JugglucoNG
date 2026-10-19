#!/usr/bin/env python3
"""Reject reused tags, version mismatch, and non-increasing Android versionCode."""
import json
import os
import subprocess
import sys
import urllib.request
from dist import version
from nightly import is_nightly


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))


def main():
    tag = os.environ['TAG']
    name, code = version()
    if tag != name:
        raise ValueError('Release tag must exactly equal appVersionName on main')
    repo = os.environ['GITHUB_REPOSITORY']
    # Listing tags is paginated by gh, and any existing ref (even without a release) is forbidden.
    refs = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp', f'repos/{repo}/git/matching-refs/tags/'], text=True))
    if any(ref['ref'] == 'refs/tags/' + tag for page in refs for ref in page):
        raise ValueError('Tag already exists; bump versionName and versionCode first')
    releases = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp', f'repos/{repo}/releases'], text=True))
    for release in (r for page in releases for r in page):
        if release['tag_name'] == tag:
            raise ValueError('Release already exists, including draft; resolve it before retrying')
        if release['draft']:
            continue
        # Nightlies reuse the committed app version; they do not reserve that
        # versionCode or block promotion of the same code to a regular release.
        if is_nightly(release):
            continue
        asset = next((a for a in release['assets'] if a['name'] == 'update-manifest.json'), None)
        if asset:
            with urllib.request.urlopen(asset['browser_download_url'], timeout=60) as response:
                previous = json.load(response)
            if code <= int(previous['versionCode']):
                raise ValueError('appVersionCode must exceed every published manifest versionCode')
    print(f'Release preflight passed: {tag}, phone versionCode {code}, wear {code + 1000000}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as e:
        sys.exit(f'Release preflight failed: {e}')
