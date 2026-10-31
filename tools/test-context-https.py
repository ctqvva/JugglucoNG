#!/usr/bin/env python3
"""Exercise complete HTTP responses through the production TLS client."""
import os
from pathlib import Path
import shlex
import socket
import ssl
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'Common/src/main/cpp'
BODY = b'{"username":"synthetic-user","password":"synthetic-password","ttl":"600"}'


def cases():
    header = b'HTTP/1.1 201 Created\r\nContent-Length: ' + str(len(BODY)).encode() + b'\r\n\r\n'
    chunks = [b'HTTP/1.1 201 Created\r\nTransfer-Encoding: chunked\r\n\r\n']
    for part in (BODY[:11], BODY[11:]):
        chunks += [f'{len(part):x};test=value\r\n'.encode(), part, b'\r\n']
    chunks += [b'0\r\nX-Test: done\r\n\r\n']
    return (
        ('fragmented headers and JSON', [header[:9], header[9:-2], header[-2:], BODY[:7], BODY[7:]], 'token'),
        ('length-delimited keepalive', [header, BODY], 'token'),
        ('body exceeding one read buffer', [b'HTTP/1.1 201 Created\r\nContent-Length: ' +
                                          str(len(BODY) + 20000).encode() + b'\r\n\r\n',
                                          BODY + b' ' * 20000], 'token'),
        ('long-poll response deadline', [header, BODY], 'longpoll'),
        ('chunked JSON and trailers', chunks, 'token'),
        ('connection-delimited JSON', [b'HTTP/1.0 201 Created\r\n\r\n', BODY[:7], BODY[7:]], 'close'),
        ('unauthenticated EOF', [b'HTTP/1.0 201 Created\r\n\r\n', BODY], 'abrupt'),
        ('interim response', [b'HTTP/1.1 100 Continue\r\n\r\n', header, BODY], 'token'),
        ('empty response', [b'HTTP/1.1 204 No Content\r\n\r\n'], 'empty'),
        ('truncated length', [header, BODY[:7]], 'reject'),
        ('truncated chunks', chunks[:-1], 'reject'),
        ('invalid chunk size', [chunks[0], b'not-hex\r\n'], 'reject'),
        ('conflicting framing', [b'HTTP/1.1 201 Created\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n'], 'reject'),
        ('invalid content length', [b'HTTP/1.1 201 Created\r\nContent-Length: 7oops\r\n\r\n'], 'reject'),
        ('oversized body', [b'HTTP/1.1 201 Created\r\nContent-Length: 1048577\r\n\r\n'], 'reject'),
        ('oversized headers', [b'HTTP/1.1 201 Created\r\nX-Test: ' + b'x' * 32768], 'reject'),
        ('partial body deadline', [header, BODY[:7]], 'timeout'),
        ('partial body cancellation', [header, BODY[:7]], 'cancel'),
    )


def run_case(binary, context, cert, parts, mode):
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(4)
    port = listener.getsockname()[1]
    errors = []

    def serve():
        try:
            connection, _ = listener.accept()
            with context.wrap_socket(connection, server_side=True) as secure:
                secure.settimeout(3)
                request = b''
                while b'\r\n\r\n' not in request:
                    part = secure.recv(4096)
                    if not part:
                        return
                    request += part
                if mode == 'longpoll':
                    time.sleep(0.45)
                for part in parts:
                    secure.sendall(part)
                    time.sleep(0.025)  # force separate TLS records / partial reads
                if mode == 'abrupt':
                    return  # close the TCP socket without TLS close-notify
                if mode in ('close', 'reject'):
                    # Send authenticated EOF. Truncated framed replies still fail.
                    try:
                        secure.unwrap().close()
                    except (ssl.SSLError, OSError):
                        pass
                else:
                    # A framed response must complete without waiting for EOF.
                    try:
                        while secure.recv(4096):
                            pass
                    except (ssl.SSLError, OSError):
                        pass
        except (BrokenPipeError, ConnectionResetError, ssl.SSLEOFError):
            pass  # expected when the client rejects framing before the last write
        except Exception as error:
            errors.append(error)
        finally:
            listener.close()

    thread = threading.Thread(target=serve)
    thread.start()
    try:
        result = subprocess.run([str(binary), str(port), mode],
                                env=dict(os.environ, SSL_CERT_FILE=str(cert)), timeout=6)
    finally:
        thread.join(timeout=5)
    assert not thread.is_alive()
    assert not errors, errors
    return result.returncode


def main():
    flags = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'openssl'], text=True))
    with tempfile.TemporaryDirectory(prefix='https-response-') as temporary:
        directory = Path(temporary)
        binary = directory / 'test'
        subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++20', '-pthread', '-DNOLOG',
                        '-I' + str(CPP), '-I' + str(CPP / 'share'),
                        str(CPP / 'net/ICE/ContextHTTPS.cpp'),
                        str(ROOT / 'Common/src/test/cpp/ContextHTTPSResponseTests.cpp'),
                        *flags, '-o', str(binary)], check=True)
        cert, key = directory / 'cert.pem', directory / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                        '-keyout', str(key), '-out', str(cert), '-days', '1',
                        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        for name, parts, mode in cases():
            print(name, flush=True)
            assert run_case(binary, context, cert, parts, mode) == 0


if __name__ == '__main__':
    main()
