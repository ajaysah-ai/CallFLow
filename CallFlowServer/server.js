// // CallFlow AI subscription server. One endpoint: POST /api/subscription
// // App sends {code, deviceId}  ->  {status: "running" | "closed", message: "..."}
// // Admin sends header x-admin-key and {action: list | create | update | resetDevice | delete}
// const express = require('express');
// const fs = require('fs');
// const path = require('path');
// const crypto = require('crypto');

// const PORT = process.env.PORT || 3000;
// const ADMIN_KEY = process.env.ADMIN_KEY || '';
// const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data', 'subscriptions.json');
// if (ADMIN_KEY.length < 8) { console.error('Set ADMIN_KEY (at least 8 characters).'); process.exit(1); }

// fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
// let db = {};
// try { db = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8')); } catch (e) { db = {}; }
// function save() {
//   const tmp = DATA_FILE + '.tmp';
//   fs.writeFileSync(tmp, JSON.stringify(db, null, 2));
//   fs.renameSync(tmp, DATA_FILE);
// }

// const ALPHA = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
// function makeCode() {
//   const pick = n => Array.from(crypto.randomBytes(n), b => ALPHA[b % ALPHA.length]).join('');
//   let code;
//   do { code = `CF-${pick(4)}-${pick(4)}`; } while (db[code]);
//   return code;
// }
// function isAdmin(req) {
//   const hash = s => crypto.createHash('sha256').update(String(s)).digest();
//   return crypto.timingSafeEqual(hash(req.get('x-admin-key') || ''), hash(ADMIN_KEY));
// }
// const hits = new Map();
// function limited(ip) {
//   const now = Date.now();
//   const arr = (hits.get(ip) || []).filter(t => now - t < 60000);
//   arr.push(now);
//   hits.set(ip, arr);
//   return arr.length > 30;
// }
// setInterval(() => hits.clear(), 10 * 60 * 1000).unref();
// const clean = (s, n) => String(s == null ? '' : s).trim().slice(0, n);

// const app = express();
// app.set('trust proxy', 1);
// app.use(express.json({ limit: '10kb' }));
// app.use(express.static(path.join(__dirname, 'public')));
// app.get('/api/subscription', (req, res) => res.json({ ok: true }));

// app.post('/api/subscription', (req, res) => {
//   if (limited(req.ip)) return res.status(429).json({ status: 'closed', message: 'Too many tries. Wait one minute.' });
//   const b = req.body || {};
//   const action = b.action || 'check';

//   if (action === 'check') {
//     const code = clean(b.code, 40).toUpperCase();
//     const deviceId = clean(b.deviceId, 80);
//     const s = db[code];
//     if (!s) return res.json({ status: 'closed', message: 'Invalid code.' });
//     if (s.status === 'running' && deviceId) {
//       if (!s.device) s.device = deviceId;
//       else if (s.device !== deviceId) return res.json({ status: 'closed', message: 'This code is already used on another phone.' });
//     }
//     s.lastCheck = new Date().toISOString();
//     save();
//     return res.json({ status: s.status, message: s.message || '' });
//   }

//   if (!isAdmin(req)) return res.status(401).json({ error: 'Wrong admin key' });
//   const code = clean(b.code, 40).toUpperCase();

//   if (action === 'list') {
//     const items = Object.entries(db).map(([c, v]) => ({ code: c, ...v })).sort((x, y) => (y.created || '').localeCompare(x.created || ''));
//     return res.json({ items });
//   }
//   if (action === 'create') {
//     const c = makeCode();
//     db[c] = { label: clean(b.label, 80), status: 'running', message: clean(b.message, 300), device: '', created: new Date().toISOString(), lastCheck: '' };
//     save();
//     return res.json({ code: c, ...db[c] });
//   }
//   const s = db[code];
//   if (!s) return res.status(404).json({ error: 'Code not found' });
//   if (action === 'update') {
//     if (b.status !== undefined) {
//       if (b.status !== 'running' && b.status !== 'closed') return res.status(400).json({ error: 'status must be running or closed' });
//       s.status = b.status;
//     }
//     if (b.message !== undefined) s.message = clean(b.message, 300);
//     if (b.label !== undefined) s.label = clean(b.label, 80);
//     save();
//     return res.json({ code, ...s });
//   }
//   if (action === 'resetDevice') { s.device = ''; save(); return res.json({ code, ...s }); }
//   if (action === 'delete') { delete db[code]; save(); return res.json({ deleted: code }); }
//   return res.status(400).json({ error: 'Unknown action' });
// });

