const express = require('express');
const adminModule = require('firebase-admin');
const admin = adminModule.default || adminModule;
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 10000;

// -------------------------------------------------------------
// Firebase Admin Helpers (Compatible with all CJS/ESM exports)
// -------------------------------------------------------------
function getCertCredential(parsed) {
  if (admin && admin.credential && typeof admin.credential.cert === 'function') {
    return admin.credential.cert(parsed);
  }
  if (adminModule && adminModule.credential && typeof adminModule.credential.cert === 'function') {
    return adminModule.credential.cert(parsed);
  }
  if (adminModule && adminModule.default && adminModule.default.credential && typeof adminModule.default.credential.cert === 'function') {
    return adminModule.default.credential.cert(parsed);
  }
  try {
    const { cert } = require('firebase-admin/app');
    if (typeof cert === 'function') {
      return cert(parsed);
    }
  } catch (_) {}
  throw new Error("Unable to locate 'cert' function on firebase-admin");
}

function getAppDefaultCredential() {
  if (admin && admin.credential && typeof admin.credential.applicationDefault === 'function') {
    return admin.credential.applicationDefault();
  }
  if (adminModule && adminModule.credential && typeof adminModule.credential.applicationDefault === 'function') {
    return adminModule.credential.applicationDefault();
  }
  if (adminModule && adminModule.default && adminModule.default.credential && typeof adminModule.default.credential.applicationDefault === 'function') {
    return adminModule.default.credential.applicationDefault();
  }
  try {
    const { applicationDefault } = require('firebase-admin/app');
    if (typeof applicationDefault === 'function') {
      return applicationDefault();
    }
  } catch (_) {}
  return null;
}

function initializeFirebaseApp(options) {
  if (admin && typeof admin.initializeApp === 'function') {
    return admin.initializeApp(options);
  }
  if (adminModule && typeof adminModule.initializeApp === 'function') {
    return adminModule.initializeApp(options);
  }
  if (adminModule && adminModule.default && typeof adminModule.default.initializeApp === 'function') {
    return adminModule.default.initializeApp(options);
  }
  try {
    const { initializeApp } = require('firebase-admin/app');
    if (typeof initializeApp === 'function') {
      return initializeApp(options);
    }
  } catch (_) {}
  throw new Error("Unable to locate 'initializeApp' function on firebase-admin");
}

function getFirebaseApps() {
  if (admin && Array.isArray(admin.apps)) return admin.apps;
  if (adminModule && Array.isArray(adminModule.apps)) return adminModule.apps;
  if (adminModule && adminModule.default && Array.isArray(adminModule.default.apps)) return adminModule.default.apps;
  try {
    const { getApps } = require('firebase-admin/app');
    if (typeof getApps === 'function') return getApps();
  } catch (_) {}
  return [];
}

// -------------------------------------------------------------
// Firebase Admin Initialization (Graceful & Multi-Credential)
// -------------------------------------------------------------
let isFirebaseAdminInitialized = false;
let adminInitMethod = 'UNINITIALIZED';
let adminInitError = null;

