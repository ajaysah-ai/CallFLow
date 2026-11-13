// CallFlow AI backend v3. One endpoint: POST /api/subscription  { action, ... }
// Roles: admin (ADMIN_KEY), leader (team access code), user (user code + approved device).
// Every permission, team limit, subscription and device rule is enforced HERE, never in the app.
const express = require('express');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
const { xlsx, csv } = require('./xlsx');

const ALPHA = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const CODE_RE = /^(CF|TL|TM)-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/;
const DAY = 86400000;
const MSG = {
  suspended: "Your team leader's subscription is suspended.",
  cancelled: "Your team leader's subscription is cancelled.",
  expired: "Your team leader's subscription has expired.",
};

function leaderState(l, t = Date.now()) {
  if (l.status === 'cancelled') return 'cancelled';
  if (l.status === 'suspended') return 'suspended';
  if (l.expiresAt && Date.parse(l.expiresAt) < t) return 'expired';
  return 'active';
}
const httpErr = (http, message) => Object.assign(new Error(message), { http });
const clean = (v, n) => String(v == null ? '' : v).trim().slice(0, n);
const iso = (t = Date.now()) => new Date(t).toISOString();
const normCode = v => clean(v, 40).toUpperCase();
const pick = n => Array.from(crypto.randomBytes(n), b => ALPHA[b % ALPHA.length]).join('');
const rid = p => p + crypto.randomBytes(6).toString('hex');
const closed = message => ({ status: 'closed', message });
// Spreadsheet formula protection: text typed by users must never start a formula.
const safe = v => (typeof v === 'string' && /^[=+\-@\t\r]/.test(v) ? "'" + v : v);

