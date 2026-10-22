"""
VibeNokia API server: a thin HTTP proxy between a J2ME phone client and the
Claude Code CLI (plus an optional Telegram bridge and voice transcription).

Plain HTTP by design (the phone cannot do modern TLS). Every phone request is
HMAC-signed with a per-device key; see README "Security". By default the server
binds to 127.0.0.1 only.
"""
import asyncio
import base64
import json
import os
import time
import logging
import tempfile
import sqlite3
import uuid
import signal
import shlex
import re
from datetime import datetime
import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, FileResponse, Response
import uvicorn

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(message)s')
logger = logging.getLogger("vibenokia")

# Last line of defense for the log: the bot token (present in every Bot API URL, and httpx
# puts the URL into exception text), Bearer headers, sk-/gsk_ keys.
_LOG_SECRET_RE = re.compile(r"(bot)?\d{6,}:[A-Za-z0-9_\-]{30,}|Bearer\s+\S+"
                            r"|\bsk-[A-Za-z0-9_\-]{6,}|\bgsk_[A-Za-z0-9_\-]{6,}")

class _SecretFilter(logging.Filter):
    def filter(self, record):
        try:
            msg = record.getMessage()
            clean = _LOG_SECRET_RE.sub("***", msg)
            if clean != msg:
                record.msg, record.args = clean, None
        except Exception:
            pass
        return True

logger.addFilter(_SecretFilter())

# docs/openapi disabled: do not expose the API structure to the internet
app = FastAPI(title="VibeNokia API", docs_url=None, redoc_url=None, openapi_url=None)

# Mode "full": Claude + Telegram. (Kept as a constant; the phone reads it from /ping.)
MODE = "full"

# Routes that are closed outright in "tg" mode: everything that spawns processes
# on the server. On a public host they simply do not exist.
CLAUDE_ROUTES = ("/api/chat", "/api/voice", "/api/stop", "/api/sessions")

# --- Brute-force protection: too many misses from one address -> ban ---
FAIL_LIMIT = int(os.getenv("VIBENOKIA_FAIL_LIMIT", "10"))
FAIL_WINDOW = 900          # 15 minutes
_fail_counts = {}          # ip -> [misses, first_miss_time, banned_until]

def _client_ip(request: Request):
    # X-Forwarded-For is NEVER trusted: relays (public relay on :8086, reverse
    # SSH tunnel) are plain TCP forwarders, the header is set by the attacker, and a
    # forged value would bypass the ban or ban someone else's address. Use the TCP peer address only.
    return request.client.host if request.client else "?"

def _key_eq(given, expected):
    """Constant-time key comparison (not stopping at the first mismatching byte)."""
    import hmac as _h
    if not given or not expected:
        return False
    return _h.compare_digest(str(given).encode("utf-8"), str(expected).encode("utf-8"))

_PATH_KEY_RE = re.compile(r"^/(ota|g)/[^/]+(/|$)")

def _safe_path(path):
    """Path for the log: without query, without the key from /ota/<key>/... and /g/<key>/...,
    without control characters."""
    path = (path or "").split("?", 1)[0]
    if _PATH_KEY_RE.match(path) and not path.startswith(("/ota/Snake.", "/ota/NokiaClaude.")):
        path = _PATH_KEY_RE.sub(lambda m: "/%s/***%s" % (m.group(1), m.group(2)), path)
    return re.sub(r"[\x00-\x1f\x7f]", "", path)[:80]

# --- Request bodies: size cap and streamed read with abort ---
MAX_BODY = int(os.getenv("VIBENOKIA_MAX_BODY", str(2 * 1024 * 1024)))   # AMR voice ~1.5 KB/s

async def _read_body_capped(request, limit):
    """Whole body, but at most limit. Returns (bytes|None, error response|None).
    The header length is not trusted: read as a stream and abort on overflow."""
    try:
        if int(request.headers.get("content-length") or 0) > limit:
            return None, JSONResponse(status_code=413, content={"error": "too_large"})
    except ValueError:
        return None, JSONResponse(status_code=400, content={"error": "bad_length"})
    buf = bytearray()
    async for chunk in request.stream():
        buf += chunk
        if len(buf) > limit:
            return None, JSONResponse(status_code=413, content={"error": "too_large"})
    body = bytes(buf)
    request._body = body          # newer Starlette versions hand the cached body to the handler
    async def _replay():
        return {"type": "http.request", "body": body, "more_body": False}
    request._receive = _replay
    return body, None

def _note_bad_key(ip):
    now = time.time()
    if len(_fail_counts) > 5000:   # guard against unbounded dict growth
        _fail_counts.clear()
    rec = _fail_counts.get(ip)
    if not rec or now - rec[1] > FAIL_WINDOW:
        _fail_counts[ip] = [1, now, 0]
        return
    rec[0] += 1
    if rec[0] >= FAIL_LIMIT and rec[2] < now:
        rec[2] = now + FAIL_WINDOW
        logger.warning(f"[SEC] {ip}: {rec[0]} invalid keys, banned for {FAIL_WINDOW // 60} min")

def _is_banned(ip):
    rec = _fail_counts.get(ip)
    return bool(rec and rec[2] > time.time())

# --- Phone request log ---
# The shared uvicorn access log is disabled (the key is part of the /ota/<key>/ path), so
# for signed requests we write our own line: method, path WITHOUT query, status, ms, first
# chars of device_id, address. No bodies, keys or parameters. Pings (every 5 s) are aggregated:
# the first after a quiet period is logged at once, then a "ping xN" summary every PING_LOG_EVERY seconds.
PING_LOG_EVERY = int(os.getenv("VIBENOKIA_PING_LOG_EVERY", "300"))
_ping_agg = {}             # dev6 -> {"n", "since", "ip", "ms", "codes": {code: n}}

def _ping_flush(dev6, rec):
    codes = ",".join(f"{c}×{n}" if n > 1 else str(c) for c, n in sorted(rec["codes"].items()))
    avg = rec["ms"] // max(1, rec["n"])
    logger.info(f"[REQ] ping ×{rec['n']} from dev {dev6} ({rec['ip']}) in "
                f"{int(time.time() - rec['since'])} s, codes {codes}, avg {avg} ms")

def _ping_flush_due(force=False):
    now = time.time()
    for dev6 in list(_ping_agg):
        rec = _ping_agg[dev6]
        if force or now - rec["since"] >= PING_LOG_EVERY:
            if rec["n"]:
                _ping_flush(dev6, rec)
                rec.update(n=0, since=now, ms=0, codes={})
            elif now - rec["since"] >= 2 * PING_LOG_EVERY:
                _ping_agg.pop(dev6, None)       # device went quiet: the next ping counts as the first again

def _access_log(method, path, status, t0, dev, ip):
    try:
        ms = int((time.monotonic() - t0) * 1000)
        dev6 = _VIBE_CTRL.sub("", (dev or "?"))[:6]
        if path == "/ping" and status == 200:
            rec = _ping_agg.get(dev6)
            if rec is None:
                _ping_agg[dev6] = {"n": 0, "since": time.time(), "ip": ip, "ms": 0, "codes": {}}
                logger.info(f"[REQ] GET /ping 200 {ms} ms dev {dev6} {ip} (first ping, then summarized)")
                return
            rec["n"] += 1
            rec["ms"] += ms
            rec["ip"] = ip
            rec["codes"][status] = rec["codes"].get(status, 0) + 1
            _ping_flush_due()
            return
        logger.info(f"[REQ] {method} {_safe_path(path)} {status} {ms} ms dev {dev6} {ip}")
    except Exception:
        pass

async def _ping_log_loop():
    """Flush the ping summary to the log even after pings have stopped."""
    while True:
        await asyncio.sleep(60)
        _ping_flush_due()

# =====================================================================
# Phone authentication: each device has its OWN key, obtained during one-time
# pairing. After pairing the key never travels over the network: every request
# is signed with it (HMAC-SHA256), and replay of an intercepted request is
# prevented by a counter: the server remembers the last one and only accepts a larger one.
# The phone clock plays no part in the scheme (on a Nokia it drifts).
#
# The static key (API_KEY) remains but is now ADMIN-only: it lives on the
# servers only, is not shipped in the app and never travels from the phone.
# =====================================================================

PAIR_TTL = 600          # pairing code lives 10 minutes
# Pairing over the network: enabled on the public tg host, disabled on the full host
# (everything arrives from loopback via relays; the device is moved with tools/devices.py).
PAIRING_ENABLED = os.getenv("VIBENOKIA_PAIRING", "0").strip() in ("1", "true", "yes", "on")
PUBLIC_PATHS = ("/pair/start", "/pair/confirm")
PUBLIC_PREFIXES = ("/ota/",)   # the JAR holds no secrets any more -> served without a key

def _new_hex(n):
    import secrets as _s
    return _s.token_hex(n)

def _dev_row(device_id):
    try:
        conn = sqlite3.connect(DB_PATH, timeout=10)
        cur = conn.execute("SELECT device_id, secret, secret_pending, counter, revoked "
                           "FROM devices WHERE device_id=?", (device_id,))
        row = cur.fetchone()
        conn.close()
        return row
    except Exception:
        return None

def _dev_accept(device_id, counter, promote_pending):
    """Request accepted: advance the counter and, if a new key matched, make it the primary one."""
    try:
        conn = sqlite3.connect(DB_PATH, timeout=10)
        if promote_pending:
            conn.execute("UPDATE devices SET secret=secret_pending, secret_pending='', "
                         "counter=?, last_seen=CURRENT_TIMESTAMP WHERE device_id=?",
                         (counter, device_id))
        else:
            conn.execute("UPDATE devices SET counter=?, last_seen=CURRENT_TIMESTAMP "
                         "WHERE device_id=?", (counter, device_id))
        conn.commit()
        conn.close()
    except Exception as e:
        logger.warning(f"[AUTH] could not update counter {device_id}: {e}")

# Allowed public addresses (host:port from the signature's X-Host). Comma-separated:
# several interchangeable relays into ONE backend, so that the phone's automatic
# fallback arrives with a valid signature from any of them. Empty = do not
# check. Replay on a "foreign host" is still stopped by the monotonic counter.
PUBLIC_HOSTS = set(h.strip() for h in os.getenv("VIBENOKIA_PUBLIC_HOST", "").split(",") if h.strip())

def _sign(secret, method, path, counter, body, host):
    import hmac as _h, hashlib as _hl
    body_hash = _hl.sha256(body or b"").hexdigest()
    msg = "%s\n%s\n%s\n%s\n%s" % (method, path, counter, body_hash, host)
    return _h.new(secret.encode(), msg.encode(), _hl.sha256).hexdigest()

# --- Anti-replay: sliding window, as in IPsec (RFC 4303, 3.4.3) ---
# The phone sends parallel requests (ping + request, sometimes via two relays into one
# backend), so they arrive out of order. Accept a number greater than the last one OR
# a number inside the window last-64..last that has not been seen yet. A repeat is rejected.
# The last number is stored in the database (as before); seen window numbers are kept in memory.
# After a server restart the window is treated as "fully seen": an intercepted old
# request will not pass even if the memory of it is gone.
REPLAY_WINDOW = int(os.getenv("VIBENOKIA_REPLAY_WINDOW", "64"))
_replay_seen = {}          # device_id -> [last, bitmask]; bit i = number last-i was already seen

def _replay_ok(dev, counter, db_last):
    """Check without recording. True means the number can be accepted."""
    st = _replay_seen.get(dev)
    if st is None or st[0] < db_last:
        # first request of the device after start (or another process advanced the database)
        st = [db_last, (1 << REPLAY_WINDOW) - 1]
        _replay_seen[dev] = st
    last, mask = st
    if counter > last:
        return True
    diff = last - counter
    if diff >= REPLAY_WINDOW:
        return False                    # too old
    return not (mask >> diff) & 1       # inside the window: only if not seen yet

def _replay_mark(dev, counter):
    """Request accepted: mark the number. Returns the new last (never decreases)."""
    st = _replay_seen.setdefault(dev, [counter, 0])
    last, mask = st
    full = (1 << REPLAY_WINDOW) - 1
    if counter > last:
        shift = counter - last
        mask = ((mask << shift) | 1) & full if shift < REPLAY_WINDOW else 1
        last = counter
    else:
        mask |= 1 << (last - counter)
    st[0], st[1] = last, mask
    return last

def _check_device(request, body):
    """Returns (ok, reason). Headers: X-Dev, X-Cnt, X-Sig."""
    import hmac as _h
    dev = request.headers.get("x-dev", "")
    cnt = request.headers.get("x-cnt", "")
    sig = request.headers.get("x-sig", "")
    if not dev or not cnt or not sig:
        return False, "no_creds"
    row = _dev_row(dev)
    if not row:
        return False, "unknown_device"
    if row[4]:
        return False, "revoked"
    try:
        counter = int(cnt)
    except Exception:
        return False, "bad_counter"
    if counter <= 0 or not _replay_ok(dev, counter, int(row[3] or 0)):
        return False, "replay"          # this number was already seen (or is outside the window): replay
    path = request.url.path
    if request.url.query:
        path += "?" + request.url.query
    method = request.method
    host = request.headers.get("x-host", "")
    if PUBLIC_HOSTS and host not in PUBLIC_HOSTS:
        return False, "wrong_host"      # signature made for an unknown host
    for secret, pending in ((row[1], False), (row[2], True)):
        if not secret:
            continue
        if _h.compare_digest(_sign(secret, method, path, counter, body, host), sig):
            # mark the number ONLY after a valid signature: otherwise foreign junk with
            # a matching number would "eat" it from the phone's genuine request
            last = _replay_mark(dev, counter)
            _dev_accept(dev, last, pending)
            return True, "ok"
    return False, "bad_signature"