function initFirebaseAdmin() {
  try {
    const apps = getFirebaseApps();
    if (apps && apps.length > 0) {
      isFirebaseAdminInitialized = true;
      adminInitMethod = 'ALREADY_INITIALIZED';
      console.log('[FCM] Firebase Admin already initialized');
      return;
    }

    const projectId = process.env.FIREBASE_PROJECT_ID || 'campus-ride-2b21b';
    let credential = null;
    let method = 'NONE';

    // 1. Explicit GOOGLE_APPLICATION_CREDENTIALS file path
    if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
      const gPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
      try {
        if (fs.existsSync(gPath)) {
          const content = fs.readFileSync(gPath, 'utf8');
          const parsed = JSON.parse(content);
          if (parsed.private_key && typeof parsed.private_key === 'string') {
            parsed.private_key = parsed.private_key.replace(/\\n/g, '\n');
          }
          credential = getCertCredential(parsed);
          method = `GOOGLE_APPLICATION_CREDENTIALS (${gPath})`;
        } else {
          try {
            credential = getAppDefaultCredential();
            method = `Application Default Credentials (${gPath})`;
          } catch (_) {}
        }
      } catch (err) {
        adminInitError = err.message;
        console.error('[FCM] Error loading GOOGLE_APPLICATION_CREDENTIALS:', err.message);
      }
    }

    // 2. Render Secret Files Auto-Detection in /etc/secrets/
    if (!credential) {
      try {
        if (fs.existsSync('/etc/secrets')) {
          const secretFiles = fs.readdirSync('/etc/secrets');
          for (const file of secretFiles) {
            const fullPath = path.join('/etc/secrets', file);
            try {
              const content = fs.readFileSync(fullPath, 'utf8');
              if (content.includes('"type": "service_account"') || content.includes('"private_key"')) {
                const parsed = JSON.parse(content);
                if (parsed.private_key && typeof parsed.private_key === 'string') {
                  parsed.private_key = parsed.private_key.replace(/\\n/g, '\n');
                }
                credential = getCertCredential(parsed);
                method = `Render Secret File (/etc/secrets/${file})`;
                break;
              }
            } catch (_) {}
          }
        }
      } catch (err) {
        console.warn('[FCM] Secret file directory check warning:', err.message);
      }
    }

    // 3. Raw JSON Environment Variable (FIREBASE_SERVICE_ACCOUNT / FIREBASE_SERVICE_ACCOUNT_JSON / FIREBASE_CREDENTIALS)
    if (!credential) {
      const envVars = ['FIREBASE_SERVICE_ACCOUNT', 'FIREBASE_SERVICE_ACCOUNT_JSON', 'FIREBASE_SERVICE_ACCOUNT_KEY', 'FIREBASE_CREDENTIALS', 'SERVICE_ACCOUNT_JSON', 'GOOGLE_CREDENTIALS'];
      for (const envKey of envVars) {
        if (process.env[envKey]) {
          try {
            const raw = process.env[envKey];
            let parsed;
            if (typeof raw === 'string') {
              try {
                parsed = JSON.parse(raw);
              } catch (_) {
                // Try replacing literal newlines if JSON.parse failed
                parsed = JSON.parse(raw.replace(/\r?\n/g, '\\n'));
              }
            } else {
              parsed = raw;
            }
            if (parsed && parsed.private_key && typeof parsed.private_key === 'string') {
              parsed.private_key = parsed.private_key.replace(/\\n/g, '\n');
            }
            credential = getCertCredential(parsed);
            method = `Environment Variable (${envKey})`;
            break;
          } catch (err) {
            adminInitError = err.message;
            console.error(`[FCM] Failed to parse JSON from ${envKey}:`, err.message);
          }
        }
      }
    }

    // 4. Base64 Encoded Service Account Environment Variable
    if (!credential && process.env.FIREBASE_SERVICE_ACCOUNT_BASE64) {
      try {
        const decoded = Buffer.from(process.env.FIREBASE_SERVICE_ACCOUNT_BASE64, 'base64').toString('utf8');
        const parsed = JSON.parse(decoded);
        if (parsed.private_key && typeof parsed.private_key === 'string') {
          parsed.private_key = parsed.private_key.replace(/\\n/g, '\n');
        }
        credential = getCertCredential(parsed);
        method = 'Environment Variable (FIREBASE_SERVICE_ACCOUNT_BASE64)';
      } catch (err) {
        adminInitError = err.message;
        console.error('[FCM] Failed to parse FIREBASE_SERVICE_ACCOUNT_BASE64:', err.message);
      }
    }

    // 5. Try Application Default Credentials (GCP environment)
    if (!credential) {
      try {
        credential = getAppDefaultCredential();
        if (credential) {
          method = 'Application Default Credentials (ADC)';
        }
      } catch (_) {}
    }

    if (credential) {
      try {
        initializeFirebaseApp({
          credential,
          projectId
        });
        isFirebaseAdminInitialized = true;
        adminInitMethod = method;
        adminInitError = null;
        console.log(`[FCM] Firebase Admin initialization status: SUCCESS`);
        console.log(`[FCM] Credential method used: ${adminInitMethod}`);
      } catch (err) {
        adminInitError = err.message;
        console.error('[FCM] Firebase Admin credential initialization error:', err.message);
        // Fallback to project ID only
        try {
          initializeFirebaseApp({ projectId });
          isFirebaseAdminInitialized = true;
          adminInitMethod = 'PROJECT_ID_FALLBACK';
          console.log(`[FCM] Firebase Admin initialized with Project ID fallback (${projectId})`);
        } catch (e2) {
          isFirebaseAdminInitialized = false;
          adminInitMethod = 'FAILED';
          adminInitError = e2.message;
          console.error('[FCM] Firebase Admin fallback failed:', e2.message);
        }
      }
    } else {
      try {
        initializeFirebaseApp({ projectId });
        isFirebaseAdminInitialized = true;
        adminInitMethod = 'PROJECT_ID_ONLY';
        adminInitError = null;
        console.log(`[FCM] Firebase Admin initialized with Project ID (${projectId})`);
      } catch (e3) {
        isFirebaseAdminInitialized = false;
        adminInitMethod = 'CREDENTIALS_MISSING';
        adminInitError = e3.message;
        console.warn('[FCM] Firebase Admin initialization failed: credentials unavailable');
      }
    }
  } catch (topErr) {
    isFirebaseAdminInitialized = false;
    adminInitMethod = 'EXCEPTION';
    adminInitError = topErr.message;
    console.error('[FCM] Uncaught initialization exception:', topErr.message);
  }
}

function getMessagingService() {
  if (admin && typeof admin.messaging === 'function') {
    return admin.messaging();
  }
  if (adminModule && typeof adminModule.messaging === 'function') {
    return adminModule.messaging();
  }
  if (adminModule && adminModule.default && typeof adminModule.default.messaging === 'function') {
    return adminModule.default.messaging();
  }
  try {
    const { getMessaging } = require('firebase-admin/messaging');
    return getMessaging();
  } catch (e) {
    throw new Error(`Firebase messaging service unavailable: ${e.message}`);
  }
}

// Execute initialization safely
initFirebaseAdmin();

// CORS, Security Headers & Rate Limiting Middleware
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept, Authorization');
  res.header('X-Content-Type-Options', 'nosniff');
  res.header('X-Frame-Options', 'DENY');
  res.header('X-XSS-Protection', '1; mode=block');
  res.header('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  if (req.method === 'OPTIONS') {
    return res.sendStatus(200);
  }
  next();
});
app.use(express.json({ limit: '1mb' }));

// In-Memory Sliding-Window Rate Limiter
const rateLimitMap = new Map();
function rateLimiter(maxRequests = 60, windowMs = 60000) {
  return (req, res, next) => {
    const ip = req.ip || req.headers['x-forwarded-for'] || req.socket.remoteAddress || 'unknown_client';
    const key = `${ip}_${req.path}`;
    const now = Date.now();
    const timestamps = (rateLimitMap.get(key) || []).filter(t => now - t < windowMs);

    if (timestamps.length >= maxRequests) {
      return res.status(429).json({
        success: false,
        error: 'Too many requests. Please slow down and try again shortly.'
      });
    }

    timestamps.push(now);
    rateLimitMap.set(key, timestamps);
    next();
  };
}

// Input Validation Helpers
function sanitizeString(str, maxLen = 100) {
  if (typeof str !== 'string') return '';
  return str.trim().substring(0, maxLen).replace(/<[^>]*>?/gm, '');
}

