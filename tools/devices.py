#!/usr/bin/env python3
"""VibeNokia devices: managed locally, directly in the server's sessions.db.

In MODE=full there is no network admin API (/admin/* returns 404), so a leaked
api.key cannot be used to register a rogue device. Moving or revoking a phone
is done with this script, run on the server itself.

  python3 tools/devices.py list
  python3 tools/devices.py import --device-id abcd1234 --secret-file /path/to/x.secret
  VIBENOKIA_DEV_SECRET=... python3 tools/devices.py import --device-id abcd1234
  python3 tools/devices.py revoke --device-id abcd1234

DB path: --db, else env VIBENOKIA_DB, else server/data/sessions.db.
Secrets are never printed. A running server needs no restart: the device is
read from the DB on every request. Revocation takes effect immediately.
"""
import argparse
import os
import re
import secrets
import sqlite3
import sys

DEFAULT_DB = "server/data/sessions.db"

SCHEMA = '''CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    secret TEXT NOT NULL,
    secret_pending TEXT DEFAULT '',
    name TEXT DEFAULT '',
    counter INTEGER DEFAULT 0,
    revoked INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen TIMESTAMP
)'''


def _db(path):
    conn = sqlite3.connect(path, timeout=10)
    conn.execute(SCHEMA)
    return conn


def _secret(args):
    if args.gen_secret_file:
        sec = secrets.token_hex(32)
        fd = os.open(args.gen_secret_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.write(fd, sec.encode())
        os.close(fd)
        return sec
    if args.secret_file:
        with open(args.secret_file) as f:
            return f.read().strip()
    sec = os.environ.get("VIBENOKIA_DEV_SECRET", "").strip()
    if not sec and not sys.stdin.isatty():
        sec = sys.stdin.read().strip()
    return sec


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=os.environ.get("VIBENOKIA_DB", DEFAULT_DB))
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list")
    i = sub.add_parser("import", help="register/move a device (device_id + secret)")
    i.add_argument("--device-id", required=True)
    i.add_argument("--name", default="phone")
    i.add_argument("--secret-file", help="file with the secret (else env VIBENOKIA_DEV_SECRET or stdin)")
    i.add_argument("--gen-secret-file", help="generate a new secret into this file (0600), for tests")
    r = sub.add_parser("revoke")
    r.add_argument("--device-id", required=True)
    u = sub.add_parser("unrevoke")
    u.add_argument("--device-id", required=True)
    a = ap.parse_args()

    if not os.path.exists(a.db) and a.cmd != "import":
        sys.exit("no database: %s" % a.db)
    conn = _db(a.db)
    try:
        if a.cmd == "list":
            rows = conn.execute("SELECT device_id, name, counter, revoked, created_at, last_seen, "
                                "secret_pending != '' FROM devices ORDER BY created_at").fetchall()
            print("%-18s %-12s %10s  %-7s %-19s %-19s %s" % ("device_id", "name", "counter",
                                                             "revoked", "created", "last_seen", "rotation"))
            for d, n, c, rv, cr, ls, rot in rows:
                print("%-18s %-12s %10s  %-7s %-19s %-19s %s" % (d, (n or "")[:12], c, "yes" if rv else "no",
                                                                 cr or "", ls or "", "pending" if rot else ""))
            print("total: %d" % len(rows))
        elif a.cmd == "import":
            if not re.match(r"^[0-9A-Za-z_-]{4,64}$", a.device_id):
                sys.exit("device_id: 4-64 characters [0-9A-Za-z_-]")
            sec = _secret(a)
            if not re.match(r"^[0-9a-f]{64}$", sec or ""):
                sys.exit("device secret: 64 hex characters required")
            conn.execute("INSERT OR REPLACE INTO devices (device_id, secret, secret_pending, name, "
                         "counter, revoked, last_seen) VALUES (?, ?, '', ?, 0, 0, CURRENT_TIMESTAMP)",
                         (a.device_id, sec, a.name[:32]))
            conn.commit()
            print("ok: device %s registered in %s" % (a.device_id, a.db))
        else:
            val = 1 if a.cmd == "revoke" else 0
            cur = conn.execute("UPDATE devices SET revoked=? WHERE device_id=?", (val, a.device_id))
            conn.commit()
            if not cur.rowcount:
                sys.exit("no such device: %s" % a.device_id)
            print("ok: %s %s" % (a.device_id, "revoked" if val else "active again"))
    finally:
        conn.close()


if __name__ == "__main__":
    main()
