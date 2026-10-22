#!/usr/bin/env python3
"""VibeNokia phone emulator: signs requests exactly like the client
(client/src/vibenokia/DeviceAuth.java + Crypto.java) and sends them to the server.

Signature: X-Sig = HMAC-SHA256(secret, "METHOD\\npath?query\\ncounter\\nsha256(body)\\nhost:port"),
headers X-Dev, X-Cnt, X-Host, X-Sig. The request counter only grows and is kept
in a state file (like RMS on the phone); it can be set manually (--cnt) to test
the replay window.

No secrets are stored in this file. The device secret comes from env
VIBENOKIA_DEV_SECRET or --secret-file; the admin key (only for `import`) from
env VIBENOKIA_ADMIN_KEY or --admin-key-file. Secrets are never printed.

Examples:
  export VIBENOKIA_DEV_SECRET=...            # 64 hex
  phone_emu.py --url http://<server>:8086 --dev abcd1234 ping
  phone_emu.py --url ... --dev abcd1234 chat "hello" --model haiku
  phone_emu.py --url ... --dev abcd1234 voice recording.amr --model haiku
  phone_emu.py --url ... --dev abcd1234 sessions
  phone_emu.py --url ... --dev abcd1234 raw GET /api/update
  # register a device: with MODE=full there is no network admin API, so do it locally on the server:
  #   python3 tools/devices.py --db <sessions.db> import --device-id emu00001 --gen-secret-file /path/to/secret
  # on a host that exposes the admin API, use the admin key over the network:
  phone_emu.py --url http://<host>:8085 --dev emu00001 import --secret-file /path/to/secret
"""
import argparse
import hashlib
import hmac
import json
import os
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "Snake/2.6"


def sign_headers(dev, secret, method, path, cnt, body, host):
    body_hash = hashlib.sha256(body or b"").hexdigest()
    msg = "%s\n%s\n%s\n%s\n%s" % (method, path, cnt, body_hash, host)
    sig = hmac.new(secret.encode("utf-8"), msg.encode("utf-8"), hashlib.sha256).hexdigest()
    return {"X-Dev": dev, "X-Cnt": str(cnt), "X-Host": host, "X-Sig": sig}


class Phone:
    def __init__(self, url, dev, secret, host=None, state=None, timeout=300):
        self.url = url.rstrip("/")
        p = urllib.parse.urlsplit(self.url)
        self.host = host or p.netloc          # the phone signs host:port from its own URL
        self.dev = dev
        self.secret = secret
        self.timeout = timeout
        self.state = state or os.path.expanduser("~/.vibenokia_emu_%s.json" % dev)

    # --- request counter (like RMS on the phone: only grows) ---
    def _load_cnt(self):
        try:
            with open(self.state) as f:
                return int(json.load(f).get("cnt", 0))
        except Exception:
            return int(time.time())            # no state: certainly larger than any previous value

    def _save_cnt(self, cnt):
        try:
            with open(self.state, "w") as f:
                json.dump({"cnt": cnt}, f)
        except Exception:
            pass

    _lock = threading.Lock()

    def next_cnt(self):
        # parallel requests (ping + request, as on the phone): counter is guarded by a lock
        with self._lock:
            cnt = self._load_cnt() + 1
            self._save_cnt(cnt)
            return cnt

    def request(self, method, path, body=b"", headers=None, cnt=None, sign=True):
        """Returns (status code, body str, ms, counter used)."""
        if isinstance(body, str):
            body = body.encode("utf-8")
        if cnt is None:
            cnt = self.next_cnt()
        h = {"User-Agent": UA}
        if sign:
            h.update(sign_headers(self.dev, self.secret, method, path, cnt, body, self.host))
        h.update(headers or {})
        req = urllib.request.Request(self.url + path, data=body if method != "GET" else None,
                                     method=method, headers=h)
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                code, text = r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            code, text = e.code, e.read().decode("utf-8", "replace")
        except Exception as e:
            code, text = 0, "%s: %s" % (e.__class__.__name__, e)
        return code, text, int((time.monotonic() - t0) * 1000), cnt

    def ping(self, **kw):
        return self.request("GET", "/ping", **kw)

    def chat(self, message, session_id="", model="haiku", lang="ru", **kw):
        body = json.dumps({"message": message, "session_id": session_id,
                           "model": model, "lang": lang}, ensure_ascii=False)
        return self.request("POST", "/api/chat", body,
                            {"Content-Type": "application/json"}, **kw)

    def voice(self, audio, session_id="", model="haiku", lang="ru", **kw):
        return self.request("POST", "/api/voice", audio,
                            {"Content-Type": "audio/amr", "X-Session-Id": session_id,
                             "X-Model": model, "X-Lang": lang}, **kw)


