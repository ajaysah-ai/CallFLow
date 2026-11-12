require('dotenv').config();
const { createApp } = require('./app');
const { firebaseStore } = require('./store');

const app = createApp(firebaseStore(require('./firebase')), process.env);
const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () => console.log(`CallFlow AI server v3 on port ${PORT}`));