function isValidCampusCoord(lat, lng) {
  const nLat = Number(lat);
  const nLng = Number(lng);
  return !isNaN(nLat) && !isNaN(nLng) && nLat >= 25.0 && nLat <= 25.5 && nLng >= 86.8 && nLng <= 87.3;
}

// In-Memory Data Store (Provides instant response if Firestore is offline or unauthenticated)
const ridesStore = new Map();
const cartsStore = new Map([
  ['cart_1', {
    cartId: 'cart_1',
    cartName: 'Golf Cart 1',
    latitude: 25.2531616,
    longitude: 87.0370730,
    speedKmH: 15,
    bearing: 90,
    status: 'HALTED',
    batteryLevel: 92,
    driverStatus: 'Available',
    isAvailable: true,
    lastUpdatedMillis: Date.now(),
    lastHeartbeatMillis: Date.now(),
    locationTimestampMillis: Date.now(),
    distanceToGateMeters: 120,
    relativeMovement: 'Stationary near Gate',
    etaMinutes: 2
  }],
  ['cart_2', {
    cartId: 'cart_2',
    cartName: 'Golf Cart 2',
    latitude: 25.2510000,
    longitude: 87.0350000,
    speedKmH: 0,
    bearing: 0,
    status: 'HALTED',
    batteryLevel: 85,
    driverStatus: 'Available',
    isAvailable: true,
    lastUpdatedMillis: Date.now(),
    lastHeartbeatMillis: Date.now(),
    locationTimestampMillis: Date.now(),
    distanceToGateMeters: 350,
    relativeMovement: 'Halted near Guest House',
    etaMinutes: 5
  }]
]);
const usersStore = new Map([
  ['usr_default', {
    userId: 'usr_default',
    name: 'Campus User',
    email: 'user@iiitbh.ac.in',
    role: 'STUDENT',
    department: 'Computer Science & Engineering',
    phone: '+91 9876543210'
  }]
]);
const chatStore = new Map(); // rideId -> Array of messages
const fcmTokensStore = new Map(); // role/userId -> token

// -------------------------------------------------------------
// 1. Health & Service Metadata Endpoints
// -------------------------------------------------------------
app.get('/', (req, res) => {
  res.json({
    service: 'campus-ride-backend',
    status: 'online',
    version: '1.0.0',
    environment: process.env.NODE_ENV || 'production',
    projectId: process.env.FIREBASE_PROJECT_ID || 'campus-ride-2b21b',
    renderUrl: 'https://campus-ride-backend-df0n.onrender.com',
    firebaseAdminActive: isFirebaseAdminInitialized,
    credentialMethod: adminInitMethod,
    timestamp: new Date().toISOString()
  });
});

app.get('/health', (req, res) => {
  res.json({
    status: 'UP',
    database: 'IN_MEMORY_MAPS',
    firebaseAdminActive: isFirebaseAdminInitialized,
    credentialMethod: adminInitMethod,
    activeRides: ridesStore.size,
    registeredCarts: cartsStore.size,
    fcmTokensStored: fcmTokensStore.size,
    uptimeSeconds: Math.floor(process.uptime())
  });
});

// -------------------------------------------------------------
// 2. User & Auth REST Endpoints
// -------------------------------------------------------------
app.post('/api/auth/login', (req, res) => {
  const { email, role } = req.body;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email is required' });
  }

  const userId = `usr_${Date.now()}`;
  const user = {
    userId,
    name: email.split('@')[0],
    email,
    role: (role || 'STUDENT').toUpperCase(),
    department: 'IIIT Bhagalpur',
    phone: '+91 9876543210'
  };

  usersStore.set(userId, user);

  res.json({
    success: true,
    token: `jwt_token_${userId}`,
    user
  });
});

app.post('/api/auth/register', (req, res) => {
  const { name, email, role, department, phone } = req.body;
  if (!email || !name) {
    return res.status(400).json({ success: false, error: 'Name and email are required' });
  }

  const userId = `usr_${Date.now()}`;
  const newUser = {
    userId,
    name,
    email,
    role: (role || 'STUDENT').toUpperCase(),
    department: department || 'IIIT Bhagalpur',
    phone: phone || '+91 9876543210'
  };

  usersStore.set(userId, newUser);

  res.status(201).json({
    success: true,
    message: 'User registered successfully',
    user: newUser
  });
});

app.get('/api/user/profile', (req, res) => {
  const userId = req.query.userId || 'usr_default';
  const profile = usersStore.get(userId) || usersStore.get('usr_default');
  res.json({ success: true, user: profile });
});

app.put('/api/user/profile', (req, res) => {
  const { userId, name, department, phone } = req.body;
  const key = userId || 'usr_default';
  const current = usersStore.get(key) || { userId: key, email: 'user@iiitbh.ac.in', role: 'STUDENT' };

  const updated = {
    ...current,
    name: name || current.name,
    department: department || current.department,
    phone: phone || current.phone
  };

  usersStore.set(key, updated);
  res.json({ success: true, user: updated });
});

