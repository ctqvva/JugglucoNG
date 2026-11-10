"""Explicit relay-only inputs for distribution APKs; never print credentials."""
import argparse
import base64
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import zipfile

import dist

ENVIRONMENT = 'JUGGLUCO_TURN_SERVERS'
POOL_ENVIRONMENT = 'JUGGLUCO_TURN_POOL'
HEADER = 'distribution-turn.hpp'
OPTIONS_HEADER = 'distribution-turn-options.hpp'
MAX_RELAYS = 16
TWILIO_ENVIRONMENT = 'JUGGLUCO_TWILIO_ACCOUNT'
TWILIO_HEADER = 'distribution-twilio.hpp'


def load_twilio():
    value = os.environ.get(TWILIO_ENVIRONMENT, '')
    if not value:
        return None
    try:
        account = json.loads(value)
    except (ValueError, TypeError):
        raise ValueError('Optional Twilio input must be an account JSON object') from None
    if (not isinstance(account, dict) or set(account) != {'sid', 'auth_token'} or
            not isinstance(account['sid'], str) or not re.fullmatch(r'AC[0-9a-fA-F]{32}', account['sid']) or
            not isinstance(account['auth_token'], str) or
            not re.fullmatch(r'[a-zA-Z0-9]{16,128}', account['auth_token'])):
        raise ValueError('Optional Twilio input requires an account SID and auth token')
    return account


def configuration(value):
    try:
        servers = json.loads(value)
    except (ValueError, TypeError):
        raise ValueError('Clone TURN input must be a JSON array') from None
    # The client tries two at a time and rotates through this larger pool.
    if not isinstance(servers, list) or not 1 <= len(servers) <= MAX_RELAYS:
        raise ValueError('Clone TURN input requires 1 to 16 relay entries')
    for server in servers:
        required = {'host', 'port', 'username', 'password'}
        optional = {'transport', 'provider', 'region'}
        if not isinstance(server, dict) or not required <= set(server) or set(server) - required - optional:
            raise ValueError('Each Clone TURN entry requires host, port, username, password; optional transport, provider, region')
        if server.get('transport', 'udp') not in ('udp', 'tcp', 'tls'):
            raise ValueError('Clone TURN transport must be udp, tcp or tls')
        for field in ('provider', 'region'):
            if field in server and (not isinstance(server[field], str) or
                                    not re.fullmatch(r'[a-zA-Z0-9._-]{1,64}', server[field])):
                raise ValueError('Clone TURN provider and region must be short identifiers')
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
    value = os.environ.get(POOL_ENVIRONMENT) or os.environ.get(ENVIRONMENT)
    if not value:
        raise ValueError('Distribution requires JUGGLUCO_TURN_SERVERS; configure CLONE_TURN_SERVERS_JSON in production-signing')
    return configuration(value)


def literal(text):
    # Fixed-width octal escapes prevent C++/preprocessor injection and preserve UTF-8 bytes.
    return '"' + ''.join(f'\\{byte:03o}' for byte in text.encode('utf-8')) + '"'


def generate(servers, directory, twilio=None):
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
    options = directory / OPTIONS_HEADER
    with options.open('w', encoding='ascii') as output:
        options.chmod(0o600)
        for server in servers:
            transport = server.get('transport', 'udp').title()
            output.write('{TurnRelayTransport::' + transport + ',' +
                         literal(server.get('provider', '')) + ',' +
                         literal(server.get('region', '')) + '},\n')
    if twilio:
        basic = base64.b64encode((twilio['sid'] + ':' + twilio['auth_token']).encode()).decode('ascii')
        with (directory / TWILIO_HEADER).open('w', encoding='ascii') as output:
            (directory / TWILIO_HEADER).chmod(0o600)
            output.write('// Private build input; never commit or upload.\n')
            output.write('#define TWILIOACCOUNT ' + literal(twilio['sid']) + '\n')
            output.write('#define USERPASSBASE64 ' + literal(basic) + '\n')


def verify_apk(apk, servers, abis, twilio=None):
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
            if twilio:
                basic = base64.b64encode((twilio['sid'] + ':' + twilio['auth_token']).encode())
                # These values are part of the URL / Authorization literals,
                # rather than standalone NUL-terminated strings.
                if twilio['sid'].encode() not in library or basic not in library:
                    raise ValueError(f'Optional Twilio input missing from {apk.name} ({abi})')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('generate').add_argument('--directory', required=True, type=Path)
    commands.add_parser('verify').add_argument('--target', required=True, choices=dist.TARGETS)
    args = parser.parse_args()
    servers = load()
    twilio = load_twilio()
    if args.command == 'generate':
        generate(servers, args.directory, twilio)
        print(f'Prepared {len(servers)} credentialed Clone TURN entries', file=sys.stderr)
    else:
        for flavor, build_type in dist.TARGETS[args.target]:
            apk = dist.ROOT / f'Common/build/outputs/apk/{flavor}/{build_type}' / dist.filename(flavor, build_type)
            verify_apk(apk, servers, dist.abis(), twilio)
        print('Verified bundled Clone TURN payload in every requested APK and ABI', file=sys.stderr)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        # JSON decoder errors and input values must never enter Actions logs.
        print(f'Clone TURN: {error}', file=sys.stderr)
        sys.exit(1)
