// Child process used by store.test.js: allocates N addresses against a shared
// data dir and prints them as JSON, to prove two processes never collide.
const { createStore } = require('../store');
const [dataDir, prefix, count] = process.argv.slice(2);
const store = createStore({ dataDir });
(async () => {
  const out = [];
  for (let i = 0; i < Number(count); i++) {
    const r = await store.reserveAddress(`${prefix}-${i}`, 'srv', '10.8.0');
    out.push(r.assignedAddress);
  }
  console.log(JSON.stringify(out));
})().catch((e) => { console.error(e); process.exit(1); });
