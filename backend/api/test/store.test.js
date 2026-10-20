const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { createStore } = require('../store');

const SUBNET = '10.8.0';
const POOL = 253; // .2 - .254

function setup(opts = {}) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fastvpn-store-'));
  const clock = { t: 1_700_000_000_000 };
  const store = createStore({ dataDir, now: () => clock.t, fsync: false, ...opts });
  return { store, dataDir, clock };
}

/** Full register flow without the network: reserve -> save. */
async function register(store, device, server = 'srv', max = Infinity) {
  const r = await store.reserveAddress(device, server, SUBNET, max);
  await store.saveRegistration(device, server, r.assignedAddress, r.token);
  return r;
}

const allAddresses = (store, server = 'srv') =>
  store.getRegistrationsByServer(server).map((r) => r.assignedAddress);

test('first allocations start at .2 and count up', async () => {
  const { store } = setup();
  assert.equal((await store.reserveAddress('a', 'srv', SUBNET)).assignedAddress, '10.8.0.2');
  assert.equal((await store.reserveAddress('b', 'srv', SUBNET)).assignedAddress, '10.8.0.3');
});

test('REGRESSION: 1000 allocate+remove cycles never run out of addresses', async () => {
  const { store } = setup();
  for (let i = 0; i < 1000; i++) {
    const r = await register(store, `device-${i}`);
    assert.ok(await store.removeRegistration(`device-${i}`, 'srv', r.token));
  }
  assert.equal(store.registrationCounts().total, 0);
});

test('freed addresses are reused once the subnet is full', async () => {
  const { store } = setup();
  const regs = [];
  for (let i = 0; i < POOL; i++) regs.push(await register(store, `d${i}`));
  await assert.rejects(store.reserveAddress('extra', 'srv', SUBNET), { code: 'NO_ADDRESSES' });

  await store.removeRegistration('d10', 'srv', regs[10].token);
  const again = await store.reserveAddress('extra', 'srv', SUBNET);
  assert.equal(again.assignedAddress, regs[10].assignedAddress);
});

test('no address is ever shared by two live registrations', async () => {
  const { store } = setup();
  const live = new Map(); // device -> {address, token}
  for (let i = 0; i < 2000; i++) {
    if (live.size < 200 && (i % 3 !== 0 || live.size === 0)) {
      const r = await register(store, `d${i}`);
      live.set(`d${i}`, r);
    } else {
      const [device, r] = live.entries().next().value;
      await store.removeRegistration(device, 'srv', r.token);
      live.delete(device);
    }
    if (i % 100 === 0) {
      const addrs = allAddresses(store);
      assert.equal(new Set(addrs).size, addrs.length);
    }
  }
  const addrs = allAddresses(store);
  assert.equal(new Set(addrs).size, addrs.length);
  assert.equal(addrs.length, live.size);
});

test('a just-freed address is the last to be reused (least-recently-freed first)', async () => {
  const { store, clock } = setup();
  const regs = [];
  for (let i = 0; i < POOL; i++) regs.push(await register(store, `d${i}`));

  clock.t += 1000;
  await store.removeRegistration('d20', 'srv', regs[20].token); // freed first
  clock.t += 1000;
  await store.removeRegistration('d5', 'srv', regs[5].token);   // freed later

  const first = await store.reserveAddress('x', 'srv', SUBNET);
  const second = await store.reserveAddress('y', 'srv', SUBNET);
  assert.equal(first.assignedAddress, regs[20].assignedAddress);
  assert.equal(second.assignedAddress, regs[5].assignedAddress);
});

test('a freed address is not reused while never-used addresses remain', async () => {
  const { store } = setup();
  const a = await register(store, 'a'); // .2
  await store.removeRegistration('a', 'srv', a.token);
  const b = await store.reserveAddress('b', 'srv', SUBNET);
  assert.notEqual(b.assignedAddress, a.assignedAddress);
});

test('200 simultaneous reservations all get different addresses', async () => {
  const { store } = setup();
  const results = await Promise.all(
    Array.from({ length: 200 }, (_, i) => store.reserveAddress(`d${i}`, 'srv', SUBNET))
  );
  const addrs = results.map((r) => r.assignedAddress);
  assert.equal(new Set(addrs).size, 200);
  assert.ok(results.every((r) => r.created));
});

test('simultaneous requests from the SAME device share one reservation', async () => {
  const { store } = setup();
  const results = await Promise.all(Array.from({ length: 10 }, () => store.reserveAddress('same', 'srv', SUBNET)));
  assert.equal(new Set(results.map((r) => r.assignedAddress)).size, 1);
  assert.equal(new Set(results.map((r) => r.token)).size, 1);
  assert.equal(results.filter((r) => r.created).length, 1);
});