_NOT_FOUND = {"detail": "Not Found"}  # same body as FastAPI for an unknown path

@app.middleware("http")
async def check_api_key(request: Request, call_next):
    # EVERY request (including serving the jar /ota/*) requires a key, otherwise API_KEY
    # could be extracted from the public jar. The key is accepted in the X-Api-Key header
    # OR in the query (?k=...); the latter is needed for OTA installation from the
    # phone browser, which cannot send custom headers.
    ip = _client_ip(request)
    path = request.url.path

    # No network admin API: devices are managed locally with tools/devices.py.
    if path.startswith("/admin"):
        return JSONResponse(status_code=404, content=_NOT_FOUND)

    # Pairing is off by default: answer like a non-existent path
    if path in PUBLIC_PATHS and not PAIRING_ENABLED:
        return JSONResponse(status_code=404, content=_NOT_FOUND)

    # Pairing and installer download are available without a key: the JAR holds no
    # secrets, and pairing is protected by a one-time code (plus the shared ban on misses).
    if path in PUBLIC_PATHS or path.startswith(PUBLIC_PREFIXES):
        if _is_banned(ip):
            return JSONResponse(status_code=429, content={"error": "too_many_attempts"})
        return await call_next(request)

    # The phone's normal path: a signature with the device's own key
    if request.headers.get("x-dev"):
        t0 = time.monotonic()
        dev = request.headers.get("x-dev", "")
        # Body cap BEFORE signature verification: otherwise anyone could make the server
        # read and hash gigabytes. An AMR voice message fits with a large margin.
        body, err = await _read_body_capped(request, MAX_BODY)
        if err:
            if _is_banned(ip):
                err = JSONResponse(status_code=429, content={"error": "too_many_attempts"})
            _access_log(request.method, path, err.status_code, t0, dev, ip)
            return err
        # Check the signature BEFORE the address ban. Through relays on :8086 everyone
        # arrives from 127.0.0.1, and a handful of scanner misses banned that address, so
        # the phone silently got 429. A valid signature (HMAC with the device key + a number
        # from the window) cannot be guessed, so the address ban does not apply to it. The ban
        # applies to invalid signatures, unsigned requests and the admin key.
        ok, why = _check_device(request, body)
        if not ok:
            loop = ip in ("127.0.0.1", "::1")
            if _is_banned(ip):
                _access_log(request.method, path, 429, t0, dev, ip)
                return JSONResponse(status_code=429, content={"error": "too_many_attempts"})
            # Behind a tunnel all requests come from loopback: banning "127.0.0.1"
            # would lock ourselves out. Count misses per device instead.
            _note_bad_key((dev or "?") if loop else ip)
            logger.warning(f"[SEC] {ip} -> {_safe_path(path)}: signature rejected ({why})")
            _access_log(request.method, path, 403, t0, dev, ip)
            return JSONResponse(status_code=403, content={"error": why})
        # A valid signature does NOT lift the ban on the ADDRESS (it may be deserved: a scanner
        # behind the same relay); only the device's miss counter is reset.
        _fail_counts.pop(dev, None)
        if MODE == "tg" and path.startswith(CLAUDE_ROUTES):
            _access_log(request.method, path, 404, t0, dev, ip)
            return JSONResponse(status_code=404, content={"error": "not_found"})
        status = 500
        try:
            resp = await call_next(request)
            status = resp.status_code
            return resp
        finally:
            _access_log(request.method, path, status, t0, dev, ip)

    # Only the admin key remains: used from the server side only, it is not in the JAR
    key = request.headers.get("x-api-key", "") or request.query_params.get("k", "")
    # The third way to pass the key is in the path (/ota/<key>/...): old phone
    # stacks break on query strings, and the phone browser cannot send headers.
    if not key and (request.url.path.startswith("/ota/") or request.url.path.startswith("/g/")):
        parts = request.url.path.split("/")
        if len(parts) > 2 and _key_eq(parts[2], API_KEY):
            key = parts[2]

    if not _key_eq(key, API_KEY):
        # Behind the :8086 relay everyone is 127.0.0.1. Scanner misses from loopback are
        # collected in their own bucket: banning "127.0.0.1" itself would close /ota/* to
        # the phone (over-the-air update), and a valid signature no longer lifts that ban.
        bucket = "admin@loopback" if ip in ("127.0.0.1", "::1") else ip
        if _is_banned(bucket):
            return JSONResponse(status_code=429, content={"error": "too_many_attempts"})
        _note_bad_key(bucket)
        logger.warning(f"[SEC] {ip} -> {_safe_path(request.url.path)}: invalid key")
        return JSONResponse(status_code=403, content={"error": "forbidden"})

    # A valid admin key passes but does NOT lift the address ban (it used to, back when
    # the phone used it). A copy of the key sits on a public relay host and is considered
    # leaked: its holder must not be able to reset the ban on scanners behind the relay
# or on pairing-code guessing.
    # The admin key does NOT execute commands: /api/chat,/voice,/stop,/sessions work only
    # with a device signature (X-Dev). Otherwise a leaked api.key (a copy of which sits
    # on a public host without a firewall) would let anyone launch Claude on the server via a relay.
    # The phone always calls command routes with a signature, not with this key, so this is safe.
    if request.url.path.startswith(CLAUDE_ROUTES):
        return JSONResponse(status_code=404, content={"error": "not_found"})

    return await call_next(request)

CLAUDE_TIMEOUT = int(os.getenv("CLAUDE_TIMEOUT", "120"))
MAX_RESPONSE_BYTES = 25000
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.getenv("VIBENOKIA_DATA", os.path.join(BASE_DIR, "data"))
DB_PATH = os.getenv("VIBENOKIA_DB", os.path.join(DATA_DIR, "sessions.db"))
CLAUDE_CWD = os.getenv("VIBENOKIA_CLAUDE_CWD", os.path.join(DATA_DIR, "workdir"))
GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")  # key is NOT in code: taken from the environment (revoke a leaked one)

# --- Voice: Gemini 2.5 Flash via OpenRouter (Groq Whisper is the fallback) ---
OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")
STT_MODEL = "google/gemini-2.5-flash"
STT_PROMPT = (
    "Transcribe the speech in this audio verbatim and in full, with correct punctuation, in the "
    "language spoken (usually Russian or English). Return ONLY the transcript: no comments, quotes or markdown."
)

# alias from the client -> CLI alias. Short names are used: claude resolves them itself to
# the CURRENT generation (opus->Opus 5, etc.), so they do not go stale like a pinned id.
MODEL_MAP = {"opus": "opus", "sonnet": "sonnet", "haiku": "haiku"}

APP_VERSION = "2.6.4"
OTA_DIR = os.getenv("VIBENOKIA_OTA_DIR", os.path.join(BASE_DIR, "ota"))
# Game catalog: the same JAR/JAD files that are put on the memory card, but installed
GAMES_DIR = os.getenv("VIBENOKIA_GAMES_DIR", os.path.join(BASE_DIR, "games"))
# Serving the catalog is OFF by default: the phone browser does not fetch it, and
# an extra open route is unnecessary. Enabled only explicitly:
# VIBENOKIA_GAMES=1 (then /g/<key> is live again).
GAMES_ENABLED = os.getenv("VIBENOKIA_GAMES", "0").strip() in ("1", "true", "yes", "on")
OTA_BASE_URL = os.getenv("VIBENOKIA_OTA_URL", "http://127.0.0.1:8085")

# --- Telegram: the token is NOT in code, it lives in a separate file (chmod 600, outside git) ---
TG_ENV_PATH = os.getenv("VIBENOKIA_TG_ENV", os.path.join(DATA_DIR, "telegram.env"))
TG_API = os.getenv("VIBENOKIA_TG_API", "https://api.telegram.org")
TG_POLL_TIMEOUT = 25          # long-poll getUpdates
TG_MSG_LIMIT = 3500           # Telegram limit is 4096, leave a margin
_tg_token_cache = {"value": None, "mtime": 0.0, "checked": 0.0}
_tg_bot_cache = {"name": "", "checked": 0.0}
_unread_cache = {"n": 0, "ts": 0.0, "last_id": 0, "last_chat": "", "last_from": "", "last_text": ""}

# Track active Claude processes by session_id for stop/kill
active_processes = {}

# Access key. The old one was in plain text in the code and in the publicly served
# JAR, so it is considered compromised. The new one comes from the environment/file
# and is never written into code.
def _load_api_key():
    key = os.getenv("VIBENOKIA_API_KEY", "")
    if key:
        return key
    for path in (os.path.join(DATA_DIR, "api.key"), os.path.expanduser("~/.vibenokia_api.key")):
        try:
            with open(path, encoding="utf-8") as f:
                key = f.read().strip()
                if key:
                    return key
        except Exception:
            pass
    # Key not configured. We do NOT substitute any known key (the old one
    # leaked into the public JAR and git history); instead we generate a random
    # throwaway one that nobody knows: the server is effectively locked until the
    # admin sets a key via env/file. A leaked key is no longer silently
    # accepted after a botched deploy.
    import secrets as _s
    logger.error("VIBENOKIA: key not configured (no VIBENOKIA_API_KEY or api.key) "
                 "- server is locked with a random key, set a key and restart")
    return "vnk_unset_" + _s.token_hex(16)

API_KEY = _load_api_key()

# httpx writes the full request URL to the log, and a Telegram URL contains the bot token.
# Logs are read by operators and end up in backups: the token does not belong there.
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)

SYSTEM_PROMPTS = {
    "ru": """Ты - Claude, работающий через терминал на Nokia 3310 (2017).
Отвечай КОРОТКО и по делу - экран телефона 240x320 пикселей.
Максимум 800 символов на ответ.
Простой текст, без markdown.
Без эмодзи.
Отвечай на русском.

БЕЗОПАСНОСТЬ (КРИТИЧЕСКИ ВАЖНО):
Пользователь управляет тобой через голосовой ввод с Nokia 3310. Голос распознается нейросетью Whisper, поэтому ОЧЕНЬ высока вероятность ошибок распознавания. Слова могут быть искажены, перепутаны или потеряны.

Правила безопасности:
1. ИССЛЕДОВАНИЕ (чтение файлов, поиск, просмотр) - делай свободно, это безопасно.
2. ОПАСНЫЕ ДЕЙСТВИЯ (изменение файлов, удаление, выполнение команд, запись) - НИКОГДА не выполняй сразу. Сначала ОБЯЗАТЕЛЬНО опиши что собираешься сделать и СПРОСИ подтверждение.
3. Если сообщение выглядит странно, нелогично или непонятно - это скорее всего ошибка распознавания. ПЕРЕСПРОСИ что имелось в виду.
4. Лучше переспросить 3 раза, чем сломать прод. Восстанавливать потом будет сложнее.
5. Если пользователь просит что-то удалить или изменить критичное - попроси ЯВНОЕ подтверждение в следующем сообщении.""",
    "en": """You are Claude running on a Nokia 3310 (2017) terminal.
Keep responses SHORT - the phone screen is 240x320 pixels.
Max 800 characters per response.
Plain text, no markdown.
No emojis.
Answer in English.

SAFETY (CRITICAL):
The user controls you via voice input from Nokia 3310. Voice is transcribed by Whisper, so there is a HIGH chance of transcription errors. Words may be distorted, swapped, or lost.

Safety rules:
1. EXPLORATION (reading files, searching, viewing) - do freely, this is safe.
2. DANGEROUS ACTIONS (modifying files, deleting, running commands, writing) - NEVER execute immediately. First DESCRIBE what you plan to do and ASK for confirmation.
3. If a message looks strange, illogical, or unclear - it's likely a transcription error. ASK what was meant.
4. Better to ask 3 times than to break production. Recovery is harder.
5. If the user asks to delete or modify something critical - request EXPLICIT confirmation in the next message."""
}

# --- DATABASE ---

