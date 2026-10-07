// Tiny file-backed store -- no database server to install or manage. The
// allocator below uses a process-wide async mutex plus persisted reservations
// so concurrent registration requests cannot receive the same WireGuard IP.
const fs = require('fs');
const crypto = require('crypto');
const path = require('path');

const DATA_DIR = path.join(__dirname, 'data');
const STORE_PATH = path.join(DATA_DIR, 'store.json');

// Node's event loop can interleave async requests between load() and save().
// Keep all allocation mutations in one critical section. The API is a single
// Node process, so this protects concurrent HTTP requests without a database.
let mutationQueue = Promise.resolve();

function withMutationLock(fn) {
  const run = mutationQueue.then(fn, fn);
  mutationQueue = run.catch(() => {});
  return run;
}

function load() {
  if (!fs.existsSync(STORE_PATH)) {
    return { registrations: {}, ipCounters: {}, reservations: {} };
  }
  const store = JSON.parse(fs.readFileSync(STORE_PATH, 'utf8'));
  // Backward compatibility with stores created by older FastVPN versions.
  store.registrations ||= {};
  store.ipCounters ||= {};
  store.reservations ||= {};
  return store;
}

function save(store) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmpPath = `${STORE_PATH}.tmp`;
  fs.writeFileSync(tmpPath, JSON.stringify(store, null, 2));
  fs.renameSync(tmpPath, STORE_PATH); // atomic on the same filesystem
}

/** Registration key is scoped to (devicePublicKey, serverId) -- the same
 *  device gets a fresh, independent registration on each server it uses. */
function registrationKey(devicePublicKey, serverId) {
  return `${devicePublicKey}::${serverId}`;
}

function getRegistration(devicePublicKey, serverId) {
  const store = load();
  return store.registrations[registrationKey(devicePublicKey, serverId)] || null;
}

/**
 * Atomically reserves the next WireGuard host address for a device/server.
 *
 * Why this exists instead of a simple allocateAddress(): two HTTP requests can
 * otherwise both execute load() before either executes save(), read the same
 * counter, and assign the same IP. Reservations are persisted so the address
 * remains taken while the API is waiting for the VPS agent to add the peer.
 *
 * Returns { assignedAddress, created } where created is true only for the
 * request that created the reservation. A repeated concurrent request gets
 * the same reservation and must not release it if its own agent call fails.
 */
async function reserveAddress(devicePublicKey, serverId, subnetBase, maxRegistrations = Infinity) {
  return withMutationLock(() => {
    const store = load();
    const key = registrationKey(devicePublicKey, serverId);

    const existing = store.registrations[key];
    if (existing) {
      return { assignedAddress: existing.assignedAddress, token: existing.registrationToken, created: false, existing: true };
    }

    const reservation = store.reservations[key];
    if (reservation) {
      return { assignedAddress: reservation.assignedAddress, token: reservation.registrationToken, created: false, existing: false };
    }

    const registrationCount = Object.values(store.registrations)
      .filter((reg) => reg.serverId === serverId).length;
    const reservationCount = Object.values(store.reservations)
      .filter((res) => res.serverId === serverId).length;

    if (registrationCount + reservationCount >= maxRegistrations) {
      throw new Error(`Server ${serverId} has reached its registration capacity`);
    }

    const next = store.ipCounters[serverId] || 2;
    if (next > 254) {
      throw new Error(`Server ${serverId} has run out of addresses in its /24 subnet`);
    }

    const assignedAddress = `${subnetBase}.${next}`;
    store.ipCounters[serverId] = next + 1;
    store.reservations[key] = {
      serverId,
      assignedAddress,
      registrationToken: crypto.randomBytes(32).toString('hex'),
      createdAt: new Date().toISOString(),
    };
    save(store);

    return { assignedAddress, token: store.reservations[key].registrationToken, created: true, existing: false };
  });
}

/** Release only a reservation created for this exact registration key. */
async function releaseReservation(devicePublicKey, serverId) {
  return withMutationLock(() => {
    const store = load();
    const key = registrationKey(devicePublicKey, serverId);
    if (!store.reservations[key]) return false;
    delete store.reservations[key];
    save(store);
    return true;
  });
}

/**
 * Finalizes a reservation after the VPS agent has successfully installed the
 * peer. Registration and reservation removal happen in one locked write.
 */
async function saveRegistration(devicePublicKey, serverId, assignedAddress, registrationToken) {
  return withMutationLock(() => {
    const store = load();
    const key = registrationKey(devicePublicKey, serverId);
    const existing = store.registrations[key];
    if (existing) return existing;

    const now = new Date().toISOString();
    store.registrations[key] = {
      serverId,
      assignedAddress,
      registrationToken,
      createdAt: now,
      lastSeenAt: now,
      // True only once the health sweep observes a real WireGuard handshake
      // from this device's public key (see markConnected). A registration
      // that stays false past PENDING_REGISTRATION_MAX_AGE_MS never actually
      // connected -- e.g. the app crashed before establishing the tunnel, or
      // the assigned config was never used -- and gets pruned quickly rather
      // than sitting on a slot for 30 days like a real, previously-used peer.
      everConnected: false,
    };
    delete store.reservations[key];
    save(store);
    return store.registrations[key];
  });
}

/** Refreshes lastSeenAt for an already-registered device -- called on every
 *  idempotent re-registration (app reconnecting to a server it's already on)
 *  so actively-used registrations never look stale to pruneStaleRegistrations,
 *  even though they were created long ago and never re-create their row. */
