#!/usr/bin/env python3
"""Refresh the README's versioned manual download links and button."""
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
BUTTON_PATH = 'docs/assets/download-android.svg'


def api(path, payload=None, paginate=False, method='PUT'):
    args = ['gh', 'api', f'repos/{REPO}/{path}']
    if paginate:
        args += ['--paginate', '--slurp']
    if payload is not None:
        args += ['--method', method, '--input', '-']
    return json.loads(subprocess.check_output(args, text=True,
                      input=json.dumps(payload) if payload is not None else None))


def newest_versioned(releases):
    candidates = [r for r in releases if not r['draft'] and r['published_at']
                  and re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', r['tag_name'])]
    if not candidates:
        raise ValueError('No published versioned release is available')
    return max(candidates, key=lambda r: r['published_at'])


def links(release):
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
    return '\n'.join(result)


def replace_region(readme, start, end, content):
    if (readme.count(start) != 1 or readme.count(end) != 1
            or readme.index(start) >= readme.index(end)):
        raise ValueError('README download link markers are missing or duplicated')
    before, _, rest = readme.partition(start)
    _, _, after = rest.partition(end)
    return before + start + '\n' + content + '\n' + end + after


def rewrite(readme, release):
    tag = release['tag_name']
    info = (f'[![Download JugglucoNG {tag} for Android]({BUTTON_PATH}?version={tag})][download-phone] '
            '[![Nightly builds](docs/assets/download-nightly.svg)]'
            f'(https://github.com/{REPO}/releases?q=nightly-&expanded=true)')
    readme = replace_region(readme, INFO_START, INFO_END, info)
    return replace_region(readme, START, END, links(release))


def rewrite_button(svg, tag):
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', tag):
        raise ValueError('Download button requires a versioned release')
    updated, count = re.subn(r'(<text id="download-version"[^>]*>)[^<]*(</text>)',
                            lambda m: m[1] + f'Download {tag}' + m[2], svg)
    if count != 1:
        raise ValueError('Download button version marker is missing or duplicated')
    return updated


def ensure_ci():
    # GITHUB_TOKEN commits do not trigger push workflows. Dispatch the existing
    # read-only main CI so nightly signing still requires this exact head to pass.
    sha = api('commits/main')['sha']
    runs = api(f'actions/workflows/ci.yml/runs?head_sha={sha}&branch=main&per_page=10')['workflow_runs']
    # Match nightly.decision(): an older success must not hide a newer failure.
    runs = [r for r in runs if r['head_sha'] == sha and r['head_branch'] == 'main'
            and r['event'] in ('push', 'workflow_dispatch')]
    if runs and (runs[0]['status'] != 'completed' or runs[0]['conclusion'] == 'success'):
        return
    subprocess.run(['gh', 'workflow', 'run', 'ci.yml', '--repo', REPO, '--ref', 'main'], check=True)


def update(local=False):
    if os.environ.get('GITHUB_REPOSITORY', REPO) != REPO:
        raise ValueError('README download updates require the official repository')
    # Actions callers share a job lock. Anchor main before release selection as
    # well, so an external writer cannot slip newer links into our base snapshot.
    head = None if local else api('git/ref/heads/main')['object']['sha']
    pages = api('releases?per_page=100', paginate=True)
    releases = [r for page in pages for r in page]
    release = newest_versioned(releases)
    if local:
        path = ROOT / 'README.md'
        button = ROOT / BUTTON_PATH
        updated = rewrite(path.read_text(), release)
        updated_button = rewrite_button(button.read_text(), release['tag_name'])
        path.write_text(updated)
        button.write_text(updated_button)
    else:
        # Read both files at one immutable head and publish one commit. A racing
        # main commit rejects the non-fast-forward ref update without overwriting it.
        current = api(f'contents/README.md?ref={head}')
        current_button = api(f'contents/{BUTTON_PATH}?ref={head}')
        readme = base64.b64decode(current['content']).decode()
        svg = base64.b64decode(current_button['content']).decode()
        updated = rewrite(readme, release)
        updated_button = rewrite_button(svg, release['tag_name'])
        if updated == readme and updated_button == svg:
            print('README download links already match the latest versioned release')
            ensure_ci()  # Retry a previous failed dispatch without another commit.
            return
        parent = api(f'git/commits/{head}')
        tree = api('git/trees', {'base_tree': parent['tree']['sha'], 'tree': [
            {'path': 'README.md', 'mode': '100644', 'type': 'blob', 'content': updated},
            {'path': BUTTON_PATH, 'mode': '100644', 'type': 'blob', 'content': updated_button},
        ]}, method='POST')
        commit = api('git/commits', {'message': f'docs: refresh downloads for {release["tag_name"]}',
                                   'tree': tree['sha'], 'parents': [head]}, method='POST')
        api('git/refs/heads/main', {'sha': commit['sha'], 'force': False}, method='PATCH')
        ensure_ci()
    print(f'Updated README downloads for {release["tag_name"]}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--local', action='store_true', help='Refresh this checkout instead of committing to GitHub main')
    update(parser.parse_args().local)
