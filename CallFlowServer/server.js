require('dotenv').config();
const { createApp } = require('./app');
const { firebaseStore } = require('./store');

const app = createApp(firebaseStore(require('./firebase')), process.env);
const PORT = process.env.PORT || 3000;
const DAYS = Number(process.env.RETENTION_DAYS || 365);
app.listen(PORT, '0.0.0.0', () => console.log(`CallFlow AI server v3 on port ${PORT}`));

// Data retention: delete call records older than RETENTION_DAYS, once a day.
const purge = () => app.purgeOld(DAYS).then(n => n && console.log(`Retention: deleted ${n} old call records`)).catch(e => console.error('Retention error:', e.message));
setTimeout(purge, 60 * 1000).unref();
setInterval(purge, 24 * 3600 * 1000).unref();
