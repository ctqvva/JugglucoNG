"""Explicit relay-only inputs for distribution APKs; never print credentials."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import zipfile

import dist

ENVIRONMENT = 'JUGGLUCO_TURN_SERVERS'
HEADER = 'distribution-turn.hpp'


def configuration(value):
    try:
        servers = json.loads(value)
    except (ValueError, TypeError):
        raise ValueError('Clone TURN input must be a JSON array') from None
    # This libjuice fork has MAX_RELAY_ENTRIES_COUNT=2. Reject extra entries
    # rather than claiming redundancy that the client would silently ignore.
    if not isinstance(servers, list) or not 1 <= len(servers) <= 2:
        raise ValueError('Clone TURN input requires 1 to 2 relay entries (libjuice limit)')
    for server in servers:
        if not isinstance(server, dict) or set(server) != {'host', 'port', 'username', 'password'}:
            raise ValueError('Each Clone TURN entry requires only host, port, username, password')
        if not isinstance(server['host'], str) or not re.fullmatch(r'[a-zA-Z0-9._:-]{1,253}', server['host']):
            raise ValueError('Clone TURN host must be a hostname or IP address, without a URL scheme')
        if ':' in server['host']:
            try:
                ipaddress.IPv6Address(server['host'])
            except ValueError:
                raise ValueError('Clone TURN host containing colons must be an IPv6 address') from None
        if type(server['port']) is not int or not 1 <= server['port'] <= 65535:
            raise ValueError('Clone TURN port must be an integer from 1 to 65535')
        for field in ('username', 'password'):
            text = server[field]
            try:
                valid = isinstance(text, str) and text.strip() and '\0' not in text and len(text.encode('utf-8')) <= 1024
            except UnicodeError:
                valid = False
            if not valid:
                raise ValueError('Clone TURN credentials must be nonempty UTF-8 strings of at most 1024 bytes without NUL')
    return servers


def load():
    value = os.environ.get(ENVIRONMENT)
    if not value:
        raise ValueError('Distribution requires JUGGLUCO_TURN_SERVERS; configure CLONE_TURN_SERVERS_JSON in production-signing')
    return configuration(value)


def literal(text):
    # Fixed-width octal escapes prevent C++/preprocessor injection and preserve UTF-8 bytes.
    return '"' + ''.join(f'\\{byte:03o}' for byte in text.encode('utf-8')) + '"'


def generate(servers, directory):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / HEADER
    text = '// Generated relay credentials. Do not commit or upload this file.\n'
    for server in servers:
        text += ('{.host=' + literal(server['host']) + ',.username=' + literal(server['username']) +
                 ',.password=' + literal(server['password']) + ',.port=' + str(server['port']) + '},\n')
    # The wrapper creates a private temporary directory, and removes it on exit.
    with path.open('w', encoding='ascii') as output:
        path.chmod(0o600)
        output.write(text)


def verify_apk(apk, servers, abis):
    with zipfile.ZipFile(apk) as archive:
        for abi in abis:
            try:
                library = archive.read(f'lib/{abi}/libg.so')
            except KeyError:
                raise ValueError(f'Clone native library missing from {apk.name} ({abi})') from None
            for index, server in enumerate(servers):
                if any(server[field].encode('utf-8') + b'\0' not in library
                       for field in ('host', 'username', 'password')):
                    raise ValueError(f'Bundled Clone TURN entry {index + 1} missing from {apk.name} ({abi}); refusing relay-less/stale APK')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('generate').add_argument('--directory', required=True, type=Path)
    commands.add_parser('verify').add_argument('--target', required=True, choices=dist.TARGETS)
    args = parser.parse_args()
    servers = load()
    if args.command == 'generate':
        generate(servers, args.directory)
        print(f'Prepared {len(servers)} credentialed Clone TURN entries', file=sys.stderr)
    else:
        for flavor, build_type in dist.TARGETS[args.target]:
            apk = dist.ROOT / f'Common/build/outputs/apk/{flavor}/{build_type}' / dist.filename(flavor, build_type)
            verify_apk(apk, servers, dist.abis())
        print('Verified bundled Clone TURN payload in every requested APK and ABI', file=sys.stderr)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        # JSON decoder errors and input values must never enter Actions logs.
        print(f'Clone TURN: {error}', file=sys.stderr)
        sys.exit(1)
