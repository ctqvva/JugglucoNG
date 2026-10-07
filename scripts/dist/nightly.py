#!/usr/bin/env python3
"""Dated main-only nightlies; successful published tags are the change baseline."""
import argparse
from datetime import date, datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

import dist

REPO = 'ctqvva/JugglucoNG'
KEEP = 3
TAG_PATTERN = r'nightly-(\d{4}-\d{2}-\d{2})-([0-9a-f]{12})'
MARKER_PATTERN = r'<!-- juggluco-nightly:([0-9a-f]{40}) -->'


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/{path}'], text=True))


def releases():
    pages = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/releases'], text=True))
    return [r for page in pages for r in page]


def nightly_tag(sha, day):
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Nightly needs an exact main commit SHA')
    day = date.fromisoformat(str(day)).isoformat()
    return f'nightly-{day}-{sha[:12]}'


def is_nightly(release):
    tag = re.fullmatch(TAG_PATTERN, release['tag_name'])
    marker = re.search(MARKER_PATTERN, release.get('body') or '')
    if not tag or not marker or not release.get('prerelease') or tag[2] != marker[1][:12]:
        return False
    try:
        date.fromisoformat(tag[1])
    except ValueError:
        return False
    return True


def published_nightlies(items):
    return sorted([r for r in items if is_nightly(r) and not r['draft'] and r.get('published_at')],
                  key=lambda r: r['published_at'], reverse=True)


def asset_names(tag):
    match = re.fullmatch(TAG_PATTERN, tag)
    if not match:
        raise ValueError('Invalid nightly asset tag')
    date.fromisoformat(match[1])
    return {dist.filename(flavor, build_type):
            f"JugglucoNG-{tag}-{'phone' if flavor == 'mobile' else 'wear'}"
            f"{'-dub' if build_type == 'releasedub' else ''}.apk"
            for flavor, build_type in dist.TARGETS['all']}


