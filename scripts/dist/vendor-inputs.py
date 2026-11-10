#!/usr/bin/env python3
"""Regenerate vendor metadata, audit baseline availability, or pin a published APK."""
import argparse
import json
import shutil
from pathlib import Path
import sys
import tempfile
import urllib.request
import zipfile
import dist

REPO = 'ctqvva/JugglucoNG'


def write_inventory(data):
    path = dist.ROOT / 'scripts/dist/build-inputs.json'
    content = json.dumps(data, indent=2) + '\n'
    # Replace only metadata, atomically; never touch local library bytes.
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as out:
        tmp = Path(out.name)
        out.write(content)
    tmp.replace(path)


def regenerate(apk=None):
    previous = dist.inventory()
    files = []
    for abi in dist.ARM_ABIS:
        directory = dist.ROOT / f'Common/src/main/jniLibs/{abi}'
        if not directory.is_dir():
            raise ValueError(f'Restore the complete JNI directory before updating: {directory}')
        for path in sorted(directory.glob('*.so')):
            relative = str(path.relative_to(dist.ROOT))
            dist.input_entry(relative)
            content = path.read_bytes()
            if not content:
                raise ValueError(f'Empty vendor library: {relative}')
            files.append({'path': relative, 'size': len(content), 'sha256': dist.digest(content)})
    if not files:
        raise ValueError('No vendor libraries found; refusing to erase an inventory from a clean checkout')
    files.sort(key=lambda f: f['path'])
    current_paths = {f['path'] for f in files}
    removed = sorted(({f['path'] for f in previous['files']} | set(previous.get('removedFiles', []))) - current_paths)
    data = {'schema': 1, 'source': previous['source'], 'files': files}
    if removed:
        data['removedFiles'] = removed
    with dist.source_archive(data, apk) as archive:
        missing = dist.unavailable_inputs(data, archive)
    if missing:
        data['bootstrapRequired'] = True
    write_inventory(data)
    print(f'Inventoried {len(files)} vendor files, {sum(f["size"] for f in files)} bytes; {len(removed)} removed paths')
    if missing:
        print('Local bootstrap release required for these new/changed inputs:\n' + '\n'.join(missing))
    else:
        print('Pinned published APK supplies every input; Actions restoration remains available')


def audit_and_restore(apk=None):
    data = dist.inventory()
    with dist.source_archive(data, apk) as archive:
        missing = dist.unavailable_inputs(data, archive)
        if bool(missing) != data.get('bootstrapRequired', False):
            raise ValueError('Bootstrap state disagrees with pinned APK; regenerate with scripts/update-build-inputs.sh')
        for path in data.get('removedFiles', []):
            dist.input_entry(path)
            if path in {f['path'] for f in data['files']}:
                raise ValueError('Removed vendor input is also active')
        if missing:
            print('Verified bootstrap state: pinned APK cannot supply these inputs:\n' + '\n'.join(missing))
            print('Ordinary CI needs no new vendor bytes. Signed Actions builds remain blocked until rebaseline.')
            return
        # Reuse the already downloaded, hash-checked source rather than fetching twice.
        dist.restore(Path(archive.filename))


def rebaseline(tag):
    if tag != dist.version()[0]:
        raise ValueError('Baseline tag must equal current appVersionName; run after publishing that release')
    url = f'https://github.com/{REPO}/releases/download/{tag}/JugglucoNG-{tag}.apk'
    request = urllib.request.Request(f'https://api.github.com/repos/{REPO}/releases/tags/{tag}',
                                     headers={'User-Agent': 'JugglucoNG-build'})
    with urllib.request.urlopen(request, timeout=60) as response:
        release = json.load(response)
    if release['draft'] or not release['published_at'] or not any(a['browser_download_url'] == url for a in release['assets']):
        raise ValueError('Baseline must be a published release with its canonical phone APK')
    data = dist.inventory()
    with tempfile.TemporaryDirectory(prefix='juggluco-baseline-') as tmp:
        apk = Path(tmp) / f'JugglucoNG-{tag}.apk'
        with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'JugglucoNG-build'}), timeout=120) as src, apk.open('wb') as out:
            shutil.copyfileobj(src, out)
        # Includes production certificate, version/package, both ABIs, all desired
        # hashes and absence of removed inputs. An unrelated APK cannot become baseline.
        if set(dist.abis()) != set(dist.ARM_ABIS):
            raise ValueError('Rebaseline requires both ARM ABIs (unset jugglucoAbi)')
        dist.verify_apk(apk, 'mobile', 'release')
        data['source'] = {'url': url, 'sha256': dist.digest(apk.read_bytes())}
    data.pop('bootstrapRequired', None)
    write_inventory(data)
    print('Pinned verified published baseline; commit scripts/dist/build-inputs.json in an owner-reviewed PR')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', help='Published release tag to pin after the first local release')
    parser.add_argument('--apk', type=Path, help='Existing pinned APK for offline inventory regeneration/audit')
    parser.add_argument('--ci', action='store_true', help='Audit bootstrap state; restore when the baseline supplies all inputs')
    args = parser.parse_args()
    if args.baseline:
        if args.apk or args.ci:
            parser.error('--baseline cannot be combined with --apk or --ci')
        rebaseline(args.baseline)
    elif args.ci:
        audit_and_restore(args.apk)
    else:
        regenerate(args.apk)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as e:
        sys.exit(f'Vendor input update failed: {e}')
