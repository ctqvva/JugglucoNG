#!/usr/bin/env python3
"""Exercise production TURN stream framing, TLS trust/hostname checks and cancellation."""
import os
from pathlib import Path
import shlex
import socket
import ssl
import struct
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'Common/src/main/cpp'


def serve(listener, context, silent=False):
    try:
        connection, _ = listener.accept()
        with connection:
            if silent:
                # The client cancels while the TLS handshake is held.
                connection.settimeout(3)
                try:
                    while connection.recv(4096):
                        pass
                except OSError:
                    pass
                return
            if context:
                try:
                    connection = context.wrap_socket(connection, server_side=True)
                except ssl.SSLError:
                    return
            with connection:
                data = b''
                while len(data) < 48:
                    part = connection.recv(4096)
                    if not part:
                        return
                    data += part
                assert len(data) == 48 and data[47] == 0  # ChannelData stream padding
                # Fragment a header, then coalesce the tail and two other frames.
                connection.sendall(data[:3])
                connection.sendall(data[3:])
    finally:
        listener.close()


def main():
    flags = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'openssl'], text=True))
    with tempfile.TemporaryDirectory(prefix='turn-stream-') as temporary:
        directory = Path(temporary)
        binary = directory / 'test'
        subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++20', '-pthread', '-DNOLOG',
                        '-I' + str(CPP), '-I' + str(CPP / 'share'),
                        str(CPP / 'net/ICE/ContextHTTPS.cpp'),
                        str(ROOT / 'Common/src/test/cpp/TurnStreamRelayTests.cpp'),
                        *flags, '-o', str(binary)], check=True)
        cert, key = directory / 'cert.pem', directory / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                        '-keyout', str(key), '-out', str(cert), '-days', '1',
                        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        proxy_test = directory / 'proxy-test'
        subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++20', '-pthread', '-Wall', '-Werror',
                        '-I' + str(CPP), str(ROOT / 'Common/src/test/cpp/TurnTestProxyTests.cpp'),
                        *flags, '-o', str(proxy_test)], check=True)
        subprocess.run([str(proxy_test)], check=True, timeout=10,
                       env=dict(os.environ, TURN_TEST_CERT=str(cert), TURN_TEST_KEY=str(key)))
        for transport, trusted, hostname, mode in (
                ('tcp', False, '127.0.0.1', 'echo'),
                ('tls', True, 'localhost', 'echo'),
                ('tls', False, 'localhost', 'reject'),
                ('tls', True, '127.0.0.1', 'reject'),
                ('tls', True, 'localhost', 'cancel')):
            listener = socket.socket()
            listener.bind(('127.0.0.1', 0)); listener.listen(1)
            port = listener.getsockname()[1]
            thread = threading.Thread(target=serve, args=(listener, context if transport == 'tls' else None,
                                                         mode == 'cancel'))
            thread.start()
            environment = dict(os.environ)
            if trusted:
                environment['SSL_CERT_FILE'] = str(cert)
            else:
                environment.pop('SSL_CERT_FILE', None)
            print(f'{transport}: trust={trusted}, host={hostname}, mode={mode}', flush=True)
            subprocess.run([str(binary), transport, hostname, str(port), mode],
                           env=environment, check=True, timeout=12)
            thread.join(timeout=5)
            assert not thread.is_alive()


if __name__ == '__main__':
    main()
