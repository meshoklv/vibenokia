# Deployment: getting the phone to the server

The server only listens on `127.0.0.1:8085`. A Nokia 3310 (2017, 2G/3G data, no Wi-Fi) has to reach
it over the public internet, so you need something in front of it.

## What the phone can and cannot do

- It speaks **plain HTTP**. Its Java stack only supports old TLS, so modern HTTPS endpoints fail
  (see [platform notes](platform-notes.md)). A TLS reverse proxy is therefore **not** something you
  can rely on for the phone. It is still useful for browsers and for any newer handset or emulator.
- Because the channel is not encrypted, the app does not depend on secrecy of the transport:
  - the per-device key **never travels**; every request carries an HMAC-SHA256 signature over
    method, path, a request counter, the body hash and the server address;
  - replayed requests are rejected (sliding window, as in IPsec);
  - repeated bad signatures/codes ban the source address for 15 minutes.
- What this does **not** give you: confidentiality. Anyone on the path can read your prompts and
  Claude's answers. Do not send secrets from the phone.

## Option A: Caddy as a plain-HTTP front (recommended)

Public port `8080` (any free port), backend on loopback `8085`:

```caddyfile
# /etc/caddy/Caddyfile
http://:8080 {
    # only the paths the phone needs; everything else is a 404
    @phone path /ping /api/* /pair/* /device/* /ota/*
    handle @phone {
        reverse_proxy 127.0.0.1:8085
    }
    handle {
        respond 404
    }
}
```

Then:

1. Open the port in your firewall (`ufw allow 8080/tcp`); keep `8085` closed.
2. Set `VIBENOKIA_PUBLIC_HOST=your.host:8080` in `.env` so signatures made for other
   addresses are refused, and `VIBENOKIA_OTA_URL=http://your.host:8080`.
3. Build the client with `make -C client SERVER_URL=http://your.host:8080`.

Behind a proxy every client looks like `127.0.0.1` to the server. That is fine: bans are
then counted per device/code instead of per address, and `X-Forwarded-For` is deliberately
ignored (an attacker could forge it).

## Option B: nginx

```nginx
server {
    listen 8080;
    location ~ ^/(ping|api/|pair/|device/|ota/) {
        proxy_pass http://127.0.0.1:8085;
        proxy_read_timeout 150s;   # Claude can take a while
    }
    location / { return 404; }
}
```

## Option C: TLS in front (untested on the phone)

For a browser, a newer handset or an emulator you can terminate TLS:

```caddyfile
vibe.example.com {
    reverse_proxy 127.0.0.1:8085
}
```

and build the client with `SERVER_URL=https://vibe.example.com`. Not verified on real
hardware; the original target phone does not negotiate modern TLS.

## Pairing

1. `.env`: `VIBENOKIA_PAIRING=1`, restart the server.
2. On the phone press `5` on the pairing screen: the server writes a one-time 6-digit code to
   `data/pair_code.txt` (mode 600) and, if `VIBENOKIA_OWNER_CHAT` and a Telegram token are set,
   sends it to that chat. The code is valid for 10 minutes and works once.
   `docker compose exec server cat /app/data/pair_code.txt`
3. Type the code on the phone. It receives its own device key and stores it on the phone.
4. Set `VIBENOKIA_PAIRING=0` and restart: the `/pair/*` routes then answer 404.

Revoke a phone at any time: `python3 tools/devices.py --db data/sessions.db revoke --device-id <id>`
(run it on the host against the mounted `./data/sessions.db`).

## Checklist before exposing the port

```bash
ss -ltn | grep -v 127.0.0.1     # only the proxy port should be listed
curl -i http://your.host:8080/ping            # 403 without a signature
curl -i http://your.host:8080/api/chat -d '{}'  # 403, never 200
```
