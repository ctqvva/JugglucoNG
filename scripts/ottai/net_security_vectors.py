#!/usr/bin/env python3
"""Independent PyCryptodome oracle. All identities, scalars and keys are synthetic test data."""
import base64
import hashlib
import hmac
import json
from pathlib import Path
from Crypto.Cipher import AES
from Crypto.Hash import SHA256
from Crypto.Protocol.KDF import HKDF
from Crypto.PublicKey import ECC


def b64(value):
    return base64.b64encode(value).decode()


def coordinate(value):
    return b64(int(value).to_bytes(32, 'big'))


def main():
    device = 'ottai:a:n:00000000-0000-0000-0000-000000000001'
    now = 1793000000000
    expiry = now + 3600000
    secret_id = 'synthetic-exchange-1'
    client = ECC.construct(curve='P-256', d=1)  # TEST ONLY, never a real client key.
    server = ECC.construct(curve='P-256', d=2)
    cx, cy = coordinate(client.pointQ.x), coordinate(client.pointQ.y)
    sx, sy = coordinate(server.pointQ.x), coordinate(server.pointQ.y)
    shared = int((server.pointQ * client.d).x).to_bytes(32, 'big')
    derived = HKDF(shared, 64, secret_id.encode(), SHA256,
                   context=f'{device}#{expiry}#{secret_id}'.encode())
    aad = f'{device}|{secret_id}'.encode()

    def seal(plain, nonce):
        cipher = AES.new(derived[:32], AES.MODE_GCM, nonce=nonce)
        cipher.update(aad)
        text, tag = cipher.encrypt_and_digest(plain.encode())
        return b64(text), b64(tag), b64(nonce)

    exchange_text, exchange_tag, exchange_nonce = seal(sx + sy, bytes(range(12)))
    request = dict(clientPubKeyX=cx, clientPubKeyY=cy, clientTimestamp=now,
                   clientSignature=hashlib.sha256(f'{device}{cx}{cy}{now}'.encode()).hexdigest())
    response = dict(code='OK', data=dict(serverPubKeyX=sx, serverPubKeyY=sy,
                    serverTimestamp=now, serverSignature=hashlib.sha256(f'{device}{sx}{sy}{now}'.encode()).hexdigest(),
                    expireTime=str(expiry), secretId=secret_id, ciphertext=exchange_text,
                    iv=exchange_nonce, authTag=exchange_tag))
    revision = 'E1.1.4(V1.7.S2530.1),STDRD'
    query = dict(deviceVersion=revision)
    report = '{"mac":"AABBCCDDEEFF","sourceVersion":"' + revision + '","targetVersion":"V1.8.0","upgradeStatus":1}'

    def packet(plain, kind, seq, nonce):
        text, tag, iv = seal(plain, nonce)
        nonce_id = str(((now - 1288834974657) << 22 | 4096) + seq - 1)
        headers = dict(cipherNonceId=nonce_id, cipherTimeNow=str(now), cipherSecretId=secret_id,
                       cipherUserId=device, cipherSeq=str(seq))
        signature = b64(hmac.new(derived[32:], f'{nonce_id}{seq}{now}{text}'.encode(), hashlib.sha256).digest())
        envelope = {f'cipher{kind}Text': text, f'cipher{kind}Mac': tag,
                    f'cipher{kind}Nonce': iv, f'cipher{kind}Signature': signature}
        return dict(headers=headers, envelope=envelope)

    plain_reply = dict(code='OK', data=dict(requiredUpgrade=False, allowUpgrade=False))
    text, tag, nonce = seal(json.dumps(plain_reply, separators=(',', ':')), bytes(range(48, 60)))
    fixture = dict(deviceId=device, now=now, expires=expiry, exchangeRequest=request, exchangeResponse=response,
                   query=query, report=report,
                   get=packet(json.dumps(query, separators=(',', ':')), 'Param', 1, bytes(range(16, 28))),
                   post=packet(report, 'Body', 2, bytes(range(32, 44))),
                   response=dict(cipherSecretId=secret_id, cipherText=text, cipherMac=tag, cipherNonce=nonce),
                   plainResponse=plain_reply)
    target = Path(__file__).resolve().parents[2] / 'Common/src/test/resources/ottai-net-security.json'
    target.write_text(json.dumps(fixture, indent=2) + '\n')


if __name__ == '__main__':
    main()