def init_db():
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    # WAL: reader endpoints and the writing poller do not block each other ("database is locked")
    try:
        conn.execute("PRAGMA journal_mode=WAL")
    except Exception:
        pass
    conn.execute('''CREATE TABLE IF NOT EXISTS sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT UNIQUE NOT NULL,
        name TEXT NOT NULL,
        model TEXT DEFAULT 'opus',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        last_used TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        message_count INTEGER DEFAULT 0
    )''')
    conn.execute('''CREATE TABLE IF NOT EXISTS state (
        key TEXT PRIMARY KEY,
        value TEXT
    )''')
    conn.execute('''CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )''')
    # --- Telegram ---
    conn.execute('''CREATE TABLE IF NOT EXISTS tg_contacts (
        chat_id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        username TEXT DEFAULT '',
        kind TEXT DEFAULT 'private',
        unread INTEGER DEFAULT 0,
        last_ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )''')
    conn.execute('''CREATE TABLE IF NOT EXISTS tg_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        chat_id TEXT NOT NULL,
        direction TEXT NOT NULL,
        kind TEXT DEFAULT 'text',
        text TEXT DEFAULT '',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )''')
    conn.execute("CREATE INDEX IF NOT EXISTS idx_tg_msg_chat ON tg_messages(chat_id, id)")
    # --- Paired devices: each has its OWN key, revocable individually ---
    conn.execute('''CREATE TABLE IF NOT EXISTS devices (
        device_id TEXT PRIMARY KEY,
        secret TEXT NOT NULL,
        secret_pending TEXT DEFAULT '',
        name TEXT DEFAULT '',
        counter INTEGER DEFAULT 0,
        revoked INTEGER DEFAULT 0,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        last_seen TIMESTAMP
    )''')
    # One-time pairing codes: live for minutes, consumed once
    conn.execute('''CREATE TABLE IF NOT EXISTS pairings (
        code TEXT PRIMARY KEY,
        created REAL NOT NULL,
        used INTEGER DEFAULT 0
    )''')
    conn.commit()
    conn.close()

def get_db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn

def get_current_session_id():
    conn = get_db()
    row = conn.execute("SELECT value FROM state WHERE key='current_session'").fetchone()
    conn.close()
    return row['value'] if row else None

def set_current_session_id(session_id):
    conn = get_db()
    conn.execute("INSERT OR REPLACE INTO state (key, value) VALUES ('current_session', ?)", (session_id,))
    conn.commit()
    conn.close()

def create_session(name, model="opus"):
    session_id = str(uuid.uuid4())
    conn = get_db()
    conn.execute("INSERT INTO sessions (session_id, name, model) VALUES (?, ?, ?)", (session_id, name, model))
    conn.commit()
    conn.close()
    set_current_session_id(session_id)
    return session_id

def list_sessions():
    conn = get_db()
    rows = conn.execute("SELECT session_id, name, model, message_count, last_used FROM sessions ORDER BY last_used DESC LIMIT 20").fetchall()
    conn.close()
    return [dict(r) for r in rows]

def get_session(session_id):
    conn = get_db()
    row = conn.execute("SELECT * FROM sessions WHERE session_id=?", (session_id,)).fetchone()
    conn.close()
    return dict(row) if row else None

def resolve_claude_session(session_id):
    """Return session_id only if it's a real Claude session (message_count > 0)."""
    if not session_id:
        return None
    session = get_session(session_id)
    if session and session.get("message_count", 0) > 0:
        return session_id  # real Claude session, safe to --resume
    return None  # first message, don't use --resume

def update_session_usage(session_id, real_session_id=None):
    try:
        conn = get_db()
        if real_session_id and real_session_id != session_id:
            conn.execute("UPDATE sessions SET session_id=?, last_used=CURRENT_TIMESTAMP, message_count=message_count+1 WHERE session_id=?", (real_session_id, session_id))
            conn.commit()
            conn.close()
            set_current_session_id(real_session_id)
        else:
            conn.execute("UPDATE sessions SET last_used=CURRENT_TIMESTAMP, message_count=message_count+1 WHERE session_id=?", (session_id,))
            conn.commit()
            conn.close()
    except Exception as e:
        logger.error(f"update_session_usage error: {e}")

def delete_session(session_id):
    conn = get_db()
    conn.execute("DELETE FROM sessions WHERE session_id=?", (session_id,))
    conn.execute("DELETE FROM messages WHERE session_id=?", (session_id,))
    conn.commit()
    conn.close()
    current = get_current_session_id()
    if current == session_id:
        set_current_session_id("")

def save_message(session_id, role, content):
    try:
        conn = get_db()
        conn.execute("INSERT INTO messages (session_id, role, content) VALUES (?, ?, ?)",
                     (session_id, role, content[:2000]))
        conn.commit()
        conn.close()
    except Exception as e:
        logger.error(f"save_message error: {e}")

def get_session_history(session_id, limit=4):
    """Get last N message pairs (user+assistant) for a session."""
    conn = get_db()
    rows = conn.execute(
        "SELECT role, content FROM messages WHERE session_id=? ORDER BY id DESC LIMIT ?",
        (session_id, limit * 2)
    ).fetchall()
    conn.close()
    # Reverse to chronological order
    return [{"role": r["role"], "content": r["content"]} for r in reversed(rows)]

def auto_name_session(session_id, message):
    """Name session after first 5 words of first message."""
    session = get_session(session_id)
    if not session or session.get("message_count", 0) > 0:
        return  # only rename on first message
    words = message.strip().split()[:5]
    name = " ".join(words)
    if len(name) > 40:
        name = name[:40]
    if name:
        rename_session(session_id, name)

def rename_session(session_id, new_name):
    conn = get_db()
    conn.execute("UPDATE sessions SET name=? WHERE session_id=?", (new_name, session_id))
    conn.commit()
    conn.close()

# --- CLAUDE ---

def _claude_cwd():
    os.makedirs(CLAUDE_CWD, exist_ok=True)
    return CLAUDE_CWD

def _clean_env():
    env = os.environ.copy()
    env.pop("CLAUDECODE", None)
    env.pop("CLAUDE_CODE_ENTRYPOINT", None)
    # Never hand the server's own secrets to child processes (Claude may run tools if you allow them).
    for k in list(env):
        if k.startswith(("VIBENOKIA_", "TELEGRAM_", "TG_BOT_", "OPENROUTER_", "GROQ_")):
            env.pop(k, None)
    return env

def _parse_tool(name):
    n = (name or "").lower()
    if "websearch" in n or "webfetch" in n:
        return "searching"
    elif "read" in n or "glob" in n or "grep" in n:
        return "reading"
    elif "write" in n:
        return "writing"
    elif "edit" in n:
        return "editing"
    elif "bash" in n:
        return "running"
    return "thinking"

# Command that launches Claude Code. Override to wrap it (container, wrapper script...).
CLAUDE_CMD = os.getenv("VIBENOKIA_CLAUDE_CMD", "claude").split()
# Extra CLI flags for every Claude call, e.g. "--allowedTools Read,Grep,Glob".
# Default is none: with --permission-mode dontAsk Claude cannot use tools that are not pre-approved.
CLAUDE_EXTRA_ARGS = shlex.split(os.getenv("VIBENOKIA_CLAUDE_ARGS", ""))

# --- OpenCode as an optional second engine. OFF by default: its agents may run tools per YOUR
# opencode config; enable with VIBENOKIA_OPENCODE=1 only if you accept that. ---
OPENCODE_ENABLED = os.getenv("VIBENOKIA_OPENCODE", "0").strip() in ("1", "true", "yes", "on")
OPENCODE_CMD = os.getenv("VIBENOKIA_OPENCODE_CMD", "opencode").split()

_VIBE_CTRL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")   # control chars never go into argv

# List of models for the phone (id -> label). Source for /api/models.
#   Claude     - by subscription.
#   opencode/* - ONLY the free Zen pool (cost 0). There is no paid account/balance,
#   so opencode-go/* and paid opencode/* are neither offered to the phone nor
#   launched at all.
# The free pool is picked up automatically from `opencode models opencode --verbose`;
# the list hardcoded below is only a fallback until the first successful poll
# (verified with live calls).
PHONE_MODELS = [
    ("opus", "Opus"),
    ("sonnet", "Sonnet"),
    ("haiku", "Haiku"),
]

def is_opencode_model(model):
    return OPENCODE_ENABLED and bool(model) and (model.startswith("opencode/") or model.startswith("opencode-go/"))

_CLAUDE_MODELS = [(m, l) for m, l in PHONE_MODELS if not is_opencode_model(m)]
_FALLBACK_FREE = []

# Cache of the fresh list and model state.
_free_cache = {"ts": 0.0, "fail_ts": 0.0, "models": None, "busy": False}
_FREE_TTL = float(os.getenv("VIBENOKIA_FREE_TTL", "3600"))        # poll the list once an hour
_FREE_RETRY = float(os.getenv("VIBENOKIA_FREE_RETRY", "300"))     # after an error: not sooner than 5 min
_DEAD_TTL = float(os.getenv("VIBENOKIA_OC_DEAD_TTL", "1800"))     # a dead model is hidden for 30 min
_PROBE_TTL = float(os.getenv("VIBENOKIA_OC_PROBE_TTL", "21600"))  # recheck a live one every 6 h
_PROBE_ON = os.getenv("VIBENOKIA_OC_PROBE", "1") != "0"
_oc_dead = {}    # id -> (hidden until, reason)
_oc_ok = {}      # id -> when it last answered
_probe_state = {"busy": False}

# Labels: 240px screen, menu ~16 chars, terminal header ~7: short and distinguishable.
_LABEL_DROP = ("free", "preview", "contributor", "flash")
_LABEL_ABBR = {"nemotron": "Nemo", "lightning": "Light", "spark": ""}

def _short_label(name, mid):
    base = (name or mid.split("/")[-1]).replace("-V", " ").replace("-", " ").replace("(", " ").replace(")", " ")
    words = []
    for w in base.split():
        lw = w.lower()
        if lw in _LABEL_DROP:
            continue
        w = _LABEL_ABBR.get(lw, w)
        if w:
            words.append(w)
    lab = " ".join(words) or mid.split("/")[-1]
    return lab[:16].rstrip()

def _oc_bin_cmd(inner_timeout):
    """OPENCODE_CMD with `timeout N` before opencode itself: opencode may run
    in a container (docker exec ... opencode), and killing the docker exec client does
    not kill it there; the inner timeout guarantees a hung process dies."""
    cmd = list(OPENCODE_CMD)
    if cmd and os.path.basename(cmd[-1]) == "opencode":
        cmd = cmd[:-1] + ["timeout", "-k", "5", str(int(inner_timeout)), cmd[-1]]
    return cmd

async def _oc_exec(args, timeout):
    """Run opencode, return (rc, stdout). A hung process is killed as a group on timeout."""
    proc = await asyncio.create_subprocess_exec(
        *(_oc_bin_cmd(timeout) + args),
        stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL, cwd="/tmp", env=_clean_env(),
        limit=4 * 1024 * 1024, start_new_session=True)
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout + 15)
    except asyncio.TimeoutError:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
        try:
            await asyncio.wait_for(proc.wait(), timeout=5)
        except Exception:
            pass
        raise
    return proc.returncode, out.decode("utf-8", "replace")

def _parse_free(text):
    """From the output of `opencode models opencode --verbose`: free models only.
    Free = present in the catalog (release_date non-empty: models from a local
    opencode.json such as union-alpha have no price and look like cost 0),
    not deprecated, cost.input == cost.output == 0 explicitly."""
    dec = json.JSONDecoder()
    models, seen = [], set()
    for mt in re.finditer(r"^\{", text, re.M):
        try:
            obj, _ = dec.raw_decode(text, mt.start())
        except Exception:
            continue
        if not isinstance(obj, dict) or not obj.get("id"):
            continue
        if obj.get("providerID", "opencode") != "opencode":
            continue
        if str(obj.get("status") or "active") in ("deprecated", "disabled"):
            continue
        if not obj.get("release_date"):
            continue
        cost = obj.get("cost")
        if not isinstance(cost, dict) or cost.get("input") != 0 or cost.get("output") != 0:
            continue
        mid = "opencode/" + obj["id"]
        if mid in seen:
            continue
        seen.add(mid)
        models.append((mid, _short_label(obj.get("name"), mid)))
    # identical labels are not allowed: distinguish by the id tail
    cnt = {}
    for _, l in models:
        cnt[l] = cnt.get(l, 0) + 1
    return [(m, l if cnt[l] == 1 else m.split("/")[-1].replace("-free", "")[:16])
            for m, l in models]

async def _fetch_free_opencode():
    """[(id, label)] of free models, or None (on error the previous list stays)."""
    try:
        rc, text = await _oc_exec(["models", "opencode", "--verbose"], 40)
    except Exception as e:
        logger.warning(f"[MODELS] opencode models did not respond: {e!r}")
        return None
    got = _parse_free(text)
    if not got:
        logger.warning(f"[MODELS] opencode models rc={rc}: no free models found")
    return got or None

def _is_dead(mid):
    d = _oc_dead.get(mid)
    if not d:
        return False
    if d[0] < time.time():
        _oc_dead.pop(mid, None)
        return False
    return True

def _mark_dead(mid, reason):
    _oc_dead[mid] = (time.time() + _DEAD_TTL, str(reason)[:120])
    _oc_ok.pop(mid, None)
    logger.warning(f"[MODELS] {mid} hidden for {int(_DEAD_TTL)}s: {str(reason)[:120]}")

def _mark_ok(mid):
    _oc_ok[mid] = time.time()
    _oc_dead.pop(mid, None)

def _free_pool():
    """All free models from the fresh list (or the fallback before the first poll)."""
    return _free_cache["models"] if _free_cache["models"] else _FALLBACK_FREE

def _current_free():
    """Free models minus dead ones; verified-alive first. If all are dead,
    return the whole pool (better an error with text than an empty menu)."""
    pool = _free_pool()
    alive = [x for x in pool if not _is_dead(x[0])] or list(pool)
    return [x for x in alive if x[0] in _oc_ok] + [x for x in alive if x[0] not in _oc_ok]