async function touchRegistration(devicePublicKey, serverId) {
  return withMutationLock(() => {
    const store = load();
    const key = registrationKey(devicePublicKey, serverId);
    if (!store.registrations[key]) return false;
    store.registrations[key].lastSeenAt = new Date().toISOString();
    save(store);
    return true;
  });
}

/** Registrations not seen in over maxAgeMs -- candidates for pruneStaleRegistrations.
 *  Falls back to createdAt for rows saved before lastSeenAt existed. Read-only:
 *  actually removing one requires telling the VPS agent to drop the peer first,
 *  which only the caller (server.js) can do -- see removeRegistrationByKey. */
function findStaleRegistrations(maxAgeMs) {
  const store = load();
  const cutoff = Date.now() - maxAgeMs;
  const stale = [];
  for (const [key, reg] of Object.entries(store.registrations)) {
    const lastActivity = new Date(reg.lastSeenAt || reg.createdAt).getTime();
    if (Number.isFinite(lastActivity) && lastActivity < cutoff) {
      const sep = key.lastIndexOf('::');
      stale.push({ devicePublicKey: key.slice(0, sep), serverId: key.slice(sep + 2), ...reg });
    }
  }
  return stale;
}

/** Administrative removal for the pruning sweep -- unlike removeRegistration,
 *  does not require the caller to present the registration's own token, since
 *  this is invoked by the server itself, not a client request. Only call this
 *  after the VPS agent has confirmed the peer is actually removed. */
async function removeRegistrationByKey(devicePublicKey, serverId) {
  return withMutationLock(() => {
    const store = load();
    const key = registrationKey(devicePublicKey, serverId);
    if (!store.registrations[key]) return false;
    delete store.registrations[key];
    delete store.reservations[key];
    save(store);
    return true;
  });
}

/** Marks a registration as having completed at least one real WireGuard
 *  handshake -- called from the health sweep once an agent reports a
 *  non-zero latest-handshake for this device's public key. Also refreshes
 *  lastSeenAt, same reasoning as touchRegistration: a device that's actively
 *  connected should never look idle to pruneStaleRegistrations even if the
 *  app itself never calls /api/register again while connected. Idempotent
 *  and cheap to call on every sweep -- skips the write if already true. */
async function markConnected(devicePublicKey, serverId) {
  return withMutationLock(() => {
    const store = load();
    const key = registrationKey(devicePublicKey, serverId);
    const reg = store.registrations[key];
    if (!reg) return false;
    if (reg.everConnected) {
      reg.lastSeenAt = new Date().toISOString();
      save(store);
      return true;
    }
    reg.everConnected = true;
    reg.lastSeenAt = new Date().toISOString();
    save(store);
    return true;
  });
}

/** Registrations older than maxAgeMs that have NEVER completed a real
 *  handshake (everConnected still false). These are "stuck pending" rows --
 *  distinct from findStaleRegistrations, which is for devices that connected
 *  successfully at some point and have since gone quiet. Falls back to
 *  treating rows saved before everConnected existed as already-connected
 *  (assume they're legitimate, let the 30-day stale check handle them
 *  instead of retroactively deleting older installs' active devices). */
function findPendingRegistrations(maxAgeMs) {
  const store = load();
  const cutoff = Date.now() - maxAgeMs;
  const pending = [];
  for (const [key, reg] of Object.entries(store.registrations)) {
    if (reg.everConnected !== false) continue; // true, or missing (legacy row) -- not stuck-pending
    const created = new Date(reg.createdAt).getTime();
    if (Number.isFinite(created) && created < cutoff) {
      const sep = key.lastIndexOf('::');
      pending.push({ devicePublicKey: key.slice(0, sep), serverId: key.slice(sep + 2), ...reg });
    }
  }
  return pending;
}

/** Counts how many devices are registered on each server, plus the total. */
function registrationCounts() {
  const store = load();
  const byServer = {};
  let total = 0;
  for (const reg of Object.values(store.registrations)) {
    byServer[reg.serverId] = (byServer[reg.serverId] || 0) + 1;
    total += 1;
  }
  return { byServer, total };
}

/** Remove a completed registration atomically. */
async function removeRegistration(devicePublicKey, serverId, registrationToken) {
  return withMutationLock(() => {
    const store = load();
    const key = registrationKey(devicePublicKey, serverId);
    const existing = store.registrations[key];
    if (!existing || typeof existing.registrationToken !== 'string' || existing.registrationToken !== registrationToken) return false;
    const existed = true;
    delete store.registrations[key];
    delete store.reservations[key];
    if (existed) save(store);
    return existed;
  });
}

/** All registrations on one server -- used by the health sweep to
 *  cross-reference each device's public key against the agent's handshake
 *  report and call markConnected where appropriate. */
function getRegistrationsByServer(serverId) {
  const store = load();
  const result = [];
  for (const [key, reg] of Object.entries(store.registrations)) {
    if (reg.serverId !== serverId) continue;
    const sep = key.lastIndexOf('::');
    result.push({ devicePublicKey: key.slice(0, sep), serverId: key.slice(sep + 2), ...reg });
  }
  return result;
}

module.exports = {
  getRegistration,
  reserveAddress,
  releaseReservation,
  saveRegistration,
  touchRegistration,
  findStaleRegistrations,
  removeRegistrationByKey,
  registrationCounts,
  removeRegistration,
  markConnected,
  findPendingRegistrations,
  getRegistrationsByServer,
};
