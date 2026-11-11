# CallFlow AI subscription server

One endpoint: `POST /api/subscription`. Data is kept in one JSON file (`data/subscriptions.json`).

## Run on your computer or a server
    npm install
    ADMIN_KEY=your-long-secret node server.js
Open http://localhost:3000 and enter the admin key. Create a code for each user, copy it, and send it to them.

## Run with Docker
    ADMIN_KEY=your-long-secret docker compose up -d --build
The JSON file is saved in the `data` folder, so it stays after restarts.

## Hosting
- Needs HTTPS (the Android app only talks to https addresses). Most hosts give HTTPS for free.
- The JSON file needs a disk that keeps files: a VPS, or Render/Railway with a volume.
- Vercel cannot save to a JSON file (its disk is temporary). To use Vercel, change the storage to a database later. Only the `save()` function and the `db` variable in `server.js` need to change.

## What the app sends and gets
    POST /api/subscription   {"code":"CF-ABCD-2345","deviceId":"..."}
    -> {"status":"running","message":"Any text you set"}
    -> {"status":"closed","message":"Invalid code."}

## Admin actions (header `x-admin-key`)
list, create {label, message}, update {code, status, message, label}, resetDevice {code}, delete {code}.

A code is linked to the first phone that uses it. If a user changes phone, press "Reset phone" in the dashboard.
