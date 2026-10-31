#!/usr/bin/env python3
"""Build and exercise the bundled TURN path against a local authenticated relay."""
import json
import os
from pathlib import Path
import subprocess
import shlex
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/dist'))
import turn


def stream_test(executable, mode, environment):
    # Keep normal CI output short, but retain transport stages and sanitized
    # libjuice diagnostics when a relay fails or its cleanup times out.
    environment = dict(environment, CLONE_TURN_TEST_LOG='1')
    def failure_output(result):
        for data in (result.stdout, result.stderr):
            if data:
                print(data.decode(errors='replace') if isinstance(data, bytes) else data,
                      file=sys.stderr, flush=True)
    try:
        result = subprocess.run([str(executable), mode], timeout=90,
                                env=environment, capture_output=True, text=True)
    except subprocess.TimeoutExpired as error:
        failure_output(error)
        raise
    if result.returncode:
        failure_output(result)
    result.check_returncode()
    print(result.stdout, end='', flush=True)


def main():
    live = '--live' in sys.argv[1:]
    streams = '--streams' in sys.argv[1:] or live
    with tempfile.TemporaryDirectory(prefix='clone-turn-test-') as tmp:
        directory = Path(tmp)
        pool_test = directory / 'turn-pool-test'
        subprocess.run(['c++', '-std=c++20', '-pthread', '-Wall', '-Werror',
                        '-I', str(ROOT / 'Common/src/main/cpp'),
                        str(ROOT / 'Common/src/test/cpp/TurnRelayPoolTests.cpp'),
                        '-o', str(pool_test)], check=True)
        subprocess.run([str(pool_test)], check=True)
        ice = (ROOT / 'Common/src/main/cpp/net/ICE/ICE.cpp').read_text()
        start = ice.index('static std::vector<TurnRelay> twilioTurnRelays()')
        end = ice.index('juice_agent *createAgent(', start)
        cache_source = directory / 'twilio-turn-cache.cpp'
        fixture = (ROOT / 'tools/fixtures/twilio-turn-cache.cpp').read_text()
        cache_source.write_text(fixture.replace('/* TWILIO_PROVIDER */', ice[start:end]))
        cache_test = directory / 'twilio-turn-cache'
        subprocess.run(['c++', '-std=c++20', '-pthread', '-Wall', '-Werror',
                        '-I', str(ROOT / 'Common/src/main/cpp'),
                        str(cache_source), '-o', str(cache_test)], check=True)
        subprocess.run([str(cache_test)], check=True, timeout=10)
        turn.generate(turn.configuration(json.dumps([
            {'host': '127.0.0.1', 'port': 3478, 'username': 'invalid-user', 'password': 'invalid-password'},
            {'host': '127.0.0.1', 'port': 3478, 'username': 'loopback-user', 'password': 'loopback-password'},
        ])), directory)
        subprocess.run(['cmake', '-S', str(ROOT / 'Common/src/main/cpp/libjuice'), '-B', str(directory / 'juice'),
                        '-DBUILD_SHARED_LIBS=OFF', '-DNO_TESTS=ON', '-DNO_SERVER=OFF',
                        '-DCMAKE_C_FLAGS=-Dsendto=clone_turn_test_sendto',
                        '-DENABLE_LOCALHOST_ADDRESS=ON'], check=True)
        subprocess.run(['cmake', '--build', str(directory / 'juice'), '--parallel', '2'], check=True)
        executable = directory / 'clone-turn-test'
        extra = []
        if streams:
            extra = ['-DTEST_TURN_STREAM', '-DNOLOG',
                     '-I', str(ROOT / 'Common/src/main/cpp/share'),
                     str(ROOT / 'Common/src/main/cpp/net/ICE/ContextHTTPS.cpp')]
            extra += shlex.split(subprocess.check_output(
                ['pkg-config', '--cflags', '--libs', 'openssl'], text=True))
        subprocess.run(['c++', '-std=c++20', '-pthread', '-DJUGGLUCO_DISTRIBUTION=1',
                        '-I', str(ROOT / 'Common/src/main/cpp'), '-I', str(directory),
                        str(ROOT / 'Common/src/test/cpp/CloneTurnRelayTests.cpp'),
                        str(directory / 'juice/libjuice.a'), *extra, '-o', str(executable)], check=True)
        if not live:
            subprocess.run([str(executable)], check=True, timeout=25)
            subprocess.run([str(executable), '--reject-all'], check=True, timeout=10)
            if streams:
                cert, key = directory / 'cert.pem', directory / 'key.pem'
                subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                                '-keyout', str(key), '-out', str(cert), '-days', '1',
                                '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                environment = dict(os.environ, TURN_TEST_CERT=str(cert), TURN_TEST_KEY=str(key),
                                   SSL_CERT_FILE=str(cert))
                for mode in ('--stream-tcp', '--stream-tls', '--stream-tls-no-udp'):
                    print('Relay delivery through ' + mode, flush=True)
                    environment['TURN_TEST_HANDSHAKE_DELAY_MS'] = '1500' if mode != '--stream-tcp' else '0'
                    stream_test(executable, mode, environment)
        if live:
            # A stable path lets host firewalls retain the owner's approval.
            # The binary embeds only synthetic fixture credentials; live
            # credentials are supplied separately through standard input.
            live_binary = ROOT / 'build/diagnostics/clone-turn-live-probe'
            live_binary.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(executable, live_binary)
            live_binary.chmod(0o700)
            print('Live probe executable: ' + str(live_binary), flush=True)
            for relay in turn.configuration(os.environ.get('JUGGLUCO_TURN_LIVE_JSON')):
                transport = relay.get('transport', 'udp')
                print(f"Public relay: {relay['host']}:{relay['port']} ({transport})", flush=True)
                for attempt in range(3):
                    result = subprocess.run([str(live_binary), '--live', relay['host'], str(relay['port']), transport],
                                            input=relay['username'] + '\n' + relay['password'] + '\n',
                                            text=True, timeout=30)
                    if result.returncode == 0:
                        break
                    print('Relay attempt failed; retrying with the same approved executable', flush=True)
                result.check_returncode()


if __name__ == '__main__':
    main()
