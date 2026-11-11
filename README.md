# CallFlow AI (Android, Kotlin)

Import customers (CSV or XLSX), call them one by one, save a remark after each call, track follow-ups, check EMI, export CSV.

## Important: signed release build
The project now builds a signed release APK with a fixed key (`app/callflow.jks`), so new versions install over old ones.
Keep your GitHub repository PRIVATE because the key file is inside it.
If you installed the older version, export your CSV, uninstall the old app once, then install this one (the key is different).
If Android shows a Play Protect warning: tap "More details" > "Install anyway". This is normal for apps not from Play Store.

## Get the APK from your phone (no laptop)
1. Make a free GitHub account. Create a new repository.
2. Upload ALL files and folders of this project (keep the folder names, including `.github`).
3. Open the repository > Actions > "Build APK" > wait until it is green (about 5 minutes).
4. Open the finished run > Artifacts > download `CallFlowAI-apk` > unzip > install `app-release.apk`.
5. If the build fails, copy the red error text and send it to Claude.

## After install (important for auto-reopen)
Open the app > Home > allow: Phone state, Notifications, Display over other apps.
Realme: Settings > Battery > CallFlow AI > Unrestricted, and allow Auto-start.

## How a call works
Manual: Open dialer > you press call > call ends > app opens on the remark screen > save > next customer.
Auto-call (tick the box on Home): after you save a remark, a 5 second countdown shows, then the app places the next call. On the countdown and on the remark screen you can Pause calls or End calls. Allow "Calling" on Home first.

## EMI rule
Total loan = loan amount from your file + 10%. Loan column = first column with "loan" or "amount" in its name.

## Files
- `Data.kt` storage, CSV/XLSX reading, export
- `CallReceiver.kt` detects call end and reopens the app
- `Emi.kt` EMI maths
- `MainActivity.kt` all screens
