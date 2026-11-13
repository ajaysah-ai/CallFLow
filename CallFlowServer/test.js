// Automated checks for the backend rules. Run: node test.js (uses an in-memory store, no Firebase needed).
const assert = require('assert');
const { createApp } = require('./app');
const { memoryStore } = require('./store');

const ADMIN_KEY = 'admin-key-1234567890';
const app = createApp(memoryStore({ firebaseLike: true }), { ADMIN_KEY, SESSION_SECRET: 'x'.repeat(40) });
const server = app.listen(0);
const url = `http://127.0.0.1:${server.address().port}/api/subscription`;
let ip = 0;
async function call(action, body = {}, token) {
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': '10.0.0.' + (++ip % 250), ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify({ action, ...body }) });
  const ct = r.headers.get('content-type') || '';
  return { status: r.status, body: ct.includes('json') ? await r.json() : Buffer.from(await r.arrayBuffer()), headers: r.headers };
}
const ok = (r, s = 200) => { assert.strictEqual(r.status, s, JSON.stringify(r.body)); return r.body; };
const t0 = Date.now();

(async () => {
  // admin login
  assert.strictEqual((await call('adminLogin', { key: 'wrong' })).status, 401);
  const admin = ok(await call('adminLogin', { key: ADMIN_KEY })).token;
  assert.strictEqual((await call('listLeaders')).status, 401, 'no token = no access');

  // admin creates two teams with different limits
  const A = ok(await call('createLeader', { name: 'Team A', userLimit: 2, days: 30, plan: 'Gold' }, admin)).leader;
  const B = ok(await call('createLeader', { name: 'Team B', userLimit: 5 }, admin)).leader;
  assert.ok(/^TL-/.test(A.accessCode) && A.inviteCode.startsWith('TM-'));
  const leaderA = ok(await call('leaderLogin', { accessCode: A.accessCode })).token;
  const leaderB = ok(await call('leaderLogin', { accessCode: B.accessCode })).token;
  assert.strictEqual((await call('leaderLogin', { accessCode: A.inviteCode })).status, 401, 'invite code is not a login');
  assert.strictEqual((await call('listLeaders', {}, leaderA)).status, 401, 'leader cannot use admin actions');

  // user limit set by admin is enforced by the backend
  const u1 = ok(await call('addUser', { name: 'Asha' }, leaderA)).user;
  const u2 = ok(await call('addUser', { name: 'Ravi' }, leaderA)).user;
  assert.strictEqual((await call('addUser', { name: 'Third' }, leaderA)).status, 409, 'limit 2 reached');
  ok(await call('updateLeader', { leaderId: A.id, userLimit: 3 }, admin));
  const u3 = ok(await call('addUser', { name: 'Third' }, leaderA)).user;
  assert.strictEqual((await call('addUser', { name: 'Fourth' }, leaderA)).status, 409);

  // device license: needs approval
  let r = ok(await call('activate', { code: u1.code, deviceId: 'DEV-1', deviceName: 'Realme 8' }));
  assert.strictEqual(r.status, 'pending'); assert.ok(!r.token);
  assert.strictEqual(ok(await call('activate', { code: u1.code, deviceId: 'DEV-2' })).status, 'closed', 'second phone refused');
  assert.strictEqual((await call('setDevice', { userId: u1.id, device: 'approve' }, leaderB)).status, 404, 'other team cannot approve');
  ok(await call('setDevice', { userId: u1.id, device: 'approve' }, leaderA));
  r = ok(await call('activate', { code: u1.code, deviceId: 'DEV-1' }));
  assert.strictEqual(r.status, 'running'); const t1 = r.token;
  assert.strictEqual(ok(await call('activate', { code: 'CF-AAAA-BBBB', deviceId: 'D' })).status, 'closed');

  // sync + isolation
  const recs = [
    { id: 'r1', cn: 'Amit', ph: 'XXXXXX3210', at: 1, rm: 'Interested', fu: '2026-11-20', fd: false, dt: Date.now() },
    { id: 'r2', cn: 'Sunil', ph: 'XXXXXX1111', at: 1, rm: '=HYPERLINK("http://evil")', fu: '', fd: false, dt: Date.now() },
    { id: 'bad/id', cn: 'x' },
  ];
  assert.strictEqual(ok(await call('sync', { records: recs }, t1)).saved, 2);
  ok(await call('sync', { records: [{ ...recs[0], fd: true }] }, t1));
  const dash = ok(await call('userDashboard', { userId: u1.id }, leaderA));
  assert.strictEqual(dash.stats.totalCalls, 2); assert.strictEqual(dash.stats.pendingFollowUps, 0);
  assert.strictEqual((await call('userDashboard', { userId: u1.id }, leaderB)).status, 404, 'team isolation');
  assert.strictEqual((await call('userDashboard', { userId: u1.id }, t1)).status, 401, 'user token is not a leader');
  assert.strictEqual(ok(await call('teamUsers', {}, leaderB)).users.length, 0);
  assert.strictEqual(ok(await call('teamUsers', {}, leaderA)).users.length, 3);

  // reports
  const csv = ok(await call('report', { type: 'calls', format: 'csv' }, leaderA)).toString('utf8');
  assert.ok(csv.includes("'=HYPERLINK"), 'formula neutralised'); assert.ok(csv.includes('Amit'));
  assert.ok(!(await call('report', { type: 'calls', format: 'csv' }, leaderB)).body.toString().includes('Amit'), 'other team sees nothing');
  for (const type of ['calls', 'followups', 'performance', 'team']) {
    const x = (await call('report', { type, format: 'xlsx' }, admin)).body;
    assert.strictEqual(x.slice(0, 2).toString(), 'PK'); require('fs').writeFileSync(`/tmp/r-${type}.xlsx`, x);
  }

  // join by invite code uses the same limit
  assert.strictEqual(ok(await call('join', { inviteCode: A.inviteCode, name: 'Late', deviceId: 'DEV-9' })).status, 'closed', 'team full');
  ok(await call('updateLeader', { leaderId: A.id, userLimit: 4 }, admin));
  const j = ok(await call('join', { inviteCode: A.inviteCode, name: 'Late', deviceId: 'DEV-9' }));
  assert.strictEqual(j.status, 'pending'); assert.ok(/^CF-/.test(j.code));
  assert.strictEqual(ok(await call('join', { inviteCode: A.inviteCode, name: 'Late', deviceId: 'DEV-9' })).code, j.code, 'same phone, same seat');

  // admin lowers the limit: newest users lose access, oldest keep it
  ok(await call('activate', { code: u2.code, deviceId: 'DEV-2' }));
  ok(await call('activate', { code: u3.code, deviceId: 'DEV-3' }));
  ok(await call('setDevice', { userId: u2.id, device: 'approve' }, admin));
  ok(await call('setDevice', { userId: u3.id, device: 'approve' }, admin));
  ok(await call('updateLeader', { leaderId: A.id, userLimit: 1 }, admin));
  assert.strictEqual(ok(await call('activate', { code: u1.code, deviceId: 'DEV-1' })).status, 'running');
  assert.strictEqual(ok(await call('activate', { code: u2.code, deviceId: 'DEV-2' })).status, 'closed', 'over the new limit');
  ok(await call('updateLeader', { leaderId: A.id, userLimit: 4 }, admin));
  assert.strictEqual(ok(await call('activate', { code: u2.code, deviceId: 'DEV-2' })).status, 'running');

  // subscription states
  ok(await call('updateLeader', { leaderId: A.id, status: 'suspended', message: 'x' }, admin));
  assert.strictEqual(ok(await call('activate', { code: u1.code, deviceId: 'DEV-1' })).status, 'closed');
  assert.strictEqual((await call('sync', { records: [] }, t1)).body.status, 'closed', 'old token stops working');
  assert.strictEqual((await call('addUser', { name: 'Z' }, leaderA)).status, 403, 'read-only when suspended');
  ok(await call('teamUsers', {}, leaderA)); ok(await call('report', { type: 'team', format: 'csv' }, leaderA));
  ok(await call('renewLeader', { leaderId: A.id, days: 30 }, admin));
  assert.strictEqual(ok(await call('activate', { code: u1.code, deviceId: 'DEV-1' })).status, 'running');
  ok(await call('updateLeader', { leaderId: A.id, expiresAt: new Date(t0 - 86400000).toISOString() }, admin));
  assert.strictEqual(ok(await call('activate', { code: u1.code, deviceId: 'DEV-1' })).status, 'closed', 'expired');
  ok(await call('updateLeader', { leaderId: A.id, status: 'cancelled', expiresAt: '' }, admin));
  assert.strictEqual((await call('leaderLogin', { accessCode: A.accessCode })).status, 403, 'cancelled leader blocked');
  assert.strictEqual((await call('teamUsers', {}, leaderA)).status, 403);
  ok(await call('updateLeader', { leaderId: A.id, status: 'active' }, admin));

  // revoke / remove / reset
  ok(await call('setAccess', { userId: u1.id, access: 'revoked' }, leaderA));
  assert.strictEqual(ok(await call('activate', { code: u1.code, deviceId: 'DEV-1' })).status, 'closed');
  ok(await call('setAccess', { userId: u1.id, access: 'active' }, leaderA));
  ok(await call('setDevice', { userId: u1.id, device: 'reset' }, admin));
  assert.strictEqual(ok(await call('activate', { code: u1.code, deviceId: 'DEV-NEW' })).status, 'pending');
  ok(await call('removeUser', { userId: u3.id }, leaderA));
  assert.strictEqual(ok(await call('activate', { code: u3.code, deviceId: 'D' })).status, 'closed');

  // admin standalone user, rate limit, bad input
  const solo = ok(await call('addUser', { name: 'Solo' }, admin)).user;
  assert.strictEqual((await call('setDevice', { userId: solo.id, device: 'approve' }, admin)).status, 409, 'no phone yet');
  assert.strictEqual((await call('createLeader', { name: 'X', userLimit: -1 }, admin)).status, 400);
  ok(await call('deleteLeader', { leaderId: B.id, confirm: true }, admin));
  let limited = 0;
  for (let i = 0; i < 25; i++) { const x = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': '9.9.9.9' }, body: JSON.stringify({ action: 'adminLogin', key: 'no' }) }); if (x.status === 429) limited++; }
  assert.ok(limited > 0, 'login attempts are rate limited');
  // consent, self-deletion, retention, legal pages
  const cu = ok(await call('addUser', { name: 'Consent' }, admin)).user;
  ok(await call('activate', { code: cu.code, deviceId: 'DEV-C', consent: '1.0' }));
  assert.strictEqual(ok(await call('userDashboard', { userId: cu.id }, admin)).user.consent, '1.0', 'consent saved');
  ok(await call('setDevice', { userId: cu.id, device: 'approve' }, admin));
  const tc = ok(await call('activate', { code: cu.code, deviceId: 'DEV-C' })).token;
  const old = Date.now() - 400 * 86400000;
  ok(await call('sync', { records: [{ id: 'old1', cn: 'Old', ph: 'XXXXXX0000', at: 1, rm: 'old', dt: old }, { id: 'new1', cn: 'New', ph: 'XXXXXX1111', at: 1, rm: 'new', dt: Date.now() }] }, tc));
  assert.strictEqual(await app.purgeOld(365), 1, 'only the old record is purged');
  assert.strictEqual(ok(await call('userDashboard', { userId: cu.id }, admin)).stats.totalCalls, 1);
  assert.strictEqual((await call('deleteMe', {}, admin)).status, 401, 'deleteMe is for users only');
  ok(await call('deleteMe', {}, tc));
  assert.strictEqual(ok(await call('activate', { code: cu.code, deviceId: 'DEV-C' })).status, 'closed', 'code gone after deleteMe');
  assert.strictEqual((await call('userDashboard', { userId: cu.id }, admin)).status, 404);
  const base = url.replace('/api/subscription', '');
  const pv = await (await fetch(base + '/legal/privacy')).text();
  assert.ok(pv.includes('Privacy Policy') && !pv.includes('{{') && pv.includes('365 days'), 'legal page filled in');
  assert.strictEqual((await fetch(base + '/legal/nope')).status, 404);
  assert.strictEqual((await fetch(base + '/legal/..%2f..%2fapp.js')).status, 404);
  const idx = await (await fetch(base + '/legal')).text();
  assert.ok((idx.match(/href="\/legal\//g) || []).length >= 10, 'index lists all 10 documents');
  for (const d of ['privacy','terms','about','data-disclosure','consent','deletion','retention','security','eula','support']) {
    const r = await fetch(base + '/legal/' + d); const h = await r.text();
    assert.ok(r.status === 200 && !h.includes('{{') && h.includes('<h1>'), d);
  }
  console.log('ALL BACKEND CHECKS PASSED');
  server.close(); process.exit(0);
})().catch(e => { console.error('FAILED:', e.message); process.exit(1); });