def _free_allowed():
    return set(m for m, _ in _free_pool())

def oc_default():
    cur = _current_free()
    return cur[0][0] if cur else ""

async def _probe_free():
    """A short live call to each free model (sequentially) so that
    dead ones do not reach the menu. Free models only; there can be no others here."""
    if not _PROBE_ON or _probe_state["busy"]:
        return
    _probe_state["busy"] = True
    try:
        now = time.time()
        for mid, _ in list(_free_pool()):
            if mid not in _free_allowed():
                continue
            if _is_dead(mid) or now - _oc_ok.get(mid, 0) < _PROBE_TTL:
                continue
            text, err, sid = "", "", ""
            try:
                rc, out = await _oc_exec(["run", "--format", "json", "--agent", "build",
                                          "--model", mid, "ответь одним словом: ок"], 90)
                for ln in out.splitlines():
                    try:
                        d = json.loads(ln)
                    except Exception:
                        continue
                    sid = d.get("sessionID") or sid
                    if d.get("type") == "text":
                        text += ((d.get("part") or {}).get("text") or "")
                    elif d.get("type") == "error":
                        e = d.get("error") or {}
                        err = str((e.get("data") or {}).get("message") or e.get("name") or "error")
            except asyncio.TimeoutError:
                err = "timeout"
            except Exception as e:
                err = repr(e)
            if text.strip():
                _mark_ok(mid)
            else:
                _mark_dead(mid, err or "пустой ответ")
            if sid.startswith("ses_"):     # do not keep the service check session
                try:
                    await _oc_exec(["session", "delete", sid], 30)
                except Exception:
                    pass
        logger.info(f"[MODELS] check: alive {len([1 for m, _ in _free_pool() if m in _oc_ok])}"
                    f", hidden {len([1 for m, _ in _free_pool() if _is_dead(m)])}")
    finally:
        _probe_state["busy"] = False

async def _refresh_free():
    if _free_cache["busy"]:
        return
    _free_cache["busy"] = True
    try:
        got = await _fetch_free_opencode()
        if got:
            _free_cache["models"] = got
            _free_cache["ts"] = time.time()
            logger.info(f"[MODELS] free models picked up: {len(got)}: "
                        + ", ".join(m.split("/")[-1] for m, _ in got))
        else:
            _free_cache["fail_ts"] = time.time()
    finally:
        _free_cache["busy"] = False
    await _probe_free()

def _maybe_refresh_free():
    now = time.time()
    if not OPENCODE_ENABLED:
        return
    if _free_cache["busy"] or _probe_state["busy"]:
        return
    if now - _free_cache["ts"] > _FREE_TTL and now - _free_cache["fail_ts"] > _FREE_RETRY:
        asyncio.create_task(_refresh_free())
    elif any(now - _oc_ok.get(m, 0) > _PROBE_TTL and not _is_dead(m) for m, _ in _free_pool()):
        asyncio.create_task(_probe_free())

async def call_opencode(message, session_id=None, model=None, lang="ru"):
    # Free models only (cost 0 per the fresh list). Paid ones and those dropped from
    # the free pool are refused without launching.
    if not model:
        model = oc_default()
    if model not in _free_allowed():
        logger.warning(f"[MODELS] refused: {model} is not in the free pool")
        return {"response": "Error: модель " + str(model).split("/")[-1]
                            + " не бесплатная или убрана. Клавиша 3 — выбрать другую.",
                "session_id": session_id or "", "actions": "",
                "model": oc_default()}

    prompt = SYSTEM_PROMPTS.get(lang, SYSTEM_PROMPTS["ru"])
    # opencode has no --append-system-prompt, so the system text is prepended to the message.
    full_msg = prompt + "\n\n" + message

    # inner timeout: a hung opencode dies on its own
    cmd = _oc_bin_cmd(CLAUDE_TIMEOUT + 10) + [
        "run",
        "--format", "json",
        "--agent", "build",   # pin the plain agent: otherwise 'lead' may fan out to paid models
        "--model", model,     # pin the model: do not trust the config default
        full_msg,
    ]
    # Continue the session only if the id really comes from opencode (ses_...),
    # so as not to mix with Claude sessions when switching engines.
    if session_id and session_id.startswith("ses_"):
        cmd.extend(["--session", session_id])

    logger.info(f"OpenCode: model={model} session={'new' if not session_id else session_id[:16]} msg={len(message)} chars")

    process = await asyncio.create_subprocess_exec(
        *cmd,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd="/tmp",
        env=_clean_env(),
        limit=1024 * 1024,
        start_new_session=True,
    )
    proc_key = session_id or "new"
    active_processes[proc_key] = process
    # drain stderr in parallel: a full pipe blocks the process -> endless spinner
    stderr_task = asyncio.create_task(process.stderr.read())

    full_response, real_session_id, actions = "", None, []
    total_cost = 0.0
    err_msg = ""
    deadline = time.monotonic() + CLAUDE_TIMEOUT   # overall budget, not per line
    try:
        while True:
            try:
                line = await asyncio.wait_for(process.stdout.readline(),
                                              timeout=max(1.0, deadline - time.monotonic()))
            except asyncio.TimeoutError:
                process.kill()
                return {"response": "Error: timeout", "session_id": session_id or "", "actions": ""}
            if not line:
                break
            try:
                data = json.loads(line.decode("utf-8", errors="replace").strip())
            except (json.JSONDecodeError, ValueError):
                continue
            try:
                if data.get("sessionID"):
                    real_session_id = data["sessionID"]
                part = data.get("part") or {}   # part may be null: do not fail on .get
                if data.get("type") == "text":
                    full_response += part.get("text") or ""
                elif data.get("type") in ("tool", "tool_use"):
                    actions.append(_parse_tool(str(part.get("tool") or "")))
                elif data.get("type") == "step_finish":
                    try:
                        total_cost += float(part.get("cost") or 0)
                    except (TypeError, ValueError):
                        pass
                elif data.get("type") == "error":
                    # provider error (happens with the free pool): show its text
                    err_msg = str((data.get("error") or {}).get("data", {}).get("message")
                                  or (data.get("error") or {}).get("name") or "")[:120]
            except Exception as e:
                logger.error(f"OpenCode parse: {e}")
    finally:
        try:
            await process.wait()
        except Exception:
            pass
        active_processes.pop(proc_key, None)

    # Model state for the menu: answered = alive; a provider error or a
    # non-zero exit without text = hide for a while. A stop (signal) does not count.
    if full_response.strip():
        _mark_ok(model)
    elif err_msg or (process.returncode or 0) > 0:
        _mark_dead(model, err_msg or f"rc={process.returncode}")

    if not full_response.strip():
        # show the stderr tail instead of a silent "empty response"
        err = ""
        try:
            err = (await asyncio.wait_for(stderr_task, timeout=5))[-300:].decode("utf-8", errors="replace").strip()
        except Exception:
            pass
        if err_msg:
            full_response = "OpenCode: " + err_msg
        else:
            full_response = ("OpenCode: " + err) if err else "[no response]"
    else:
        stderr_task.cancel()

    # Truncate to fit the phone's memory (a 3310 dies on megabyte responses)
    b = full_response.encode("utf-8")
    if len(b) > MAX_RESPONSE_BYTES:
        full_response = b[:MAX_RESPONSE_BYTES - 20].decode("utf-8", "ignore") + "\n\n[обрезано]"

    out = {"response": full_response.strip(),
           "session_id": real_session_id or session_id or "",
           "actions": ",".join(actions) if actions else ""}
    # Response cost for paid models: the phone shows a line like "~$0.0036"
    if total_cost > 0:
        out["cost"] = f"~${total_cost:.4f}"
    return out

async def call_claude(message, session_id=None, model="opus", lang="ru"):
    return await _call_claude_headless(message, resolve_claude_session(session_id), model, lang)


async def claude_for_phone(message, session_id, model, lang):
    """Shared path of /api/chat and /api/voice: call Claude and record the session."""
    result = await call_claude(message, session_id, model, lang)
    if session_id:
        update_session_usage(session_id, result.get("session_id"))
    return result


async def _call_claude_headless(message, session_id=None, model="opus", lang="ru"):
    prompt = SYSTEM_PROMPTS.get(lang, SYSTEM_PROMPTS["ru"])

    cmd = list(CLAUDE_CMD) + [
        "-p",
        "--output-format", "stream-json",
        "--verbose",
        "--model", MODEL_MAP.get(model, model),
        "--permission-mode", "dontAsk",
        "--append-system-prompt", prompt,
    ] + CLAUDE_EXTRA_ARGS

    is_new = not session_id
    if session_id:
        cmd.extend(["--resume", session_id])

    logger.info(f"Claude: model={model} session={'new' if is_new else session_id[:16]} msg={len(message)} chars")

    process = await asyncio.create_subprocess_exec(
        *cmd,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd=_claude_cwd(),
        env=_clean_env(),
        limit=1024 * 1024,
        start_new_session=True,
    )

    # Track process for stop/kill
    proc_key = session_id or "new"
    active_processes[proc_key] = process

    process.stdin.write(message.encode())
    await process.stdin.drain()
    process.stdin.close()

    full_response = ""
    real_session_id = None
    actions = []  # track tool usage
    # drain stderr in parallel (claude --verbose writes there; a full pipe = deadlock)
    stderr_task = asyncio.create_task(process.stderr.read())
    deadline = time.monotonic() + CLAUDE_TIMEOUT   # overall budget, not per line

    try:
        while True:
            try:
                line = await asyncio.wait_for(process.stdout.readline(),
                                              timeout=max(1.0, deadline - time.monotonic()))
            except asyncio.TimeoutError:
                process.kill()
                return {"response": "Error: timeout", "session_id": session_id or "", "actions": ",".join(actions)}

            if not line:
                break

            line_str = line.decode("utf-8", errors="replace").strip()
            if not line_str:
                continue

            try:
                data = json.loads(line_str)
            except json.JSONDecodeError:
                continue

            msg_type = data.get("type", "")

            if msg_type == "system" and "session_id" in data:
                real_session_id = data["session_id"]

            if msg_type == "assistant" and "message" in data:
                for block in data["message"].get("content", []):
                    if block.get("type") == "text":
                        full_response += block.get("text", "")
                    elif block.get("type") == "tool_use":
                        tool_name = block.get("name", "")
                        action = _parse_tool(tool_name)
                        if not actions or actions[-1] != action:
                            actions.append(action)

            if msg_type == "content_block_delta":
                delta = data.get("delta", {})
                if delta.get("type") == "text_delta":
                    full_response += delta.get("text", "")

            if msg_type == "result":
                if "result" in data and isinstance(data["result"], str):
                    full_response = data["result"]
                if "session_id" in data:
                    real_session_id = data["session_id"]
    except Exception as e:
        logger.error(f"Claude read error: {e}")
    finally:
        stderr_task.cancel()
        try:
            await process.wait()
        except Exception:
            pass
        active_processes.pop(proc_key, None)

    # Truncate with a single slice: the former loop froze the WHOLE server on a megabyte response
    b = full_response.encode("utf-8")
    if len(b) > MAX_RESPONSE_BYTES:
        full_response = b[:MAX_RESPONSE_BYTES - 20].decode("utf-8", "ignore") + "\n\n[обрезано]"

    if not full_response:
        full_response = "[no response]"

    final_sid = real_session_id or session_id or ""
    # actions as comma-separated string for easy J2ME parsing
    actions_str = ",".join(actions) if actions else ""
    return {"response": full_response, "session_id": final_sid, "actions": actions_str}

_KEYLIKE = re.compile(r"(Bearer\s+\S+|sk-[A-Za-z0-9_\-]{6,}|gsk_[A-Za-z0-9_\-]{6,}"
                       r"|[A-Za-z0-9_\-]{32,})")

def _redact(s):
    """Service responses sometimes echo a piece of the key: keep it out of the log."""
    return _KEYLIKE.sub("***", s or "")

async def _kill_proc(proc):
    try:
        proc.kill()
    except Exception:
        pass
    try:
        await asyncio.wait_for(proc.wait(), timeout=5)
    except BaseException:
        pass

# OpenRouter without credit (402) refuses every request: do not spend
# seconds of the voice signature window on it until the pause passes.
OR_COOLDOWN = 600
_or_dead_until = [0.0]

def _or_alive():
    return time.monotonic() >= _or_dead_until[0]

def _or_note_error(msg):
    if "402" in (msg or ""):
        if _or_alive():
            logger.error(f"STT: OpenRouter 402 (out of credit), skipping it for {OR_COOLDOWN} s")
        _or_dead_until[0] = time.monotonic() + OR_COOLDOWN