def stage_assets(source, destination, tag):
    """Rename verified copies for publication; never modify signed APK bytes."""
    names = asset_names(tag)
    if {p.name for p in source.iterdir()} != set(names) | {'update-manifest.json'}:
        raise ValueError('Nightly source asset set mismatch')
    manifest = json.loads((source / 'update-manifest.json').read_text())
    if manifest != dist.manifest(source):
        raise ValueError('Nightly source manifest differs from verified APKs')
    destination.mkdir()
    for old, new in names.items():
        original, copied = source / old, destination / new
        shutil.copyfile(original, copied)
        if dist.digest(original.read_bytes()) != dist.digest(copied.read_bytes()):
            raise ValueError('Nightly APK bytes changed while staging')
    for artifact in manifest['artifacts']:
        artifact['file'] = names[artifact['file']]
    manifest['artifacts'].sort(key=lambda artifact: artifact['file'])
    (destination / 'update-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return destination


def source_sha(release):
    names = {a['name'] for a in release.get('assets', [])}
    expected = set(asset_names(release['tag_name']).values()) | {'update-manifest.json'}
    if names != expected:
        # Existing published nightlies used version-based names. Keep their
        # provenance/change baseline and retention valid across this migration.
        primary = [n for n in names if re.fullmatch(r'JugglucoNG-\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?\.apk', n)
                   and not n.endswith(('-wear.apk', '-dub.apk'))]
        if len(primary) != 1:
            raise ValueError('Published nightly lacks its complete APK set')
        stem = primary[0][:-4]
        expected = {stem + suffix + '.apk' for suffix in ['', '-dub', '-wear', '-wear-dub']} | {'update-manifest.json'}
    if names != expected or any(a['size'] <= 0 or a['state'] != 'uploaded' for a in release['assets']):
        raise ValueError('Published nightly has an incomplete asset set')
    # Resolve the immutable tag, never the mutable target_commitish 'main'.
    obj = api(f'git/ref/tags/{release["tag_name"]}')['object']
    marker = re.search(MARKER_PATTERN, release['body'])[1]
    if obj['type'] != 'commit' or obj['sha'] != marker:
        raise ValueError('Nightly release tag/provenance mismatch')
    return obj['sha']


def decision(sha, day, items):
    tag = nightly_tag(sha, day)
    previous = published_nightlies(items)
    last = previous[0] if previous else None
    if last:
        previous_sha = source_sha(last)
        if previous_sha == sha:
            return False, tag, last, 'Main is unchanged since the last published nightly.'
        comparison = api(f'compare/{previous_sha}...{sha}')['status']
        if comparison == 'behind':
            return False, tag, last, 'This queued snapshot predates the last published nightly.'
        if comparison != 'ahead':
            raise ValueError('Main diverged from the last nightly; inspect the history before publishing')
    if any(r['tag_name'] == tag for r in items):
        raise ValueError('Nightly tag/release already reserved, including draft; resolve the failed draft/tag before retrying today')
    refs = api('git/matching-refs/tags/' + tag)
    if any(r['ref'] == 'refs/tags/' + tag for r in refs):
        raise ValueError('Nightly tag already reserved; resolve the failed tag before retrying today')
    runs = api(f'actions/workflows/ci.yml/runs?head_sha={sha}&event=push&per_page=10')['workflow_runs']
    runs = [r for r in runs if r['head_sha'] == sha and r['head_branch'] == 'main' and r['event'] == 'push']
    if not runs or runs[0]['status'] != 'completed' or runs[0]['conclusion'] != 'success':
        return False, tag, last, 'Exact main snapshot has not passed CI yet; retry after CI or on the next schedule.'
    return True, tag, last, 'New main changes with passing CI; build the canonical four APKs.'


def trusted_context():
    if os.environ.get('GITHUB_REPOSITORY') != REPO or os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Nightlies run only from the official main branch')
    return os.environ['GITHUB_SHA']


def summary(message):
    print(message)
    path = os.environ.get('GITHUB_STEP_SUMMARY')
    if path:
        with Path(path).open('a') as out:
            out.write(message + '\n')


def notes(sha, tag, previous):
    day = re.fullmatch(TAG_PATTERN, tag)[1]
    version, code = dist.version()
    changes = (f'https://github.com/{REPO}/compare/{previous["tag_name"]}...{sha}' if previous
               else f'https://github.com/{REPO}/commit/{sha}')
    changes_label = 'Changes since the previous nightly' if previous else 'Source commit (first nightly)'
    return f'''<!-- juggluco-nightly:{sha} -->
> **Experimental nightly — for testing only.** Automatically built from merged main changes.
> May contain untested changes affecting readings, alarms or sensor connectivity.
> Do not rely on this build for treatment decisions or critical alarms.

Nightly **{day} (UTC)**, source [{sha}](https://github.com/{REPO}/commit/{sha}).
[{changes_label}]({changes}).

These production-signed APKs replace the matching existing installation and keep its data.
Back up settings/data before testing. Choose phone, phone-dub, wear or wear-dub as appropriate.
APK filenames identify the nightly date, source commit and variant.
The internal app version remains **{version}** (phone versionCode **{code}**); successive
nightlies may show the same version. Android can reject installing over a higher versionCode.

All four APKs and update-manifest.json are verified together. This prerelease never becomes
GitHub's latest release and is excluded from the normal in-app updater. The {KEEP} most
recent published nightlies are retained; regular releases are retained unchanged.
'''


def prune(items):
    # Delete only this automation's marked, dated prereleases; never historical
    # releases, arbitrary prereleases, drafts or the pinned vendor baseline.
    pinned = dist.inventory()['source']['url']
    for release in published_nightlies(items)[KEEP:]:
        tag = release['tag_name']
        if f'/download/{tag}/' in pinned:
            continue
        source_sha(release)  # Metadata/tag must still identify the same snapshot.
        subprocess.run(['gh', 'release', 'delete', tag, '--repo', REPO, '--cleanup-tag', '--yes'], check=True)


def publication_helper():
    spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-release.py'))
    publisher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(publisher)
    return publisher


def publish(tag):
    sha = trusted_context()
    match = re.fullmatch(TAG_PATTERN, tag)
    if not match or tag != nightly_tag(sha, match[1]):
        raise ValueError('Nightly tag does not match the captured source SHA/date')
    should_build, _, previous, message = decision(sha, match[1], releases())
    if not should_build:
        summary(message)
        return
    # Use exactly the same verified manifest/draft/upload publisher as releases.
    publisher = publication_helper()
    publisher.tracked_source(sha)
    source = publisher.verified_assets()
    with tempfile.TemporaryDirectory(prefix='juggluco-nightly-notes-') as tmp:
        directory = stage_assets(source, Path(tmp) / 'assets', tag)
        body = Path(tmp) / 'notes.md'
        body.write_text(notes(sha, tag, previous))
        publisher.publish_assets(tag, sha, f'Nightly {match[1]}', prerelease=True, notes_file=body, directory=directory)
    prune(releases())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'publish', 'prune'])
    args = parser.parse_args()
    if args.command == 'publish':
        publish(os.environ['TAG'])
        return
    sha = trusted_context()
    if args.command == 'prune':
        prune(releases())
        return
    should_build, tag, _, message = decision(sha, datetime.now(timezone.utc).date(), releases())
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write(f'build={str(should_build).lower()}\ntag={tag}\n')
    summary(message + f' Source: `{sha}`; planned tag: `{tag}`.')


if __name__ == '__main__':
    main()
