# CallFlow AI

One repository, two parts:

- `app/` + the Gradle files in this folder: the Android app. GitHub Actions builds it (`.github/workflows/build.yml`).
- `CallFlowServer/`: the backend (Node.js + Firebase). Run it on Render.

There must be only ONE copy of the app, here at the top. Delete the old `CallFlowAI/` folder if you still have it.

## Roles
- **Admin**: opens the server address, enters `ADMIN_KEY`. Creates team leaders, sets each leader's plan, expiry and USER LIMIT, suspends / renews / cancels subscriptions, approves or blocks phones, sees every team and every report.
- **Team leader**: opens the same address, enters the team access code (TL-...). Sees only their own team: users, calls, follow-ups, remarks, performance. Adds users, grants or revokes access, approves phones, exports reports (CSV and XLSX).
- **User**: uses the Android app. Enters a user code (CF-...) or joins with the team invite code (TM-...). Then waits for the phone to be approved.

## Rules enforced by the backend (not by the app)
1. No code = no access. A phone must be approved before the app opens.
2. A code works on one phone only. Reset it in the dashboard to move to a new phone.
3. A team can never have more active users than the limit the Admin set. If the Admin lowers the limit, the newest users are locked first.
4. If a team leader's subscription is suspended, expired or cancelled, the whole team is locked. The leader sees a read-only dashboard (cancelled = no login).
5. A team leader can only read or change users of their own team.
6. Offline: the app works for 7 days after the last successful check, then it locks until it can check again.

## What the app uploads
Only call records: customer name, phone with the first digits hidden (XXXXXX1234), remark, follow-up date, time. Loan amounts and the full customer file stay on the phone. Tell your customers and users about this in your privacy policy.

## Set up (short)
1. Make this repository PRIVATE.
2. Firebase: Realtime Database > Rules: paste `CallFlowServer/database.rules.json`. Create a NEW service account key (delete any old one).
3. Render > your service > Environment: `ADMIN_KEY` (16+ characters), `SESSION_SECRET` (32+ random characters), `FIREBASE_DATABASE_URL`, `FIREBASE_SERVICE_ACCOUNT_BASE64`. Redeploy.
4. Open your Render address and enter the admin key.
5. In `app/src/main/java/com/callflow/ai/License.kt` check `SERVER_URL`. Push to GitHub, download the APK from Actions.

## Tests
`cd CallFlowServer && npm install && npm test` runs the backend rule tests (no Firebase needed).
