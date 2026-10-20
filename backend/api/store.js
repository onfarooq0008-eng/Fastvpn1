// Tiny file-backed store -- no database server to install or manage.
//
// Address allocation (the part that used to run out):
//   * Addresses are no longer handed out from a one-way counter. Every request
//     computes the set of addresses currently in use (registrations +
//     reservations) and picks a free one, so any address freed by an
//     unregister / prune / failed registration becomes available again.
//   * Reuse is "least recently freed first": never-used addresses are preferred,
//     then the address that has been free the longest. A just-freed address is
//     therefore the LAST one to be handed out again, which gives the VPS agent
//     time to drop the old peer and avoids two peers briefly sharing an IP.
//   * Reservations expire (RESERVATION_TTL_MS) so a crash between "reserve" and
//     "save" can no longer leak an address forever.
//
// Concurrency:
//   * In-process: all mutations run one at a time through a promise queue.
//   * Cross-process: each mutation also holds a lock file while it does its
//     (synchronous) load -> modify -> save, so `reconcile-registrations.js` or a
//     second API process cannot interleave with the running API.
//   * saveRegistration() re-checks under the lock that the address still belongs
//     to the caller's reservation, so a duplicate can never be persisted.
//
// For a large user base, move this to SQLite (see README "Scaling the store"):
// the same function names map 1:1 onto transactions.
const fs = require('fs');
const crypto = require('crypto');
const path = require('path');

const FIRST_HOST = 2;   // .1 is the VPS itself
const LAST_HOST = 254;  // .255 is the broadcast address of the /24
const DEFAULT_RESERVATION_TTL_MS = 2 * 60_000;
const LOCK_WAIT_MS = 5_000;
const LOCK_STALE_MS = 10_000;

function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/**
 * Creates an independent store. The default instance (used by server.js) is
 * exported below; tests create their own with a temp dataDir and a fake clock.
 *   dataDir          where store.json lives (default: ./data or $FASTVPN_DATA_DIR)
 *   now()            clock in ms (default Date.now)
 *   reservationTtlMs how long an unfinished reservation holds an address
 *   fsync            flush each write to disk (default true; tests turn it off for speed)
 */
