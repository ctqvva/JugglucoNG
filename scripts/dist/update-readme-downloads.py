#!/usr/bin/env python3
"""Refresh README manual downloads and show the separate in-app update channel."""
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
INFO_START = '<!-- download-info:start -->'
INFO_END = '<!-- download-info:end -->'


def api(path, payload=None, paginate=False):
    args = ['gh', 'api', f'repos/{REPO}/{path}']
    if paginate:
        args += ['--paginate', '--slurp']
    if payload is not None:
        args += ['--method', 'PUT', '--input', '-']
    return json.loads(subprocess.check_output(args, text=True,
                      input=json.dumps(payload) if payload is not None else None))


def newest_versioned(releases, include_previews=True):
    candidates = [r for r in releases if not r['draft'] and r['published_at']
                  and (include_previews or not r['prerelease'])
                  and re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', r['tag_name'])]
    if not candidates:
        raise ValueError('No published versioned release is available')
    return max(candidates, key=lambda r: r['published_at'])


def links(release, automatic):
    tag = release['tag_name']
    if (release['draft'] or not release['published_at']
            or not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', tag)):
        raise ValueError('Downloads require a published versioned release')
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
    result.append(f'[download-update-channel]: https://github.com/{REPO}/releases/tag/{automatic["tag_name"]}')
    return '\n'.join(result)


def replace_region(readme, start, end, content):
    if (readme.count(start) != 1 or readme.count(end) != 1
            or readme.index(start) >= readme.index(end)):
        raise ValueError('README download link markers are missing or duplicated')
    before, _, rest = readme.partition(start)
    _, _, after = rest.partition(end)
    return before + start + '\n' + content + '\n' + end + after


def rewrite(readme, release, automatic):
    info = f'### [{release["tag_name"]}][download-release]\n\n'
    if release['prerelease']:
        info += '**Preview release** · Manual installation; held back from the in-app updater.\n\n'
    else:
        info += '**Released build** · Available for manual installation and through the in-app updater.\n\n'
    info += f'<sub>In-app update channel: [{automatic["tag_name"]}][download-update-channel].</sub>'
    readme = replace_region(readme, INFO_START, INFO_END, info)
    return replace_region(readme, START, END, links(release, automatic))


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
    pages = api('releases?per_page=100', paginate=True)
    releases = [r for page in pages for r in page]
    release = newest_versioned(releases)
    automatic = newest_versioned(releases, include_previews=False)
    if local:
        path = ROOT / 'README.md'
        path.write_text(rewrite(path.read_text(), release, automatic))
    else:
        current = api('contents/README.md?ref=main')
        readme = base64.b64decode(current['content']).decode()
        updated = rewrite(readme, release, automatic)
        if updated == readme:
            print('README download links already match the latest versioned release')
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
