#!/bin/bash
# ============================================================================
# FastVPN unified setup script. Two modes, same file:
#
#   MODE 1 -- run ONCE, on whichever VPS you want to be the "brain":
#     sudo bash setup.sh --role api
#
#     For a real public launch with HTTPS behind Cloudflare (recommended),
#     add --domain. This puts Nginx + a Cloudflare Origin Certificate in
#     front of the API, binds the API to 127.0.0.1 only, and blocks the
#     API's own port from the public internet with ufw:
#       sudo bash setup.sh --role api --domain api.yourdomain.com
#     (Grab a Cloudflare Origin Certificate first -- Cloudflare dashboard ->
#     SSL/TLS -> Origin Server -> Create Certificate -- or just run the
#     command above once first: it prints exactly where to put the two
#     files, then re-run the same command to pick them up. Safe to re-run
#     either way.)
#
#   MODE 2 -- run on EVERY OTHER VPS (the ones that will actually carry VPN
#   traffic). It sets up WireGuard AND registers itself with your API
#   automatically -- no manual copying of keys or editing JSON files:
#     sudo bash setup.sh --role node \
#       --api-url http://YOUR_API_VPS_IP:8080 \
#       --admin-key PASTE_FROM_STEP_1 \
#       --country "United Kingdom" --country-code GB --city London
#
# That's the entire setup: one command on your brain VPS, one command per
# other VPS. Nothing to edit by hand.
# ============================================================================
set -e

ROLE=""
API_URL=""
ADMIN_KEY=""
COUNTRY_NAME="Unknown"
COUNTRY_CODE="US"
CITY=""
SERVER_NAME=""
WG_PORT=51820
AGENT_PORT=8787
API_PORT=8080
WG_SUBNET_BASE="10.8.0"
DOMAIN=""
CF_CERT=""
CF_KEY=""
CF_ONLY=0
BRAIN_IP=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --role) ROLE="$2"; shift 2 ;;
    --api-url) API_URL="$2"; shift 2 ;;
    --brain-ip) BRAIN_IP="$2"; shift 2 ;;
    --admin-key) ADMIN_KEY="$2"; shift 2 ;;
    --country) COUNTRY_NAME="$2"; shift 2 ;;
    --country-code) COUNTRY_CODE="$2"; shift 2 ;;
    --city) CITY="$2"; shift 2 ;;
    --name) SERVER_NAME="$2"; shift 2 ;;
    --wg-port) WG_PORT="$2"; shift 2 ;;
    --agent-port) AGENT_PORT="$2"; shift 2 ;;
    --api-port) API_PORT="$2"; shift 2 ;;
    --wg-subnet-base) WG_SUBNET_BASE="$2"; shift 2 ;;
    # HTTPS mode for --role api: puts Nginx + a Cloudflare Origin Certificate in
    # front of the API, binds the API to 127.0.0.1 only, and blocks 8080 from
    # the public internet with ufw. See backend/README.md for how to fetch a
    # Cloudflare Origin Certificate before running this.
    --domain) DOMAIN="$2"; shift 2 ;;
    --cf-cert) CF_CERT="$2"; shift 2 ;;
    --cf-key) CF_KEY="$2"; shift 2 ;;
    # Extra hardening: only allow 80/443 from Cloudflare's own IP ranges,
    # fetched fresh from Cloudflare at run time. Optional -- off by default
    # because it makes port 443 unreachable for direct testing (curl from your
    # own machine, browser without going through Cloudflare, etc).
    --cf-only) CF_ONLY=1; shift 1 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ "$ROLE" != "api" && "$ROLE" != "node" ]]; then
  echo "Usage:"
  echo "  sudo bash setup.sh --role api"
  echo "  sudo bash setup.sh --role api --domain api.yourdomain.com [--cf-cert <path>] [--cf-key <path>] [--cf-only]"
  echo "  sudo bash setup.sh --role node --api-url <url> --admin-key <key> --country <name> --country-code <cc> [--city <city>] [--name <name>] [--brain-ip <ip>]"
  echo ""
  echo "  --brain-ip is required when --api-url is a domain name (e.g. behind"
  echo "  Cloudflare) rather than a raw IP -- pass the brain VPS's real public"
  echo "  IPv4, found by running 'curl -s -4 ifconfig.me' on the brain VPS."
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