// -------------------------------------------------------------
// 3. Rides Management REST Endpoints
// -------------------------------------------------------------
app.post('/api/rides/request', rateLimiter(20, 60000), async (req, res) => {
  const { id, requestId, requesterType, studentName, pickupLocation, dropoffLocation, distanceToGateMeters, studentsWaiting, assignedCartId } = req.body;

  const sanitizedRequesterType = String(requesterType || 'STUDENT').toUpperCase();
  if (sanitizedRequesterType !== 'STUDENT' && sanitizedRequesterType !== 'FACULTY') {
    return res.status(400).json({ success: false, error: 'Invalid requesterType. Must be STUDENT or FACULTY.' });
  }

  const sanitizedPickup = sanitizeString(pickupLocation, 100);
  if (!sanitizedPickup) {
    return res.status(400).json({ success: false, error: 'Valid pickupLocation is required' });
  }

  const sanitizedName = sanitizeString(studentName, 60) || (sanitizedRequesterType === 'FACULTY' ? 'Faculty Member' : 'Student Passenger');
  const sanitizedDropoff = sanitizeString(dropoffLocation, 100) || 'Academic Block';
  const validatedWaiting = Math.max(1, Math.min(10, Number(studentsWaiting || 1)));

  const rideId = sanitizeString(id || requestId, 64) || `ride_${Date.now()}`;
  const requestedCartId = sanitizeString(assignedCartId, 32) || 'cart_1';
  const cart = cartsStore.get(requestedCartId) || cartsStore.get('cart_1');
  const effectiveCartId = cart ? cart.cartId : requestedCartId;
  const effectiveCartName = cart ? cart.cartName : (effectiveCartId === 'cart_2' ? 'Cart 2' : 'Golf Cart 1');

  // STEP 1: STUDENT_REQUEST_CREATED
  console.log(`[CAMPUS_RIDE_AUDIT] 1. STUDENT_REQUEST_CREATED: requestId=${rideId}, requester=${sanitizedRequesterType}, pickup=${sanitizedPickup}, waiting=${validatedWaiting}`);

  // STEP 2: DRIVER_SELECTED
  console.log(`[CAMPUS_RIDE_AUDIT] 2. DRIVER_SELECTED: targetCartId=${effectiveCartId}, cartName=${effectiveCartName}`);

  // STEP 3: DRIVER_ID_FOUND
  const targetDriverId = `driver_${effectiveCartId}`;
  console.log(`[CAMPUS_RIDE_AUDIT] 3. DRIVER_ID_FOUND: driverId=${targetDriverId}, cartId=${effectiveCartId}`);

  const newRide = {
    id: rideId,
    requestId: rideId,
    requesterType: sanitizedRequesterType,
    studentName: sanitizedName,
    pickupLocation: sanitizedPickup,
    dropoffLocation: sanitizedDropoff,
    distanceToGateMeters: Math.max(0, Math.min(5000, Number(distanceToGateMeters || 0))),
    studentsWaiting: validatedWaiting,
    status: 'PENDING',
    assignedCartId: effectiveCartId,
    assignedCartName: effectiveCartName,
    timestamp: Date.now(),
    updatedAt: Date.now()
  };

  ridesStore.set(rideId, newRide);

  // Only skip FCM dispatch if explicitly flagged by caller
  const shouldSkipFcm = Boolean(req.body.skipFcm);
  if (shouldSkipFcm) {
    console.log(`[RIDE_SYNC] Ride request ${rideId} explicitly flagged to skip duplicate FCM dispatch.`);
    return res.status(200).json({
      success: true,
      message: 'Ride request synchronized successfully',
      ride: newRide,
      fcmResult: { success: true, reason: 'CLIENT_DISPATCHED' }
    });
  }

  // STEP 4: DRIVER_FCM_TOKEN_LOOKUP
  console.log(`[CAMPUS_RIDE_AUDIT] 4. DRIVER_FCM_TOKEN_LOOKUP: looking for token in cart=${effectiveCartId}`);
  let driverToken = fcmTokensStore.get(effectiveCartId);
  if (!driverToken && effectiveCartId === 'cart_1') {
    driverToken = fcmTokensStore.get('DRIVER');
  }

  // If token is not in memory, query Firestore drivers collection
  if (!driverToken && isFirebaseAdminInitialized) {
    try {
      const doc = await admin.firestore().collection('drivers').doc(effectiveCartId).get();
      if (doc.exists && doc.data().fcmToken) {
        driverToken = doc.data().fcmToken;
        fcmTokensStore.set(effectiveCartId, driverToken);
        console.log(`[FCM] Restored driver token for ${effectiveCartId} from Firestore`);
      } else if (effectiveCartId === 'cart_1') {
        const fallbackDoc = await admin.firestore().collection('drivers').doc('cart_1').get();
        if (fallbackDoc.exists && fallbackDoc.data().fcmToken) {
          driverToken = fallbackDoc.data().fcmToken;
          fcmTokensStore.set('cart_1', driverToken);
          fcmTokensStore.set('DRIVER', driverToken);
          console.log(`[FCM] Restored driver token from cart_1`);
        }
      }
    } catch (e) {
      console.warn(`[FCM] Firestore driver token restore warning: ${e.message}`);
    }
  }

  // STEP 5: DRIVER_FCM_TOKEN_FOUND
  if (driverToken) {
    console.log(`[CAMPUS_RIDE_AUDIT] 5. DRIVER_FCM_TOKEN_FOUND: tokenPreview=${driverToken.substring(0, 10)}... for cart=${effectiveCartId}`);
  } else {
    console.warn(`[CAMPUS_RIDE_AUDIT] 5. DRIVER_FCM_TOKEN_LOOKUP: No direct token found for ${effectiveCartId}. Will fallback to topic 'drivers'.`);
  }

  // STEP 6: FIRESTORE_REQUEST_WRITE (NON-BLOCKING - NEVER BLOCK FCM ON FIRESTORE!)
  if (isFirebaseAdminInitialized) {
    admin.firestore().collection('ride_requests').doc(rideId).set({
      ...newRide,
      fcmStatus: 'PENDING'
    }).then(() => {
      console.log(`[CAMPUS_RIDE_AUDIT] 6. FIRESTORE_REQUEST_WRITE: successfully wrote ride request doc ${rideId}`);
    }).catch(fsErr => {
      console.warn(`[CAMPUS_RIDE_AUDIT] 6. FIRESTORE_REQUEST_WRITE warning (non-fatal): ${fsErr.message}`);
    });
  }

  // STEP 7: FCM_SEND_STARTED
  console.log(`[CAMPUS_RIDE_AUDIT] 7. FCM_SEND_STARTED: requestId=${rideId}, targetCart=${effectiveCartId}, hasToken=${Boolean(driverToken)}`);

  // High Priority Android DATA-ONLY FCM message (guarantees onMessageReceived fires in all Android app states)
  const fcmPayload = {
    data: {
      type: 'RIDE_REQUEST',
      requestId: String(newRide.id),
      rideId: String(newRide.id),
      requesterType: String(newRide.requesterType),
      passengerName: String(newRide.studentName || 'Passenger'),
      studentName: String(newRide.studentName || 'Passenger'),
      pickupLocation: String(newRide.pickupLocation),
      dropoffLocation: String(newRide.dropoffLocation),
      distanceToGateMeters: String(newRide.distanceToGateMeters || 0),
      studentsWaiting: String(newRide.studentsWaiting || 1),
      assignedCartId: String(newRide.assignedCartId),
      assignedCartName: String(newRide.assignedCartName),
      timestamp: String(newRide.timestamp),
      title: `🚨 URGENT ${newRide.requesterType} REQUEST`,
      body: `Pickup: ${newRide.pickupLocation}`
    },
    android: {
      priority: 'high',
      ttl: 0
    }
  };

  let sendResult = null;

  if (isFirebaseAdminInitialized) {
    let directSendSuccessful = false;

    // 1. Direct Token Dispatch (if token available)
    if (driverToken) {
      try {
        const directPayload = { ...fcmPayload, token: driverToken };
        delete directPayload.topic;
        const response = await getMessagingService().send(directPayload);
        console.log(`[CAMPUS_RIDE_AUDIT] 8. FCM_SEND_SUCCESS (direct token): messageId=${response}, requestId=${rideId}, targetCart=${effectiveCartId}`);
        sendResult = { success: true, messageId: response, code: 'FCM_SEND_SUCCESS' };
        directSendSuccessful = true;

        admin.firestore().collection('ride_requests').doc(rideId).update({
          fcmStatus: 'SENT',
          fcmMessageId: response,
          fcmSentAt: Date.now()
        }).catch(_ => {});
      } catch (err) {
        console.warn(`[CAMPUS_RIDE_AUDIT] Direct FCM send to driver token failed: code=${err.code || 'UNKNOWN'}, error=${err.message}. Purging stale token and falling back to topic...`);
        fcmTokensStore.delete(effectiveCartId);
        fcmTokensStore.delete('DRIVER');
        fcmTokensStore.delete(targetDriverId);
        admin.firestore().collection('drivers').doc(effectiveCartId).update({
          fcmToken: admin.firestore.FieldValue.delete()
        }).catch(_ => {});
      }
    }

    // 2. Topic Dispatch (if no direct token or direct send failed)
    if (!directSendSuccessful) {
      try {
        console.log(`[FCM] Dispatching ride request to topic 'driver_${effectiveCartId}'...`);
        const cartTopicPayload = { ...fcmPayload, topic: `driver_${effectiveCartId}` };
        delete cartTopicPayload.token;
        const topicResp = await getMessagingService().send(cartTopicPayload);
        console.log(`[CAMPUS_RIDE_AUDIT] 8. FCM_SEND_SUCCESS (cart topic driver_${effectiveCartId}): messageId=${topicResp}`);
        sendResult = { success: true, messageId: topicResp, code: 'FCM_SEND_SUCCESS_CART_TOPIC' };

        admin.firestore().collection('ride_requests').doc(rideId).update({
          fcmStatus: 'SENT',
          fcmMessageId: topicResp,
          fcmSentAt: Date.now()
        }).catch(_ => {});
      } catch (cartTopicErr) {
        console.warn(`[FCM] Cart topic driver_${effectiveCartId} failed (${cartTopicErr.message}), retrying general topic 'drivers'...`);
        try {
          const generalTopicPayload = { ...fcmPayload, topic: 'drivers' };
          delete generalTopicPayload.token;
          const genResp = await getMessagingService().send(generalTopicPayload);
          console.log(`[CAMPUS_RIDE_AUDIT] 8. FCM_SEND_SUCCESS (general drivers topic): messageId=${genResp}`);
          sendResult = { success: true, messageId: genResp, code: 'FCM_SEND_SUCCESS_GENERAL_TOPIC' };

          admin.firestore().collection('ride_requests').doc(rideId).update({
            fcmStatus: 'SENT',
            fcmMessageId: genResp,
            fcmSentAt: Date.now()
          }).catch(_ => {});
        } catch (genErr) {
          console.error(`[CAMPUS_RIDE_AUDIT] 9. FCM_SEND_FAILURE: All FCM delivery channels failed: ${genErr.message}`);
          sendResult = { success: false, error: genErr.message, code: genErr.code || 'FCM_SEND_ERROR' };
        }
      }
    }
  } else {
    console.warn('[FCM] Firebase Admin offline / credentials unavailable on backend');
    sendResult = { success: false, error: 'Firebase Admin credentials offline', code: 'FCM_AUTHENTICATION_ERROR' };
  }

  // Return truthful response
  const isFcmDelivered = Boolean(sendResult && sendResult.success);
  return res.status(isFcmDelivered ? 201 : 200).json({
    success: isFcmDelivered,
    message: isFcmDelivered ? 'Ride request created and driver notified' : 'Ride request created but driver could not be notified',
    ride: newRide,
    fcmResult: sendResult,
    error: isFcmDelivered ? null : (sendResult?.error || 'Failed to dispatch FCM notification to driver')
  });
});