async def _stt_gemini(audio_path):
    """Gemini 2.5 Flash via OpenRouter (input_audio, mp3)."""
    if not OPENROUTER_API_KEY:
        logger.error("STT: no OpenRouter key")
        return None
    if not _or_alive():
        return None
    b64 = base64.b64encode(open(audio_path, "rb").read()).decode("utf-8")
    fmt = "mp3" if audio_path.endswith(".mp3") else "amr"  # do not lie about the format if ffmpeg failed
    payload = {
        "model": STT_MODEL, "temperature": 0, "max_tokens": 8000,
        "reasoning": {"max_tokens": 1024},
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": STT_PROMPT},
            {"type": "input_audio", "input_audio": {"data": b64, "format": fmt}},
        ]}],
    }
    try:
        async with httpx.AsyncClient(timeout=130) as client:
            resp = await client.post(
                "https://openrouter.ai/api/v1/chat/completions",
                headers={"Authorization": f"Bearer {OPENROUTER_API_KEY}",
                         "Content-Type": "application/json",
                         "User-Agent": "vibenokia/1.0"},
                json=payload,
            )
        d = resp.json()
        if "choices" in d:
            return (d["choices"][0]["message"].get("content") or "").strip()
        e = _redact(json.dumps(d.get('error') or d, ensure_ascii=False))[:200]
        if resp.status_code == 402:
            _or_note_error("402")
        logger.error(f"Gemini STT error: {resp.status_code} {e}")
    except Exception as e:
        logger.error(f"Gemini STT exception: {_redact(str(e))[:200]}")
    return None

async def _stt_groq(audio_path, lang="ru"):
    """Groq Whisper: fallback if Gemini failed."""
    if not GROQ_API_KEY:
        logger.error("STT: no Groq key, fallback unavailable")
        return None
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            with open(audio_path, "rb") as f:
                resp = await client.post(
                    "https://api.groq.com/openai/v1/audio/transcriptions",
                    headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
                    data={"model": "whisper-large-v3", "language": lang},
                    files={"file": ("audio.mp3", f, "audio/mpeg")},
                )
        if resp.status_code == 200:
            return (resp.json().get("text") or "").strip()
        logger.error(f"Whisper error: {resp.status_code} {_redact(resp.text)[:200]}")
    except Exception as e:
        logger.error(f"Groq fallback error: {_redact(str(e))[:200]}")
    return None

# The ffmpeg input format is set EXPLICITLY: otherwise ffmpeg guesses the format from
# the content, and a foreign body (an hls/concat playlist, etc.) could make it
# read files or reach the network. Plus a protocol whitelist: file/pipe only.
FFMPEG_IN = ["-protocol_whitelist", "file,pipe"]

async def transcribe_audio(audio_data, lang="ru", fmt="amr"):
    """AMR from the Nokia (fmt="amr"; Telegram voice: "ogg"/"mp3"/"mov") -> mp3 ->
    OpenRouter (Gemini) -> Groq Whisper."""
    if fmt not in ("amr", "ogg", "mp3", "mov", "wav"):
        logger.warning("Transcribe: unknown format, skipping")
        return None
    fd, amr_path = tempfile.mkstemp(suffix=".amr" if fmt == "amr" else ".in")
    os.write(fd, audio_data)
    os.close(fd)
    mp3_path = amr_path.rsplit(".", 1)[0] + ".mp3"
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            *FFMPEG_IN, "-f", fmt,
            "-i", amr_path, "-ac", "1", "-ar", "16000", "-b:a", "48k", mp3_path,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            await asyncio.wait_for(proc.wait(), timeout=30)
        except asyncio.TimeoutError:
            proc.kill()
            try:
                await proc.wait()
            except Exception:
                pass
        if os.path.exists(mp3_path) and os.path.getsize(mp3_path) > 0:
            use_path = mp3_path
        elif fmt == "amr":
            use_path = amr_path        # ffmpeg failed: the services accept AMR as is
        else:
            logger.warning(f"Transcribe: ffmpeg failed to transcode {fmt}")
            return None

        # 1) Direct request (works on hosts without regional blocking)
        text = await _stt_gemini(use_path)
        if text:
            return text
        # 2) fallback: Groq Whisper
        logger.info("Gemini STT failed or no key, falling back to Groq Whisper")
        return await _stt_groq(use_path, lang)
    except Exception as e:
        logger.error(f"Transcribe error: {_redact(str(e))[:200]}")
        return None
    finally:
        for p in (amr_path, mp3_path):
            try:
                os.unlink(p)
            except Exception:
                pass

# --- TELEGRAM ---

