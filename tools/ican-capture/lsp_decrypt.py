import base64
import json
import os
import re
import sys
import urllib.parse

TT = re.compile(r"^(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+ICANX\s*:\s(.*)$")
TV = re.compile(r"^(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) [VDIWEF]/ICANX\s*\(\s*(\d+)\):\s(.*)$")
ATTR = re.compile(r"(\w+)=(\S+)")


def label_split(msg, limit=200):
    head = msg[:limit]
    best = None
    for m in re.finditer(r"#(\d+) ", head):
        best = m
    if best is None:
        return None
    label = msg[: best.start()]
    n = int(best.group(1))
    rest = msg[best.end():]
    return label, n, rest


class Log:
    def __init__(self, path):
        self.path = path
        self.events = []
        self.chunks = {}
        with open(path, "r", errors="replace") as f:
            for line in f:
                self.handle(line.rstrip("\r\n"))

    def handle(self, line):
        m = TT.match(line)
        if m:
            t, pid, tid, msg = m.group(2), int(m.group(3)), int(m.group(4)), m.group(5)
        else:
            mv = TV.match(line)
            if not mv:
                return
            t, pid, tid, msg = mv.group(2), int(mv.group(3)), -1, mv.group(4)
        ls = label_split(msg)
        if ls is not None:
            label, n, part = ls
            key = (pid, tid, label)
            prev = self.chunks.get(key)
            if prev is not None and prev[0] == n - 1 and n > 1:
                prev[1].append(part)
                self.chunks[key] = (n, prev[1])
                return
            self.chunks[key] = (n, [part])
            if n != 1:
                return
            return self.event(t, pid, tid, label + part)
        self.event(t, pid, tid, msg)

    def event(self, t, pid, tid, msg):
        if msg.startswith("CIPHER-INIT "):
            d = dict(ATTR.findall(msg))
            self.events.append({"t": t, "pid": pid, "tid": tid, "src": self.path, "kind": "INIT",
                                "alg": d.get("alg"), "mode": d.get("mode"), "keyHex": d.get("keyHex"),
                                "iv": d.get("iv"), "spec": d.get("spec")})
            return
        for k, kind in (("CIPHER-UPD-IN", "UPD"), ("CIPHER-UPD-OUT", "UPD"), ("CIPHER-OUT", "OUT"), ("CIPHER-IN", "IN")):
            if msg.startswith(k + " "):
                d = dict(ATTR.findall(msg))
                i = msg.rfind(" hex=")
                j = msg.rfind(" hex0=")
                h = msg[i + 5:] if i >= 0 and i > j else None
                head = msg[j + 6:] if j >= 0 and j > i else None
                ev = {"t": t, "pid": pid, "tid": tid, "src": self.path, "kind": kind, "alg": d.get("alg"),
                      "len": int(d.get("len") or 0), "hex": h, "head": head}
                if k.startswith("CIPHER-UPD"):
                    ev["dir"] = k.split("-")[2]
                self.events.append(ev)
                return
        if msg.startswith("SECRETKEY "):
            d = dict(ATTR.findall(msg))
            self.events.append({"t": t, "pid": pid, "tid": tid, "src": self.path, "kind": "SECRETKEY",
                                "alg": d.get("alg"), "len": int(d.get("len") or 0), "hex": d.get("hex"), "b64": d.get("b64")})
            return
        if msg.startswith("SQLCIPHER"):
            self.events.append({"t": t, "pid": pid, "tid": tid, "src": self.path, "kind": "SQLCIPHER", "msg": msg})
            return
        mb = re.match(r"BUILDER (\w+) url=(.*)", msg)
        if mb:
            self.events.append({"t": t, "pid": pid, "tid": tid, "src": self.path, "kind": "BUILDER",
                                "method": mb.group(1), "url": mb.group(2)})
            return
        mh = re.match(r"HTTP (\w+) len=(\d+) url=(.*)", msg)
        if mh:
            self.events.append({"t": t, "pid": pid, "tid": tid, "src": self.path, "kind": "HTTP",
                                "method": mh.group(1), "len": int(mh.group(2)), "url": mh.group(3)})
            return
        mg = re.match(r"GSON-OUT src=(.*?) json=(.*)", msg)
        if mg:
            self.events.append({"t": t, "pid": pid, "tid": tid, "src": self.path, "kind": "GSON",
                                "src_obj": mg.group(1), "json": mg.group(2)})
            return
        for k in ("BHDR", "BREQ", "KEYSTORE"):
            if msg.startswith(k + " "):
                self.events.append({"t": t, "pid": pid, "tid": tid, "src": self.path, "kind": k, "msg": msg})
                return


def threadmatch(a, b):
    if a["pid"] != b["pid"]:
        return False
    return a["tid"] < 0 or b["tid"] < 0 or a["tid"] == b["tid"]


def strip_pad(b):
    if b:
        n = b[-1]
        if 1 <= n <= 32 and all(x == n for x in b[-n:]):
            return b[:-n], n
    return b, 0


def b64url_dec(s):
    try:
        d = base64.b64decode(urllib.parse.unquote(s))
        return d.hex()
    except Exception:
        return None


def parse_qs(url):
    try:
        u = urllib.parse.urlsplit(url)
        q = urllib.parse.parse_qs(u.query, keep_blank_values=True)
        return u.scheme + "://" + u.netloc + u.path, q
    except Exception:
        return url, {}


