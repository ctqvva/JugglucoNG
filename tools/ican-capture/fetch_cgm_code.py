#!/usr/bin/env python3
"""Fetch the iCan (Sinocare) per-SN calibration table (parameterCode) from the
vendor cloud and decrypt it offline.

Pipeline (verified 2026-10-22, no hooks, no vendor app, no Frida):
  GET {base}sino-deviceaccess/sdk/cgm-code-encrypt
      ?appId=..&deviceSn=<SN>&timestamp=<ms>&sign=<upper(md5(signString))>
      (secret never leaves the device; sign = md5("appId=..&deviceSn=..&timestamp=..&key=<secret>"))
  -> {"code":200,"data":{"k":"<RSA-1024 ct, b64, may be 129B (leading 0x00)>",
                         "v":"<AES-256-CBC ct, b64>"}}
  k: RSA public-op with the SDK's embedded 1024-bit public key (from the vendor dex,
     java class com.sinocare.cgm.cgm_sdk.util.RSAUtils.decryptByPublicKey) -> 32-char AES key.
  v: AES-256-CBC, IV = first 16 ASCII chars of the AES key, NoPadding (payload is a
     16-byte multiple). Envelope: {"code":200,"data":{"code":"<parameterCode JSON>"}}.

Probing (2026-10-23): an unregistered/bogus SN gets a stable DEFAULT table
(x8=0.3 x9=1.0 x10=1.2 x11=13.0 x12=1.5, x13=0.4 before the client-side
c(shortSN) override) every time, byte-identical across repeat requests; the
server generates a fresh AES key per request (k differs), the payload does
not. Known SNs return their own stable rows. So the table is a per-SN lookup
with a default fallback, not an SN-hash generated value.

Credentials come from ~/.config/ican-cloud-creds.json (not committed):
  {"appId": "...", "appSecret": "...", "base": "https://iot.ru.sinocareintl.com/api/"}

Manual fallback for a phone whose network cannot reach the cloud:
  Requires the installed debuggable tk.glucodata.ng.debug app, launched once.
  Run from the repository root; replace SN with the sensor's 13-character box serial:

  set -eu
  SN='REPLACE_WITH_BOX_SERIAL'
  OUT='./ican-table'
  APP_ID='tk.glucodata.ng.debug'
  mkdir -p "$OUT"
  python3 tools/ican-capture/fetch_cgm_code.py "$SN" --out "$OUT"
  TABLE="ican-cgm-code-$SN.json"
  adb shell -T "run-as $APP_ID sh -c 'umask 077; mkdir -p files; cat > files/$TABLE'" < "$OUT/$TABLE"
  adb shell "run-as $APP_ID test -s files/$TABLE"

  The redirect runs inside run-as, placing the file in the app's internal filesDir,
  where cachedCode reads it (ICanHealthCloudCode.parseTableFileContent).
  Use adb -s <device-id> for each adb command if multiple devices are connected.

usage: fetch_cgm_code.py SN [SN ...]
"""
import base64
import gzip
import hashlib
import json
import os
import sys
import time
import urllib.request
import urllib.error

from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.serialization import load_der_public_key

# 1024-bit RSA public key from the vendor SDK dex (java: RSAUtils key = f4812d).
# This is a *public* key (modulus starts ...QC+), not a secret.
SDK_RSA_KEY_B64 = (
    "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC+RO5/amIJ96LEzlVwWE8ZbIvmdzquqcKNg"
    "HRzivSX7+AtQYT52hgmcEWbP2dgNvElxgOadrBkw+xEYwuglcBoPubirpQJyKGgTbUSDNt357"
    "vOEOLiUT4pcMeQix/aDY8INulFWMuo7+SPrGyBtTVD9US1k7k6bEQ3MXFjh19hbQIDAQAB"
)


def sdk_public_key():
    """Parse the embedded 1024-bit RSA public key (f4812d)."""
    key = load_der_public_key(base64.b64decode(SDK_RSA_KEY_B64))
    n, e = key.public_numbers().n, key.public_numbers().e
    mlen = (n.bit_length() + 7) // 8

    def pub(b64):
        ct = base64.b64decode(b64 + "=" * ((-len(b64)) % 4))
        while len(ct) > mlen:  # vendor writes 129-byte ct (leading 0x00)
            ct = ct[1:]
        m = pow(int.from_bytes(ct, "big"), e, n)
        raw = m.to_bytes(mlen, "big")
        if raw[0] == 0 and raw[1] == 1:
            return raw[raw.find(b"\x00", 2) + 1:]
        if raw[0] == 0 and raw[1] == 2:
            return raw[raw.rfind(b"\x00") + 1:]
        return raw.lstrip(b"\x00")
    return pub


PUB_OP = None
CREDS = None


def creds():
    global CREDS
    if CREDS is None:
        path = os.environ.get("ICAN_CLOUD_CREDS", os.path.expanduser("~/.config/ican-cloud-creds.json"))
        CREDS = json.loads(open(path).read())
    return CREDS


def fetch(sn):
    global PUB_OP
    if PUB_OP is None:
        PUB_OP = sdk_public_key()
    c = creds()
    ts = str(int(time.time() * 1000))
    sign = hashlib.md5(("appId=%s&deviceSn=%s&timestamp=%s&key=%s"
                        % (c["appId"], sn, ts, c["appSecret"])).encode()).hexdigest().upper()
    url = "%ssino-deviceaccess/sdk/cgm-code-encrypt?appId=%s&deviceSn=%s&timestamp=%s&sign=%s" % (
        c["base"], c["appId"], sn, ts, sign)
    with urllib.request.urlopen(url, timeout=90) as r:
        js = json.loads(r.read().decode())
    data = js["data"]
    aes = PUB_OP(data["k"]).decode("latin-1")
    iv = aes[:16].encode()
    raw = base64.b64decode(data["v"])
    dec = Cipher(algorithms.AES(aes.encode()), modes.CBC(iv), backend=default_backend()).decryptor()
    out = dec.update(raw) + dec.finalize()
    if out[:1] == b"\x1f" and out[1:3] == b"\x8b":
        out = gzip.decompress(out)
    env = json.JSONDecoder().raw_decode(out.decode("utf-8", "replace"))[0]
    return aes, env["data"]["code"]


def main(argv):
    outdir = None
    sns = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--out":
            outdir = argv[i + 1] if i + 1 < len(argv) else None
            i += 2
            continue
        sns.append(arg)
        i += 1
    if not sns:
        print(__doc__)
        return 2
    for sn in sns:
        ts = str(int(time.time() * 1000))
        try:
            aes, code = fetch(sn)
        except urllib.error.HTTPError as ex:
            print(sn, "HTTP", ex.code, ex.read().decode("utf-8", "replace")[:200])
            continue
        except Exception as ex:
            print(sn, "ERR", type(ex).__name__, ex)
            continue
        print(sn, "aesKey", aes)
        print(json.dumps(code, sort_keys=True))
        if outdir:
            path = os.path.join(outdir, "ican-cgm-code-%s.json" % sn)
            with open(path, "w") as fh:
                fh.write(code)
            print(sn, "table saved to", path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