test('simultaneous reserve + remove + reserve never duplicates an address', async () => {
  const { store } = setup();
  const seeded = [];
  for (let i = 0; i < POOL; i++) seeded.push(await register(store, `d${i}`));
  // Free 50, then race 50 new devices for those 50 slots.
  await Promise.all(seeded.slice(0, 50).map((r, i) => store.removeRegistration(`d${i}`, 'srv', r.token)));
  const outcomes = await Promise.allSettled(
    Array.from({ length: 80 }, (_, i) => store.reserveAddress(`new${i}`, 'srv', SUBNET))
  );
  const ok = outcomes.filter((o) => o.status === 'fulfilled').map((o) => o.value.assignedAddress);
  const failed = outcomes.filter((o) => o.status === 'rejected');
  assert.equal(ok.length, 50);
  assert.equal(failed.length, 30);
  assert.ok(failed.every((o) => o.reason.code === 'NO_ADDRESSES'));
  const live = allAddresses(store).concat(ok);
  assert.equal(new Set(live).size, live.length);
});

test('two OS processes sharing one data dir never hand out the same address', async () => {
  const { dataDir } = setup();
  const run = (prefix) => new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(__dirname, 'alloc-worker.js'), dataDir, prefix, '40']);
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => process.stderr.write(d));
    child.on('exit', (code) => (code === 0 ? resolve(JSON.parse(out)) : reject(new Error(`worker exited ${code}`))));
  });
  const results = (await Promise.all([run('p1'), run('p2'), run('p3'), run('p4')])).flat();
  assert.equal(results.length, 160);
  assert.equal(new Set(results).size, 160);
});

test('capacity limit counts registrations and reservations', async () => {
  const { store } = setup();
  await store.reserveAddress('a', 'srv', SUBNET, 2);
  await register(store, 'b', 'srv', 2);
  await assert.rejects(store.reserveAddress('c', 'srv', SUBNET, 2), { code: 'SERVER_FULL' });
});

test('servers have independent address pools', async () => {
  const { store } = setup();
  const a = await store.reserveAddress('dev', 'srv-a', SUBNET);
  const b = await store.reserveAddress('dev', 'srv-b', SUBNET);
  assert.equal(a.assignedAddress, b.assignedAddress);
});

test('releaseReservation frees the address for later reuse', async () => {
  const { store } = setup();
  const regs = [];
  for (let i = 0; i < POOL - 1; i++) regs.push(await register(store, `d${i}`));
  const pending = await store.reserveAddress('flaky', 'srv', SUBNET);
  await assert.rejects(store.reserveAddress('blocked', 'srv', SUBNET), { code: 'NO_ADDRESSES' });
  assert.equal(await store.releaseReservation('flaky', 'srv'), true);
  const next = await store.reserveAddress('blocked', 'srv', SUBNET);
  assert.equal(next.assignedAddress, pending.assignedAddress);
});

test('abandoned reservations expire instead of leaking addresses forever', async () => {
  const { store, clock } = setup({ reservationTtlMs: 60_000 });
  for (let i = 0; i < POOL; i++) await store.reserveAddress(`crashed${i}`, 'srv', SUBNET); // never saved/released
  await assert.rejects(store.reserveAddress('late', 'srv', SUBNET), { code: 'NO_ADDRESSES' });

  clock.t += 61_000;
  const r = await store.reserveAddress('late', 'srv', SUBNET);
  assert.ok(r.created);
  assert.equal(await store.reapReservations(), 0); // already reaped by the reserve above
});

test('saveRegistration refuses to create a duplicate after the reservation was lost', async () => {
  const { store, clock } = setup({ reservationTtlMs: 60_000 });
  const slow = await store.reserveAddress('slow', 'srv', SUBNET); // .2
  for (let i = 1; i < POOL; i++) await store.reserveAddress(`fill${i}`, 'srv', SUBNET);
  clock.t += 61_000; // every reservation expires
  const thief = await store.reserveAddress('thief', 'srv', SUBNET);
  assert.equal(thief.assignedAddress, slow.assignedAddress); // lowest of the equally-old free addresses

  await assert.rejects(
    store.saveRegistration('slow', 'srv', slow.assignedAddress, slow.token),
    { code: 'RESERVATION_LOST' }
  );
  await store.saveRegistration('thief', 'srv', thief.assignedAddress, thief.token);
  assert.deepEqual(allAddresses(store), [thief.assignedAddress]);
});