def _read_secret(path, env):
    if path:
        with open(path) as f:
            return f.read().strip()
    return os.environ.get(env, "").strip()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", required=True, help="http://host:port of the server/relay")
    ap.add_argument("--host", help="X-Host for the signature (default: host:port from --url)")
    ap.add_argument("--dev", required=True, help="device_id")
    ap.add_argument("--secret-file", help="file with the device secret (else env VIBENOKIA_DEV_SECRET)")
    ap.add_argument("--state", help="request counter file")
    ap.add_argument("--cnt", type=int, help="explicit X-Cnt value (for replay checks)")
    ap.add_argument("--timeout", type=int, default=300)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("ping")
    c = sub.add_parser("chat"); c.add_argument("text"); c.add_argument("--session", default="")
    c.add_argument("--model", default="haiku"); c.add_argument("--lang", default="ru")
    v = sub.add_parser("voice"); v.add_argument("file"); v.add_argument("--session", default="")
    v.add_argument("--model", default="haiku"); v.add_argument("--lang", default="ru")
    sub.add_parser("sessions")
    r = sub.add_parser("raw"); r.add_argument("method"); r.add_argument("path")
    r.add_argument("--body", default="")
    i = sub.add_parser("import", help="register a device with the admin key (only where the admin API is exposed; with MODE=full use tools/devices.py)")
    i.add_argument("--admin-key-file", help="else env VIBENOKIA_ADMIN_KEY")
    i.add_argument("--gen-secret-file", help="generate a device secret into this file (0600)")
    a = ap.parse_args()

    if a.cmd == "import":
        admin = _read_secret(a.admin_key_file, "VIBENOKIA_ADMIN_KEY")
        if a.gen_secret_file:
            sec = secrets.token_hex(32)
            fd = os.open(a.gen_secret_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.write(fd, sec.encode()); os.close(fd)
        else:
            sec = _read_secret(a.secret_file, "VIBENOKIA_DEV_SECRET")
        if not admin or not sec:
            sys.exit("admin key and device secret are required")
        body = json.dumps({"device_id": a.dev, "secret": sec, "name": "emu"}).encode()
        req = urllib.request.Request(a.url.rstrip("/") + "/admin/devices/import", data=body,
                                     method="POST", headers={"X-Api-Key": admin,
                                                             "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                print(resp.status, resp.read().decode())
        except urllib.error.HTTPError as e:
            print(e.code, e.read().decode())
        return

    secret = _read_secret(a.secret_file, "VIBENOKIA_DEV_SECRET")
    if not secret:
        sys.exit("no device secret: set VIBENOKIA_DEV_SECRET or use --secret-file")
    ph = Phone(a.url, a.dev, secret, host=a.host, state=a.state, timeout=a.timeout)
    kw = {"cnt": a.cnt} if a.cnt is not None else {}
    if a.cmd == "ping":
        res = ph.ping(**kw)
    elif a.cmd == "chat":
        res = ph.chat(a.text, a.session, a.model, a.lang, **kw)
    elif a.cmd == "voice":
        with open(a.file, "rb") as f:
            res = ph.voice(f.read(), a.session, a.model, a.lang, **kw)
    elif a.cmd == "sessions":
        res = ph.request("GET", "/api/sessions", **kw)
    else:
        res = ph.request(a.method.upper(), a.path, a.body, **kw)
    code, text, ms, cnt = res
    print("HTTP %s  %d ms  cnt=%d" % (code, ms, cnt))
    print(text)
    sys.exit(0 if code == 200 else 1)


if __name__ == "__main__":
    main()