function createApp(store, cfg) {
  const ADMIN_KEY = String(cfg.ADMIN_KEY || '');
  const SECRET = String(cfg.SESSION_SECRET || '');
  const TZ = Number(cfg.TZ_OFFSET_MIN || 330) * 60000;
  const RETENTION = Number(cfg.RETENTION_DAYS || 365);
  if (ADMIN_KEY.length < 16) throw new Error('ADMIN_KEY must have at least 16 characters.');
  if (SECRET.length < 32) throw new Error('SESSION_SECRET must have at least 32 characters.');

  const dayKey = t => new Date(t + TZ).toISOString().slice(0, 10);
  const stamp = t => new Date(t + TZ).toISOString().replace('T', ' ').slice(0, 16);

  /* ---------- tokens ---------- */
  const mac = body => crypto.createHmac('sha256', SECRET).update(body).digest('base64url');
  function sign(payload, ttl) {
    const body = Buffer.from(JSON.stringify({ ...payload, exp: Date.now() + ttl })).toString('base64url');
    return body + '.' + mac(body);
  }
  function verify(token) {
    const [body, sig] = String(token || '').split('.');
    if (!body || !sig) return null;
    const a = Buffer.from(sig);
    const b = Buffer.from(mac(body));
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
    try {
      const p = JSON.parse(Buffer.from(body, 'base64url').toString());
      return p.exp > Date.now() ? p : null;
    } catch (e) { return null; }
  }

  /* ---------- helpers ---------- */
  let chain = Promise.resolve();
  const locked = fn => { const run = chain.then(fn, fn); chain = run.catch(() => {}); return run; };
  const hits = new Map();
  const limited = (key, max) => {
    const t = Date.now();
    const a = (hits.get(key) || []).filter(x => t - x < 60000);
    a.push(t);
    hits.set(key, a);
    return a.length > max;
  };
  setInterval(() => hits.clear(), 10 * 60 * 1000).unref();

  async function newCode(prefix, type, id) {
    for (let i = 0; i < 10; i++) {
      const code = `${prefix}-${pick(4)}-${pick(4)}`;
      if (await store.transaction('codes/' + code, cur => (cur === null ? { type, id } : undefined))) return code;
    }
    throw httpErr(500, 'Could not make a code.');
  }
  async function createUser(name, leaderId, device, consent) {
    const id = rid('u');
    const code = await newCode('CF', 'user', id);
    const u = {
      id, name, code, leaderId: leaderId || '', status: 'active', created: iso(), joinedAt: iso(), lastCheck: '', consent: { v: consent || '', at: consent ? iso() : '' },
      device: device ? { id: device.id, name: device.name || '', status: 'pending', requestedAt: iso(), approvedAt: '', lastSeen: iso() }
        : { id: '', name: '', status: 'none', requestedAt: '', approvedAt: '', lastSeen: '' },
    };
    await store.set('users/' + id, u);
    return u;
  }
  async function members(leaderId) { return Object.values(await store.findBy('users', 'leaderId', leaderId)); }
  async function assertSeat(leaderId) {
    const l = await store.get('leaders/' + leaderId);
    if (!l) throw httpErr(404, 'Team not found');
    const used = (await members(leaderId)).filter(m => m.status === 'active').length;
    if (used >= Number(l.userLimit)) throw httpErr(409, `User limit reached (${used}/${l.userLimit}).`);
    return l;
  }
  const pub = u => ({
    id: u.id, name: u.name, code: u.code, leaderId: u.leaderId, status: u.status, joinedAt: u.joinedAt, lastCheck: u.lastCheck,
    consent: (u.consent && u.consent.v) || '',
    device: { name: u.device.name, status: u.device.status, requestedAt: u.device.requestedAt, approvedAt: u.device.approvedAt, lastSeen: u.device.lastSeen, has: !!u.device.id },
  });

  // Decides what a user may do right now. All rules live here.
  async function access(u) {
    if (u.status !== 'active') return closed('Your access was removed by your team leader.');
    let notice = '';
    if (u.leaderId) {
      const l = await store.get('leaders/' + u.leaderId);
      if (!l) return closed('This team no longer exists.');
      const st = leaderState(l);
      if (st !== 'active') return closed(MSG[st]);
      notice = l.message || '';
      const list = (await members(u.leaderId)).filter(m => m.status === 'active')
        .sort((a, b) => (a.joinedAt || '').localeCompare(b.joinedAt || '') || a.id.localeCompare(b.id));
      if (list.findIndex(m => m.id === u.id) >= Number(l.userLimit)) return closed('Your team has reached its user limit. Please contact your team leader.');
    }
    const ds = u.device && u.device.status;
    if (ds === 'revoked') return closed('This phone has been blocked.');
    if (ds !== 'approved') return { status: 'pending', message: 'Waiting for approval. Please contact your admin or team leader.' };
    return { status: 'running', message: notice };
  }

  /* ---------- statistics ---------- */
  function stats(calls) {
    const a = Object.values(calls || {});
    const today = dayKey(Date.now());
    const from7 = dayKey(Date.now() - 6 * DAY);
    const last = a.reduce((m, c) => Math.max(m, c.dt || 0), 0);
    return {
      totalCalls: a.length,
      customers: new Set(a.map(c => c.cn + '|' + c.ph)).size,
      withRemarks: a.filter(c => c.rm).length,
      followUps: a.filter(c => c.fu).length,
      pendingFollowUps: a.filter(c => c.fu && !c.fd).length,
      today: a.filter(c => dayKey(c.dt) === today).length,
      last7: a.filter(c => dayKey(c.dt) >= from7).length,
      lastCall: last ? iso(last) : '',
    };
  }
  function daily(calls, n) {
    const a = Object.values(calls || {});
    const out = [];
    for (let i = n - 1; i >= 0; i--) { const k = dayKey(Date.now() - i * DAY); out.push({ day: k, calls: a.filter(c => dayKey(c.dt) === k).length }); }
    return out;
  }
  const callsOf = async id => (await store.get('calls/' + id)) || {};
  async function withStats(list, leaders) {
    const out = [];
    for (const u of list.sort((a, b) => a.name.localeCompare(b.name))) {
      out.push({ ...pub(u), leaderName: u.leaderId && leaders[u.leaderId] ? leaders[u.leaderId].name : '', stats: stats(await callsOf(u.id)) });
    }
    return out;
  }
  const leaderView = (l, users) => {
    const mine = users.filter(u => u.leaderId === l.id);
    return {
      id: l.id, name: l.name, status: l.status, state: leaderState(l), plan: l.plan, userLimit: l.userLimit, expiresAt: l.expiresAt,
      message: l.message, created: l.created, inviteCode: l.inviteCode,
      used: mine.filter(u => u.status === 'active').length, pendingDevices: mine.filter(u => u.device.status === 'pending').length,
    };
  };

  /* ---------- scope checks ---------- */
  async function getUserFor(ctx, userId) {
    const u = await store.get('users/' + clean(userId, 40));
    // A leader asking for someone else's user gets the same answer as for a missing user.
    if (!u || (ctx.role === 'leader' && u.leaderId !== ctx.id)) throw httpErr(404, 'User not found');
    return u;
  }
  const writable = ctx => {
    if (ctx.role === 'leader' && leaderState(ctx.leader) !== 'active') throw httpErr(403, 'Your subscription is not active. The dashboard is read-only.');
  };
  const intIn = (v, lo, hi, label) => {
    const n = Number(v);
    if (!Number.isInteger(n) || n < lo || n > hi) throw httpErr(400, `${label} must be a whole number from ${lo} to ${hi}.`);
    return n;
  };

  /* ---------- handlers ---------- */
  const H = {};

  H.adminLogin = { roles: null, fn: async b => {
    const a = Buffer.from(String(b.key || ''));
    const e = Buffer.from(ADMIN_KEY);
    if (a.length !== e.length || !crypto.timingSafeEqual(a, e)) throw httpErr(401, 'Wrong admin key');
    return { role: 'admin', token: sign({ role: 'admin' }, 12 * 3600e3) };
  } };

  H.leaderLogin = { roles: null, fn: async b => {
    const code = normCode(b.accessCode);
    const c = CODE_RE.test(code) && (await store.get('codes/' + code));
    const l = c && c.type === 'leader' ? await store.get('leaders/' + c.id) : null;
    if (!l) throw httpErr(401, 'Invalid access code');
    if (leaderState(l) === 'cancelled') throw httpErr(403, 'This subscription is cancelled.');
    return { role: 'leader', name: l.name, token: sign({ role: 'leader', id: l.id }, 12 * 3600e3) };
  } };

  H.activate = { roles: null, fn: async b => {
    const code = normCode(b.code);
    const dev = clean(b.deviceId, 80);
    if (!/^CF-/.test(code) || !CODE_RE.test(code) || !dev) return closed('Invalid code or device.');
    const c = await store.get('codes/' + code);
    if (!c || c.type !== 'user') return closed('Invalid code.');
    // Read, change, write. Done one request at a time (no Firebase transaction: a transaction that
    // returns "nothing" on its first call, when Firebase has nothing cached yet, is aborted and hid real users).
    return locked(async () => {
      const cur = await store.get('users/' + c.id);
      if (!cur) return closed('Invalid code.');
      const d = cur.device || { id: '' };
      const up = { lastCheck: iso() };
      if (!d.id) up.device = { id: dev, name: clean(b.deviceName, 60), status: 'pending', requestedAt: iso(), approvedAt: '', lastSeen: iso() };
      else if (d.id !== dev) return closed('This code is already used on another phone.');
      else up['device/lastSeen'] = iso();
      const cv = clean(b.consent, 10);
      if (cv && (!cur.consent || cur.consent.v !== cv)) up.consent = { v: cv, at: iso() };
      await store.update('users/' + c.id, up);
      const u = await store.get('users/' + c.id);
      const acc = await access(u);
      const out = { status: acc.status, message: acc.message, name: u.name };
      if (acc.status === 'running') out.token = sign({ role: 'user', id: u.id, dev }, 24 * 3600e3);
      return out;
    });
  } };

  H.join = { roles: null, fn: async b => {
    const code = normCode(b.inviteCode);
    const dev = clean(b.deviceId, 80);
    const name = clean(b.name, 60);
    if (!/^TM-/.test(code) || !CODE_RE.test(code) || !dev || !name) return closed('Invalid invite code, name or device.');
    const c = await store.get('codes/' + code);
    if (!c || c.type !== 'invite') return closed('Invalid invite code.');
    return locked(async () => {
      const l = await store.get('leaders/' + c.id);
      if (!l) return closed('Invalid invite code.');
      const st = leaderState(l);
      if (st !== 'active') return closed(MSG[st]);
      const list = await members(c.id);
      const again = list.find(m => m.device && m.device.id === dev);
      if (again) { const acc = await access(again); return { ...acc, code: again.code, name: again.name }; }
      if (list.filter(m => m.status === 'active').length >= Number(l.userLimit)) return closed('This team is full. Ask your team leader to raise the user limit.');
      const u = await createUser(name, c.id, { id: dev, name: clean(b.deviceName, 60) }, clean(b.consent, 10));
      return { status: 'pending', message: 'Joined. Waiting for approval.', code: u.code, name: u.name };
    });
  } };

  // ---- user (phone app) ----
  const userGate = async ctx => access(ctx.user);
  H.status = { roles: ['user'], fn: async (b, ctx) => userGate(ctx) };
  H.sync = { roles: ['user'], fn: async (b, ctx) => {
    const acc = await userGate(ctx);
    if (acc.status !== 'running') return acc;
    const up = {};
    let saved = 0;
    for (const r of (Array.isArray(b.records) ? b.records : []).slice(0, 200)) {
      if (!r || !/^[A-Za-z0-9_-]{1,64}$/.test(String(r.id))) continue;
      const dt = Math.floor(Number(r.dt));
      up[r.id] = {
        cn: clean(r.cn, 120), ph: clean(r.ph, 20), at: Math.max(1, parseInt(r.at, 10) || 1), rm: clean(r.rm, 1000),
        fu: /^\d{4}-\d{2}-\d{2}$/.test(String(r.fu || '')) ? r.fu : '', fd: !!r.fd, dt: dt > 0 ? dt : Date.now(),
      };
      saved++;
    }
    if (saved) await store.update('calls/' + ctx.id, up);
    return { status: 'running', message: acc.message, saved };
  } };

  // A user deletes their own account and all their call records (withdraws consent).
  H.deleteMe = { roles: ['user'], fn: async (b, ctx) => locked(async () => {
    const u = ctx.user;
    await store.remove('calls/' + u.id);
    await store.remove('codes/' + u.code);
    await store.remove('users/' + u.id);
    return { ok: true };
  }) };

  // ---- admin: team leaders ----
  H.listLeaders = { roles: ['admin'], fn: async () => {
    const leaders = (await store.get('leaders')) || {};
    const users = Object.values((await store.get('users')) || {});
    return { leaders: Object.values(leaders).map(l => ({ ...leaderView(l, users), accessCode: l.accessCode })).sort((a, b) => b.created.localeCompare(a.created)) };
  } };
  H.createLeader = { roles: ['admin'], fn: async b => {
    const name = clean(b.name, 80);
    if (!name) throw httpErr(400, 'Name is required.');
    const id = rid('l');
    const days = b.days ? intIn(b.days, 1, 3650, 'Days') : 0;
    const l = {
      id, name, status: 'active', plan: clean(b.plan, 40) || 'Standard', userLimit: intIn(b.userLimit, 0, 100000, 'User limit'),
      expiresAt: days ? iso(Date.now() + days * DAY) : '', message: '', created: iso(), accessCode: '', inviteCode: '',
    };
    l.accessCode = await newCode('TL', 'leader', id);
    l.inviteCode = await newCode('TM', 'invite', id);
    await store.set('leaders/' + id, l);
    return { leader: { ...leaderView(l, []), accessCode: l.accessCode } };
  } };
  const getLeader = async id => {
    const l = await store.get('leaders/' + clean(id, 40));
    if (!l) throw httpErr(404, 'Team leader not found');
    return l;
  };
  H.updateLeader = { roles: ['admin'], fn: async b => {
    const l = await getLeader(b.leaderId);
    const up = {};
    if (b.name !== undefined) up.name = clean(b.name, 80) || l.name;
    if (b.plan !== undefined) up.plan = clean(b.plan, 40) || l.plan;
    if (b.message !== undefined) up.message = clean(b.message, 300);
    if (b.userLimit !== undefined) up.userLimit = intIn(b.userLimit, 0, 100000, 'User limit');
    if (b.status !== undefined) {
      if (!['active', 'suspended', 'cancelled'].includes(b.status)) throw httpErr(400, 'status must be active, suspended or cancelled');
      up.status = b.status;
    }
    if (b.expiresAt !== undefined) {
      if (b.expiresAt !== '' && isNaN(Date.parse(b.expiresAt))) throw httpErr(400, 'expiresAt must be a date or empty');
      up.expiresAt = b.expiresAt === '' ? '' : iso(Date.parse(b.expiresAt));
    }
    await store.update('leaders/' + l.id, up);
    return { ok: true };
  } };
  H.renewLeader = { roles: ['admin'], fn: async b => {
    const l = await getLeader(b.leaderId);
    const days = intIn(b.days, 1, 3650, 'Days');
    const base = Math.max(Date.now(), l.expiresAt ? Date.parse(l.expiresAt) : 0);
    await store.update('leaders/' + l.id, { status: 'active', expiresAt: iso(base + days * DAY) });
    return { ok: true };
  } };
  H.newCode = { roles: ['admin'], fn: async b => {
    const l = await getLeader(b.leaderId);
    const access_ = b.which === 'access';
    if (!access_ && b.which !== 'invite') throw httpErr(400, 'which must be access or invite');
    const code = await newCode(access_ ? 'TL' : 'TM', access_ ? 'leader' : 'invite', l.id);
    await store.remove('codes/' + (access_ ? l.accessCode : l.inviteCode));
    await store.update('leaders/' + l.id, access_ ? { accessCode: code } : { inviteCode: code });
    return { code };
  } };
  H.deleteLeader = { roles: ['admin'], fn: async b => {
    if (b.confirm !== true) throw httpErr(400, 'Confirmation required.');
    const l = await getLeader(b.leaderId);
    return locked(async () => {
      for (const u of await members(l.id)) { await store.remove('calls/' + u.id); await store.remove('codes/' + u.code); await store.remove('users/' + u.id); }
      await store.remove('codes/' + l.accessCode);
      await store.remove('codes/' + l.inviteCode);
      await store.remove('leaders/' + l.id);
      return { ok: true };
    });
  } };

  // ---- team: leader (own team only) and admin ----
  H.teamInfo = { roles: ['leader'], fn: async (b, ctx) => {
    const users = await members(ctx.id);
    const calls = [];
    for (const u of users) calls.push(stats(await callsOf(u.id)));
    const sum = k => calls.reduce((s, c) => s + c[k], 0);
    return { leader: leaderView(ctx.leader, users), summary: { totalCalls: sum('totalCalls'), today: sum('today'), pendingFollowUps: sum('pendingFollowUps'), last7: sum('last7') } };
  } };
  H.teamUsers = { roles: ['leader'], fn: async (b, ctx) => ({ users: await withStats(await members(ctx.id), { [ctx.id]: ctx.leader }) }) };
  H.listUsers = { roles: ['admin'], fn: async b => {
    const leaders = (await store.get('leaders')) || {};
    let list = Object.values((await store.get('users')) || {});
    if (b.leaderId !== undefined && b.leaderId !== null && b.leaderId !== '*') list = list.filter(u => u.leaderId === clean(b.leaderId, 40));
    return { users: await withStats(list, leaders) };
  } };
  H.addUser = { roles: ['admin', 'leader'], fn: async (b, ctx) => {
    writable(ctx);
    const name = clean(b.name, 60);
    if (!name) throw httpErr(400, 'Name is required.');
    const leaderId = ctx.role === 'leader' ? ctx.id : clean(b.leaderId, 40);
    return locked(async () => {
      if (leaderId) await assertSeat(leaderId);
      return { user: pub(await createUser(name, leaderId)) };
    });
  } };
  H.setAccess = { roles: ['admin', 'leader'], fn: async (b, ctx) => {
    writable(ctx);
    if (b.access !== 'active' && b.access !== 'revoked') throw httpErr(400, 'access must be active or revoked');
    return locked(async () => {
      const u = await getUserFor(ctx, b.userId);
      if (b.access === 'active' && u.status !== 'active' && u.leaderId) await assertSeat(u.leaderId);
      await store.update('users/' + u.id, { status: b.access });
      return { ok: true };
    });
  } };
  H.setDevice = { roles: ['admin', 'leader'], fn: async (b, ctx) => {
    writable(ctx);
    const u = await getUserFor(ctx, b.userId);
    if (b.device === 'reset') {
      await store.update('users/' + u.id, { device: { id: '', name: '', status: 'none', requestedAt: '', approvedAt: '', lastSeen: '' } });
    } else if (b.device === 'approve' || b.device === 'revoke') {
      if (!u.device.id) throw httpErr(409, 'This user has not registered a phone yet.');
      await store.update('users/' + u.id, b.device === 'approve' ? { 'device/status': 'approved', 'device/approvedAt': iso() } : { 'device/status': 'revoked' });
    } else throw httpErr(400, 'device must be approve, revoke or reset');
    return { ok: true };
  } };
  H.removeUser = { roles: ['admin', 'leader'], fn: async (b, ctx) => {
    writable(ctx);
    return locked(async () => {
      const u = await getUserFor(ctx, b.userId);
      await store.remove('calls/' + u.id);
      await store.remove('codes/' + u.code);
      await store.remove('users/' + u.id);
      return { ok: true };
    });
  } };
  H.userDashboard = { roles: ['admin', 'leader'], fn: async (b, ctx) => {
    const u = await getUserFor(ctx, b.userId);
    const calls = await callsOf(u.id);
    const recent = Object.entries(calls).map(([id, c]) => ({ id, ...c })).sort((a, c) => c.dt - a.dt).slice(0, 50);
    return { user: pub(u), stats: stats(calls), daily: daily(calls, 7), recent };
  } };

  // ---- reports (csv / xlsx) ----
  H.report = { roles: ['admin', 'leader'], fn: async (b, ctx) => {
    const type = b.type;
    const fmt = b.format === 'xlsx' ? 'xlsx' : 'csv';
    if (!['calls', 'followups', 'performance', 'team'].includes(type)) throw httpErr(400, 'Unknown report type');
    const leaders = ctx.role === 'leader' ? { [ctx.id]: ctx.leader } : ((await store.get('leaders')) || {});
    let users;
    if (b.userId) users = [await getUserFor(ctx, b.userId)];
    else if (ctx.role === 'leader') users = await members(ctx.id);
    else if (b.leaderId) users = await members(clean(b.leaderId, 40));
    else users = Object.values((await store.get('users')) || {});
    users.sort((a, c) => a.name.localeCompare(c.name));
    const team = u => (u.leaderId && leaders[u.leaderId] ? leaders[u.leaderId].name : 'No team');
    let rows;
    if (type === 'calls' || type === 'followups') {
      rows = [type === 'calls'
        ? ['User', 'Team', 'Customer', 'Phone', 'Attempt', 'Date and time', 'Remark', 'Follow-up date', 'Follow-up status']
        : ['User', 'Team', 'Customer', 'Phone', 'Follow-up date', 'Status', 'Remark']];
      for (const u of users) {
        const list = Object.values(await callsOf(u.id)).sort((a, c) => a.dt - c.dt);
        for (const c of list) {
          if (type === 'calls') rows.push([u.name, team(u), c.cn, c.ph, c.at, stamp(c.dt), c.rm, c.fu, c.fu ? (c.fd ? 'Done' : 'Pending') : '']);
          else if (c.fu) rows.push([u.name, team(u), c.cn, c.ph, c.fu, c.fd ? 'Done' : 'Pending', c.rm]);
        }
      }
      if (type === 'followups') rows = [rows[0], ...rows.slice(1).sort((a, c) => String(a[4]).localeCompare(String(c[4])))];
    } else if (type === 'performance') {
      rows = [['User', 'Team', 'Access', 'Phone status', 'Total calls', 'Customers called', 'With remarks', 'Follow-ups set', 'Follow-ups pending', 'Calls today', 'Calls last 7 days', 'Last call']];
      for (const u of users) {
        const s = stats(await callsOf(u.id));
        rows.push([u.name, team(u), u.status, u.device.status, s.totalCalls, s.customers, s.withRemarks, s.followUps, s.pendingFollowUps, s.today, s.last7, s.lastCall ? stamp(Date.parse(s.lastCall)) : '']);
      }
    } else {
      rows = [['Team leader', 'Subscription', 'Plan', 'User limit', 'Active users', 'Pending phones', 'Total calls', 'Follow-ups pending', 'Calls today', 'Expires']];
      let list = Object.values(leaders);
      if (ctx.role === 'admin' && b.leaderId) list = list.filter(l => l.id === clean(b.leaderId, 40));
      for (const l of list.sort((a, c) => a.name.localeCompare(c.name))) {
        const mine = await members(l.id);
        let t = 0, td = 0, fp = 0;
        for (const u of mine) { const s = stats(await callsOf(u.id)); t += s.totalCalls; td += s.today; fp += s.pendingFollowUps; }
        rows.push([l.name, leaderState(l), l.plan, Number(l.userLimit), mine.filter(u => u.status === 'active').length, mine.filter(u => u.device.status === 'pending').length, t, fp, td, l.expiresAt ? dayKey(Date.parse(l.expiresAt)) : 'No expiry']);
      }
    }
    rows = rows.map(r => r.map(safe));
    const names = { calls: 'calling-history', followups: 'follow-ups', performance: 'user-performance', team: 'team-summary' };
    const body = fmt === 'xlsx' ? xlsx(rows, names[type]) : Buffer.from(csv(rows), 'utf8');
    return { __file: { name: `callflow-${names[type]}-${dayKey(Date.now())}.${fmt}`, body,
      type: fmt === 'xlsx' ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' : 'text/csv; charset=utf-8' } };
  } };

  // Retention: delete call records older than `days` days. Runs daily from server.js.
  async function purgeOld(days) {
    const cutoff = Date.now() - days * DAY;
    let n = 0;
    for (const [uid, recs] of Object.entries((await store.get('calls')) || {})) {
      const del = {};
      for (const [id, c] of Object.entries(recs || {})) if ((c.dt || 0) < cutoff) del[id] = null;
      if (Object.keys(del).length) { await store.update('calls/' + uid, del); n += Object.keys(del).length; }
    }
    return n;
  }

  /* ---------- legal pages (same files the app shows) ---------- */
  const LEGAL_DIR = path.join(__dirname, 'public', 'legal');
  const readLegal = f => fs.readFileSync(path.join(LEGAL_DIR, f), 'utf8');
  const legalDocs = () => JSON.parse(readLegal('index.json')).docs;
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  function mdToHtml(md) {
    const inl = s => esc(s).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    let out = '';
    let list = false;
    for (const raw of md.split(/\r?\n/)) {
      const line = raw.trimEnd();
      if (line.startsWith('- ')) { if (!list) { out += '<ul>'; list = true; } out += '<li>' + inl(line.slice(2)) + '</li>'; continue; }
      if (list) { out += '</ul>'; list = false; }
      if (!line.trim()) continue;
      if (line.startsWith('## ')) out += '<h2>' + inl(line.slice(3)) + '</h2>';
      else if (line.startsWith('# ')) out += '<h1>' + inl(line.slice(2)) + '</h1>';
      else out += '<p>' + inl(line) + '</p>';
    }
    return out + (list ? '</ul>' : '');
  }
  function legalPage(title, inner) {
    const nav = legalDocs().map(d => `<a href="/legal/${d.slug}">${esc(d.title)}</a>`).join(' · ');
    return `<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>:root{color-scheme:dark}body{margin:0;background:#0a0a0a;color:#f2f5e8;font:16px/1.6 system-ui,sans-serif}main{max-width:760px;margin:0 auto;padding:16px}h1,h2{color:#c6f432}a{color:#ffd60a}nav{font-size:14px;margin:12px 0}li{margin:4px 0}footer{color:#8a9a5b;font-size:12px;margin:30px 0;text-align:center}</style></head><body><main>${inner}<nav>${nav}</nav><footer>© 2026 CallFlow AI · All rights reserved by Ajay Sah</footer></main></body></html>`;
  }

  /* ---------- web ---------- */
  const app = express();
  app.set('trust proxy', 1);
  app.use((req, res, next) => {
    res.set({ 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY' });
    if (req.path.startsWith('/api')) res.set('Cache-Control', 'no-store');
    next();
  });
  app.use(express.json({ limit: '400kb' }));
  app.use(express.static(path.join(__dirname, 'public'), { redirect: false }));
  app.get('/legal', (req, res) => res.type('html').send(legalPage('Legal', '<h1>CallFlow AI: legal documents</h1><ul>' + legalDocs().map(d => `<li><a href="/legal/${d.slug}">${esc(d.title)}</a></li>`).join('') + '</ul>')));
  app.get('/legal/:slug', (req, res) => {
    const d = legalDocs().find(x => x.slug === req.params.slug);
    if (!d) return res.status(404).type('html').send(legalPage('Not found', '<h1>Page not found</h1>'));
    const set = { ...JSON.parse(readLegal('settings.json')), retentionDays: String(RETENTION) };
    const md = readLegal(d.slug + '.md').replace(/\{\{(\w+)\}\}/g, (m, k) => (set[k] !== undefined ? set[k] : m));
    return res.type('html').send(legalPage(d.title, mdToHtml(md)));
  });
  app.get('/health', (req, res) => res.json({ ok: true, service: 'CallFlow AI server', version: 3 }));
  app.get('/api/subscription', (req, res) => res.json({ ok: true, version: 3 }));

  app.post('/api/subscription', async (req, res) => {
    try {
      const b = req.body || {};
      const action = String(b.action || 'check');
      const h = H[action === 'check' ? 'status' : action];
      if (!h) return res.status(400).json({ error: 'Unknown action' });
      const loginLike = !h.roles;
      if (limited(req.ip + (loginLike ? ':login' : ':api'), loginLike ? 20 : 240)) {
        return res.status(429).json({ status: 'closed', error: 'Too many tries. Wait one minute.', message: 'Too many tries. Wait one minute.' });
      }
      let ctx = {};
      if (h.roles) {
        const m = /^Bearer (.+)$/.exec(req.get('authorization') || '');
        const t = m && verify(m[1]);
        if (!t || !h.roles.includes(t.role)) throw httpErr(401, 'Please sign in again.');
        ctx = { role: t.role, id: t.id || '' };
        if (t.role === 'leader') {
          ctx.leader = await store.get('leaders/' + t.id);
          if (!ctx.leader) throw httpErr(401, 'Please sign in again.');
          if (leaderState(ctx.leader) === 'cancelled') throw httpErr(403, 'This subscription is cancelled.');
        }
        if (t.role === 'user') {
          ctx.user = await store.get('users/' + t.id);
          if (!ctx.user || !ctx.user.device || ctx.user.device.id !== t.dev) throw httpErr(401, 'Please activate again.');
        }
      }
      const out = await h.fn(b, ctx);
      if (out && out.__file) {
        res.set({ 'Content-Type': out.__file.type, 'Content-Disposition': `attachment; filename="${out.__file.name}"` });
        return res.send(out.__file.body);
      }
      return res.json(out);
    } catch (e) {
      if (e.http) return res.status(e.http).json({ error: e.message });
      console.error('API error:', e.message);
      return res.status(500).json({ error: 'Internal server error' });
    }
  });
  app.purgeOld = purgeOld;
  return app;
}

module.exports = { createApp, leaderState };
