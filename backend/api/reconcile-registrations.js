// reconcile-registrations.js
//
// Run this on the BRAIN VPS, from inside backend/api/ (same folder as
// server.js), so it can reuse store.js and serverStore.js directly.
//
// Problem this fixes: the dashboard's "X/200 slots" count is read straight
// from the brain's registrations database -- it is never re-verified live
// against what WireGuard is actually running on that node. If a node gets
// reinstalled, has a peer removed manually, or a save silently failed, the
// brain's count can drift above the real number forever, because nothing
// currently cross-checks the two.
//
// This script asks each node's agent for its live peer list (via the same
// /health handshakes map used elsewhere) and flags any brain-side
// registration whose public key ISN'T actually present as a peer on that
// node -- i.e. a phantom slot.
//
// Usage (from backend/api/):
//   node reconcile-registrations.js            # dry run, just reports
//   node reconcile-registrations.js --apply     # removes phantom registrations

const store = require('./store');
const serverStore = require('./serverStore');

const APPLY = process.argv.includes('--apply');

async function fetchHandshakes(server) {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    const resp = await fetch(`${server.agentUrl}/health`, {
      headers: { 'X-Api-Key': server.agentApiKey },
      signal: controller.signal,
    });
    clearTimeout(timeout);
    if (!resp.ok) return null; // couldn't reach this node -- skip it, don't guess
    const body = await resp.json();
    return body.handshakes || {}; // { publicKey: latestHandshakeUnixSeconds }
  } catch (err) {
    return null;
  }
}

async function main() {
  const servers = serverStore.loadServers();
  if (servers.length === 0) {
    console.log('No servers configured.');
    return;
  }

  let totalPhantoms = 0;

  for (const server of servers) {
    const registrations = store.getRegistrationsByServer(server.id);
    if (registrations.length === 0) {
      console.log(`${server.id}: no registrations, skipping.`);
      continue;
    }

    const handshakes = await fetchHandshakes(server);
    if (handshakes === null) {
      console.log(`${server.id}: agent unreachable, SKIPPED (can't verify safely).`);
      continue;
    }

    const livePublicKeys = new Set(Object.keys(handshakes));
    const phantoms = registrations.filter((reg) => !livePublicKeys.has(reg.devicePublicKey));

    console.log(
      `${server.id}: ${registrations.length} registered, ${livePublicKeys.size} real peer(s) on the node, ${phantoms.length} phantom.`
    );

    for (const reg of phantoms) {
      console.log(`  PHANTOM  ${reg.devicePublicKey}  (registered ${reg.createdAt}, address ${reg.assignedAddress})`);
      totalPhantoms += 1;
      if (APPLY) {
        await store.removeRegistrationByKey(reg.devicePublicKey, reg.serverId);
        console.log(`    removed from brain's database.`);
      }
    }
  }

  console.log();
  if (totalPhantoms === 0) {
    console.log('No phantom registrations found. Dashboard counts should already match reality.');
  } else if (APPLY) {
    console.log(`Removed ${totalPhantoms} phantom registration(s). Refresh the dashboard to see corrected counts.`);
  } else {
    console.log(`Found ${totalPhantoms} phantom registration(s). Dry run only -- re-run with --apply to remove them:`);
    console.log('  node reconcile-registrations.js --apply');
  }
}

main().catch((err) => {
  console.error('Reconciliation failed:', err);
  process.exit(1);
});
