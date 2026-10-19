#!/usr/bin/env python3
"""Distribution contract shared by local builds, Actions and release validation."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TARGETS = {
    'phone': [('mobile', 'release')],
    'phone-all': [('mobile', 'release'), ('mobile', 'releasedub')],
    'wear': [('wear', 'release')],
    'wear-all': [('wear', 'release'), ('wear', 'releasedub')],
    'all': [('mobile', 'release'), ('mobile', 'releasedub'), ('wear', 'release'), ('wear', 'releasedub')],
}
ARM_ABIS = ['arm64-v8a', 'armeabi-v7a']


def digest(data):
    return hashlib.sha256(data).hexdigest()


def version():
    source = (ROOT / 'Common/build.gradle').read_text()
    name = re.search(r"^\s*appVersionName\s*=\s*'([^']+)'", source, re.M)[1]
    code = int(re.search(r'^\s*appVersionCode\s*=\s*(\d+)', source, re.M)[1])
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', name):
        raise ValueError('Invalid appVersionName')
    return name, code


def filename(flavor, build_type):
    name, _ = version()
    return f"JugglucoNG-{name}{'-wear' if flavor == 'wear' else ''}{'-dub' if build_type == 'releasedub' else ''}.apk"


def abis():
    value = os.environ.get('ORG_GRADLE_PROJECT_jugglucoAbi', ','.join(ARM_ABIS))
    result = value.split(',')
    if not result or len(set(result)) != len(result) or any(a not in ARM_ABIS for a in result):
        raise ValueError('Distribution supports only arm64-v8a and armeabi-v7a')
    return result


def inventory():
    data = json.loads((ROOT / 'scripts/dist/build-inputs.json').read_text())
    if (data['schema'] != 1 or not isinstance(data['source']['url'], str)
            or not re.fullmatch(r'[0-9a-f]{64}', data['source']['sha256'])
            or type(data.get('bootstrapRequired', False)) is not bool):
        raise ValueError('Invalid vendor inventory metadata')
    paths = set()
    for spec in data['files']:
        input_entry(spec['path'])
        if (spec['path'] in paths or type(spec['size']) is not int or spec['size'] <= 0
                or not re.fullmatch(r'[0-9a-f]{64}', spec['sha256'])):
            raise ValueError('Invalid or duplicate vendor input specification')
        paths.add(spec['path'])
    removed = data.get('removedFiles', [])
    if len(set(removed)) != len(removed) or paths.intersection(removed):
        raise ValueError('Invalid removed vendor inputs')
    for path in removed:
        input_entry(path)
    return data


def inputs_check():
    data = inventory()
    expected = {f['path']: f for f in data['files'] if Path(f['path']).parent.name in abis()}
    for path, spec in expected.items():
        p = ROOT / path
        if not p.is_file() or p.stat().st_size != spec['size'] or digest(p.read_bytes()) != spec['sha256']:
            raise ValueError(f'Missing or changed distribution input: {path}; run scripts/restore-build-inputs.sh')
    # Refuse silent addition of unreviewed JNI inputs to a distribution.
    for source in ['main', 'mobile', 'wear', 'wearSi']:
        for abi in abis():
            for p in (ROOT / f'Common/src/{source}/jniLibs/{abi}').glob('*.so'):
                if str(p.relative_to(ROOT)) not in expected:
                    raise ValueError(f'Uninventoried distribution input: {p.relative_to(ROOT)}')
    print(f'Verified {len(expected)} private JNI inputs', file=sys.stderr)


def input_entry(path):
    path = Path(path)
    if (path.parts[:4] != ('Common', 'src', 'main', 'jniLibs') or len(path.parts) != 6
            or path.parts[4] not in ARM_ABIS or not re.fullmatch(r'lib[A-Za-z0-9_+.-]+\.so', path.parts[5])
            or path.name in {'libg.so', 'libnative.so'}):
        raise ValueError(f'Invalid vendor input path: {path}')
    return 'lib/' + '/'.join(path.parts[4:])


@contextmanager
def source_archive(data, apk=None):
    with tempfile.TemporaryDirectory(prefix='juggluco-inputs-') as tmp:
        archive = Path(apk) if apk else Path(tmp) / 'source.apk'
        if not apk:
            request = urllib.request.Request(data['source']['url'], headers={'User-Agent': 'JugglucoNG-build'})
            with urllib.request.urlopen(request, timeout=120) as src, archive.open('wb') as out:
                shutil.copyfileobj(src, out)
        if digest(archive.read_bytes()) != data['source']['sha256']:
            raise ValueError('Build input source APK checksum mismatch')
        with zipfile.ZipFile(archive) as z:
            yield z


def unavailable_inputs(data, archive):
    missing = []
    for spec in data['files']:
        entry = input_entry(spec['path'])
        try:
            content = archive.read(entry)
        except KeyError:
            missing.append(spec['path'])
            continue
        if len(content) != spec['size'] or digest(content) != spec['sha256']:
            missing.append(spec['path'])
    return missing


def restore(apk=None):
    data = inventory()
    if data.get('bootstrapRequired', False):
        raise ValueError('Vendor inputs await their first published baseline. Build locally with '
                         'scripts/build-dist.sh all, then use scripts/release-local.sh; '
                         'run scripts/update-build-inputs.sh --baseline <published-tag> afterwards.')
    with source_archive(data, apk) as z:
        payloads = []
        for spec in data['files']:
            content = z.read(input_entry(spec['path']))
            if len(content) != spec['size'] or digest(content) != spec['sha256']:
                raise ValueError(f"Input checksum mismatch: {spec['path']}")
            dest = ROOT / spec['path']
            if not dest.resolve().is_relative_to(ROOT.resolve()):
                raise ValueError(f"Input path escapes checkout: {spec['path']}")
            if dest.exists() and digest(dest.read_bytes()) != spec['sha256']:
                raise ValueError(f"Refusing to overwrite different local input: {spec['path']}")
            payloads.append((dest, content))
        for dest, content in payloads:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(content)
    inputs_check()


def sdk_tool(name):
    override = os.environ.get(name.upper())
    if override:
        return override
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        local = ROOT / 'local.properties'
        if local.exists():
            match = re.search(r'^sdk.dir=(.+)$', local.read_text(), re.M)
            sdk = match[1].replace('\\:', ':').replace('\\ ', ' ') if match else None
    if not sdk:
        raise ValueError('Set ANDROID_HOME to the Android SDK')
    # Use stable build tools only, newest first.
    paths = [p for p in (Path(sdk) / 'build-tools').glob('*') if re.fullmatch(r'\d+\.\d+\.\d+', p.name)]
    for p in sorted(paths, key=lambda p: tuple(map(int, p.name.split('.'))), reverse=True):
        if (p / name).is_file():
            return str(p / name)
    raise ValueError(f'Android SDK tool not found: {name}')


def verify_apk(apk, flavor, build_type, expected_certificate=None):
    cert = expected_certificate or (ROOT / 'scripts/dist/production-cert.sha256').read_text().strip()
    result = subprocess.check_output([sdk_tool('apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    found = re.findall(r'^(?:Signer #\d+|V\d+ Signer):? certificate SHA-256 digest: ([0-9a-f]+)$', result, re.M)
    if not found or set(found) != {cert}:
        raise ValueError(f'Production signing certificate mismatch: {apk.name}')
    badging = subprocess.check_output([sdk_tool('aapt2'), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    name, code = version()
    appid = 'tk.glucodata.ng' + ('.dub' if build_type == 'releasedub' else '')
    code += 1000000 if flavor == 'wear' else 0
    vn = name + ('-wear' if flavor == 'wear' else '-phone') + ('DUB' if build_type == 'releasedub' else '')
    if not package or package.groups() != (appid, str(code), vn):
        raise ValueError(f'APK package/version mismatch: {apk.name}')
    if 'application-debuggable' in badging:
        raise ValueError(f'Debuggable distribution APK: {apk.name}')
    with zipfile.ZipFile(apk) as z:
        actual_abis = {n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')}
        if actual_abis != set(abis()):
            raise ValueError(f'APK ABI mismatch: {apk.name}: {actual_abis}')
        for spec in inventory()['files']:
            path = spec['path'].split('/jniLibs/')[1]
            if path.split('/')[0] in abis() and digest(z.read('lib/' + path)) != spec['sha256']:
                raise ValueError(f'APK JNI checksum mismatch: {apk.name}: {path}')
        for path in inventory().get('removedFiles', []):
            if input_entry(path) in z.namelist():
                raise ValueError(f'Removed vendor input still packaged: {apk.name}: {path}')
    print(f'Verified production signature, version and JNI payload: {apk.name}', file=sys.stderr)


def verify_set(directory, target, expected_certificate=None):
    expected = {filename(f, b) for f, b in TARGETS[target]}
    if {p.name for p in directory.glob('*.apk')} != expected:
        raise ValueError('Distribution APK set mismatch')
    for flavor, build_type in TARGETS[target]:
        verify_apk(directory / filename(flavor, build_type), flavor, build_type, expected_certificate)


def stage(target):
    out = ROOT / 'build/dist' / target
    sources = [(ROOT / f'Common/build/outputs/apk/{f}/{b}' / filename(f, b), f, b) for f, b in TARGETS[target]]
    for apk, flavor, build_type in sources:
        verify_apk(apk, flavor, build_type)
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=out.parent) as tmp:
        for apk, _, _ in sources:
            shutil.copy2(apk, Path(tmp) / apk.name)
        if out.exists():
            shutil.rmtree(out)
        shutil.move(tmp, out)
    for p in sorted(out.glob('*.apk')):
        print(p.relative_to(ROOT))


def stage_test_assets(directory, target, sha, pr_number=None, expected_certificate=None):
    """Copy verified APKs with snapshot names, retaining canonical build outputs."""
    if not isinstance(sha, str) or not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Test APKs require a full source SHA')
    if pr_number is not None and not re.fullmatch(r'[1-9][0-9]*', str(pr_number)):
        raise ValueError('Invalid test APK PR number')
    if target not in TARGETS:
        raise ValueError('Invalid test APK target')
    verify_set(directory, target, expected_certificate=expected_certificate)
    source = 'main' if pr_number is None else f'pr{pr_number}'
    out = directory.parent / 'test' / target
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=out.parent) as tmp:
        for flavor, build_type in TARGETS[target]:
            variant = ('phone' if flavor == 'mobile' else 'wear') + ('-dub' if build_type == 'releasedub' else '')
            original = directory / filename(flavor, build_type)
            copied = Path(tmp) / f'JugglucoNG-test-{source}-{sha[:12]}-{variant}.apk'
            shutil.copyfile(original, copied)
            if digest(original.read_bytes()) != digest(copied.read_bytes()):
                raise ValueError('Test APK bytes changed while staging')
        if out.exists():
            shutil.rmtree(out)
        shutil.move(tmp, out)
    return out


def write_build_info(directory, info):
    info = dict(info, apks=[{'file': p.name, 'size': p.stat().st_size, 'sha256': digest(p.read_bytes())}
                           for p in sorted(directory.glob('*.apk'))])
    (directory / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n')


def manifest(directory):
    name, code = version()
    artifacts = []
    for flavor, build_type in TARGETS['phone-all']:
        apk = directory / filename(flavor, build_type)
        if not apk.is_file():
            raise ValueError(f'Missing manifest APK: {apk}')
        verify_apk(apk, flavor, build_type)
        artifacts.append({'applicationId': 'tk.glucodata.ng' + ('.dub' if build_type == 'releasedub' else ''),
                          'file': apk.name, 'size': apk.stat().st_size, 'sha256': digest(apk.read_bytes())})
    return {'schema': 1, 'versionName': name, 'versionCode': code, 'minSdk': 26, 'artifacts': sorted(artifacts, key=lambda a: a['file'])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['tasks', 'check-inputs', 'restore', 'stage', 'test-stage', 'verify', 'manifest', 'version', 'release-check'])
    parser.add_argument('--target', choices=TARGETS, default='all')
    parser.add_argument('--dir', type=Path, default=ROOT / 'build/dist/all')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--tag')
    parser.add_argument('--sha')
    args = parser.parse_args()
    if args.command == 'tasks':
        for f, b in TARGETS[args.target]:
            print(':Common:assemble' + f.capitalize() + b.capitalize())
    elif args.command == 'check-inputs':
        inputs_check()
    elif args.command == 'restore':
        restore(args.apk)
    elif args.command == 'stage':
        stage(args.target)
    elif args.command == 'test-stage':
        out = stage_test_assets(args.dir, args.target, args.sha)
        write_build_info(out, {'source_sha': args.sha, 'source_kind': 'trusted-main', 'target': args.target,
                              'run_url': f'https://github.com/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'})
    elif args.command == 'verify':
        verify_set(args.dir, args.target)
    elif args.command == 'manifest':
        print(json.dumps(manifest(args.dir), indent=2))
    elif args.command == 'version':
        print(version()[0])
    elif args.command == 'release-check':
        if args.tag != version()[0]:
            raise ValueError('Release tag must exactly equal appVersionName')
        if set(abis()) != set(ARM_ABIS):
            raise ValueError('Release requires both ARM ABIs')
        verify_set(args.dir, 'all')
        if json.loads((args.dir / 'update-manifest.json').read_text()) != manifest(args.dir):
            raise ValueError('Updater manifest differs from verified APKs')
        if {p.name for p in args.dir.iterdir()} != {filename(f, b) for f, b in TARGETS['all']} | {'update-manifest.json'}:
            raise ValueError('Unexpected release assets')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as e:
        sys.exit(f'Distribution validation failed: {e}')
