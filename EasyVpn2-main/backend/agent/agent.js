// FastVPN agent -- runs on EACH VPS, right next to WireGuard.
// The only thing it does: add a WireGuard peer when asked, by someone who
// knows this VPS's API key. It never talks to other VPS or knows about them.
//
// SECURITY NOTES (read before deploying):
// - This process needs root to run `wg set`, so every input is strictly
//   validated (regex) and passed to execFile as separate arguments (never
//   built into a shell string) so there is no command-injection surface.
// - Protect this port with a firewall: only the control API's IP should be
//   able to reach it. setup.sh refuses to expose it publicly when the API
//   host cannot be safely resolved to an IPv4 address.
// - The API key below is generated fresh by setup.sh --role node; never
//   reuse the example value.

const express = require('express');
const { execFile } = require('child_process');
const fs = require('fs');
const path = require('path');

const CONFIG_PATH = path.join(__dirname, 'agent.config.json');
if (!fs.existsSync(CONFIG_PATH)) {
  console.error('Missing agent.config.json -- run setup.sh --role node first.');
  process.exit(1);
}
const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
// config: { port, apiKey, wgInterface }

const app = express();
app.use(express.json({ limit: '10kb' })); // small, deliberate cap -- peer add/remove payloads are tiny

const IP_RE = /^(\d{1,3}\.){3}\d{1,3}$/;

function isValidPublicKey(key) {
  if (typeof key !== 'string') return false;
  try { const raw = Buffer.from(key, 'base64'); return raw.length === 32 && raw.toString('base64') === key; } catch (_) { return false; }
}
function isValidIp(ip) {
  if (typeof ip !== 'string' || !IP_RE.test(ip)) return false;
  return ip.split('.').every((octet) => Number(octet) >= 0 && Number(octet) <= 255);
}

function requireApiKey(req, res, next) {
  const key = req.header('X-Api-Key');
  if (key !== config.apiKey) {
    return res.status(401).json({ error: 'invalid or missing X-Api-Key' });
  }
  next();
}

// WireGuard is connectionless (UDP) -- a peer stays "configured" even after
// the user closes the app, so peerCount alone can't tell you who's actually
// connected right now. A peer that's actually in use re-handshakes roughly
// every 2 minutes, so we treat anything with a handshake in the last 3
// minutes as "active" -- that's the number the dashboard shows as connected.
const ACTIVE_HANDSHAKE_WINDOW_SECONDS = 180;

app.get('/health', requireApiKey, (req, res) => {
  execFile('wg', ['show', config.wgInterface, 'peers'], (err, stdout) => {
    if (err) {
      return res.json({ ok: false, peerCount: -1, activePeerCount: -1 });
    }
    const peerCount = stdout.trim().split('\n').filter(Boolean).length;
    execFile('wg', ['show', config.wgInterface, 'latest-handshakes'], (hsErr, hsStdout) => {
      if (hsErr) {
        // Older wg-tools or a transient error -- still report peerCount.
        return res.json({ ok: true, peerCount, activePeerCount: -1, handshakes: {} });
      }
      const nowSec = Math.floor(Date.now() / 1000);
      const lines = hsStdout.trim().split('\n').filter(Boolean);
      const activePeerCount = lines
        .filter((line) => {
          const ts = Number(line.trim().split(/\s+/)[1]);
          return ts > 0 && nowSec - ts <= ACTIVE_HANDSHAKE_WINDOW_SECONDS;
        }).length;
      // Full publicKey -> latest-handshake-unix-seconds map (0 = never
      // handshaked). Unlike activePeerCount (a recency-windowed count used
      // for the "connected now" number), this lets the control API tell
      // whether one specific device has EVER completed a handshake, even if
      // it isn't active right now -- see /api/admin markPendingRegistrations
      // logic in the control API, which needs that per-peer distinction.
      const handshakes = {};
      for (const line of lines) {
        const [publicKey, tsRaw] = line.trim().split(/\s+/);
        handshakes[publicKey] = Number(tsRaw) || 0;
      }
      res.json({ ok: true, peerCount, activePeerCount, handshakes });
    });
  });
});

app.post('/add-peer', requireApiKey, (req, res) => {
  const { publicKey, allowedIp } = req.body || {};
  if (!isValidPublicKey(publicKey)) {
    return res.status(400).json({ error: 'invalid publicKey' });
  }
  if (!isValidIp(allowedIp)) {
    return res.status(400).json({ error: 'invalid allowedIp' });
  }

  const allowedIpCidr = `${allowedIp}/32`;
  execFile('wg', ['set', config.wgInterface, 'peer', publicKey, 'allowed-ips', allowedIpCidr], (err, _stdout, stderr) => {
    if (err) {
      console.error('wg set failed:', stderr || err.message);
      return res.status(500).json({ error: 'failed to add peer', detail: stderr || err.message });
    }
    execFile('wg-quick', ['save', config.wgInterface], (saveErr, _saveStdout, saveStderr) => {
      if (saveErr) {
        console.error('wg-quick save failed:', saveStderr || saveErr.message);
        execFile('wg', ['set', config.wgInterface, 'peer', publicKey, 'remove'], () => {});
        return res.status(500).json({ error: 'peer was added live but could not be persisted' });
      }
      res.json({ ok: true });
    });
  });
});

app.post('/remove-peer', requireApiKey, (req, res) => {
  const { publicKey } = req.body || {};
  if (!isValidPublicKey(publicKey)) {
    return res.status(400).json({ error: 'invalid publicKey' });
  }
  execFile('wg', ['set', config.wgInterface, 'peer', publicKey, 'remove'], (err, _stdout, stderr) => {
    if (err) {
      return res.status(500).json({ error: 'failed to remove peer', detail: stderr || err.message });
    }
    execFile('wg-quick', ['save', config.wgInterface], (saveErr, _saveStdout, saveStderr) => {
      if (saveErr) {
        console.error('wg-quick save after remove failed:', saveStderr || saveErr.message);
        return res.status(500).json({ error: 'peer removed live but configuration could not be persisted' });
      }
      res.json({ ok: true });
    });
  });
});

// Safety net: guarantees no raw Node stack trace is ever returned to a
// caller, regardless of NODE_ENV (setup.sh sets it, but this doesn't rely on
// that alone -- same reasoning as the equivalent handler in api/server.js).
app.use((err, req, res, next) => {
  console.error('Unhandled error:', err.message);
  if (res.headersSent) return next(err);
  res.status(err.status || 500).json({ error: 'internal server error' });
});

app.listen(config.port, '0.0.0.0', () => {
  console.log(`FastVPN agent listening on port ${config.port} for interface ${config.wgInterface}`);
});