app.get('/api/rides', rateLimiter(60, 60000), (req, res) => {
  const { status, requesterType, limit } = req.query;
  let list = Array.from(ridesStore.values());

  if (status) {
    list = list.filter(r => r.status.toUpperCase() === status.toUpperCase());
  }
  if (requesterType) {
    list = list.filter(r => r.requesterType.toUpperCase() === requesterType.toUpperCase());
  }

  list.sort((a, b) => b.timestamp - a.timestamp);

  if (limit) {
    list = list.slice(0, Math.min(100, Math.max(1, Number(limit))));
  }

  res.json({
    success: true,
    count: list.length,
    rides: list
  });
});

app.get('/api/rides/my-rides', rateLimiter(60, 60000), (req, res) => {
  const list = Array.from(ridesStore.values()).sort((a, b) => b.timestamp - a.timestamp);
  res.json({
    success: true,
    count: list.length,
    rides: list
  });
});

app.get('/api/rides/:id', (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }
  res.json({ success: true, ride });
});

app.post('/api/rides/:id/accept', rateLimiter(30, 60000), async (req, res) => {
  const rideId = req.params.id;
  const ride = ridesStore.get(rideId);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  // Atomic Concurrency Protection: Only PENDING requests can be accepted
  if (ride.status !== 'PENDING') {
    return res.status(409).json({
      success: false,
      error: `Ride request is no longer available (current status: ${ride.status}). Already claimed by another driver.`
    });
  }

  ride.status = 'ACCEPTED';
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  if (ride.assignedCartId && cartsStore.has(ride.assignedCartId)) {
    const cart = cartsStore.get(ride.assignedCartId);
    cart.isAvailable = false;
    cart.driverStatus = 'Occupied';
    cartsStore.set(ride.assignedCartId, cart);
  }

  if (isFirebaseAdminInitialized) {
    try {
      const rideRef = admin.firestore().collection('ride_requests').doc(ride.id);
      await admin.firestore().runTransaction(async (t) => {
        const snap = await t.get(rideRef);
        if (snap.exists && snap.data().status !== 'PENDING') {
          throw new Error('ALREADY_ACCEPTED');
        }
        t.update(rideRef, {
          status: 'ACCEPTED',
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      });
    } catch (err) {
      if (err.message === 'ALREADY_ACCEPTED') {
        return res.status(409).json({
          success: false,
          error: 'Ride request was already accepted by another driver.'
        });
      }
      console.log('[Firestore] accept notice:', err.message);
    }
  }

  res.json({ success: true, message: 'Ride request accepted', ride });
});

app.post('/api/rides/:id/decline', async (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.status = 'REJECTED';
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  if (ride.assignedCartId && cartsStore.has(ride.assignedCartId)) {
    const cart = cartsStore.get(ride.assignedCartId);
    cart.isAvailable = true;
    cart.driverStatus = 'Available';
    cartsStore.set(ride.assignedCartId, cart);
  }

  if (isFirebaseAdminInitialized) {
    try {
      await admin.firestore().collection('ride_requests').doc(ride.id).update({
        status: 'REJECTED',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    } catch (err) {
      console.log('[Firestore] decline notice:', err.message);
    }
  }

  res.json({ success: true, message: 'Ride request rejected', ride });
});

app.post('/api/rides/:id/complete', async (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.status = 'COMPLETED';
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  if (ride.assignedCartId && cartsStore.has(ride.assignedCartId)) {
    const cart = cartsStore.get(ride.assignedCartId);
    cart.isAvailable = true;
    cart.driverStatus = 'Available';
    cartsStore.set(ride.assignedCartId, cart);
  }

  if (isFirebaseAdminInitialized) {
    try {
      await admin.firestore().collection('ride_requests').doc(ride.id).update({
        status: 'COMPLETED',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    } catch (err) {
      console.log('[Firestore] complete notice:', err.message);
    }
  }

  res.json({ success: true, message: 'Ride marked as completed', ride });
});

app.post('/api/rides/:id/cancel', async (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.status = 'CANCELLED';
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  res.json({ success: true, message: 'Ride cancelled successfully', ride });
});

app.post('/api/rides/:id/join', (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.passengerCount = (ride.passengerCount || 1) + 1;
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  res.json({ success: true, message: 'Joined ride successfully', ride });
});

// -------------------------------------------------------------
// 4. Golf Cart & Driver Telemetry REST Endpoints
// -------------------------------------------------------------
app.get('/api/carts', rateLimiter(60, 60000), (req, res) => {
  res.json({
    success: true,
    count: cartsStore.size,
    carts: Array.from(cartsStore.values())
  });
});

app.post('/api/carts/location', rateLimiter(60, 60000), (req, res) => {
  const { cartId, latitude, longitude, speedKmH, bearing } = req.body;
  const id = sanitizeString(cartId, 32) || 'cart_1';
  const cart = cartsStore.get(id) || {
    cartId: id,
    cartName: `Golf Cart ${id}`,
    batteryLevel: 90,
    driverStatus: 'Available',
    isAvailable: true
  };

  const numLat = Number(latitude);
  const numLng = Number(longitude);

  if (!isValidCampusCoord(numLat, numLng)) {
    return res.status(400).json({ success: false, error: 'Invalid telemetry: coordinates outside campus boundary' });
  }

  const speed = Math.max(0, Math.min(45, Number(speedKmH || 0)));
  const dir = Math.max(0, Math.min(360, Number(bearing || 0)));

  cart.latitude = numLat;
  cart.longitude = numLng;
  cart.speedKmH = speed;
  cart.bearing = dir;
  cart.status = speed > 0 ? 'MOVING' : 'HALTED';
  cart.lastUpdatedMillis = Date.now();
  cart.lastHeartbeatMillis = Date.now();
  cart.locationTimestampMillis = Date.now();

  cartsStore.set(id, cart);
  res.json({ success: true, message: 'Cart telemetry updated successfully', cart });
});

app.post('/api/carts/heartbeat', (req, res) => {
  const { cartId, driverStatus, isOnline, isAvailable } = req.body;
  const id = sanitizeString(cartId, 32) || 'cart_1';
  const cart = cartsStore.get(id) || {
    cartId: id,
    cartName: `Golf Cart ${id}`,
    batteryLevel: 90
  };

  const now = Date.now();
  cart.lastHeartbeatMillis = now;
  cart.lastUpdatedMillis = now;
  if (driverStatus) cart.driverStatus = driverStatus;
  if (typeof isAvailable === 'boolean') cart.isAvailable = isAvailable;
  cart.status = (cart.driverStatus === 'Off Duty' || isOnline === false) ? 'OFFLINE' : 'HALTED';

  cartsStore.set(id, cart);
  res.json({ success: true, message: 'Heartbeat recorded successfully', cart });
});

app.post('/api/carts/duty-status', (req, res) => {
  const { cartId, driverStatus } = req.body;
  const id = sanitizeString(cartId, 32) || 'cart_1';
  const cart = cartsStore.get(id);

  if (!cart) {
    return res.status(404).json({ success: false, error: 'Cart not found' });
  }

  const validStatuses = ['Available', 'Occupied', 'Lunch Break', 'Off Duty', 'Unavailable'];
  const sanitizedStatus = validStatuses.includes(driverStatus) ? driverStatus : 'Available';

  cart.driverStatus = sanitizedStatus;
  cart.isAvailable = (sanitizedStatus === 'Available');
  cart.status = sanitizedStatus === 'Available' ? 'HALTED' : 'OFFLINE';
  cart.lastUpdatedMillis = Date.now();

  cartsStore.set(id, cart);
  res.json({ success: true, message: 'Driver duty status updated', cart });
});

// -------------------------------------------------------------
// 5. Notifications & FCM REST Endpoints
// -------------------------------------------------------------
app.get('/api/notifications/status', (req, res) => {
  const token = fcmTokensStore.get('DRIVER') || fcmTokensStore.get('cart_1') || '';
  res.json({
    success: true,
    firebaseAdminActive: isFirebaseAdminInitialized,
    credentialMethod: adminInitMethod,
    credentialError: adminInitError ? adminInitError.replace(/(?:-----BEGIN PRIVATE KEY-----[\s\S]*?-----END PRIVATE KEY-----)/gi, '[REDACTED_KEY]') : null,
    hasServiceAccountEnv: Boolean(process.env.FIREBASE_SERVICE_ACCOUNT || process.env.FIREBASE_SERVICE_ACCOUNT_JSON || process.env.FIREBASE_SERVICE_ACCOUNT_KEY || process.env.FIREBASE_CREDENTIALS || process.env.GOOGLE_APPLICATION_CREDENTIALS || process.env.FIREBASE_SERVICE_ACCOUNT_BASE64),
    driverTokenPresent: Boolean(token),
    driverTokenPreview: token ? `${token.substring(0, 10)}...` : null
  });
});

app.post('/api/notifications/fcm-token', async (req, res) => {
  const { role, userId, fcmToken, cartId } = req.body;

  if (!fcmToken || typeof fcmToken !== 'string' || fcmToken.startsWith('fallback_') || fcmToken.startsWith('device_') || fcmToken.length < 20) {
    console.log('[FCM TOKEN] Rejected invalid/fallback token:', fcmToken);
    return res.status(400).json({ success: false, error: 'Valid FCM registration token required. Fallback device IDs not accepted.' });
  }

  const normalizedRole = String(role || 'DRIVER').toUpperCase();
  const assignedCart = cartId || (userId && userId.startsWith('cart_') ? userId : 'cart_1');
  const preview = `${fcmToken.substring(0, 10)}...`;

  console.log(`[FCM TOKEN] Registering token for role=${normalizedRole}, userId=${userId || 'none'}, cartId=${assignedCart}, token=${preview}`);

  if (normalizedRole === 'DRIVER') {
    // Strictly register DRIVER tokens to their assigned cart
    fcmTokensStore.set(assignedCart, fcmToken);
    if (assignedCart === 'cart_1' || !fcmTokensStore.has('DRIVER')) {
      fcmTokensStore.set('DRIVER', fcmToken);
    }
    if (userId) fcmTokensStore.set(userId, fcmToken);
    console.log(`[FCM TOKEN] DRIVER token stored for ${assignedCart}`);

    // Asynchronously & non-blockingly persist to Firestore drivers/{cartId} and fcm_tokens
    if (isFirebaseAdminInitialized) {
      Promise.all([
        admin.firestore().collection('drivers').doc(assignedCart).set({
          fcmToken,
          cartId: assignedCart,
          lastUpdatedMillis: Date.now()
        }, { merge: true }),
        admin.firestore().collection('fcm_tokens').doc(`driver_${assignedCart}`).set({
          fcmToken,
          role: 'DRIVER',
          cartId: assignedCart,
          userId: userId || assignedCart,
          updatedAt: Date.now()
        }, { merge: true })
      ]).then(() => {
        console.log(`[FCM TOKEN] Driver token persisted to Firestore drivers/${assignedCart}`);
      }).catch(err => {
        console.warn('[FCM TOKEN] Non-blocking Firestore driver token sync notice:', err.message);
      });
    }
  } else {
    // STUDENT or FACULTY token - DO NOT touch DRIVER or cart_1!
    const key = userId || `${normalizedRole.toLowerCase()}_device`;
    fcmTokensStore.set(key, fcmToken);
    fcmTokensStore.set(normalizedRole, fcmToken);
    console.log(`[FCM TOKEN] Non-driver token stored for ${key} and ${normalizedRole} (driver tokens untouched)`);

    if (isFirebaseAdminInitialized) {
      admin.firestore().collection('fcm_tokens').doc(key).set({
        fcmToken,
        role: normalizedRole,
        userId: key,
        updatedAt: Date.now()
      }, { merge: true }).then(() => {
        console.log(`[FCM TOKEN] Non-driver token persisted to Firestore fcm_tokens/${key}`);
      }).catch(err => {
        console.warn('[FCM TOKEN] Non-blocking Firestore token sync notice:', err.message);
      });
    }
  }

  res.json({
    success: true,
    message: 'FCM token registered successfully',
    role: normalizedRole,
    tokenPreview: preview
  });
});

app.post('/api/notifications/dispatch', async (req, res) => {
  const { targetTopic, targetToken, title, body, rideId, requesterType, pickupLocation, studentName, assignedCartId } = req.body;

  const payload = {
    data: {
      type: 'RIDE_REQUEST',
      requestId: String(rideId || `ride_${Date.now()}`),
      rideId: String(rideId || `ride_${Date.now()}`),
      requesterType: String(requesterType || 'STUDENT'),
      passengerName: String(studentName || 'Passenger'),
      studentName: String(studentName || 'Passenger'),
      pickupLocation: String(pickupLocation || 'IIIT Bhagalpur Main Gate'),
      assignedCartId: String(assignedCartId || 'cart_1'),
      title: String(title || `🚨 URGENT RIDE REQUEST`),
      body: String(body || 'Pickup Location: IIIT Bhagalpur Main Gate')
    },
    android: {
      priority: 'high',
      ttl: 0
    }
  };

  let tokenToUse = targetToken || fcmTokensStore.get('DRIVER') || fcmTokensStore.get('cart_1');

  if (!tokenToUse && isFirebaseAdminInitialized) {
    try {
      const doc = await admin.firestore().collection('drivers').doc('cart_1').get();
      if (doc.exists && doc.data().fcmToken) {
        tokenToUse = doc.data().fcmToken;
        fcmTokensStore.set('DRIVER', tokenToUse);
        fcmTokensStore.set('cart_1', tokenToUse);
        console.log(`[FCM Backend] Restored driver token from Firestore drivers/cart_1`);
      }
    } catch (e) {
      console.error(`[FCM Backend] Token restore from Firestore error:`, e.message);
    }
  }

  if (tokenToUse) {
    payload.token = tokenToUse;
  } else {
    payload.topic = targetTopic || 'drivers';
  }

  console.log('[FCM] Attempting notification send');
  console.log(`[FCM] Token present: ${Boolean(tokenToUse)}`);
  console.log(`[FCM] Firebase Admin initialized: ${isFirebaseAdminInitialized}`);

  if (isFirebaseAdminInitialized) {
    try {
      const response = await getMessagingService().send(payload);
      console.log(`[FCM] Firebase send SUCCESS: ${response}`);
      return res.json({
        success: true,
        messageId: response,
        targetedToken: tokenToUse ? `${tokenToUse.substring(0, 10)}...` : null
      });
    } catch (err) {
      console.error('[FCM] Firebase send FAILED');
      console.error(`[FCM] Error code: ${err.code || 'UNKNOWN'}`);
      console.error(`[FCM] Error message: ${err.message || 'No error message'}`);
      return res.status(500).json({ success: false, error: err.message, code: err.code });
    }
  }

  console.warn('[FCM] Firebase Admin initialization failed: credentials unavailable');
  res.status(500).json({
    success: false,
    error: 'Firebase Admin initialization failed: credentials unavailable'
  });
});

// -------------------------------------------------------------
// 6. Chat & Messaging REST Endpoints
// -------------------------------------------------------------
app.get('/api/chat/:rideId', (req, res) => {
  const rideId = req.params.rideId;
  const messages = chatStore.get(rideId) || [
    {
      id: 'msg_init',
      rideId,
      senderId: 'driver_1',
      senderRole: 'DRIVER',
      text: 'Hello! I am on my way to your pickup location.',
      timestamp: Date.now() - 30000
    }
  ];
  res.json({ success: true, count: messages.length, messages });
});

app.post('/api/chat/:rideId', (req, res) => {
  const rideId = req.params.rideId;
  const { senderId, senderRole, text } = req.body;

  if (!text) {
    return res.status(400).json({ success: false, error: 'Message text is required' });
  }

  const list = chatStore.get(rideId) || [];
  const newMsg = {
    id: `msg_${Date.now()}`,
    rideId,
    senderId: senderId || 'usr_passenger',
    senderRole: senderRole || 'STUDENT',
    text,
    timestamp: Date.now()
  };

  list.push(newMsg);
  chatStore.set(rideId, list);
  res.status(201).json({ success: true, message: newMsg });
});

// -------------------------------------------------------------
// 7. Operational Analytics Endpoint
// -------------------------------------------------------------
app.get('/api/analytics', (req, res) => {
  const allRides = Array.from(ridesStore.values());
  const accepted = allRides.filter(r => r.status === 'ACCEPTED' || r.status === 'COMPLETED').length;
  const pending = allRides.filter(r => r.status === 'PENDING').length;
  const rejected = allRides.filter(r => r.status === 'REJECTED').length;

  res.json({
    success: true,
    analytics: {
      totalRequests: allRides.length,
      acceptedRequests: accepted,
      pendingRequests: pending,
      rejectedRequests: rejected,
      activeFleetCount: Array.from(cartsStore.values()).filter(c => c.isAvailable).length,
      geofenceRadiusMeters: 70,
      gateCoordinates: '25.2531616, 87.0370730',
      serverUptimeSeconds: Math.floor(process.uptime())
    }
  });
});

app.listen(PORT, () => {
  console.log(`Campus Ride Express Server listening on port ${PORT}`);
});
