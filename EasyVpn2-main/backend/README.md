# FastVPN backend — automatic device registration

This replaces manually running `add-client.sh` for every user, and it's set
up with **one script, run once per VPS** — nothing to edit by hand, nothing
to copy-paste between files.

Matches the flow you described: pick one VPS to be the "brain" (the control
API), and every other VPS just gets pointed at it.

```
User installs app
      ↓
App generates WireGuard key
      ↓
App registers public key with your API   <- backend/api (auto-deployed by setup.sh --role api)
      ↓
API selects VPS + allocates tunnel IP
      ↓
API adds WireGuard peer to VPS           <- backend/agent (auto-deployed by setup.sh --role node)
      ↓
App receives its VPN configuration
      ↓
Connect
```

---

## The entire setup, start to finish

### Step 1 — pick ONE of your VPS to be the "brain," run this on it:

```bash
scp -r backend root@your-brain-vps-ip:~/backend
ssh root@your-brain-vps-ip
cd ~/backend
sudo bash setup.sh --role api
```

(Have a domain ready to go behind Cloudflare? Use
`sudo bash setup.sh --role api --domain api.yourdomain.com` instead — see
"HTTPS before you actually launch publicly" below. Plain `--role api` is
fine for now if you just want to test first.)

It prints something like this — **copy the whole block down**, you'll reuse
it for every other VPS:

```
Your control API is running at: http://203.0.113.1:8080

Now run this SAME script with --role node on EVERY OTHER VPS:

  sudo bash setup.sh --role node \
    --api-url http://203.0.113.1:8080 \
    --admin-key 9f2ab31c...

Then bake http://203.0.113.1:8080 into the app as DEFAULT_BACKEND_API_URL (see Step 3 below)
```

### Step 2 — run this on EVERY OTHER VPS (the ones that will carry VPN traffic)

Copy the same `backend` folder there too, then run the command it gave you,
just adding which country that VPS represents:

```bash
scp -r backend root@your-2nd-vps-ip:~/backend
ssh root@your-2nd-vps-ip
cd ~/backend
sudo bash setup.sh --role node \
  --api-url http://203.0.113.1:8080 \
  --admin-key 9f2ab31c... \
  --country "United Kingdom" --country-code GB --city London
```

Repeat for each remaining VPS, changing only `--country` / `--country-code`
/ `--city`. Each one:
- installs and configures WireGuard
- installs its own small agent (the only thing allowed to touch WireGuard)
- **automatically tells the brain VPS about itself** — no manual JSON editing

That's it. If you have 6 VPS: 1 command on the brain, 5 commands on the
others (or 6 if the brain VPS also carries traffic — that's fine too, just
run both `--role api` and `--role node` on it).

### Step 3 — point the app at it

The backend URL is baked into the app at build time — one line in
`app/build.gradle`:

```groovy
buildConfigField "String", "DEFAULT_BACKEND_API_URL", "\"http://203.0.113.1:8080\""
```

Rebuild the app, and every install automatically uses that backend — no
manual setup needed by anyone. Server management (adding/removing VPS,
countries, etc.) all happens on the backend side via `setup.sh`; there's no
in-app admin UI.

---

## Watching it live: the dashboard

The domain root (`/`) is now a public marketing page for the app itself --
what a visitor sees if they land on your domain. The admin panel moved to
`/adminui` so it isn't the first thing a stranger sees. Update the
`PLAY_STORE_URL` constant near the bottom of `api/public/index.html` once
your Play Store listing is live -- until then the button shows "Coming soon".