def tg_token():
    """Bot token. The file is re-read on the fly: drop in a token, no restart needed."""
    now = time.time()
    if now - _tg_token_cache["checked"] < 5 and _tg_token_cache["value"]:
        return _tg_token_cache["value"]
    _tg_token_cache["checked"] = now
    token = ""
    try:
        with open(TG_ENV_PATH, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("TELEGRAM_BOT_TOKEN=") or line.startswith("TG_BOT_TOKEN="):
                    token = line.split("=", 1)[1].strip().strip('"').strip("'")
                    break
    except Exception:
        pass
    if not token:
        token = os.getenv("TELEGRAM_BOT_TOKEN", "")
    _tg_token_cache["value"] = token
    return token

async def tg_bot_username():
    """@name of the bot, cached for an hour so getMe is not called on every phone request."""
    now = time.time()
    # Failure is cached TOO (empty name for 60 s), otherwise every visit to the section
    # waits 8 seconds for a repeated getMe on a host with broken egress
    ttl = 3600 if _tg_bot_cache["name"] else 60
    if now - _tg_bot_cache["checked"] < ttl and _tg_bot_cache["checked"] > 0:
        return _tg_bot_cache["name"]
    if not tg_token():
        return ""
    ok, res = await tg_api("getMe", {}, timeout=8)
    _tg_bot_cache["checked"] = now
    if ok and isinstance(res, dict):
        _tg_bot_cache["name"] = res.get("username", "")
    return _tg_bot_cache["name"]

async def tg_api(method, params=None, files=None, timeout=30):
    """Bot API call. Returns (ok, result_or_error)."""
    token = tg_token()
    if not token:
        return False, "no_token"
    url = f"{TG_API}/bot{token}/{method}"
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            if files:
                resp = await client.post(url, data=params or {}, files=files)
            else:
                resp = await client.post(url, json=params or {})
        data = resp.json()
        if data.get("ok"):
            return True, data.get("result")
        return False, _redact(str(data.get("description") or data))[:200]
    except Exception as e:
        # httpx timeouts stringify to "" -> the phone printed a bare "ERR:"
        return False, _redact((str(e) or e.__class__.__name__).replace(token, "***"))[:200]

# --- Telegram: DB ---

def tg_upsert_contact(chat_id, title, username="", kind="private", bump_unread=False):
    # A backslash in a name breaks the phone's naive JSON parser (the whole contact list)
    title = (title or "").replace("\\", "/")
    try:
        conn = get_db()
        row = conn.execute("SELECT chat_id FROM tg_contacts WHERE chat_id=?", (str(chat_id),)).fetchone()
        if row:
            if bump_unread:
                conn.execute("UPDATE tg_contacts SET title=?, username=?, unread=unread+1, last_ts=CURRENT_TIMESTAMP WHERE chat_id=?",
                             (title, username, str(chat_id)))
            else:
                conn.execute("UPDATE tg_contacts SET title=?, username=?, last_ts=CURRENT_TIMESTAMP WHERE chat_id=?",
                             (title, username, str(chat_id)))
        else:
            conn.execute("INSERT INTO tg_contacts (chat_id, title, username, kind, unread) VALUES (?, ?, ?, ?, ?)",
                         (str(chat_id), title, username, kind, 1 if bump_unread else 0))
        conn.commit()
        conn.close()
    except Exception as e:
        logger.error(f"tg_upsert_contact: {e}")

def tg_list_contacts():
    try:
        conn = get_db()
        rows = conn.execute(
            "SELECT chat_id, title, username, unread FROM tg_contacts ORDER BY unread DESC, last_ts DESC LIMIT 40"
        ).fetchall()
        conn.close()
        return [dict(r) for r in rows]
    except Exception as e:
        logger.error(f"tg_list_contacts: {e}")
        return []

def tg_contact_exists(chat_id):
    conn = get_db()
    row = conn.execute("SELECT chat_id FROM tg_contacts WHERE chat_id=?", (str(chat_id),)).fetchone()
    conn.close()
    return row is not None

def tg_save_message(chat_id, direction, text, kind="text"):
    # Backslashes and curly braces break the phone's conversation parser: neutralize them
    text = (text or "").replace("\\", "/").replace("{", "(").replace("}", ")")
    try:
        conn = get_db()
        conn.execute("INSERT INTO tg_messages (chat_id, direction, kind, text) VALUES (?, ?, ?, ?)",
                     (str(chat_id), direction, kind, (text or "")[:2000]))
        conn.execute("UPDATE tg_contacts SET last_ts=CURRENT_TIMESTAMP WHERE chat_id=?", (str(chat_id),))
        conn.commit()
        conn.close()
    except Exception as e:
        logger.error(f"tg_save_message: {e}")

def tg_get_messages(chat_id, limit=12):
    try:
        conn = get_db()
        rows = conn.execute(
            "SELECT direction, kind, text, strftime('%H:%M', created_at, 'localtime') AS tm "
            "FROM tg_messages WHERE chat_id=? ORDER BY id DESC LIMIT ?",
            (str(chat_id), limit)
        ).fetchall()
        conn.close()
        # sanitize on output too: the database may hold old records with braces
        return [{"direction": r["direction"], "kind": r["kind"],
                 "time": r["tm"] or "",
                 "text": (r["text"] or "").replace("\\", "/").replace("{", "(").replace("}", ")")}
                for r in reversed(rows)]
    except Exception as e:
        logger.error(f"tg_get_messages: {e}")
        return []

def tg_mark_read(chat_id):
    try:
        conn = get_db()
        conn.execute("UPDATE tg_contacts SET unread=0 WHERE chat_id=?", (str(chat_id),))
        conn.commit()
        conn.close()
    except Exception as e:
        logger.error(f"tg_mark_read: {e}")

def tg_total_unread():
    try:
        conn = get_db()
        row = conn.execute("SELECT COALESCE(SUM(unread), 0) AS n FROM tg_contacts").fetchone()
        conn.close()
        return int(row["n"] or 0)
    except Exception as e:
        logger.error(f"tg_total_unread: {e}")
        return 0

def _ping_sanitize(s, maxlen=40):
    """String safe for the phone's naive parser: without \\ { } \" or line breaks."""
    s = (s or "").replace("\\", " ").replace("{", " ").replace("}", " ").replace('"', " ")
    s = "".join(ch if ch >= " " else " " for ch in s)   # control chars -> space
    s = " ".join(s.split())
    return s[:maxlen]

def tg_last_unread_preview():
    """Latest INCOMING message from chats with unread>0: (id, chat_id, title, text) or None."""
    try:
        conn = get_db()
        row = conn.execute(
            "SELECT m.id AS id, m.chat_id AS chat_id, c.title AS title, m.text AS text "
            "FROM tg_messages m JOIN tg_contacts c ON c.chat_id = m.chat_id "
            "WHERE m.direction='in' AND c.unread > 0 "
            "ORDER BY m.id DESC LIMIT 1").fetchone()
        conn.close()
        return row
    except Exception as e:
        logger.error(f"tg_last_unread_preview: {e}")
        return None

# --- Telegram: audio ---

async def amr_to_voice(audio_data):
    """AMR from the Nokia -> OGG/Opus (Telegram voice format). Fallback: mp3 file."""
    fd, amr_path = tempfile.mkstemp(suffix=".amr")
    os.write(fd, audio_data)
    os.close(fd)
    ogg_path = amr_path[:-4] + ".ogg"
    mp3_path = amr_path[:-4] + ".mp3"
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            *FFMPEG_IN, "-f", "amr",
            "-i", amr_path, "-c:a", "libopus", "-b:a", "24k", "-ar", "48000", "-ac", "1", ogg_path,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            await asyncio.wait_for(proc.wait(), timeout=30)
        except asyncio.TimeoutError:
            proc.kill()
            try:
                await proc.wait()
            except Exception:
                pass
        if os.path.exists(ogg_path) and os.path.getsize(ogg_path) > 0:
            data = open(ogg_path, "rb").read()
            return "voice", data
        # no libopus: send as a regular audio file
        logger.warning("TG: libopus unavailable, sending mp3 via sendAudio")
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            *FFMPEG_IN, "-f", "amr",
            "-i", amr_path, "-ac", "1", "-ar", "16000", "-b:a", "48k", mp3_path,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            await asyncio.wait_for(proc.wait(), timeout=30)
        except asyncio.TimeoutError:
            proc.kill()
            try:
                await proc.wait()
            except Exception:
                pass
        if os.path.exists(mp3_path) and os.path.getsize(mp3_path) > 0:
            return "audio", open(mp3_path, "rb").read()
        return None, None
    except Exception as e:
        logger.error(f"amr_to_voice: {e}")
        return None, None
    finally:
        for p in (amr_path, ogg_path, mp3_path):
            try:
                os.unlink(p)
            except Exception:
                pass

async def tg_download_file(file_id):
    """Download a Telegram file by file_id (to transcribe incoming voice messages)."""
    ok, res = await tg_api("getFile", {"file_id": file_id})
    if not ok or not isinstance(res, dict):
        return None
    path = res.get("file_path")
    if not path:
        return None
    token = tg_token()
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.get(f"{TG_API}/file/bot{token}/{path}")
        if resp.status_code == 200:
            return resp.content
    except Exception as e:
        logger.error(f"tg_download_file: {_redact(str(e))[:200]}")
    return None

# --- Telegram: receiving incoming messages (long-poll) ---

def _tg_chat_title(chat):
    if chat.get("title"):
        return chat["title"]
    name = " ".join(x for x in [chat.get("first_name"), chat.get("last_name")] if x).strip()
    if not name:
        name = chat.get("username") or str(chat.get("id"))
    return name

async def _tg_handle_update(upd):
    msg = upd.get("message") or upd.get("channel_post")
    if not msg:
        return
    chat = msg.get("chat") or {}
    chat_id = chat.get("id")
    if chat_id is None:
        return
    title = _tg_chat_title(chat)
    username = chat.get("username") or ""
    kind = chat.get("type") or "private"

    text = (msg.get("text") or msg.get("caption") or "").strip()
    mkind = "text"

    voice = msg.get("voice") or msg.get("audio") or msg.get("video_note")
    if voice and not text:
        mkind = "voice"
        # the ffmpeg format is set explicitly (see FFMPEG_IN): Telegram voice messages are ogg/opus,
        # round videos are mp4, an audio file by mime
        if msg.get("voice"):
            vfmt = "ogg"
        elif msg.get("video_note"):
            vfmt = "mov"
        else:
            mime = str(voice.get("mime_type") or "").lower()
            vfmt = ("mp3" if "mpeg" in mime or "mp3" in mime else "ogg" if "ogg" in mime
                    else "mov" if ("mp4" in mime or "m4a" in mime or "aac" in mime)
                    else "wav" if "wav" in mime else "")
        audio = await tg_download_file(voice.get("file_id")) if vfmt else None
        if audio:
            transcript = await transcribe_audio(audio, fmt=vfmt)
            text = transcript or "[голосовое, не распозналось]"
        else:
            text = "[голосовое]"
    elif msg.get("photo"):
        mkind = "photo"
        text = text or "[фото]"
    elif msg.get("document"):
        mkind = "file"
        text = text or "[файл]"
    elif msg.get("sticker"):
        mkind = "sticker"
        text = msg["sticker"].get("emoji", "") + " [стикер]"

    if not text:
        text = "[сообщение]"

    tg_upsert_contact(chat_id, title, username, kind, bump_unread=True)
    tg_save_message(chat_id, "in", text, mkind)
    logger.info(f"[TG IN] {chat_id}: {mkind}, {len(text)} chars")

async def tg_poller():
    """The only consumer of getUpdates for this bot. Maintains contacts and incoming messages."""
    offset = None
    warned_no_token = False
    while True:
        token = tg_token()
        if not token:
            if not warned_no_token:
                logger.info(f"TG: no token ({TG_ENV_PATH}), Telegram section idle, waiting for a token")
                warned_no_token = True
            await asyncio.sleep(15)
            continue
        warned_no_token = False
        params = {"timeout": TG_POLL_TIMEOUT, "allowed_updates": ["message", "channel_post"]}
        if offset is not None:
            params["offset"] = offset
        started = time.time()
        ok, res = await tg_api("getUpdates", params, timeout=TG_POLL_TIMEOUT + 15)
        # idle-storm safeguard: if the response came back instantly and empty,
        # pause, otherwise with a "fast" API the loop eats CPU and traffic
        if ok and not res and time.time() - started < 1:
            await asyncio.sleep(2)
        if not ok:
            if "Conflict" in str(res) or "409" in str(res):
                logger.error("TG: 409 Conflict: another process is already listening on this token. "
                             "A SEPARATE bot is needed for the Nokia. Retry in 60s")
                await asyncio.sleep(60)
            else:
                logger.error(f"TG getUpdates: {_redact(str(res))[:200]}")
                await asyncio.sleep(10)
            continue
        for upd in (res or []):
            offset = upd.get("update_id", 0) + 1
            try:
                await _tg_handle_update(upd)
            except Exception as e:
                logger.error(f"TG update error: {_redact(str(e))[:200]}")

# --- Launching the engine for the phone: entry check + overall cap on launches ---

def _str(v):
    return v if isinstance(v, str) else ""

def _safe_model(model):
    """Known models only: Claude from MODEL_MAP or a free opencode model from the
    fresh pool. Everything else (including strings starting with "-", which would become a
    flag in argv) falls back to opus."""
    model = _str(model).strip()
    if model in MODEL_MAP:
        return model
    if is_opencode_model(model) and len(model) <= 120 and not _VIBE_CTRL.search(model):
        # "opencode/..." will not become an argv flag; call_opencode checks that it is free and
        # replies to the phone with a clear error including a model field (the client switches itself)
        # - instead of silently routing the request to a paid opus.
        return model
    if model:
        logger.warning(f"[MODELS] unknown model from the phone, using opus")
    return "opus"

def _safe_lang(lang):
    lang = _str(lang)
    return lang if lang in SYSTEM_PROMPTS else "ru"

# How many Claude/opencode launches run at once (subprocesses and tab turns).
# The rest get a clear "busy" reply at once, not a queue until timeout.
MAX_RUNS = int(os.getenv("VIBENOKIA_MAX_RUNS", "2"))
_runs = {"n": 0}

def _busy_result(session_id, lang):
    text = ("[Сервер занят: уже идут %d запроса к Клоду. Подожди минуту и повтори]" % MAX_RUNS
            if lang != "en" else "[Server busy: %d requests already running. Retry in a minute]" % MAX_RUNS)
    return {"response": text, "session_id": session_id or "", "actions": "", "_busy": True}

async def run_engine(message, session_id, model, lang):
    if _runs["n"] >= MAX_RUNS:
        logger.warning(f"[RUN] busy: {_runs['n']}/{MAX_RUNS}")
        return _busy_result(session_id, lang)
    _runs["n"] += 1
    try:
        if is_opencode_model(model):
            # OpenCode keeps its own sessions (ses_...): do not touch Claude's mapping
            return await call_opencode(message, session_id, model, lang)
        return await claude_for_phone(message, session_id, model, lang)
    finally:
        _runs["n"] -= 1

# --- ENDPOINTS ---

@app.get("/ping")
async def ping():
    # mode tells the phone what this server can do: full = Claude + Telegram,
    # tg = Telegram only (public gateway).
    # unread lets the phone show "Telegram (N)" and signal new messages.
    # 2 s cache: the ping every 5 s must not hit the database while a write holds a lock
    now = time.time()
    if now - _unread_cache["ts"] > 2:
        _unread_cache["n"] = tg_total_unread()
        _unread_cache["last_id"] = 0
        if _unread_cache["n"] > 0:
            row = tg_last_unread_preview()
            if row:
                _unread_cache["last_id"] = int(row["id"])
                _unread_cache["last_chat"] = str(row["chat_id"])
                _unread_cache["last_from"] = _ping_sanitize(row["title"], 24)
                _unread_cache["last_text"] = _ping_sanitize(row["text"], 40)
        _unread_cache["ts"] = now

    out = {"status": "ok", "mode": MODE, "version": APP_VERSION,
           "unread": _unread_cache["n"], "time": int(time.time())}
    if _unread_cache["n"] > 0 and _unread_cache["last_id"]:
        out["last_id"] = _unread_cache["last_id"]
        out["last_chat"] = _unread_cache["last_chat"]
        out["last_from"] = _unread_cache["last_from"]
        out["last_text"] = _unread_cache["last_text"]
    return out

@app.post("/api/chat")
async def chat(request: Request):
    try:
        body = await request.json()
    except:
        raw = await request.body()
        try:
            body = json.loads(raw.decode("utf-8"))
        except:
            return JSONResponse(status_code=400, content={"response": "Bad request", "session_id": ""})

    if not isinstance(body, dict):
        return JSONResponse(status_code=400, content={"response": "Bad request", "session_id": ""})
    message = _str(body.get("message")).strip()
    session_id = _str(body.get("session_id"))
    model = _safe_model(body.get("model"))
    lang = _safe_lang(body.get("lang"))

    if not message:
        return JSONResponse(status_code=400, content={"response": "Empty message", "session_id": ""})

    # "/stop" as text: the phone sends /api/stop only while waiting for a reply (isLoading), so a
    # hung turn in a tab / "still working on the previous one" cannot be reset that way. As text it
    # means Esc in the tab and a reset of the wait; these words are not passed to Claude.
    if message.lower() in ("/stop", "/стоп"):
        st = await _do_stop(session_id)
        logger.info(f"[CHAT] /stop as text: {st}")
        return JSONResponse(content={
            "response": ("[Остановлено — можно писать дальше]" if lang != "en" else "[Stopped]")
                        if st == "killed" else
                        ("[Нечего останавливать — можно писать]" if lang != "en" else "[Nothing to stop]"),
            "session_id": session_id, "actions": ""})

    logger.info(f"[CHAT] model={model} msg={len(message)} chars")

    # Auto-name session on first message
    if session_id:
        auto_name_session(session_id, message)

    result = await run_engine(message, session_id, model, lang)

    # Track session usage and save messages
    busy = result.pop("_busy", False)      # the service flag is not passed to the phone
    final_sid = result.get("session_id") or session_id
    if final_sid and not busy:
        save_message(final_sid, "user", message)
        save_message(final_sid, "assistant", result.get("response", ""))

    logger.info(f"[CHAT] response={len(result['response'])} chars")
    return JSONResponse(content=result)

@app.post("/api/voice/transcribe")
async def voice_transcribe(request: Request):
    audio_data = await request.body()
    lang = request.headers.get("x-lang", "ru")

    if not audio_data or len(audio_data) < 100:
        return JSONResponse(status_code=400, content={"transcript": ""})

    t0 = time.time()
    logger.info(f"[TRANSCRIBE] {len(audio_data)} bytes")
    transcript = await transcribe_audio(audio_data, lang)
    elapsed = time.time() - t0
    logger.info(f"[TRANSCRIBE] {elapsed:.1f}s result: {len(transcript or '')} chars")

    return JSONResponse(content={"transcript": transcript or ""})

@app.post("/api/voice")
async def voice(request: Request):
    audio_data = await request.body()
    session_id = request.headers.get("x-session-id", "")
    model = _safe_model(request.headers.get("x-model", "opus"))
    lang = _safe_lang(request.headers.get("x-lang", "ru"))

    ru = lang != "en"
    if not audio_data or len(audio_data) < 100:
        # 200 with text: on a non-200 the phone shows "No connection", which is not the issue here
        return JSONResponse(content={
            "response": "[Запись пустая — голосовое не дошло. Запиши ещё раз]" if ru
                        else "[Empty recording — record again]",
            "session_id": session_id, "transcript": ""})

    logger.info(f"[VOICE] {len(audio_data)} bytes model={model}")
    if _runs["n"] >= MAX_RUNS:
        # busy: do not waste the transcription (paid/slow)
        res = _busy_result(session_id, lang)
        res.pop("_busy", None)
        res["transcript"] = ""
        return JSONResponse(content=res)

    transcript = (await transcribe_audio(audio_data, lang) or "").strip()
    if not transcript:
        # Do not call Claude: it has nothing to answer, and a tab/subprocess would be occupied for nothing
        logger.warning("[VOICE] transcript empty, not calling Claude")
        return JSONResponse(content={
            "response": "[Не разобрал голосовое: расшифровка пустая (тишина или сервисы "
                        "распознавания не ответили). Повтори или напиши текстом]" if ru
                        else "[Could not transcribe the voice message. Repeat or type it]",
            "session_id": session_id, "transcript": ""})

    logger.info(f"[VOICE] transcript: {len(transcript)} chars")

    # Auto-name session on first message
    if session_id:
        auto_name_session(session_id, transcript)

    # Wrap transcript with voice context for Claude
    voice_prefix = "[Голосовое сообщение с Nokia 3310, распознано Whisper. Возможны ошибки.]\n\n" if lang == "ru" else "[Voice message from Nokia 3310, transcribed by Whisper. May contain errors.]\n\n"
    claude_message = voice_prefix + transcript

    result = await run_engine(claude_message, session_id, model, lang)
    result["transcript"] = transcript

    # Track session usage and save messages
    busy = result.pop("_busy", False)      # the service flag is not passed to the phone
    final_sid = result.get("session_id") or session_id
    if final_sid and not busy:
        save_message(final_sid, "user", transcript)
        save_message(final_sid, "assistant", result.get("response", ""))

    return JSONResponse(content=result)

@app.post("/api/stop")
async def stop_request(request: Request):
    try:
        body = await request.json()
    except:
        body = {}
    session_id = _str(body.get("session_id", "")) if isinstance(body, dict) else ""
    return JSONResponse(content={"status": await _do_stop(session_id)})

async def _do_stop(session_id):
    """Stop the session's current request: /api/stop and the "/stop" text in /api/chat."""
    vibe_stopped = False
    killed = False
    # Kill the launch process group (docker exec / opencode) on the HOST: that is
    # enough for the phone's stream to unblock at once (stdout closes).
    # We deliberately do NOT pkill INSIDE the container: live work runs there and must not be killed.
    for key in [session_id, "new"]:
        proc = active_processes.pop(key, None)
        if proc and proc.returncode is None:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                killed = True
            except Exception:
                try:
                    proc.kill()
                    killed = True
                except Exception as e:
                    logger.error(f"[STOP] Kill error: {e}")
            logger.info(f"[STOP] {key} killed={killed}")
            break
    return "killed" if (killed or vibe_stopped) else "no_process"

@app.get("/api/models")
async def get_models():
    # Model list for the phone menu: Claude + ONLY free OpenCode models
    # (cost 0 per the fresh `opencode models`, without hidden dead ones). If stale,
    # refresh in the background without delaying the reply. oc_default is the first live free one.
    _maybe_refresh_free()
    models = _CLAUDE_MODELS + _current_free()
    return JSONResponse(content={"oc_default": oc_default(),
                                 "models": [{"id": i, "label": l} for i, l in models]})

@app.get("/api/sessions")
async def get_sessions():
    sessions = list_sessions()
    current = get_current_session_id()
    return JSONResponse(content={"sessions": sessions, "current": current or ""})

@app.post("/api/sessions/new")
async def new_session(request: Request):
    try:
        body = await request.json()
    except:
        body = {}
    name = body.get("name", f"Chat {len(list_sessions()) + 1}")
    model = body.get("model", "opus")
    sid = create_session(name, model)
    return JSONResponse(content={"session_id": sid, "name": name})

@app.post("/api/sessions/switch")
async def switch_session(request: Request):
    try:
        body = await request.json()
    except Exception:
        body = {}
    sid = body.get("session_id", "")
    if sid:
        session = get_session(sid)
        if session:
            set_current_session_id(sid)
            history = get_session_history(sid, limit=4)
            return JSONResponse(content={"status": "ok", "session": session, "history": history})
    return JSONResponse(status_code=404, content={"status": "not found"})

@app.post("/api/sessions/delete")
async def del_session(request: Request):
    try:
        body = await request.json()
    except Exception:
        body = {}
    sid = body.get("session_id", "")
    if sid:
        delete_session(sid)
        return JSONResponse(content={"status": "ok"})
    return JSONResponse(status_code=400, content={"status": "bad request"})

@app.post("/api/sessions/rename")
async def ren_session(request: Request):
    body = await request.json()
    sid = body.get("session_id", "")
    name = body.get("name", "")
    if sid and name:
        rename_session(sid, name)
        return JSONResponse(content={"status": "ok"})
    return JSONResponse(status_code=400, content={"status": "bad request"})

# --- TELEGRAM ENDPOINTS ---

@app.get("/api/tg/status")
async def tg_status():
    token = tg_token()
    if not token:
        return JSONResponse(content={"enabled": False, "bot": "", "contacts": 0, "unread": 0,
                                     "error": "no_token"})
    bot = await tg_bot_username()
    return JSONResponse(content={
        "enabled": bool(bot),
        "bot": bot,
        "contacts": len(tg_list_contacts()),
        "unread": tg_total_unread(),
        "error": "" if bot else "getMe_failed",
    })

@app.get("/api/tg/contacts")
async def tg_contacts():
    contacts = tg_list_contacts()
    return JSONResponse(content={
        "enabled": bool(tg_token()),
        "bot": await tg_bot_username(),
        "unread": tg_total_unread(),
        "contacts": contacts,
    })

@app.post("/api/tg/contacts/add")
async def tg_contact_add(request: Request):
    """Add a contact manually by chat_id (if the person has not written to the bot yet)."""
    try:
        body = await request.json()
    except Exception:
        body = {}
    chat_id = str(body.get("chat_id", "")).strip()
    title = (body.get("title") or "").strip() or chat_id
    if not chat_id:
        return JSONResponse(status_code=400, content={"status": "bad_request"})
    tg_upsert_contact(chat_id, title, body.get("username", ""), "private")
    return JSONResponse(content={"status": "ok", "chat_id": chat_id, "title": title})

@app.post("/api/tg/contacts/delete")
async def tg_contact_delete(request: Request):
    try:
        body = await request.json()
    except Exception:
        body = {}
    chat_id = str(body.get("chat_id", "")).strip()
    if not chat_id:
        return JSONResponse(status_code=400, content={"status": "bad_request"})
    conn = get_db()
    conn.execute("DELETE FROM tg_contacts WHERE chat_id=?", (chat_id,))
    conn.execute("DELETE FROM tg_messages WHERE chat_id=?", (chat_id,))
    conn.commit()
    conn.close()
    return JSONResponse(content={"status": "ok"})

@app.get("/api/tg/messages")
async def tg_messages(request: Request):
    chat_id = request.query_params.get("chat_id", "").strip()
    if not chat_id:
        return JSONResponse(status_code=400, content={"messages": []})
    try:
        limit = min(30, max(1, int(request.query_params.get("limit", "12"))))
    except Exception:
        limit = 12
    msgs = tg_get_messages(chat_id, limit)
    tg_mark_read(chat_id)
    return JSONResponse(content={"messages": msgs, "chat_id": chat_id})

@app.post("/api/tg/send")
async def tg_send(request: Request):
    try:
        body = await request.json()
    except Exception:
        raw = await request.body()
        try:
            body = json.loads(raw.decode("utf-8"))
        except Exception:
            body = {}
    chat_id = str(body.get("chat_id", "")).strip()
    text = (body.get("text") or "").strip()
    if not chat_id or not text:
        return JSONResponse(content={"status": "error", "error": "chat_id и text обязательны"})
    if not tg_token():
        return JSONResponse(content={"status": "error", "error": "нет токена бота"})

    ok, res = await tg_api("sendMessage", {
        "chat_id": chat_id,
        "text": text[:TG_MSG_LIMIT],
        "disable_web_page_preview": True,
    })
    if ok:
        tg_save_message(chat_id, "out", text, "text")
        logger.info(f"[TG OUT] {chat_id}: {len(text)} chars")
        return JSONResponse(content={"status": "ok"})
    logger.error(f"[TG OUT] error {chat_id}: {_redact(str(res))[:200]}")
    return JSONResponse(content={"status": "error", "error": str(res)[:160]})

# How long the phone waits for the caption of a voice message (after that transcription goes to the background)
TG_CAPTION_WAIT = float(os.getenv("VIBENOKIA_TG_CAPTION_WAIT", "25"))
# How long background transcription may keep running after the voice message is sent
TG_LATE_STT_LIMIT = float(os.getenv("VIBENOKIA_TG_LATE_STT_LIMIT", "240"))
TG_STT_FAILED = "(расшифровка не удалась)"
_tg_bg_tasks = set()

async def _tg_late_transcript(chat_id, stt_task, reply_to):
    """Deliver the transcript as a separate reply to the voice message.
    stt_task=None means transcription already finished empty: go straight to "failed"."""
    text = ""
    if stt_task is not None:
        try:
            text = await asyncio.wait_for(stt_task, timeout=TG_LATE_STT_LIMIT) or ""
        except Exception:
            text = ""
    body = text[:TG_MSG_LIMIT] if text else TG_STT_FAILED
    params = {"chat_id": chat_id, "text": body, "disable_web_page_preview": True}
    if reply_to:
        params["reply_to_message_id"] = reply_to
        params["allow_sending_without_reply"] = True
    ok, res = await tg_api("sendMessage", params)
    if ok:
        tg_save_message(chat_id, "out", body, "text")
        logger.info(f"[TG VOICE] {chat_id}: transcript delivered separately "
                    f"({'ok' if text else 'failed'})")
    else:
        logger.error(f"[TG VOICE] {chat_id}: failed to deliver transcript: {_redact(str(res))[:200]}")

@app.post("/api/tg/voice")
async def tg_voice(request: Request):
    """Voice from the Nokia (AMR) -> OGG/Opus -> sent to the contact as a voice message."""
    audio_data = await request.body()
    chat_id = request.headers.get("x-chat-id", "").strip()
    want_transcript = request.headers.get("x-transcribe", "0") == "1"

    if not chat_id:
        return JSONResponse(content={"status": "error", "error": "нет chat_id"})
    if not audio_data or len(audio_data) < 100:
        return JSONResponse(content={"status": "error", "error": "нет аудио"})
    if not tg_token():
        return JSONResponse(content={"status": "error", "error": "нет токена бота"})

    logger.info(f"[TG VOICE] {len(audio_data)} bytes -> {chat_id}")
    kind, data = await amr_to_voice(audio_data)
    if not data:
        return JSONResponse(content={"status": "error", "error": "не сконвертировал аудио"})

    transcript = ""
    stt_task = None
    if want_transcript:
        # The phone waits at most TG_CAPTION_WAIT: if transcription is ready it goes
        # as the caption of the voice message. If not, the voice message goes out at once without a caption,
        # while transcription CONTINUES in the background and reaches the contact as a separate reply
        # (the chain may take up to ~2 min). The task is not cancelled on timeout.
        stt_task = asyncio.create_task(transcribe_audio(audio_data))
        done, _ = await asyncio.wait({stt_task}, timeout=TG_CAPTION_WAIT)
        if stt_task in done:
            try:
                transcript = stt_task.result() or ""
            except Exception:
                transcript = ""
            stt_task = None

    if kind == "voice":
        params = {"chat_id": chat_id}
        if transcript:
            params["caption"] = transcript[:1000]
        ok, res = await tg_api("sendVoice", params,
                               files={"voice": ("voice.ogg", data, "audio/ogg")}, timeout=40)
    else:
        params = {"chat_id": chat_id, "title": "Голосовое с Nokia"}
        if transcript:
            params["caption"] = transcript[:1000]
        ok, res = await tg_api("sendAudio", params,
                               files={"audio": ("voice.mp3", data, "audio/mpeg")}, timeout=40)

    if ok:
        tg_save_message(chat_id, "out", transcript or "[голосовое]", "voice")
        if want_transcript and not transcript:
            # The phone gets the same reply as before (status/transcript): the protocol does not change;
            # the contact gets the transcript later, or an honest "failed".
            msg_id = res.get("message_id") if isinstance(res, dict) else None
            t = asyncio.create_task(_tg_late_transcript(chat_id, stt_task, msg_id))
            _tg_bg_tasks.add(t)
            t.add_done_callback(_tg_bg_tasks.discard)
        return JSONResponse(content={"status": "ok", "transcript": transcript})
    if stt_task:
        stt_task.cancel()   # the voice message was not sent: the transcript is not needed
    logger.error(f"[TG VOICE] error {chat_id}: {_redact(str(res))[:200]}")
    return JSONResponse(content={"status": "error", "error": str(res)[:160]})

def _ota_base(request):
    # The base is the address the phone REALLY reached (otherwise a phone
    # sitting on the :8086 relay would pull a build from another server/version)
    try:
        host = request.headers.get("host", "")
        if host:
            return f"http://{host}"
    except Exception:
        pass
    return OTA_BASE_URL

# --- OTA: serving Snake.jar/jad (the client is disguised as Snake) ---
# The old /ota/NokiaClaude.* paths are kept as aliases to the SAME files, so that
# an already installed copy does not hit 404 but gets the installer.
# IMPORTANT, what this is NOT: the phone identifies an application by the pair
# MIDlet-Name + MIDlet-Vendor, and it has changed (NokiaClaude/VibeNokia ->
# Snake/Mobile Apps). So an old copy will install Snake as a SECOND application,
# stay in place and keep offering an "update".
# This is the migration path: install Snake, then delete the old icon.

@app.get("/api/update")
async def check_update(request: Request):
    jar_path = os.path.join(OTA_DIR, "Snake.jar")
    jar_size = os.path.getsize(jar_path) if os.path.exists(jar_path) else 0
    if jar_size == 0:
        return JSONResponse(status_code=404, content={"error": "jar_missing"})
    base = _ota_base(request)
    # jad_url WITHOUT a query string: the key is hidden in the path, since old phone
    # stacks (and the Opera Mini proxy) stumble on ?k=...
    return JSONResponse(content={
        "version": APP_VERSION,
        "jad_url": f"{base}/ota/Snake.jad",
        "jar_url": f"{base}/ota/Snake.jar",
        "jar_size": jar_size
    })

def _jad_text(base=None):
    # Manifest 1:1 as in client/Makefile (Snake / vibenokia.SnakeMIDlet),
    # otherwise the installer sees a foreign suite and installs a second application.
    jar_path = os.path.join(OTA_DIR, "Snake.jar")
    jar_size = os.path.getsize(jar_path) if os.path.exists(jar_path) else 0
    return f"""MIDlet-Name: Snake
MIDlet-Version: {APP_VERSION}
MIDlet-Vendor: Mobile Apps
MIDlet-Description: Classic snake game
MIDlet-Icon: /vibenokia/icon.png
MIDlet-1: Snake,/vibenokia/icon.png,vibenokia.SnakeMIDlet
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Jar-URL: {base or OTA_BASE_URL}/ota/Snake.jar
MIDlet-Jar-Size: {jar_size}
MIDlet-Permissions: javax.microedition.io.Connector.http, javax.microedition.io.Connector.file.read, javax.microedition.media.control.RecordControl
"""

def _jad_response(head=False, base=None):
    body = _jad_text(base)
    # Type WITHOUT '; charset=...': strict installers want it plain
    headers = {"Content-Type": "text/vnd.sun.j2me.app-descriptor",
               "Content-Length": str(len(body.encode("utf-8"))),
               "Cache-Control": "no-cache"}
    return Response(content=b"" if head else body.encode("utf-8"), headers=headers)

def _jar_response(head=False):
    jar_path = os.path.join(OTA_DIR, "Snake.jar")
    if not os.path.exists(jar_path):
        return JSONResponse(status_code=404, content={"error": "JAR not found"})
    with open(jar_path, "rb") as f:
        data = f.read()
    # Without content-disposition: attachment, otherwise "download as a file" instead of installing
    headers = {"Content-Type": "application/java-archive",
               "Content-Length": str(len(data)),
               "Cache-Control": "no-cache"}
    return Response(content=b"" if head else data, headers=headers)

# HEAD is supported explicitly: phones/proxies often send HEAD first, and a 405 means a hang.
# Two kinds of paths: the old ?k=... and the clean /ota/<key>/... without a query string.
@app.api_route("/ota/Snake.jad", methods=["GET", "HEAD"])
async def ota_jad(request: Request):
    return _jad_response(head=request.method == "HEAD", base=_ota_base(request))

@app.api_route("/ota/Snake.jar", methods=["GET", "HEAD"])
async def ota_jar(request: Request):
    return _jar_response(head=request.method == "HEAD")

@app.api_route("/ota/{key}/Snake.jad", methods=["GET", "HEAD"])
async def ota_jad_pathkey(key: str, request: Request):
    if not _key_eq(key, API_KEY):
        return JSONResponse(status_code=403, content={"error": "forbidden"})
    return _jad_response(head=request.method == "HEAD", base=_ota_base(request))

@app.api_route("/ota/{key}/Snake.jar", methods=["GET", "HEAD"])
async def ota_jar_pathkey(key: str, request: Request):
    if not _key_eq(key, API_KEY):
        return JSONResponse(status_code=403, content={"error": "forbidden"})
    return _jar_response(head=request.method == "HEAD")

# --- Game catalog: /g/<key> is the list, /g/<key>/<file> is the install ---
# The phone browser cannot send headers or (sometimes) a query, so the key
# lives right in the path, as with OTA. Only files from GAMES_DIR are served, by the exact
# name from the listing: no joining of paths from the request.

def _games_list():
    try:
        names = sorted(os.listdir(GAMES_DIR))
    except Exception:
        return []
    out = []
    for n in names:
        if not n.endswith(".jad"):
            continue
        jar = n[:-4] + ".jar"
        if jar not in names:
            continue
        title, vendor, desc = n[:-4], "", ""
        try:
            with open(os.path.join(GAMES_DIR, n), encoding="utf-8", errors="replace") as f:
                for line in f:
                    if line.startswith("MIDlet-Name:"):
                        title = line.split(":", 1)[1].strip()
                    elif line.startswith("MIDlet-Vendor:"):
                        vendor = line.split(":", 1)[1].strip()
                    elif line.startswith("MIDlet-Description:"):
                        desc = line.split(":", 1)[1].strip()
        except Exception:
            pass
        size = os.path.getsize(os.path.join(GAMES_DIR, jar))
        out.append((n, title, vendor, desc, size))
    return out

@app.get("/g/{key}")
async def games_index(key: str, request: Request):
    if not GAMES_ENABLED:
        return JSONResponse(status_code=404, content={"error": "not_found"})
    if not _key_eq(key, API_KEY):
        return JSONResponse(status_code=403, content={"error": "forbidden"})
    rows = _games_list()
    base = _ota_base(request) + "/g/" + API_KEY
    html = ["<html><head><meta charset=\"utf-8\"><title>Games</title></head><body>",
            "<h3>Games (%d)</h3><ul>" % len(rows)]
    for name, title, vendor, desc, size in rows:
        html.append('<li><a href="%s/%s">%s</a> &mdash; %s, %d KB</li>'
                    % (base, name, title, desc or vendor, size // 1024))
    html.append("</ul></body></html>")
    return Response(content="".join(html), media_type="text/html; charset=utf-8")

@app.api_route("/g/{key}/{name}", methods=["GET", "HEAD"])
async def games_file(key: str, name: str, request: Request):
    if not GAMES_ENABLED:
        return JSONResponse(status_code=404, content={"error": "not_found"})
    if not _key_eq(key, API_KEY):
        return JSONResponse(status_code=403, content={"error": "forbidden"})
    try:
        allowed = set(os.listdir(GAMES_DIR))
    except Exception:
        allowed = set()
    if name not in allowed or not (name.endswith(".jad") or name.endswith(".jar")):
        return JSONResponse(status_code=404, content={"error": "not_found"})
    path = os.path.join(GAMES_DIR, name)
    if name.endswith(".jad"):
        # Rewrite the jar URL to "our" address with the key, otherwise the phone
        # fetches the file without a key and gets 403 in the middle of the install
        base = _ota_base(request) + "/g/" + API_KEY
        lines = []
        for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
            if line.startswith("MIDlet-Jar-URL:"):
                jar = line.split(":", 1)[1].strip()
                line = "MIDlet-Jar-URL: %s/%s" % (base, os.path.basename(jar))
            lines.append(line)
        body = "\n".join(lines) + "\n"
        if request.method == "HEAD":
            return Response(status_code=200, media_type="text/vnd.sun.j2me.app-descriptor",
                            headers={"Content-Length": str(len(body.encode()))})
        return Response(content=body, media_type="text/vnd.sun.j2me.app-descriptor")
    if request.method == "HEAD":
        return Response(status_code=200, media_type="application/java-archive",
                        headers={"Content-Length": str(os.path.getsize(path))})
    return FileResponse(path, media_type="application/java-archive", filename=name)

# --- Phone pairing: once, then the device lives with its own key ---

PAIR_STARTS_PER_HOUR = int(os.getenv("VIBENOKIA_PAIR_PER_HOUR", "5"))
_pair_starts = []          # times of code requests in the last hour, SERVER-WIDE

def _pair_rate_ok(ip):
    # The limit is global, not per address: behind relays everyone is 127.0.0.1, and from outside addresses
    # change cheaply. Each new code cancels the previous one (see _pair_new_code), so
    # frequent code requests do not help guessing, and 5 per hour is enough for a person.
    now = time.time()
    _pair_starts[:] = [t for t in _pair_starts if now - t < 3600]
    if len(_pair_starts) >= PAIR_STARTS_PER_HOUR:
        return False
    _pair_starts.append(now)
    return True

# The pairing code is not written to the log in plain text (logs go to backups and are
# readable more widely than needed). It lives in a 0600 file next to the database; fetch it over ssh.
PAIR_CODE_FILE = os.getenv("VIBENOKIA_PAIR_CODE_FILE",
                           os.path.join(os.path.dirname(os.path.abspath(DB_PATH)), "pair_code.txt"))

def _pair_write_code_file(code):
    try:
        fd = os.open(PAIR_CODE_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write("%s %s\n" % (code, datetime.now().strftime("%Y-%m-%d %H:%M:%S")))
        os.chmod(PAIR_CODE_FILE, 0o600)
        return True
    except Exception as e:
        logger.warning(f"[PAIR] could not write the code to a file: {e.__class__.__name__}")
        return False

def _pair_new_code():
    import secrets as _s
    code = "%06d" % _s.randbelow(1000000)
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.execute("DELETE FROM pairings WHERE created < ?", (time.time() - PAIR_TTL,))
    # only one code stays alive: the last issued
    conn.execute("DELETE FROM pairings WHERE used=0")
    conn.execute("INSERT OR REPLACE INTO pairings (code, created, used) VALUES (?, ?, 0)",
                 (code, time.time()))
    conn.commit()
    conn.close()
    return code

_pair_misses = [0, 0.0]        # consecutive misses, when the streak started

def _pair_note_miss():
    """A few consecutive misses cancel ALL live codes: guessing within 10 minutes will not succeed."""
    now = time.time()
    if now - _pair_misses[1] > PAIR_TTL:
        _pair_misses[0] = 0
        _pair_misses[1] = now
    _pair_misses[0] += 1
    if _pair_misses[0] >= 5:
        try:
            conn = sqlite3.connect(DB_PATH, timeout=10)
            conn.execute("UPDATE pairings SET used=1 WHERE used=0")
            conn.commit()
            conn.close()
        except Exception:
            pass
        logger.warning("[PAIR] too many wrong codes, all live codes cancelled, request a new one")
        _pair_misses[0] = 0

def _pair_take_code(code):
    """One-time: the code is accepted exactly once and only while it has not expired."""
    conn = sqlite3.connect(DB_PATH, timeout=10)
    cur = conn.execute("SELECT created, used FROM pairings WHERE code=?", (code,))
    row = cur.fetchone()
    if not row or row[1] or time.time() - row[0] > PAIR_TTL:
        conn.close()
        _pair_note_miss()
        return False
    conn.execute("UPDATE pairings SET used=1 WHERE code=?", (code,))
    conn.commit()
    conn.close()
    return True

@app.post("/pair/start")
async def pair_start(request: Request):
    ip = _client_ip(request)
    if not _pair_rate_ok(ip):
        return JSONResponse(status_code=429, content={"error": "too_many_attempts"})
    code = _pair_new_code()
    # The code lives in a 0600 file (PAIR_CODE_FILE), which is enough to pair
    # a phone even when the Telegram bot is unavailable or its token is being changed.
    in_file = _pair_write_code_file(code)
    logger.warning(f"[PAIR] {ip}: pairing code issued (10 minutes)"
                   + (f", stored in {PAIR_CODE_FILE}" if in_file else ""))
    sent = False
    owner = os.getenv("VIBENOKIA_OWNER_CHAT", "").strip()
    if owner and tg_token():
        try:
            sent, _ = await tg_api("sendMessage", {"chat_id": owner,
                                         "text": f"Код привязки телефона: {code}\nДействует 10 минут."})
        except Exception as e:
            logger.warning(f"[PAIR] code was not sent to Telegram: {_redact(str(e))[:200]}")
    return JSONResponse(content={"status": "ok", "sent": sent, "ttl": PAIR_TTL})

@app.post("/pair/confirm")
async def pair_confirm(request: Request):
    ip = _client_ip(request)
    try:
        data = await request.json()
    except Exception:
        data = {}
    code = str(data.get("code", "")).strip()
    name = str(data.get("name", "phone")).strip()[:32] or "phone"
    if not code or not _pair_take_code(code):
        _note_bad_key(ip)              # wrong code = the same kind of guessing attempt
        logger.warning(f"[PAIR] {ip}: wrong or expired code")
        return JSONResponse(status_code=403, content={"error": "bad_code"})
    _pair_misses[0] = 0
    device_id = _new_hex(8)
    secret = _new_hex(32)
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.execute("INSERT INTO devices (device_id, secret, name, counter, revoked, last_seen) "
                 "VALUES (?, ?, ?, 0, 0, CURRENT_TIMESTAMP)", (device_id, secret, name))
    conn.commit()
    conn.close()
    _fail_counts.pop(ip, None)
    logger.warning(f"[PAIR] {ip}: device paired {device_id} ({name})")
    return JSONResponse(content={"status": "ok", "device_id": device_id, "secret": secret})

@app.post("/device/rotate")
async def device_rotate(request: Request):
    """Key reissue from the phone itself. The old key works until the new one takes hold."""
    dev = request.headers.get("x-dev", "")
    row = _dev_row(dev)
    if not row:
        return JSONResponse(status_code=403, content={"error": "unknown_device"})
    if row[2]:
        # rotation already started and not confirmed: return the SAME new key,
        # otherwise a dropped connection would leave the phone without a valid key
        return JSONResponse(content={"status": "ok", "secret": row[2], "repeat": True})
    secret = _new_hex(32)
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.execute("UPDATE devices SET secret_pending=? WHERE device_id=?", (secret, dev))
    conn.commit()
    conn.close()
    logger.warning(f"[AUTH] {dev}: new key issued (the old one works until the first request with the new one)")
    return JSONResponse(content={"status": "ok", "secret": secret, "repeat": False})

@app.on_event("startup")
async def startup():
    init_db()
    # Only ONE server holds the getUpdates poller (otherwise 409 Conflict and contacts
    # diverge across two databases). On the full-mode host set VIBENOKIA_TG_POLL=0.
    if os.getenv("VIBENOKIA_TG_POLL", "1") != "0":
        asyncio.create_task(tg_poller())
        logger.info("VibeNokia: Telegram poller enabled")
    else:
        logger.info("VibeNokia: Telegram poller disabled (VIBENOKIA_TG_POLL=0)")
    if OPENCODE_ENABLED:
        asyncio.create_task(_refresh_free())
    asyncio.create_task(_ping_log_loop())
    logger.info("VibeNokia API started")

if __name__ == "__main__":
    # Loopback by default. Expose it deliberately (reverse proxy / Docker publish), never raw.
    host = os.getenv("VIBENOKIA_HOST", "127.0.0.1")
    port = int(os.getenv("VIBENOKIA_PORT", "8085"))
    # access_log=False: the key in /ota/<key>/ is not exposed in the log (own log: [REQ]).
    # proxy_headers=False is MANDATORY: by default uvicorn replaces the client address
    # for the 127.0.0.1 peer with X-Forwarded-For, but through relays everyone arrives from
    # loopback and the header is set by the attacker (ban bypass / banning someone else's address).
    uvicorn.run(app, host=host, port=port, log_level="info", access_log=False,
                proxy_headers=False, forwarded_allow_ips="")