install_node_runtime() {
  if ! command -v node >/dev/null 2>&1; then
    echo "==> Installing Node.js..."
    # Node 20 reached end-of-life in April 2026 (no more security patches).
    # 24.x is the current Active LTS line (supported into 2028).
    curl -fsSL https://deb.nodesource.com/setup_24.x | bash -
    apt-get install -y nodejs
  fi
}

# ============================================================================
# ROLE: api -- the brain. Run this on ONE machine.
# ============================================================================
if [[ "$ROLE" == "api" ]]; then
  install_node_runtime

  echo "==> Installing firewall + curl (needed by this script itself)..."
  apt-get update -y
  apt-get install -y ufw curl openssl

  INSTALL_DIR="/opt/fastvpn-api"
  echo "==> Setting up the control API at ${INSTALL_DIR}..."

  # Re-running this script (e.g. to pick up an updated server.js/dashboard.html)
  # stops the old service first, THEN copies the new files and starts it back
  # up -- so the running process is always either the old code or the new
  # code, never a half-updated mix, and you never end up with two copies
  # fighting over the same port. Safe on a brand-new machine too: stopping a
  # service that was never installed is just a harmless no-op.
  echo "==> Stopping any existing fastvpn-api service first..."
  systemctl stop fastvpn-api 2>/dev/null || true

  mkdir -p "${INSTALL_DIR}/data"
  cp "${SCRIPT_DIR}/api/server.js" "${INSTALL_DIR}/server.js"
  cp "${SCRIPT_DIR}/api/store.js" "${INSTALL_DIR}/store.js"
  cp "${SCRIPT_DIR}/api/serverStore.js" "${INSTALL_DIR}/serverStore.js"
  cp "${SCRIPT_DIR}/api/rateLimiter.js" "${INSTALL_DIR}/rateLimiter.js"
  cp "${SCRIPT_DIR}/api/package.json" "${INSTALL_DIR}/package.json"
  mkdir -p "${INSTALL_DIR}/public"
  cp -r "${SCRIPT_DIR}/api/public/." "${INSTALL_DIR}/public/"

  cd "${INSTALL_DIR}"
  echo "==> Installing dependencies..."
  npm install --omit=dev --no-audit --no-fund

  # When --domain is set, the API only listens on localhost -- Nginx is what
  # actually faces the internet on 443. Without --domain, keep the old
  # behavior (listens on all interfaces) so existing plain-HTTP installs
  # aren't affected by upgrading this script.
  BIND_HOST="0.0.0.0"
  if [[ -n "$DOMAIN" ]]; then
    BIND_HOST="127.0.0.1"
  fi

  cat > /etc/systemd/system/fastvpn-api.service << EOF
[Unit]
Description=FastVPN control API
After=network.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
Environment=PORT=${API_PORT}
Environment=HOST=${BIND_HOST}
Environment=NODE_ENV=production
ExecStart=$(command -v node) ${INSTALL_DIR}/server.js
Restart=on-failure
User=root

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable fastvpn-api
  systemctl start fastvpn-api
  sleep 2 # give it a moment to start and generate its admin key on first boot

  # Actually verify the service is alive and answering, rather than just
  # assuming success -- a crashed/crash-looping service (e.g. a missing file)
  # would otherwise go unnoticed here, since data/admin.config.json can
  # persist from an earlier successful run even while the CURRENT one is
  # broken, which previously made this check falsely report success.
  if ! systemctl is-active --quiet fastvpn-api; then
    echo ""
    echo "############################################################"
    echo " FAILED: the fastvpn-api service did not start. Recent logs:"
    echo "############################################################"
    journalctl -u fastvpn-api --no-pager -n 30
    exit 1
  fi
  if ! curl -s -f "http://localhost:${API_PORT}/api/health" > /dev/null; then
    echo ""
    echo "############################################################"
    echo " FAILED: the service is running but isn't answering on port"
    echo " ${API_PORT}. Recent logs:"
    echo "############################################################"
    journalctl -u fastvpn-api --no-pager -n 30
    exit 1
  fi
  echo "==> Verified: the API is running and responding."

  # ==========================================================================
  # Optional HTTPS front-end: Internet -> Cloudflare -> Nginx :443 -> API on
  # 127.0.0.1 only. Only runs when --domain is passed. Safe to re-run: it only
  # touches the one Nginx site file it creates, never other sites-enabled
  # entries, and never touches ufw rules that don't mention ${API_PORT}/80/443.
  # ==========================================================================
  NGINX_CONFIGURED=0
  if [[ -n "$DOMAIN" ]]; then
    echo "==> Installing Nginx for HTTPS reverse proxy (domain: ${DOMAIN})..."
    apt-get install -y nginx

    CERT_DIR="/etc/ssl/fastvpn"
    mkdir -p "${CERT_DIR}"
    CERT_PATH="${CF_CERT:-${CERT_DIR}/${DOMAIN}.pem}"
    KEY_PATH="${CF_KEY:-${CERT_DIR}/${DOMAIN}.key}"

    # Cloudflare Origin Certificate for api.fastvpnn.pp.ua, bundled in so this
    # one domain is truly one-click with no manual scp/paste step. Covers
    # *.fastvpnn.pp.ua + fastvpnn.pp.ua, issued by Cloudflare Origin SSL CA,
    # valid Sep 2026 - Aug 2041. Only written if nothing already exists at
    # these paths, so it never overwrites a cert you've since rotated
    # yourself, and it's a no-op for any other --domain value.
    if [[ "$DOMAIN" == "api.fastvpnn.pp.ua" && ( ! -f "$CERT_PATH" || ! -f "$KEY_PATH" ) ]]; then
      echo "==> Installing bundled Cloudflare Origin Certificate for ${DOMAIN}..."
      cat > "${CERT_PATH}" << 'CF_CERT_EOF'