// app.listen(PORT, () => console.log('Subscription server on port ' + PORT));


// CallFlow AI Subscription Server v2
// Storage: Firebase Realtime Database
// Hosting: Render

require("dotenv").config();
const express = require("express");
const crypto = require("crypto");
const path = require("path");

const db = require("./firebase");

const app = express();

const PORT = process.env.PORT || 3000;
const ADMIN_KEY = process.env.ADMIN_KEY || "";

const subscriptions = db.ref("subscriptions");

if (ADMIN_KEY.length < 16) {
    throw new Error("ADMIN_KEY must contain at least 16 characters.");
}

app.set("trust proxy", 1);

app.use(express.json({ limit: "10kb" }));

app.use(express.static(path.join(__dirname, "public")));

// --------------------------------------------------
// HEALTH CHECK
// --------------------------------------------------

app.get("/health", (req, res) => {
    res.json({
        ok: true,
        service: "CallFlow AI Subscription Server",
        storage: "Firebase"
    });
});

app.get("/api/subscription", (req, res) => {
    res.json({ ok: true });
});

// --------------------------------------------------
// HELPERS
// --------------------------------------------------

const ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

function clean(value, maxLength) {
    return String(value == null ? "" : value)
        .trim()
        .slice(0, maxLength);
}

function makeCode() {
    const pick = n =>
        Array.from(crypto.randomBytes(n), b =>
            ALPHA[b % ALPHA.length]
        ).join("");

    return `CF-${pick(4)}-${pick(4)}`;
}

function isAdmin(req) {
    const supplied = Buffer.from(
        String(req.get("x-admin-key") || "")
    );

    const expected = Buffer.from(ADMIN_KEY);

    return (
        supplied.length === expected.length &&
        crypto.timingSafeEqual(supplied, expected)
    );
}

// --------------------------------------------------
// RATE LIMITING
// --------------------------------------------------

const hits = new Map();

function limited(ip) {
    const now = Date.now();

    const arr = (hits.get(ip) || [])
        .filter(t => now - t < 60000);

    arr.push(now);

    hits.set(ip, arr);

    return arr.length > 30;
}

setInterval(() => {
    hits.clear();
}, 10 * 60 * 1000).unref();

// --------------------------------------------------
// VALIDATE FIREBASE KEYS
// --------------------------------------------------

function validCode(code) {
    return (
        typeof code === "string" &&
        /^CF-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/.test(code)
    );
}

// --------------------------------------------------
// MAIN SUBSCRIPTION ENDPOINT
// --------------------------------------------------

