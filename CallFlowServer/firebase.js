const admin = require("firebase-admin");

const databaseURL = process.env.FIREBASE_DATABASE_URL;
const credentialsBase64 =
    process.env.FIREBASE_SERVICE_ACCOUNT_BASE64;

if (!databaseURL || !credentialsBase64) {
    throw new Error("Firebase configuration is missing.");
}

let serviceAccount;

try {
    serviceAccount = JSON.parse(
        Buffer.from(credentialsBase64, "base64").toString("utf8")
    );
} catch {
    throw new Error("Invalid Firebase service account credentials.");
}

admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
    databaseURL: databaseURL
});

const database = admin.database();

module.exports = database;