Open `http://<brain-vps-ip>:8080/adminui` (or `https://yourdomain/adminui` if
you've set up a domain) in **your phone's browser** — no
terminal, no `curl`. Log in with the admin key from Step 1 (it remembers it
after that) and you'll see:

- Every connected VPS, with a green/red dot (checked in the background every
  60 seconds, not just "was it registered once") and a "checked Xs ago" note
  so you can see how fresh that is
- How many devices are registered on each one
- The exact command to add your next VPS, with the URL and admin key already
  filled in — just copy, paste onto the new VPS, and fill in the country. It
  shows up on the dashboard within a few seconds of the script finishing.

It refreshes automatically every 10 seconds.

---

## Checking it worked (command line, if you prefer)

On the brain VPS:

```bash
curl http://localhost:8080/api/health
# {"ok":true,"serverCount":5}   <- should match how many --role node VPS you set up

curl http://localhost:8080/api/servers
# should print all your servers as JSON
```

If a `--role node` run fails to register (it'll tell you clearly if so), it's
almost always one of: wrong `--api-url`, wrong `--admin-key` (copy-paste
exactly, no extra spaces), or the brain VPS's firewall blocking the
connection. Fix and just run the exact same command again — it's safe to
re-run.

---

## HTTPS before you actually launch publicly

Plain HTTP is fine while you're testing (debug builds of the app have
cleartext HTTP enabled just for this — see `app/src/debug/`). But:

- **Release builds enforce HTTPS by default** (Android blocks cleartext HTTP
  since API 28) — a plain `http://` Backend API URL simply won't work once
  you build a release APK/AAB for the Play Store.
- Play Store expects HTTPS for anything talking to a real backend.

`setup.sh` has this built in — one flag, no separate tools to install
yourself:

```bash
sudo bash setup.sh --role api --domain api.yourdomain.com
```

This sets up: `Internet → Cloudflare → HTTPS :443 → Nginx → 127.0.0.1:8080 →
API`. Specifically it:
- Installs and configures **Nginx** as a reverse proxy on port 443
- Makes the API listen on **127.0.0.1 only** — never on the public interface
- Blocks port 8080 from the public internet with **ufw** (SSH stays open)
- Prepares everything for Cloudflare's **SSL/TLS → Full (strict)** mode

**Before running it**, point your domain's DNS at the VPS through Cloudflare
(proxied / orange cloud), and grab a **Cloudflare Origin Certificate**:
Cloudflare dashboard → your domain → SSL/TLS → Origin Server → Create
Certificate (RSA 2048, include your domain, 15-year validity). It gives you
two blocks of text — save them as `<domain>.pem` (the certificate) and
`<domain>.key` (the private key).

If you run the command above *before* you have those two files, it still
works — it self-signs a temporary certificate so Nginx starts and the API
stays reachable, but Cloudflare's "Full (strict)" mode won't trust that
temporary cert. It prints the exact two file paths to drop your real
Cloudflare cert/key into (default: `/etc/ssl/fastvpn/<domain>.pem` and
`.key`) — once they're there, just **run the exact same command again** and
it picks them up and reloads Nginx automatically. Safe to re-run any time.

Then set your app's `DEFAULT_BACKEND_API_URL` to `https://api.yourdomain.com`
and rebuild.

**Verify it worked**, from your own machine (not the VPS itself):
```bash
curl -s https://api.yourdomain.com/api/health        # should return JSON
curl -v --max-time 5 http://YOUR_VPS_IP:8080/api/health  # should time out / refuse
```

Optional extra hardening — restrict 80/443 to Cloudflare's own IP ranges
(so nothing can reach Nginx except through Cloudflare):
```bash
sudo bash setup.sh --role api --domain api.yourdomain.com --cf-only
```
Only add `--cf-only` once you've confirmed the domain works normally first —
it makes port 443 unreachable for direct testing (curl from your own machine
outside Cloudflare, browser previews, etc).

---

## If something needs fixing later (advanced)

- **Re-running `setup.sh --role node`** on the same VPS is safe — it reuses
  the existing WireGuard key and just updates that server's entry.
- **Server list lives at** `/opt/fastvpn-api/data/servers.json` on the brain
  VPS if you ever want to hand-edit an entry (e.g. change a display name).
  See `api/data/servers_example.json` in this repo for the expected shape —
  it's a documentation template with placeholder values (example IP, a
  `PASTE_VPS1_PUBLIC_KEY_HERE` stand-in), not a real server, so copy it to
  `servers.json` and fill in real values rather than using it as-is.
- **Server picking** is currently random among your servers. If you want
  load-based picking instead (send new users to whichever server has fewest
  registrations), that logic is one function in `api/server.js`'s
  `/api/register` handler.
- **IP allocation** is a simple incrementing counter per server (good for up
  to ~250 users per server's /24 subnet). Fine for beta/moderate scale.
- **No revocation UI yet.** `agent.js` has a `/remove-peer` endpoint ready,
  but nothing calls it automatically yet (apps can't reliably detect their
  own uninstall). A "sign out this device" button calling a future
  `/api/unregister` endpoint is the natural next addition.