app.post("/api/subscription", async(req, res) => {

    try {

        if (limited(req.ip)) {
            return res.status(429).json({
                status: "closed",
                message: "Too many tries. Wait one minute."
            });
        }

        const b = req.body || {};
        const action = b.action || "check";

        // ------------------------------------------
        // CUSTOMER SUBSCRIPTION CHECK
        // ------------------------------------------

        if (action === "check") {

            const code = clean(b.code, 40).toUpperCase();
            const deviceId = clean(b.deviceId, 80);

            if (!validCode(code) || !deviceId) {
                return res.json({
                    status: "closed",
                    message: "Invalid code or device."
                });
            }

            const ref = subscriptions.child(code);

            let deviceMismatch = false;

            const result = await ref.transaction(current => {

                if (!current) {
                    return;
                }

                if (current.status === "running") {

                    if (!current.device) {
                        current.device = deviceId;
                    } else if (current.device !== deviceId) {
                        deviceMismatch = true;
                        return;
                    }
                }

                current.lastCheck = new Date().toISOString();

                return current;

            });

            if (!result.committed) {

                if (deviceMismatch) {
                    return res.json({
                        status: "closed",
                        message: "This code is already used on another phone."
                    });
                }

                return res.json({
                    status: "closed",
                    message: "Invalid code."
                });
            }

            const subscription = result.snapshot.val();

            return res.json({
                status: subscription.status,
                message: subscription.message || ""
            });
        }

        // ------------------------------------------
        // ADMIN AUTHENTICATION
        // ------------------------------------------

        if (!isAdmin(req)) {
            return res.status(401).json({
                error: "Wrong admin key"
            });
        }

        const code = clean(b.code, 40).toUpperCase();

        // ------------------------------------------
        // LIST SUBSCRIPTIONS
        // ------------------------------------------

        if (action === "list") {

            const snapshot = await subscriptions.get();

            const data = snapshot.val() || {};

            const items = Object.entries(data)
                .map(([code, value]) => ({
                    code,
                    ...value
                }))
                .sort((a, b) =>
                    (b.created || "").localeCompare(a.created || "")
                );

            return res.json({ items });
        }

        // ------------------------------------------
        // CREATE SUBSCRIPTION
        // ------------------------------------------

        if (action === "create") {

            let createdCode;
            let createdData;

            // Transaction prevents overwriting an existing code.
            for (let attempt = 0; attempt < 10; attempt++) {

                const candidate = makeCode();

                const data = {
                    label: clean(b.label, 80),
                    status: "running",
                    message: clean(b.message, 300),
                    device: "",
                    created: new Date().toISOString(),
                    lastCheck: ""
                };

                const result = await subscriptions
                    .child(candidate)
                    .transaction(current =>
                        current === null ? data : undefined
                    );

                if (result.committed) {
                    createdCode = candidate;
                    createdData = data;
                    break;
                }
            }

            if (!createdCode) {
                return res.status(500).json({
                    error: "Unable to generate subscription code."
                });
            }

            return res.json({
                code: createdCode,
                ...createdData
            });
        }

        // ------------------------------------------
        // VALIDATE ADMIN CODE
        // ------------------------------------------

        if (!validCode(code)) {
            return res.status(400).json({
                error: "Invalid subscription code"
            });
        }

        const ref = subscriptions.child(code);

        const snapshot = await ref.get();

        if (!snapshot.exists()) {
            return res.status(404).json({
                error: "Code not found"
            });
        }

        const subscription = snapshot.val();

        // ------------------------------------------
        // UPDATE SUBSCRIPTION
        // ------------------------------------------

        if (action === "update") {

            if (
                b.status !== undefined &&
                b.status !== "running" &&
                b.status !== "closed"
            ) {
                return res.status(400).json({
                    error: "status must be running or closed"
                });
            }

            const updates = {};

            if (b.status !== undefined) {
                updates.status = b.status;
            }

            if (b.message !== undefined) {
                updates.message = clean(b.message, 300);
            }

            if (b.label !== undefined) {
                updates.label = clean(b.label, 80);
            }

            await ref.update(updates);

            return res.json({
                code,
                ...subscription,
                ...updates
            });
        }

        // ------------------------------------------
        // RESET DEVICE
        // ------------------------------------------

        if (action === "resetDevice") {

            await ref.update({
                device: ""
            });

            return res.json({
                code,
                ...subscription,
                device: ""
            });
        }

        // ------------------------------------------
        // DELETE SUBSCRIPTION
        // ------------------------------------------

        if (action === "delete") {

            await ref.remove();

            return res.json({
                deleted: code
            });
        }

        return res.status(400).json({
            error: "Unknown action"
        });

    } catch (error) {

        console.error("Subscription API error:", error.message);

        return res.status(500).json({
            error: "Internal server error"
        });
    }

});

// --------------------------------------------------
// SERVER
// --------------------------------------------------

app.listen(PORT, "0.0.0.0", () => {
    console.log(
        `CallFlow AI Subscription Server running on port ${PORT}`
    );
});