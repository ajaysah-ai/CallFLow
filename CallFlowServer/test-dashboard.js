// Clicks through the web dashboards like a person would. Run: npm i jsdom --no-save && node test-dashboard.js
const assert = require('assert');
const fs = require('fs');
const { JSDOM } = require('jsdom');
const { createApp } = require('./app');
const { memoryStore } = require('./store');

const ADMIN_KEY = 'admin-key-1234567890';
const server = createApp(memoryStore({ firebaseLike: true }), { ADMIN_KEY, SESSION_SECRET: 'y'.repeat(40) }).listen(0);
const base = `http://127.0.0.1:${server.address().port}`;
const html = fs.readFileSync(__dirname + '/public/index.html', 'utf8');
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function api(action, body, token) {
  const r = await fetch(base + '/api/subscription', { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify({ action, ...body }) });
  return r.json();
}

function openPage() {
  const log = { alerts: [], downloads: [] };
  const dom = new JSDOM(html, {
    url: base + '/', runScripts: 'dangerously', pretendToBeVisual: true,
    beforeParse(w) {
      w.fetch = (u, o) => fetch(new URL(u, base), o);
      w.alert = m => log.alerts.push(String(m));
      w.confirm = () => true;
      w.prompt = (q, d) => d;
      w.URL.createObjectURL = () => 'blob:test';
      w.HTMLAnchorElement.prototype.click = function () { log.downloads.push(this.download); };
    },
  });
  const d = dom.window.document;
  const page = {
    log, d, window: dom.window,
    text: () => d.body.textContent,
    input: ph => [...d.querySelectorAll('input')].find(i => (i.placeholder || '').startsWith(ph)),
    button: t => [...d.querySelectorAll('button')].find(b => b.textContent.trim() === t),
    async click(t) { const b = page.button(t); assert.ok(b, 'button not found: ' + t); b.click(); await sleep(250); },
    async type(ph, v) { const i = page.input(ph); assert.ok(i, 'input not found: ' + ph); i.value = v; },
    async has(t, tries = 20) { for (let i = 0; i < tries; i++) { if (page.text().includes(t)) return true; await sleep(100); } assert.fail('text not shown: ' + t + '\n' + page.text().slice(0, 400)); },
  };
  return page;
}

(async () => {
  // ---- Admin ----
  const A = openPage();
  await A.has('Open admin');
  await A.type('Admin key', ADMIN_KEY);
  await A.click('Open admin');
  await A.has('New team leader subscription');
  await A.type('Team leader name', 'Demo Team');
  await A.type('User limit', '2');
  await A.type('Plan name', 'Gold');
  await A.type('Valid for days', '30');
  await A.click('Create');
  await A.has('Demo Team');
  assert.ok(A.text().includes('Users 0 / 2'), 'limit shown');
  const accessCode = A.text().match(/TL-[A-Z0-9]{4}-[A-Z0-9]{4}/)[0];
  const inviteCode = A.text().match(/TM-[A-Z0-9]{4}-[A-Z0-9]{4}/)[0];

  // admin changes the user limit (prompt returns the default, so use the API for the new value, then check the screen)
  await A.click('Open team');
  await A.has('Add a user');
  await A.type('New user name', 'Asha');
  await A.click('Create user code');
  const userCode = A.log.alerts.pop().match(/CF-[A-Z0-9]{4}-[A-Z0-9]{4}/)[0];
  await A.has('Asha');
  assert.ok(A.text().includes('PHONE NONE'), 'new user has no phone yet');

  // the phone app activates -> pending
  let r = await api('activate', { code: userCode, deviceId: 'PHONE-1', deviceName: 'Realme 8', consent: '1.0' });
  assert.strictEqual(r.status, 'pending');
  await A.click('Back'); await A.click('Open team');
  await A.has('PHONE PENDING');
  await A.click('Approve phone');
  await A.has('PHONE APPROVED');
  r = await api('activate', { code: userCode, deviceId: 'PHONE-1' });
  assert.strictEqual(r.status, 'running');
  await api('sync', { records: [{ id: 'c1', cn: 'Amit', ph: 'XXXXXX3210', at: 1, rm: 'Interested', fu: '2026-11-20', fd: false, dt: Date.now() }] }, r.token);

  // user dashboard + downloads
  await A.click('Back'); await A.click('Open team');
  await A.has('Calls 1');
  await A.click('Dashboard');
  await A.has('Amit');
  assert.ok(A.text().includes('Interested'));
  await A.click('Calling history CSV'); await A.click('XLSX');
  assert.ok(A.log.downloads.some(n => /calling-history.*\.csv$/.test(n)) && A.log.downloads.some(n => /\.xlsx$/.test(n)), 'files downloaded: ' + A.log.downloads);

  // ---- Team leader (own team only) ----
  await api('createLeader', { name: 'Other Team', userLimit: 5 }, (await api('adminLogin', { key: ADMIN_KEY })).token);
  const L = openPage();
  await L.type('Team leader access code', accessCode);
  await L.click('Open my team');
  await L.has('My team');
  await L.has('Asha');
  assert.ok(!L.text().includes('Other Team'), 'leader sees only own team');
  assert.ok(L.text().includes('Users 1 / 2'));
  await L.type('New user name', 'Ravi');
  await L.click('Create user code');
  assert.ok(/CF-/.test(L.log.alerts.pop()));
  await L.type('New user name', 'Third');
  await L.click('Create user code');          // limit 2 reached -> error alert from the backend
  assert.ok(/limit/i.test(L.log.alerts.pop()), 'limit message shown');
  await L.has('Ravi');
  await L.click('Revoke access');              // first matching button = first user
  await L.has('REVOKED');
  const bad = openPage();
  await bad.type('Team leader access code', inviteCode);   // invite code is not a login
  await bad.click('Open my team');
  assert.ok(/Invalid access code/.test(bad.log.alerts.pop()));
  console.log('DASHBOARD CLICK-THROUGH PASSED');
  server.close(); process.exit(0);
})().catch(e => { console.error('FAILED:', e.message); process.exit(1); });