-----BEGIN CERTIFICATE-----
MIIEqDCCA5CgAwIBAgIUApIZF+0njJEyo2AAoX1CcJzkkI0wDQYJKoZIhvcNAQEL
BQAwgYsxCzAJBgNVBAYTAlVTMRkwFwYDVQQKExBDbG91ZEZsYXJlLCBJbmMuMTQw
MgYDVQQLEytDbG91ZEZsYXJlIE9yaWdpbiBTU0wgQ2VydGlmaWNhdGUgQXV0aG9y
aXR5MRYwFAYDVQQHEw1TYW4gRnJhbmNpc2NvMRMwEQYDVQQIEwpDYWxpZm9ybmlh
MB4XDTI2MDkwMjA0MjUwMFoXDTQxMDgyOTA0MjUwMFowYjEZMBcGA1UEChMQQ2xv
dWRGbGFyZSwgSW5jLjEdMBsGA1UECxMUQ2xvdWRGbGFyZSBPcmlnaW4gQ0ExJjAk
BgNVBAMTHUNsb3VkRmxhcmUgT3JpZ2luIENlcnRpZmljYXRlMIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEAl7BNfRcPp0RULL4juep2iHehrgLbRB29sJ8d
Lc39qOy3iBc2F8SQM0ZDA5kRbadRLWBAsFFfYVJEpTECbXQ00Leu2Xf7jkJPNevL
Ix3Aw45HnOlyRtsZkGDfy2mjPvevfmkCB+Q88GTdvbwNk9V+GjqSaKAG0IOObu+g
CrZKSXDm+1fzZv9HWKwTS+vcdGGH2h8RZPg2krgn/BGRvCMdwzA3whmGJ6qIaoLJ
7AKT1Qrecv12QcexOHX4QwO4XK1zGCfVqZsSXcUbI3SJGvZUzpsmecYeS3/If5Rp
S6vIvzCfLmvzOnIX5GWHRSWxsd7Wv0ueID7upoG7jdC01bNXDwIDAQABo4IBKjCC
ASYwDgYDVR0PAQH/BAQDAgWgMB0GA1UdJQQWMBQGCCsGAQUFBwMCBggrBgEFBQcD
ATAMBgNVHRMBAf8EAjAAMB0GA1UdDgQWBBRb4J1F5ktJUeJEX792wBhV5QwDqjAf
BgNVHSMEGDAWgBQk6FNXXXw0QIep65TbuuEWePwppDBABggrBgEFBQcBAQQ0MDIw
MAYIKwYBBQUHMAGGJGh0dHA6Ly9vY3NwLmNsb3VkZmxhcmUuY29tL29yaWdpbl9j
YTArBgNVHREEJDAighAqLmZhc3R2cG5uLnBwLnVhgg5mYXN0dnBubi5wcC51YTA4
BgNVHR8EMTAvMC2gK6AphidodHRwOi8vY3JsLmNsb3VkZmxhcmUuY29tL29yaWdp
bl9jYS5jcmwwDQYJKoZIhvcNAQELBQADggEBALh8cn3o19oyCVcTMFBSZChd580Q
W3Z3RZfwrOaxLW7AcnL4RgKm3V0tMR1/tW5qie4SYZlJIQywsnnNHTcA6o7S6Jyf
oL3SgbhN+EHL1FTuLVO92pdMA42u4WiLLS2kTRZUAbeHbNmYXiJstmu4RuPLah/G
3e4kjGGzrDy1TUSpQxKzpc1MovCUcGj0f2JUybxdMES7upnvRXYfUQRfGL/XJDl9
w0srNbL/qMgISCIWtMqt74rSrtSM8qmo1oHKNOzrxq/jy7UQCQ7XjjFYixWgGPRP
gLgFqJ589zRG5LXukSXz+lJgNwJGpQGjUYEhAgJKOehm16/n0NIycpkMcPU=
-----END CERTIFICATE-----
CF_CERT_EOF
      cat > "${KEY_PATH}" << 'CF_KEY_EOF'
