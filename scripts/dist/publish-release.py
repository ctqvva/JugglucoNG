#!/usr/bin/env python3
"""Publish the verified canonical asset set; --bootstrap builds locally as owner."""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
import dist

REPO = 'ctqvva/JugglucoNG'
OWNER_ID = 3178592


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True, cwd=dist.ROOT))


def tracked_source(sha):
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True, cwd=dist.ROOT).strip()
    dirty = subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=all'], text=True, cwd=dist.ROOT)
    if head != sha or dirty:
        raise ValueError('Release requires clean committed source at the captured trusted main SHA')


def preflight():
    subprocess.run([sys.executable, str(dist.ROOT / 'scripts/dist/release-preflight.py')], cwd=dist.ROOT, check=True)


def bootstrap():
    if api('user')['id'] != OWNER_ID:
        raise ValueError('Local bootstrap publication is restricted to the repository owner')
    if api(f'repos/{REPO}/environments/production-signing/variables/DISTRIBUTION_LICENSE_APPROVED')['value'] != 'true':
        raise ValueError('Confirm vendor redistribution rights before enabling DISTRIBUTION_LICENSE_APPROVED')
    sha = api(f'repos/{REPO}/commits/main')['sha']
    tracked_source(sha)
    os.environ['GITHUB_REPOSITORY'] = REPO
    os.environ['GITHUB_SHA'] = sha
    os.environ['TAG'] = dist.version()[0]
    preflight()
    if set(dist.abis()) != set(dist.ARM_ABIS):
        raise ValueError('Bootstrap release requires both ARM ABIs (unset jugglucoAbi)')
    dist.inputs_check()
    # Same Gradle/signing path as Actions, with the existing local key properties.
    subprocess.run([str(dist.ROOT / 'scripts/build-dist.sh'), 'all', '--no-daemon', '--no-configuration-cache'],
                   cwd=dist.ROOT, check=True)
    dist.inputs_check()
    tracked_source(sha)


def verified_assets():
    """The same five-asset contract for regular releases and nightly prereleases."""
    directory = dist.ROOT / 'build/dist/all'
    # Reuse the existing updater format. Verify first to avoid stale/partial inputs.
    dist.verify_set(directory, 'all')
    content = subprocess.check_output([str(dist.ROOT / 'scripts/make-update-manifest.sh'), str(directory)],
                                      cwd=dist.ROOT, text=True)
    (directory / 'update-manifest.json').write_text(content)
    subprocess.run([sys.executable, str(dist.ROOT / 'scripts/dist/dist.py'), 'release-check', '--tag', dist.version()[0]],
                   cwd=dist.ROOT, check=True)
    return directory


def publish_assets(tag, sha, title, prerelease=False, notes_file=None, directory=None):
    repo = os.environ['GITHUB_REPOSITORY']
    if repo != REPO:
        raise ValueError('Release repository mismatch')
    directory = directory if directory is not None else dist.ROOT / 'build/dist/all'
    # Reserve the exact SHA atomically; an existing/racing tag fails closed.
    subprocess.run(['gh', 'api', '--method', 'POST', f'repos/{repo}/git/refs', '--input', '-'],
                   input=json.dumps({'ref': 'refs/tags/' + tag, 'sha': sha}), text=True, cwd=dist.ROOT, check=True)
    create = ['gh', 'release', 'create', tag, '--repo', repo, '--verify-tag', '--target', sha,
              '--title', title, '--draft']
    create += ['--notes-file', str(notes_file)] if notes_file else ['--generate-notes']
    subprocess.run(create, cwd=dist.ROOT, check=True)
    assets = [str(p) for p in sorted(directory.glob('*.apk'))] + [str(directory / 'update-manifest.json')]
    subprocess.run(['gh', 'release', 'upload', tag, '--repo', repo, *assets], cwd=dist.ROOT, check=True)
    flags = ['--prerelease', '--latest=false'] if prerelease else ['--prerelease=false', '--latest']
    subprocess.run(['gh', 'release', 'edit', tag, '--repo', repo, '--draft=false', *flags], cwd=dist.ROOT, check=True)
    message = f'Published https://github.com/{repo}/releases/tag/{tag}'
    print(message)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with Path(summary).open('a') as out:
            out.write(message + '\n')


def publish():
    tag = os.environ['TAG']
    repo = os.environ['GITHUB_REPOSITORY']
    sha = os.environ['GITHUB_SHA']
    if repo != REPO or tag != dist.version()[0]:
        raise ValueError('Release repository/version mismatch')
    tracked_source(sha)
    verified_assets()
    preflight()  # Repeat after build/approval, just before publishing.
    publish_assets(tag, sha, tag, prerelease=os.environ.get('PRERELEASE') == 'true')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bootstrap', action='store_true', help='Owner-only first local release for changed vendor inputs')
    parser.add_argument('--prerelease', action='store_true')
    args = parser.parse_args()
    os.chdir(dist.ROOT)
    if args.prerelease:
        os.environ['PRERELEASE'] = 'true'
    if args.bootstrap:
        bootstrap()
    publish()
    if args.bootstrap:
        print(f'Now run scripts/update-build-inputs.sh --baseline {os.environ["TAG"]} and commit the metadata PR.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as e:
        sys.exit(f'Release publication failed: {e}')
