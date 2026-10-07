#!/bin/bash
# ============================================================================
# FastVPN — WireGuard server setup script
# Run this as root on each of your 6 VPS boxes (Ubuntu 22.04/24.04, 1GB RAM).
# It installs WireGuard, generates server keys, enables NAT/forwarding, and
# opens the firewall — everything you need to plug the server into the
# backend afterwards.
#
# Usage:  sudo bash setup-wireguard.sh [wg-subnet-base]
#   wg-subnet-base defaults to 10.8.0 (i.e. WireGuard uses 10.8.0.0/24).
#   Only override this if 10.8.0.x also happens to collide with something --
#   see the safety check below, which will tell you clearly if it does.
# ============================================================================
set -e

WG_PORT=51820
WG_IFACE="wg0"
WG_SUBNET_BASE="${1:-10.8.0}"
WG_SUBNET="${WG_SUBNET_BASE}.1/24"

echo "==> Installing WireGuard..."
apt-get update -y
apt-get install -y wireguard qrencode ufw

# Detect the primary network interface for NAT masquerading
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
  echo "   sudo bash setup-wireguard.sh 10.9.0"
  echo "############################################################"
  exit 1
fi

echo "==> Enabling IP forwarding..."
sed -i '/net.ipv4.ip_forward/d' /etc/sysctl.conf
echo "net.ipv4.ip_forward=1" >> /etc/sysctl.conf
sysctl -p
sysctl -w net.ipv4.ip_forward=1 >/dev/null # apply immediately too, don't rely only on sysctl -p

echo "==> Generating server keypair..."
mkdir -p /etc/wireguard
cd /etc/wireguard
umask 077
wg genkey | tee server_private.key | wg pubkey > server_public.key
SERVER_PRIV=$(cat server_private.key)
SERVER_PUB=$(cat server_public.key)

cat > /etc/wireguard/${WG_IFACE}.conf << EOF
[Interface]
Address = ${WG_SUBNET}
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIV}
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o ${NET_IF} -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o ${NET_IF} -j MASQUERADE

# Peers (your app users) get appended below by add-client.sh, or manually with `wg set`.
EOF

echo "==> Opening firewall..."
ufw allow ${WG_PORT}/udp
ufw allow OpenSSH
ufw --force enable

echo "==> Starting WireGuard..."
systemctl enable wg-quick@${WG_IFACE}
systemctl start wg-quick@${WG_IFACE}

echo ""
echo "============================================================"
echo " DONE. Register this server with the backend using these values"
echo "   Host (VPS IP):     $(curl -s -4 ifconfig.me)"
echo "   Port:               ${WG_PORT}"
echo "   Server public key:  ${SERVER_PUB}"
echo "============================================================"
echo ""
echo "NOTE: With one shared server public key, every app user connects as a"
echo "peer of this server. For basic launch you can allow all clients with a"
echo "single client keypair baked into the app, OR (recommended, more secure"
echo "and lets you revoke individual users) run add-client.sh once per user"
echo "to register their device's own public key as a peer. See README.md."
