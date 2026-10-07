// Manages the list of VPS servers the control API knows about. Servers can
// arrive two ways:
//   1. Self-registration -- each VPS's setup.sh calls POST /api/admin/add-server
//      once, automatically, during its own setup. This is the easy path.
//   2. Manual editing of data/servers.json, for advanced/manual setups -- see
//      data/servers_example.json for the expected shape (placeholder IP and
//      key, not a real server; copy it to servers.json and fill in real
//      values for manual setups).
// Both write to the same file, so either approach (or a mix) works.
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, 'data');
const SERVERS_PATH = path.join(DATA_DIR, 'servers.json');
let mutationQueue = Promise.resolve();
function withMutationLock(fn) { const run = mutationQueue.then(fn, fn); mutationQueue = run.catch(() => {}); return run; }

function loadServers() {
  if (!fs.existsSync(SERVERS_PATH)) return [];
  return JSON.parse(fs.readFileSync(SERVERS_PATH, 'utf8'));
}

function saveServers(servers) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmpPath = `${SERVERS_PATH}.tmp`;
  fs.writeFileSync(tmpPath, JSON.stringify(servers, null, 2));
  fs.renameSync(tmpPath, SERVERS_PATH); // atomic on the same filesystem
}

/** Stable, unique id derived from the VPS's own IP -- so a re-run of setup.sh
 *  on the same box updates its existing entry instead of creating a duplicate. */
function idForHost(endpointHost) {
  return `vps-${endpointHost.replace(/[^a-zA-Z0-9]/g, '-')}`;
}

async function upsertServer(entry) {
  return withMutationLock(() => {
  const servers = loadServers();
  const id = idForHost(entry.endpointHost);
  const idx = servers.findIndex((s) => s.id === id);
  const withId = { ...entry, id };
  if (idx >= 0) {
    servers[idx] = withId;
  } else {
    servers.push(withId);
  }
  saveServers(servers);
  return withId;
  });
}

/** Drops a server from the list (e.g. because it's been unreachable for too
 *  long). Idempotent -- returns false if it was already gone. */
async function removeServer(id) {
  return withMutationLock(() => {
    const servers = loadServers();
    const filtered = servers.filter((s) => s.id !== id);
    if (filtered.length === servers.length) return false;
    saveServers(filtered);
    return true;
  });
}

/** Look up a single server by id. */
function getServer(id) {
  return loadServers().find((s) => s.id === id) || null;
}

/** Patches display metadata only (name/countryName/countryCode/city/dns) --
 *  never endpointHost/serverPublicKey/agentUrl/agentApiKey, since changing
 *  those would silently point the API at a different machine than the one
 *  that actually holds the WireGuard keys/peers. To change connection
 *  details, re-run setup.sh on that VPS instead (it upserts by endpointHost).
 *  Returns the updated server, or null if the id doesn't exist. */
async function updateServer(id, patch) {
  return withMutationLock(() => {
    const servers = loadServers();
    const idx = servers.findIndex((s) => s.id === id);
    if (idx < 0) return null;
    const allowed = ['name', 'countryName', 'countryCode', 'city', 'dns'];
    const updated = { ...servers[idx] };
    for (const field of allowed) {
      if (patch[field] !== undefined && patch[field] !== '') updated[field] = patch[field];
    }
    servers[idx] = updated;
    saveServers(servers);
    return updated;
  });
}

module.exports = { loadServers, saveServers, upsertServer, idForHost, removeServer, getServer, updateServer };