-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCXsE19Fw+nRFQs
viO56naId6GuAttEHb2wnx0tzf2o7LeIFzYXxJAzRkMDmRFtp1EtYECwUV9hUkSl
MQJtdDTQt67Zd/uOQk8168sjHcDDjkec6XJG2xmQYN/LaaM+969+aQIH5DzwZN29
vA2T1X4aOpJooAbQg45u76AKtkpJcOb7V/Nm/0dYrBNL69x0YYfaHxFk+DaSuCf8
EZG8Ix3DMDfCGYYnqohqgsnsApPVCt5y/XZBx7E4dfhDA7hcrXMYJ9WpmxJdxRsj
dIka9lTOmyZ5xh5Lf8h/lGlLq8i/MJ8ua/M6chfkZYdFJbGx3ta/S54gPu6mgbuN
0LTVs1cPAgMBAAECggEAArat0ekUZK3/RR4dYUNGPz0pZn+LG4kbPaQ6rDwrpH5K
1rRkjHSiY6pWX/K+2CSA6ACj+SOCwwpYmr5OA+JYznvqWWQ2Zz5QxH5XfY/hDhbp
9laHcc0aIrYlg+jjYTr4GLeQJAwMPWqA5wF6iAkZlCZ+bNMPep/JY6KeHGX+rNYQ
byZRurlzTfHe99M95Qrnx2fkgqBku3ZqQaxCoLzN1Z+sfEm+zCMRcreC2yBSUTSg
gRM/d5h0PUh84WQ38qDCH1NYs4l+tde2QV+SND9JH9OLmjBOgsMwQatknqRns44d
p6od+HfbscU7+0yLmsHTM0aHOX/X0dbDLfX6iB+joQKBgQDGiCmnHMhDkQ1Tvkfq
DvlJcQVqatClLP8nLfDGeS/QMK1Pbv5PrAHLmFixstIZZqeU9FS3K94LIqHMzs8C
ox/ZvM3XgapyM0BubGIQg7MMuXUZKcfVnZ0Ue+LGiN8nOjjhJZ+Guw9f7pPTDuva
jTwTqFG5Ae2BROlIvgMwJ6Cn4QKBgQDDmOlIYul/bYFcuHhXoTkLneI+sWwBlHMX
m/cbE2km6c9QH8toBfQpGAP/I8nKh71UAnbdK2bEAALkonW57DPqPn5yazvGJK0R
1hckwaGM6aNjNbzifWIGmV03CBh0dr88dn5Xyt/7dKmQs3zux2iXGxHeABJTbhoN
s4ByJ5oc7wKBgGk3jsO3WFeez25bgSeF6g5HLPSaSZvQviVYjqvRXEq9EYzwqS02
Xvk084Sx3fGCWzxxRViSkipan1+5FzMxmta93mXhnaDKktIy9MIF2mXbADlm0Dbh
QnextJ09uu7CR5TjaKlyCBLykTuhQ9RfV8CfSzh+0g3ZpL1dnrjtt5JhAoGAErgP
m13b+tYAC1+cJMbJLtNtU5KnQ39xhFDo1S6GYbL+pCxmyw3G0Cf8Oe6y4S8cb23M
8l20+A0IOdlSaviv/zr73vdkQRJBffN/Q4VEcLfraxM5gHN/biI/SYT80iiLJL4y
WTSROv9vOunyiueKmut8SkK3fkSLOcR0BnjDxwECgYBmCoUu2CqsO714kQh1inVs
eifPUMlztgsfKqTIi2pzrdI+6UovOqQKixIX6jZQXMFon1cGNTx49uKb0Ydv5LN/
ZmG3Dfsa9XDSanjMp8OLr4MyAYsZHcq+OkoUKTfkDWbRcdUpXvHHKHncpbhMgEb7
6TSm3Ec71vsKdCjITTLgKA==
-----END PRIVATE KEY-----
CF_KEY_EOF
      chmod 600 "${KEY_PATH}"
    fi

    if [[ ! -f "$CERT_PATH" || ! -f "$KEY_PATH" ]]; then
      echo ""
      echo "############################################################"
      echo " No Cloudflare Origin Certificate found yet at:"
      echo "   ${CERT_PATH}"
      echo "   ${KEY_PATH}"
      echo ""
      echo " Nginx will start with a temporary self-signed certificate so"
      echo " the API stays reachable in the meantime -- but Cloudflare's"
      echo " \"Full (strict)\" mode will NOT trust it until you replace it."
      echo ""
      echo " To fix: Cloudflare dashboard -> your domain -> SSL/TLS ->"
      echo " Origin Server -> Create Certificate (RSA 2048, include"
      echo " ${DOMAIN}, validity 15 years). Paste the two blocks it gives"
      echo " you into these exact paths on this VPS:"
      echo "   ${CERT_PATH}   (the \"Origin Certificate\")"
      echo "   ${KEY_PATH}    (the \"Private key\")"
      echo " Then re-run this same command -- it will pick up the real"
      echo " cert and reload Nginx automatically, nothing else changes."
      echo "############################################################"
      echo ""
      openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
        -keyout "${KEY_PATH}" -out "${CERT_PATH}" \
        -subj "/CN=${DOMAIN}" >/dev/null 2>&1
    else
      echo "==> Found existing certificate files, using them."
    fi
    chmod 600 "${KEY_PATH}"

    cat > /etc/nginx/sites-available/fastvpn-api.conf << EOF
