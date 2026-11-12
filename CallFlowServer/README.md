# CallFlow AI server (v3)

One endpoint: `POST /api/subscription` with `{ "action": "...", ... }`. Dashboard: open `/` in a browser.

| Who | Actions |
|---|---|
| anyone | `adminLogin {key}`, `leaderLogin {accessCode}`, `activate {code, deviceId, deviceName}`, `join {inviteCode, name, deviceId}` |
| user (Bearer token) | `status`, `sync {records[]}` |
| admin | `listLeaders`, `createLeader {name, userLimit, plan, days}`, `updateLeader {leaderId, status, userLimit, expiresAt, plan, name, message}`, `renewLeader {leaderId, days}`, `newCode {leaderId, which}`, `deleteLeader {leaderId, confirm:true}`, `listUsers {leaderId?}` |
| admin + leader | `addUser`, `setAccess {userId, access}`, `setDevice {userId, device: approve/revoke/reset}`, `removeUser`, `userDashboard {userId}`, `report {type: calls/followups/performance/team, format: csv/xlsx, userId?, leaderId?}` |
| leader | `teamInfo`, `teamUsers` |

Environment: `ADMIN_KEY` (16+), `SESSION_SECRET` (32+), `FIREBASE_DATABASE_URL`, `FIREBASE_SERVICE_ACCOUNT_BASE64`, optional `TZ_OFFSET_MIN` (default 330 = India).
Run one server instance only (the user-limit lock lives in memory).
