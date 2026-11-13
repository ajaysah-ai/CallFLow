# Security and Data Protection Policy
Effective: {{effective}} · Version 1.0

## What we do
- **Encrypted connection:** the app and dashboards talk to our server only over HTTPS.
- **Locked database:** nobody can read the database directly. Only our server can, with a private key kept in a secret setting, not in the code.
- **Rules on the server:** access, team limits, subscriptions and phone approval are checked on the server for every request, not in the app.
- **Team separation:** a Team Leader can only reach their own team's users and records.
- **Codes and sessions:** codes are random and one phone only. Sign-ins use short-lived signed tokens. Wrong guesses are slowed down by rate limits.
- **Less data:** customer phone numbers are sent with only the last 4 digits visible. Loan amounts are not sent.
- **No call recording** and no access to contacts, SMS or location.
- **Safe exports:** text in exported spreadsheets cannot run as a formula.

## What you should do
- Keep your phone locked with a PIN, fingerprint or face unlock.
- Never share your user code or the Team Leader access code.
- Install the app only from the link given by the Admin.
- Tell us at once if your phone is lost or you think your code is misused. The Admin can block the phone.

## If something goes wrong
If we find a data breach we will act at once to stop it, and we will tell the affected people and the authorities as the law requires.

## Report a problem
Found a security problem? Write to {{email}}. Please give us time to fix it before you tell others.