# Managed by FastVPN setup.sh -- safe to re-run, only this file is touched.
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};
    return 301 https://\$host\$request_uri;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name ${DOMAIN};

    ssl_certificate ${CERT_PATH};
    ssl_certificate_key ${KEY_PATH};
    ssl_protocols TLSv1.2 TLSv1.3;

    client_max_body_size 4M;

    location / {
        proxy_pass http://127.0.0.1:${API_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF

    ln -sf /etc/nginx/sites-available/fastvpn-api.conf /etc/nginx/sites-enabled/fastvpn-api.conf
    # Only remove Nginx's own stock placeholder page, never anything else you
    # may have configured yourself in sites-enabled.
    if [[ -L /etc/nginx/sites-enabled/default ]]; then
      rm -f /etc/nginx/sites-enabled/default
    fi

    nginx -t
    systemctl enable nginx
    systemctl reload nginx 2>/dev/null || systemctl restart nginx

    if ! curl -sk -f "https://127.0.0.1/api/health" -H "Host: ${DOMAIN}" > /dev/null; then
      echo ""
      echo "############################################################"
      echo " FAILED: Nginx is running but isn't proxying to the API"
      echo " correctly on 443. Check: nginx -t / journalctl -u nginx"
      echo "############################################################"
      exit 1
    fi
    echo "==> Verified: Nginx is proxying HTTPS -> 127.0.0.1:${API_PORT} correctly."
    NGINX_CONFIGURED=1
  fi

  # NOTE: allow rules alone do nothing until ufw is actually enabled -- unlike
  # the node role below, a fresh VPS image typically has no firewall active
  # at all yet, so both steps are required (SSH allowed first, same as the
  # node role, so enabling ufw can never lock you out of the VPS over SSH).
  echo "==> Configuring firewall..."
  ufw allow OpenSSH

  if [[ "$NGINX_CONFIGURED" == "1" ]]; then
    # Never expose the API's own port publicly once Nginx is the front door.
    # Remove any earlier "allow" rule for it first (e.g. re-running this
    # script after a previous plain-HTTP install), then explicitly deny it --
    # belt and suspenders alongside the API only listening on 127.0.0.1.
    ufw delete allow ${API_PORT}/tcp 2>/dev/null || true
    ufw deny ${API_PORT}/tcp

    if [[ "$CF_ONLY" == "1" ]]; then
      echo "==> Restricting 80/443 to Cloudflare's IP ranges only..."
      ufw delete allow 80/tcp 2>/dev/null || true
      ufw delete allow 443/tcp 2>/dev/null || true
      ufw delete allow "Nginx Full" 2>/dev/null || true
      for ip in $(curl -s https://www.cloudflare.com/ips-v4); do
        ufw allow from "$ip" to any port 80,443 proto tcp
      done
      for ip in $(curl -s https://www.cloudflare.com/ips-v6); do
        ufw allow from "$ip" to any port 80,443 proto tcp
      done
    else
      ufw allow "Nginx Full"
    fi
  else
    # No --domain: preserve the original behavior (API port open directly),
    # so existing plain-HTTP installs are unaffected by upgrading this script.
    ufw allow ${API_PORT}/tcp
  fi

  ufw --force enable

  ADMIN_KEY_GENERATED=$(node -e "console.log(JSON.parse(require('fs').readFileSync('${INSTALL_DIR}/data/admin.config.json')).adminKey)")
  MY_IP=$(curl -s -4 ifconfig.me)

  if [[ "$NGINX_CONFIGURED" == "1" ]]; then
    PUBLIC_URL="https://${DOMAIN}"
    echo ""
    echo "============================================================"
    echo " Your control API is running at: ${PUBLIC_URL}"
    echo " (Nginx on 443 -> 127.0.0.1:${API_PORT}; port ${API_PORT} itself"
    echo " is now blocked from the public internet by ufw.)"
    echo ""
    echo " Dashboard: ${PUBLIC_URL}/"
    echo "   Log in with this admin key: ${ADMIN_KEY_GENERATED}"
    echo ""
    echo " Now run this SAME script with --role node on EVERY OTHER VPS"
    echo " (fill in --country / --country-code / --city for each one) --"
    echo " the dashboard also shows this exact command, ready to copy:"
    echo ""
    echo "   sudo bash setup.sh --role node \\"
    echo "     --api-url ${PUBLIC_URL} \\"
    echo "     --admin-key ${ADMIN_KEY_GENERATED} \\"
    echo "     --country \"United Kingdom\" --country-code GB --city London"
    echo ""
    echo " Then bake ${PUBLIC_URL} into the app as DEFAULT_BACKEND_API_URL"
    echo " (app/build.gradle) and rebuild."
    echo ""
    echo " Two things to verify yourself once DNS has propagated:"
    echo "  1. In Cloudflare -> DNS, make sure the record for ${DOMAIN} is"
    echo "     proxied (orange cloud, not grey) and SSL/TLS mode is set to"
    echo "     \"Full (strict)\"."
    echo "  2. From your OWN machine (not this VPS), confirm 8080 is closed:"
    echo "       curl -v --max-time 5 http://${MY_IP}:${API_PORT}/api/health"
    echo "     -- this should time out / refuse, NOT return JSON. And:"
    echo "       curl -s ${PUBLIC_URL}/api/health"
    echo "     -- this SHOULD return JSON, confirming HTTPS end-to-end."
    echo "============================================================"
  else
    echo ""
    echo "============================================================"
    echo " Your control API is running at: http://${MY_IP}:${API_PORT}"
    echo ""
    echo " Dashboard (open this in your phone's browser to see connected"
    echo " VPS and registered device counts, updates live):"
    echo "   http://${MY_IP}:${API_PORT}/"
    echo "   Log in with this admin key: ${ADMIN_KEY_GENERATED}"
    echo ""
    echo " Now run this SAME script with --role node on EVERY OTHER VPS"
    echo " (fill in --country / --country-code / --city for each one) --"
    echo " the dashboard also shows this exact command, ready to copy:"
    echo ""
    echo "   sudo bash setup.sh --role node \\"
    echo "     --api-url http://${MY_IP}:${API_PORT} \\"
    echo "     --admin-key ${ADMIN_KEY_GENERATED} \\"
    echo "     --country \"United Kingdom\" --country-code GB --city London"
    echo ""
    echo " Then bake http://${MY_IP}:${API_PORT} into the app as DEFAULT_BACKEND_API_URL"
    echo " (app/build.gradle) and rebuild."
    echo ""
    echo " NOTE: plain HTTP is fine for testing. For a real public launch,"
    echo " put this behind HTTPS by re-running with --domain, e.g.:"
    echo "   sudo bash setup.sh --role api --domain api.yourdomain.com"
    echo " (see backend/README.md) -- release builds of the app won't"
    echo " connect to a plain http:// backend."
    echo "============================================================"
  fi
  exit 0
fi

# ============================================================================
# ROLE: node -- an actual VPN server. Run this on every VPS that will carry
# traffic (i.e. all of them except whichever one you picked for --role api,
# unless you want that one to double up as a VPN server too, which is fine).
# ============================================================================
if [[ -z "$API_URL" || -z "$ADMIN_KEY" ]]; then
  echo "Error: --role node requires --api-url and --admin-key (printed by --role api)."
  exit 1
fi

WG_IFACE="wg0"
WG_SUBNET="${WG_SUBNET_BASE}.1/24"

echo "==> Installing WireGuard..."
apt-get update -y
apt-get install -y wireguard qrencode ufw curl

NET_IF=$(ip route | awk '/default/ {print $5; exit}')

# SAFETY CHECK: if this VPS's own private network already uses the same
# subnet we're about to give WireGuard, routing breaks in a very confusing
# way -- the tunnel connects fine (that's a separate handshake), but no
# actual internet traffic gets through. This has bitten real setups before.
NET_IF_IP=$(ip -4 addr show "${NET_IF}" | grep -oP 'inet \K[\d.]+' | head -1)
if [[ -n "$NET_IF_IP" && "${NET_IF_IP%.*}" == "${WG_SUBNET_BASE}" ]]; then
  echo ""
  echo "############################################################"
  echo " STOP: This VPS's own network interface (${NET_IF}) is already"
  echo " using ${NET_IF_IP}, which is in the SAME subnet (${WG_SUBNET_BASE}.0/24)"
  echo " this script was about to give WireGuard. Using it anyway would"
  echo " connect the VPN but silently break all internet access through it."
  echo ""
  echo " Re-run with a different subnet, e.g.:"
  echo "   sudo bash setup.sh --role node ... --wg-subnet-base 10.9.0"
  echo "############################################################"
  exit 1
fi

echo "==> Enabling IP forwarding..."
sed -i '/net.ipv4.ip_forward/d' /etc/sysctl.conf
echo "net.ipv4.ip_forward=1" >> /etc/sysctl.conf
sysctl -p
sysctl -w net.ipv4.ip_forward=1 >/dev/null # apply immediately too, don't rely only on sysctl -p

echo "==> Generating server keypair (or reusing existing one if this VPS was set up before)..."
mkdir -p /etc/wireguard
cd /etc/wireguard
umask 077
if [[ ! -f server_private.key ]]; then
  wg genkey | tee server_private.key | wg pubkey > server_public.key
fi
SERVER_PRIV=$(cat server_private.key)
SERVER_PUB=$(cat server_public.key)

cat > /etc/wireguard/${WG_IFACE}.conf << EOF
[Interface]
Address = ${WG_SUBNET}
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIV}
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o ${NET_IF} -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o ${NET_IF} -j MASQUERADE
EOF

echo "==> Opening firewall for WireGuard..."
ufw allow ${WG_PORT}/udp
ufw allow OpenSSH
ufw --force enable

echo "==> Starting WireGuard..."
systemctl enable wg-quick@${WG_IFACE}
systemctl restart wg-quick@${WG_IFACE}

echo "==> Installing the agent (the only thing allowed to add WireGuard peers here)..."

# Same reasoning as the api role: stop the old service first so a re-run
# (e.g. to pick up an updated agent.js) always starts completely fresh
# instead of overlapping with whatever's currently running. Harmless no-op
# on a brand-new machine where the service doesn't exist yet.
echo "==> Stopping any existing fastvpn-agent service first..."
systemctl stop fastvpn-agent 2>/dev/null || true

install_node_runtime
AGENT_DIR="/opt/fastvpn-agent"
mkdir -p "${AGENT_DIR}"
cp "${SCRIPT_DIR}/agent/agent.js" "${AGENT_DIR}/agent.js"
cp "${SCRIPT_DIR}/agent/package.json" "${AGENT_DIR}/package.json"

cd "${AGENT_DIR}"
npm install --omit=dev --no-audit --no-fund

AGENT_API_KEY=$(node -e "console.log(require('crypto').randomBytes(24).toString('hex'))")
cat > "${AGENT_DIR}/agent.config.json" << EOF
{
  "port": ${AGENT_PORT},
  "apiKey": "${AGENT_API_KEY}",
  "wgInterface": "${WG_IFACE}"
}
EOF

cat > /etc/systemd/system/fastvpn-agent.service << EOF
[Unit]
Description=FastVPN agent
After=network.target wg-quick@${WG_IFACE}.service

[Service]
Type=simple
WorkingDirectory=${AGENT_DIR}
Environment=NODE_ENV=production
ExecStart=$(command -v node) ${AGENT_DIR}/agent.js
Restart=on-failure
User=root

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable fastvpn-agent
systemctl start fastvpn-agent
sleep 1

# Actually verify the agent started, rather than assuming success and only
# finding out later when registration with the brain fails with a confusing
# error -- same reasoning as the equivalent check in the api role above.
if ! systemctl is-active --quiet fastvpn-agent; then
  echo ""
  echo "############################################################"
  echo " FAILED: the fastvpn-agent service did not start. Recent logs:"
  echo "############################################################"
  journalctl -u fastvpn-agent --no-pager -n 30
  exit 1
fi
if ! curl -s -f -H "X-Api-Key: ${AGENT_API_KEY}" "http://localhost:${AGENT_PORT}/health" > /dev/null; then
  echo ""
  echo "############################################################"
  echo " FAILED: the agent is running but isn't answering on port"
  echo " ${AGENT_PORT}. Recent logs:"
  echo "############################################################"
  journalctl -u fastvpn-agent --no-pager -n 30
  exit 1
fi
echo "==> Verified: the agent is running and responding."

# Restrict the agent port to ONLY the brain API's IP, rather than opening it
# to the whole internet -- nobody else has any reason to reach this port,
# and it accepts commands that add WireGuard peers.
#
# If --api-url is a raw IP (http://1.2.3.4:8080), that IP is what's allowed.
# If --api-url is a domain (e.g. behind Cloudflare, like https://api.example.com),
# the domain can't be used directly: resolving it would give Cloudflare's edge
# IP, not your actual brain server -- and agent<->brain calls go direct,
# bypassing Cloudflare entirely. In that case --brain-ip must be given
# explicitly with the brain VPS's real public IPv4.
BRAIN_HOST=$(echo "${API_URL}" | sed -E 's#^[a-zA-Z]+://##; s#[:/].*$##')
if [[ "$BRAIN_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  ALLOWED_IP="$BRAIN_HOST"
elif [[ -n "$BRAIN_IP" ]] && [[ "$BRAIN_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  ALLOWED_IP="$BRAIN_IP"
else
  echo "ERROR: --api-url uses a domain name (${BRAIN_HOST}), so the agent"
  echo "firewall can't tell which IP is allowed to reach it from that alone."
  echo "Pass the brain VPS's real public IPv4 explicitly with --brain-ip <IP>."
  echo "Find it by running this ON THE BRAIN VPS: curl -s -4 ifconfig.me"
  exit 1
fi
ufw allow from "${ALLOWED_IP}" to any port ${AGENT_PORT} proto tcp

MY_IP=$(curl -s -4 ifconfig.me)
AGENT_URL="http://${MY_IP}:${AGENT_PORT}"
DISPLAY_NAME="${SERVER_NAME:-$COUNTRY_NAME}"

echo "==> Registering this VPS with your control API..."
HTTP_CODE=$(curl -s -o /tmp/fastvpn-register-response.json -w "%{http_code}" \
  -X POST "${API_URL}/api/admin/add-server" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: ${ADMIN_KEY}" \
  -d "{
        \"name\": \"${DISPLAY_NAME}\",
        \"countryName\": \"${COUNTRY_NAME}\",
        \"countryCode\": \"${COUNTRY_CODE}\",
        \"city\": \"${CITY}\",
        \"endpointHost\": \"${MY_IP}\",
        \"endpointPort\": ${WG_PORT},
        \"serverPublicKey\": \"${SERVER_PUB}\",
        \"clientSubnet\": \"${WG_SUBNET_BASE}.0/24\",
        \"dns\": \"1.1.1.1\",
        \"agentUrl\": \"${AGENT_URL}\",
        \"agentApiKey\": \"${AGENT_API_KEY}\"
      }")

echo ""
if [[ "$HTTP_CODE" == "200" ]]; then
  echo "============================================================"
  echo " DONE. This VPS is set up and registered with your API."
  echo " Country: ${COUNTRY_NAME} (${COUNTRY_CODE})   Endpoint: ${MY_IP}:${WG_PORT}"
  echo " Nothing else to do -- it'll show up in the app automatically."
  echo "============================================================"
else
  echo "============================================================"
  echo " WireGuard + agent are installed and running, but registering"
  echo " with your API FAILED (HTTP ${HTTP_CODE}). Response:"
  cat /tmp/fastvpn-register-response.json
  echo ""
  echo " Common causes: wrong --api-url, wrong --admin-key, or the API"
  echo " VPS's firewall isn't allowing this connection. Fix the issue and"
  echo " re-run this exact command -- it's safe to run again."
  echo "============================================================"
  exit 1
fi