test('saveRegistration re-claims its own address if the reservation expired but nobody took it', async () => {
  const { store, clock } = setup({ reservationTtlMs: 60_000 });
  const r = await store.reserveAddress('slow', 'srv', SUBNET);
  clock.t += 61_000;
  await store.reapReservations();
  const saved = await store.saveRegistration('slow', 'srv', r.assignedAddress, r.token);
  assert.equal(saved.assignedAddress, r.assignedAddress);
  const next = await store.reserveAddress('other', 'srv', SUBNET);
  assert.notEqual(next.assignedAddress, r.assignedAddress);
});

test('saveRegistration rejects a mismatched address or token', async () => {
  const { store } = setup();
  const r = await store.reserveAddress('a', 'srv', SUBNET);
  await assert.rejects(store.saveRegistration('a', 'srv', '10.8.0.99', r.token), { code: 'RESERVATION_MISMATCH' });
  await assert.rejects(store.saveRegistration('a', 'srv', r.assignedAddress, 'bad'), { code: 'RESERVATION_MISMATCH' });
});

test('removeRegistration with the wrong token keeps the address allocated', async () => {
  const { store } = setup();
  const r = await register(store, 'a');
  assert.equal(await store.removeRegistration('a', 'srv', 'wrong'), false);
  assert.equal(store.getRegistration('a', 'srv').assignedAddress, r.assignedAddress);
});

test('removeRegistrationByKey frees both registrations and reservations', async () => {
  const { store } = setup();
  const regs = [];
  for (let i = 0; i < POOL; i++) regs.push(await register(store, `d${i}`));
  assert.equal(await store.removeRegistrationByKey('d3', 'srv'), true);
  assert.equal(await store.removeRegistrationByKey('d3', 'srv'), false);
  const next = await store.reserveAddress('new', 'srv', SUBNET);
  assert.equal(next.assignedAddress, regs[3].assignedAddress);
});

test('state survives a restart (new store instance on the same files)', async () => {
  const { store, dataDir, clock } = setup();
  const r = await register(store, 'a');
  const pending = await store.reserveAddress('b', 'srv', SUBNET);
  const reopened = createStore({ dataDir, now: () => clock.t });
  assert.equal(reopened.getRegistration('a', 'srv').assignedAddress, r.assignedAddress);
  const again = await reopened.reserveAddress('b', 'srv', SUBNET);
  assert.equal(again.assignedAddress, pending.assignedAddress);
  assert.equal(again.created, false);
  const c = await reopened.reserveAddress('c', 'srv', SUBNET);
  assert.ok(![r.assignedAddress, pending.assignedAddress].includes(c.assignedAddress));
});

test('legacy counter-based store: holes become reusable, nothing is double-assigned', async () => {
  const { dataDir, clock } = setup();
  // Old format: counter at 6, .4 was freed long ago and leaked.
  const row = (address) => ({ serverId: 'srv', assignedAddress: address, registrationToken: 't', createdAt: new Date(clock.t).toISOString() });
  fs.writeFileSync(path.join(dataDir, 'store.json'), JSON.stringify({
    registrations: { 'a::srv': row('10.8.0.2'), 'b::srv': row('10.8.0.3'), 'c::srv': row('10.8.0.5') },
    ipCounters: { srv: 6 },
    reservations: {},
  }));
  const store = createStore({ dataDir, now: () => clock.t });
  // Fresh addresses (.6, .7 ...) first; the .4 hole only once everything else is used.
  assert.equal((await store.reserveAddress('n1', 'srv', SUBNET)).assignedAddress, '10.8.0.6');
  assert.equal((await store.reserveAddress('n2', 'srv', SUBNET)).assignedAddress, '10.8.0.7');
  const seen = new Set(['10.8.0.2', '10.8.0.3', '10.8.0.5', '10.8.0.6', '10.8.0.7']);
  for (let i = 0; i < POOL - 6; i++) {
    const r = await store.reserveAddress(`fill${i}`, 'srv', SUBNET);
    assert.ok(!seen.has(r.assignedAddress));
    seen.add(r.assignedAddress);
  }
  assert.ok(!seen.has('10.8.0.4'));
  assert.equal((await store.reserveAddress('last', 'srv', SUBNET)).assignedAddress, '10.8.0.4');
  await assert.rejects(store.reserveAddress('over', 'srv', SUBNET), { code: 'NO_ADDRESSES' });
});

test('idempotent re-reserve for a registered device returns the same address', async () => {
  const { store } = setup();
  const r = await register(store, 'a');
  const again = await store.reserveAddress('a', 'srv', SUBNET);
  assert.equal(again.assignedAddress, r.assignedAddress);
  assert.equal(again.existing, true);
});