function createStore(options = {}) {
  const dataDir = options.dataDir || process.env.FASTVPN_DATA_DIR || path.join(__dirname, 'data');
  const storePath = path.join(dataDir, 'store.json');
  const lockPath = `${storePath}.lock`;
  const now = options.now || Date.now;
  const fsync = options.fsync !== false;
  const reservationTtlMs = options.reservationTtlMs
    ?? (Number(process.env.RESERVATION_TTL_MS) || DEFAULT_RESERVATION_TTL_MS);

  // ---------- persistence ----------

  function load() {
    let store = {};
    if (fs.existsSync(storePath)) store = JSON.parse(fs.readFileSync(storePath, 'utf8'));
    // Backward compatibility with stores created by older FastVPN versions.
    store.registrations ||= {};
    store.ipCounters ||= {};   // legacy; only read once to migrate old stores
    store.reservations ||= {};
    store.freedAddresses ||= {}; // { serverId: { "10.8.0.5": freedAtMs } }
    return store;
  }

  function save(store) {
    fs.mkdirSync(dataDir, { recursive: true });
    const tmpPath = `${storePath}.${process.pid}.tmp`;
    const fd = fs.openSync(tmpPath, 'w');
    try {
      fs.writeSync(fd, JSON.stringify(store, null, 2));
      if (fsync) fs.fsyncSync(fd); // data is on disk before the rename makes it visible
    } finally {
      fs.closeSync(fd);
    }
    fs.renameSync(tmpPath, storePath); // atomic on the same filesystem
  }

  // ---------- locking ----------

  function withFileLock(fn) {
    fs.mkdirSync(dataDir, { recursive: true });
    const deadline = Date.now() + LOCK_WAIT_MS;
    let fd;
    for (;;) {
      try {
        fd = fs.openSync(lockPath, 'wx');
        break;
      } catch (err) {
        if (err.code !== 'EEXIST') throw err;
        try {
          // A crashed process can leave its lock behind; mutations take milliseconds,
          // so a lock this old is dead.
          if (Date.now() - fs.statSync(lockPath).mtimeMs > LOCK_STALE_MS) {
            fs.unlinkSync(lockPath);
            continue;
          }
        } catch { /* lock vanished between calls -- just retry */ }
        if (Date.now() > deadline) throw new Error('Timed out waiting for the store lock');
        sleepSync(5);
      }
    }
    try {
      return fn();
    } finally {
      fs.closeSync(fd);
      try { fs.unlinkSync(lockPath); } catch { /* already gone */ }
    }
  }

  // Node can interleave async requests between awaits, so every mutation is
  // queued; inside the queue it also takes the cross-process file lock.
  let mutationQueue = Promise.resolve();
  function withMutationLock(fn) {
    const run = mutationQueue.then(() => withFileLock(fn));
    mutationQueue = run.catch(() => {});
    return run;
  }

  // ---------- address bookkeeping ----------

  /** Registration key is scoped to (devicePublicKey, serverId) -- the same
   *  device gets a fresh, independent registration on each server it uses. */
  function registrationKey(devicePublicKey, serverId) {
    return `${devicePublicKey}::${serverId}`;
  }

  function isAddressUsed(store, serverId, address, exceptKey = null) {
    for (const group of [store.registrations, store.reservations]) {
      for (const [key, row] of Object.entries(group)) {
        if (key !== exceptKey && row.serverId === serverId && row.assignedAddress === address) return true;
      }
    }
    return false;
  }

  /** Records that [address] is free again (unless another row still holds it). */
  function markFree(store, serverId, address) {
    if (!address || isAddressUsed(store, serverId, address)) return;
    (store.freedAddresses[serverId] ||= {})[address] = now();
  }

  /** Old stores only had a counter, so addresses freed below it were never
   *  recorded. Treat those holes as "freed right now" the first time we see the
   *  server: they are then reused only after every never-used address. */
  function migrateLegacyCounter(store, serverId, subnetBase) {
    if (store.freedAddresses[serverId]) return false;
    const freed = (store.freedAddresses[serverId] = {});
    const counter = store.ipCounters[serverId];
    if (!counter) return true;
    for (let host = FIRST_HOST; host < Math.min(counter, LAST_HOST + 1); host++) {
      const address = `${subnetBase}.${host}`;
      if (!isAddressUsed(store, serverId, address)) freed[address] = now();
    }
    return true;
  }

  /** Drops reservations whose request never finished (crash, hung agent...). */
  function reapExpiredReservations(store) {
    let reaped = 0;
    const cutoff = now() - reservationTtlMs;
    for (const [key, res] of Object.entries(store.reservations)) {
      const created = Date.parse(res.createdAt);
      if (Number.isFinite(created) && created <= cutoff) {
        delete store.reservations[key];
        markFree(store, res.serverId, res.assignedAddress);
        reaped += 1;
      }
    }
    return reaped;
  }

  /** Never-used addresses first (lowest first), then the longest-free one. */
  function pickFreeAddress(store, serverId, subnetBase) {
    const used = new Set();
    for (const group of [store.registrations, store.reservations]) {
      for (const row of Object.values(group)) {
        if (row.serverId === serverId) used.add(row.assignedAddress);
      }
    }
    const freed = store.freedAddresses[serverId] || {};
    let best = null;
    let bestFreedAt = Infinity;
    for (let host = FIRST_HOST; host <= LAST_HOST; host++) {
      const address = `${subnetBase}.${host}`;
      if (used.has(address)) continue;
      const freedAt = freed[address] || 0;
      if (freedAt < bestFreedAt) { best = address; bestFreedAt = freedAt; }
    }
    return best;
  }

  function failure(code, message) {
    return Object.assign(new Error(message), { code });
  }

  // ---------- public API ----------

  function getRegistration(devicePublicKey, serverId) {
    return load().registrations[registrationKey(devicePublicKey, serverId)] || null;
  }

  /**
   * Atomically reserves a free WireGuard host address for a device/server.
   * The reservation keeps the address taken while the API waits for the VPS
   * agent to add the peer.
   *
   * Returns { assignedAddress, token, created, existing }; `created` is true
   * only for the request that created the reservation, so a repeated concurrent
   * request can safely share it without releasing it when its own agent call fails.
   * Throws err.code === 'SERVER_FULL' (capacity) or 'NO_ADDRESSES' (subnet full).
   */
  async function reserveAddress(devicePublicKey, serverId, subnetBase, maxRegistrations = Infinity) {
    return withMutationLock(() => {
      const store = load();
      const key = registrationKey(devicePublicKey, serverId);
      const reaped = reapExpiredReservations(store);
      let result;
      let error = null;
      let dirty = reaped > 0;

      const existing = store.registrations[key];
      const reservation = store.reservations[key];
      if (existing) {
        result = { assignedAddress: existing.assignedAddress, token: existing.registrationToken, created: false, existing: true };
      } else if (reservation) {
        result = { assignedAddress: reservation.assignedAddress, token: reservation.registrationToken, created: false, existing: false };
      } else {
        const inUse = Object.values(store.registrations).filter((r) => r.serverId === serverId).length
          + Object.values(store.reservations).filter((r) => r.serverId === serverId).length;
        if (inUse >= maxRegistrations) {
          error = failure('SERVER_FULL', `Server ${serverId} has reached its registration capacity`);
        } else {
          if (migrateLegacyCounter(store, serverId, subnetBase)) dirty = true;
          const assignedAddress = pickFreeAddress(store, serverId, subnetBase);
          if (!assignedAddress) {
            error = failure('NO_ADDRESSES', `Server ${serverId} has no free addresses left in ${subnetBase}.0/24`);
          } else {
            const token = crypto.randomBytes(32).toString('hex');
            store.reservations[key] = { serverId, assignedAddress, registrationToken: token, createdAt: new Date(now()).toISOString() };
            delete store.freedAddresses[serverId]?.[assignedAddress];
            result = { assignedAddress, token, created: true, existing: false };
            dirty = true;
          }
        }
      }

      if (dirty) save(store);
      if (error) throw error;
      return result;
    });
  }

  /** Release a reservation (e.g. the agent refused the peer). The address goes
   *  to the back of the reuse queue. */
  async function releaseReservation(devicePublicKey, serverId) {
    return withMutationLock(() => {
      const store = load();
      const key = registrationKey(devicePublicKey, serverId);
      const reservation = store.reservations[key];
      if (!reservation) return false;
      delete store.reservations[key];
      markFree(store, serverId, reservation.assignedAddress);
      save(store);
      return true;
    });
  }

  /**
   * Finalizes a reservation after the VPS agent has installed the peer.
   * Throws err.code === 'RESERVATION_LOST' if the reservation expired AND the
   * address has meanwhile been given to someone else -- the caller must then
   * remove the peer it just added. A duplicate is never written.
   */
  async function saveRegistration(devicePublicKey, serverId, assignedAddress, registrationToken) {
    return withMutationLock(() => {
      const store = load();
      const key = registrationKey(devicePublicKey, serverId);
      const existing = store.registrations[key];
      if (existing) return existing;

      const reservation = store.reservations[key];
      if (reservation) {
        if (reservation.assignedAddress !== assignedAddress || reservation.registrationToken !== registrationToken) {
          throw failure('RESERVATION_MISMATCH', 'Reservation does not match the address/token being saved');
        }
      } else if (isAddressUsed(store, serverId, assignedAddress)) {
        throw failure('RESERVATION_LOST', `Reservation for ${assignedAddress} expired and the address was reassigned`);
      }

      const at = new Date(now()).toISOString();
      store.registrations[key] = {
        serverId,
        assignedAddress,
        registrationToken,
        createdAt: at,
        lastSeenAt: at,
        // True only once the health sweep observes a real WireGuard handshake
        // from this device's public key (see markConnected). A registration
        // that stays false past PENDING_REGISTRATION_MAX_AGE_MS never actually
        // connected and gets pruned quickly rather than sitting on a slot for 30 days.
        everConnected: false,
      };
      delete store.reservations[key];
      delete store.freedAddresses[serverId]?.[assignedAddress];
      save(store);
      return store.registrations[key];
    });
  }

  /** Refreshes lastSeenAt for an already-registered device (idempotent re-registration). */
  async function touchRegistration(devicePublicKey, serverId) {
    return withMutationLock(() => {
      const store = load();
      const key = registrationKey(devicePublicKey, serverId);
      if (!store.registrations[key]) return false;
      store.registrations[key].lastSeenAt = new Date(now()).toISOString();
      save(store);
      return true;
    });
  }

  /** Registrations not seen in over maxAgeMs. Read-only: actually removing one
   *  requires telling the VPS agent to drop the peer first (server.js does that). */
  function findStaleRegistrations(maxAgeMs) {
    const store = load();
    const cutoff = now() - maxAgeMs;
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

  /** Administrative removal for the pruning sweep (no client token needed). Only
   *  call this after the VPS agent has confirmed the peer is removed -- the
   *  address is then immediately eligible for reuse (after never-used ones). */
  async function removeRegistrationByKey(devicePublicKey, serverId) {
    return withMutationLock(() => {
      const store = load();
      const key = registrationKey(devicePublicKey, serverId);
      const registration = store.registrations[key];
      const reservation = store.reservations[key];
      if (!registration && !reservation) return false;
      delete store.registrations[key];
      delete store.reservations[key];
      markFree(store, serverId, registration?.assignedAddress);
      markFree(store, serverId, reservation?.assignedAddress);
      save(store);
      return true;
    });
  }

  /** Marks a registration as having completed a real WireGuard handshake and
   *  refreshes lastSeenAt. Cheap to call on every sweep. */
  async function markConnected(devicePublicKey, serverId) {
    return withMutationLock(() => {
      const store = load();
      const reg = store.registrations[registrationKey(devicePublicKey, serverId)];
      if (!reg) return false;
      reg.everConnected = true;
      reg.lastSeenAt = new Date(now()).toISOString();
      save(store);
      return true;
    });
  }

  /** Registrations older than maxAgeMs that have NEVER completed a handshake
   *  ("stuck pending"). Legacy rows without the flag are treated as connected. */
  function findPendingRegistrations(maxAgeMs) {
    const store = load();
    const cutoff = now() - maxAgeMs;
    const pending = [];
    for (const [key, reg] of Object.entries(store.registrations)) {
      if (reg.everConnected !== false) continue;
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

  /** Remove a completed registration; requires the registration's own token. */
  async function removeRegistration(devicePublicKey, serverId, registrationToken) {
    return withMutationLock(() => {
      const store = load();
      const key = registrationKey(devicePublicKey, serverId);
      const existing = store.registrations[key];
      if (!existing || typeof existing.registrationToken !== 'string' || existing.registrationToken !== registrationToken) return false;
      delete store.registrations[key];
      delete store.reservations[key];
      markFree(store, serverId, existing.assignedAddress);
      save(store);
      return true;
    });
  }

  /** All registrations on one server (health sweep / reconcile script). */
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

  /** Drops expired reservations now (reserveAddress also does this lazily). */
  async function reapReservations() {
    return withMutationLock(() => {
      const store = load();
      const reaped = reapExpiredReservations(store);
      if (reaped > 0) save(store);
      return reaped;
    });
  }

  return {
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
    reapReservations,
  };
}

module.exports = { createStore, ...createStore() };
