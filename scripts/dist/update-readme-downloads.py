#!/usr/bin/env python3
"""Refresh only README download links from the latest published regular release."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REPO = 'ctqvva/JugglucoNG'
START = '<!-- download-links:start -->'
END = '<!-- download-links:end -->'


def api(path, payload=None):
    args = ['gh', 'api', f'repos/{REPO}/{path}']
    if payload is not None:
        args += ['--method', 'PUT', '--input', '-']
    return json.loads(subprocess.check_output(args, text=True,
                      input=json.dumps(payload) if payload is not None else None))


def links(release):
    tag = release['tag_name']
    if (release['draft'] or release['prerelease'] or not release['published_at']
            or not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', tag)):
        raise ValueError('Downloads require a published regular versioned release')
    result = []
    for key, suffix in [('phone', ''), ('wear', '-wear'), ('phone-dub', '-dub'), ('wear-dub', '-wear-dub')]:
        name = f'JugglucoNG-{tag}{suffix}.apk'
        url = f'https://github.com/{REPO}/releases/download/{tag}/{name}'
        matches = [a for a in release['assets'] if a['name'] == name]
        if (len(matches) != 1 or matches[0]['state'] != 'uploaded' or matches[0]['size'] <= 0
                or matches[0]['browser_download_url'] != url):
            raise ValueError(f'Missing or invalid download asset: {name}')
        result.append(f'[download-{key}]: {url}')
    result.append(f'[download-release]: https://github.com/{REPO}/releases/tag/{tag}')
    # Static version badge and links advance together, with no latest-tag cache race.
    result.append('[download-version-badge]: https://img.shields.io/badge/Latest_regular_release-'
                  + tag.replace('-', '--') + '-blue')
    return '\n'.join(result)


def rewrite(readme, release):
    if (readme.count(START) != 1 or readme.count(END) != 1
            or readme.index(START) >= readme.index(END)):
        raise ValueError('README download link markers are missing or duplicated')
    before, _, rest = readme.partition(START)
    _, _, after = rest.partition(END)
    return before + START + '\n' + links(release) + '\n' + END + after


def ensure_ci():
    # GITHUB_TOKEN commits do not trigger push workflows. Dispatch the existing
    # read-only main CI so nightly signing still requires this exact head to pass.
    sha = api('commits/main')['sha']
    runs = api(f'actions/workflows/ci.yml/runs?head_sha={sha}&branch=main&per_page=10')['workflow_runs']
    if any(r['head_sha'] == sha and r['head_branch'] == 'main'
           and r['event'] in ('push', 'workflow_dispatch')
           and (r['status'] != 'completed' or r['conclusion'] == 'success') for r in runs):
        return
    subprocess.run(['gh', 'workflow', 'run', 'ci.yml', '--repo', REPO, '--ref', 'main'], check=True)


def update(local=False):
    if os.environ.get('GITHUB_REPOSITORY', REPO) != REPO:
        raise ValueError('README download updates require the official repository')
    release = api('releases/latest')
    if os.environ.get('TAG') and release['tag_name'] != os.environ['TAG']:
        print('Skipping README update: this release is no longer the latest regular release')
        return
    if local:
        path = ROOT / 'README.md'
        path.write_text(rewrite(path.read_text(), release))
    else:
        current = api('contents/README.md?ref=main')
        readme = base64.b64decode(current['content']).decode()
        updated = rewrite(readme, release)
        if updated == readme:
            print('README download links already match the latest regular release')
            ensure_ci()  # Retry a previous failed dispatch without another commit.
            return
        # Read the current main file and use its SHA: concurrent edits fail safely.
        api('contents/README.md', {'branch': 'main', 'sha': current['sha'],
            'message': f'docs: refresh downloads for {release["tag_name"]}',
            'content': base64.b64encode(updated.encode()).decode()})
        ensure_ci()
    print(f'Updated README downloads for {release["tag_name"]}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--local', action='store_true', help='Refresh this checkout instead of committing to GitHub main')
    update(parser.parse_args().local)