def main():
    logs = sys.argv[1:]
    all_events = []
    for p in logs:
        all_events.extend(Log(p).events)
    all_events.sort(key=lambda e: (e["t"], e["pid"], e["tid"]))

    rsa_wraps, aes_encs, aes_decs, urls, gson, license_ops, consts = [], [], [], [], [], [], []
    for i, e in enumerate(all_events):
        if e["kind"] == "INIT":
            k = i + 1
            while k < len(all_events) and (all_events[k]["kind"] != "INIT" or not threadmatch(all_events[k], e)):
                k += 1
            grp = [x for x in all_events[i + 1:k] if threadmatch(x, e) and x["kind"] in ("OUT", "IN")]
            outs = "".join(x["hex"] for x in grp if x["kind"] == "OUT" and x.get("hex"))
            ins = "".join(x["hex"] for x in grp if x["kind"] == "IN" and x.get("hex"))
            heads = [(x["len"], x["head"]) for x in grp if x["kind"] == "OUT" and x.get("head")]
            lens = [x["len"] for x in grp if x["kind"] == "OUT"]
            alg, mode, key, iv = e.get("alg") or "", e["mode"], e.get("keyHex"), e.get("iv")
            rec = {"t": e["t"], "pid": e["pid"], "alg": alg, "mode": mode, "key": key, "iv": iv, "out_hex": outs,
                   "in_hex": ins, "out_len": lens[0] if lens else None}
            if heads:
                rec["head_len"], rec["head"] = heads[0]
            if alg.startswith("RSA") and mode == "ENC":
                rsa_wraps.append(rec)
            elif alg.startswith("RSA") and mode == "DEC":
                license_ops.append(rec)
            elif alg.startswith("AES/CBC") and mode == "ENC" and key:
                aes_encs.append(rec)
            elif alg.startswith("AES/CBC") and mode == "DEC" and key:
                pt = None
                if outs:
                    data = bytes.fromhex(outs)
                    pt, pad = strip_pad(data)
                    rec["pad"] = pad
                    try:
                        pt = json.loads(pt.decode("utf-8", "replace"))
                    except Exception:
                        pt = pt.decode("utf-8", "replace")
                if heads:
                    try:
                        pt = json.loads(bytes.fromhex(heads[0][1]).decode("utf-8", "replace"))
                    except Exception:
                        pt = bytes.fromhex(heads[0][1]).decode("utf-8", "replace")
                rec["plain"] = pt
                aes_decs.append(rec)
            elif (alg.startswith("AES/ECB") or alg.startswith("AES/CTR")) and key:
                consts.append(rec)
        elif e["kind"] == "BUILDER" or e["kind"] == "HTTP":
            urls.append(e)
        elif e["kind"] == "GSON":
            gson.append(e)

    url2rsa = []
    for u in urls:
        if "icancgm.com" not in (u.get("url") or "") or "key" not in (u.get("url") or ""):
            continue
        base, q = parse_qs(u["url"])
        uh = b64url_dec(q["key"][0]) if "key" in q else None
        dh = b64url_dec(q["data"][0]) if "data" in q else None
        url2rsa.append({"t": u["t"], "pid": u["pid"], "kind": u["kind"], "method": u.get("method"),
                        "base": base, "url": u["url"], "rsa_hex": uh, "data_hex": dh})

    wraps_by_hex = {}
    for r in rsa_wraps:
        if r.get("out_hex"):
            wraps_by_hex.setdefault(r["out_hex"], r)
    reqs = []
    for u in url2rsa:
        w = wraps_by_hex.get(u["rsa_hex"]) if u.get("rsa_hex") else None
        aes = None
        if w is not None:
            cands = [a for a in aes_encs if a.get("key") == w.get("in_hex") or a.get("key") and len(w.get("in_hex") or "") == 64 and a["key"] == w["in_hex"]]
            exact = [a for a in cands if a.get("out_hex") == u.get("data_hex")]
            lm = [a for a in cands if a["pid"] == u["pid"]]
            aes = (exact or lm or cands or [None])[0]
        reqs.append({"t": u["t"], "pid": u["pid"], "method": u["method"], "base": u["base"], "url": u["url"],
                     "aes": aes, "wrapped": w})
    seen = set()
    uniq_reqs = []
    for r in reqs:
        k = (r["base"], r["method"], (r.get("aes") or {}).get("key") or r.get("rsa_hex"))
        if k in seen:
            continue
        seen.add(k)
        uniq_reqs.append(r)

    req_by_aeskey = {}
    for r in uniq_reqs:
        a = r.get("aes")
        if a and a.get("key"):
            req_by_aeskey.setdefault(a["key"], r)
    for d in aes_decs:
        dreq = req_by_aeskey.get(d.get("key"))
        d["endpoint"] = dreq["base"] if dreq else None

    out = {"requests": uniq_reqs, "responses": aes_decs, "license": license_ops, "constant_ops": consts,
           "gson": [{k: g.get(k) for k in ("t", "pid", "src_obj", "json")} for g in gson]}
    print(json.dumps(out, ensure_ascii=False, indent=1, sort_keys=False))


if __name__ == "__main__":
    main()
