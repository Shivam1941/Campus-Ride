const test = require("node:test");
const assert = require("node:assert/strict");
const { handleRideRequestCreated } = require("../index.js");

function createMockEnvironment(driverData, messagingMock) {
  const driverUpdates = [];
  const snapshotUpdates = [];
  const logEntries = { info: [], warn: [], error: [] };

  const mockDriverDocRef = {
    get: async () => ({
      exists: Boolean(driverData),
      data: () => ({ ...driverData })
    }),
    update: async (updates) => {
      driverUpdates.push(updates);
      Object.assign(driverData, updates);
    }
  };

  const mockSnapshotRef = {
    update: async (updates) => {
      snapshotUpdates.push(updates);
    }
  };

  const mockAdmin = {
    firestore: () => ({
      collection: (colName) => {
        if (colName === "drivers") {
          return {
            doc: (docId) => mockDriverDocRef
          };
        }
        throw new Error(`Unexpected collection: ${colName}`);
      }
    }),
    messaging: () => ({
      send: messagingMock || (async () => "mock-message-id-12345")
    })
  };

  mockAdmin.firestore.FieldValue = {
    delete: () => "__DELETE_FIELD__",
    serverTimestamp: () => "__SERVER_TIMESTAMP__"
  };

  const mockLogger = {
    info: (msg, meta) => logEntries.info.push({ msg, meta }),
    warn: (msg, meta) => logEntries.warn.push({ msg, meta }),
    error: (msg, meta) => logEntries.error.push({ msg, meta })
  };

  return {
    mockAdmin,
    mockLogger,
    mockSnapshotRef,
    driverUpdates,
    snapshotUpdates,
    logEntries,
    driverData
  };
}

test("CASE A: Driver has valid GPS/telemetry + FCM token -> notification sent, operational status unchanged", async () => {
  const driverData = {
    driverId: "driver_cart_1",
    driverName: "Ramesh",
    fcmToken: "valid_fcm_token_xyz_987654321",
    isOnline: true,
    isAvailable: true,
    status: "Available",
    latitude: 12.8235,
    longitude: 80.0452,
    speedKmh: 15.0,
    lastHeartbeatMillis: Date.now()
  };

  const env = createMockEnvironment(driverData);

  const snapshot = {
    data: () => ({
      assignedCartId: "cart_1",
      pickupLocation: "Main Gate",
      studentName: "Alex",
      requesterType: "STUDENT",
      fcmStatus: "PENDING"
    }),
    ref: env.mockSnapshotRef
  };

  const result = await handleRideRequestCreated(snapshot, "req_001", {
    admin: env.mockAdmin,
    logger: env.mockLogger
  });

  assert.equal(result.success, true);
  assert.equal(result.messageId, "mock-message-id-12345");

  // Operational state of the driver must NOT have been updated
  assert.equal(env.driverUpdates.length, 0);
  assert.equal(driverData.isOnline, true);
  assert.equal(driverData.isAvailable, true);
  assert.equal(driverData.status, "Available");
  assert.equal(driverData.latitude, 12.8235);
  assert.equal(driverData.longitude, 80.0452);

  // Snapshot has fcmStatus: SENT and status: DISPATCHED
  assert.equal(env.snapshotUpdates.length, 1);
  assert.equal(env.snapshotUpdates[0].fcmStatus, "SENT");
  assert.equal(env.snapshotUpdates[0].status, "DISPATCHED");
});

test("CASE B: Driver has valid GPS/telemetry + NO FCM token -> notification skipped, driver/cart NOT offline or unavailable", async () => {
  const driverData = {
    driverId: "driver_cart_1",
    driverName: "Ramesh",
    fcmToken: null, // NO TOKEN
    isOnline: true,
    isAvailable: true,
    status: "Available",
    latitude: 12.8235,
    longitude: 80.0452,
    speedKmh: 12.0,
    lastHeartbeatMillis: Date.now()
  };

  const env = createMockEnvironment(driverData);

  const snapshot = {
    data: () => ({
      assignedCartId: "cart_1",
      pickupLocation: "Library",
      studentName: "Priya",
      requesterType: "STUDENT",
      fcmStatus: "PENDING"
    }),
    ref: env.mockSnapshotRef
  };

  const result = await handleRideRequestCreated(snapshot, "req_002", {
    admin: env.mockAdmin,
    logger: env.mockLogger
  });

  // Notification is skipped safely
  assert.equal(result.success, true);
  assert.equal(result.skipped, true);
  assert.equal(result.reason, "NO_FCM_TOKEN");

  // CORE INVARIANT: Driver MUST NOT be marked offline, unavailable, or given unavailability reason
  assert.equal(env.driverUpdates.length, 0, "Driver document must NEVER be mutated due to missing FCM token");
  assert.equal(driverData.isOnline, true, "Driver must remain online");
  assert.equal(driverData.isAvailable, true, "Driver must remain available");
  assert.equal(driverData.status, "Available", "Driver status must remain Available");
  assert.equal(driverData.unavailabilityReason, undefined);

  // Ride request must NOT be cancelled with NO_DRIVER_AVAILABLE
  assert.equal(env.snapshotUpdates.length, 1);
  assert.equal(env.snapshotUpdates[0].fcmStatus, "SKIPPED_NO_TOKEN");
  assert.equal(env.snapshotUpdates[0].status, undefined, "Ride request status must not be overridden to NO_DRIVER_AVAILABLE");
  assert.equal(env.snapshotUpdates[0].studentNotification, undefined);
});

test("CASE C: Driver has valid GPS/telemetry + INVALID/UNREGISTERED FCM token -> token cleared, operational state intact", async () => {
  const driverData = {
    driverId: "driver_cart_1",
    driverName: "Ramesh",
    fcmToken: "invalid_unregistered_token_12345",
    isOnline: true,
    isAvailable: true,
    status: "Available",
    latitude: 12.8235,
    longitude: 80.0452,
    lastHeartbeatMillis: Date.now()
  };

  const invalidTokenError = new Error("Registration token not registered");
  invalidTokenError.code = "messaging/registration-token-not-registered";

  const env = createMockEnvironment(driverData, async () => {
    throw invalidTokenError;
  });

  const snapshot = {
    data: () => ({
      assignedCartId: "cart_1",
      pickupLocation: "Admin Block",
      studentName: "John",
      requesterType: "STUDENT",
      fcmStatus: "PENDING"
    }),
    ref: env.mockSnapshotRef
  };

  const result = await handleRideRequestCreated(snapshot, "req_003", {
    admin: env.mockAdmin,
    logger: env.mockLogger
  });

  assert.equal(result.success, false);
  assert.equal(result.reason, "INVALID_TOKEN");

  // Backend clears ONLY the invalid FCM token
  assert.equal(env.driverUpdates.length, 1);
  assert.equal(env.driverUpdates[0].fcmToken, "__DELETE_FIELD__");
  assert.equal(env.driverUpdates[0].isOnline, undefined, "isOnline must not be set to false");
  assert.equal(env.driverUpdates[0].isAvailable, undefined, "isAvailable must not be set to false");
  assert.equal(env.driverUpdates[0].status, undefined, "status must not be set to OFFLINE");
  assert.equal(env.driverUpdates[0].offlineReason, undefined);

  // Operational fields remain online and available
  assert.equal(driverData.isOnline, true);
  assert.equal(driverData.isAvailable, true);
  assert.equal(driverData.status, "Available");

  // Ride request must NOT be aborted to NO_DRIVER_AVAILABLE
  assert.equal(env.snapshotUpdates.length, 1);
  assert.equal(env.snapshotUpdates[0].fcmStatus, "FAILED_INVALID_TOKEN");
  assert.equal(env.snapshotUpdates[0].status, undefined, "Ride request status must not be aborted");
});

test("CASE D: Driver is genuinely offline according to telemetry/duty status + FCM token exists -> offline behavior preserved, FCM does not force online", async () => {
  const driverData = {
    driverId: "driver_cart_1",
    driverName: "Ramesh",
    fcmToken: "valid_fcm_token_xyz_987654321",
    isOnline: false,
    isAvailable: false,
    status: "OFFLINE",
    driverStatus: "Off Duty",
    lastHeartbeatMillis: 0
  };

  const env = createMockEnvironment(driverData);

  const snapshot = {
    data: () => ({
      assignedCartId: "cart_1",
      pickupLocation: "Main Gate",
      studentName: "Alex",
      requesterType: "STUDENT",
      fcmStatus: "PENDING"
    }),
    ref: env.mockSnapshotRef
  };

  await handleRideRequestCreated(snapshot, "req_004", {
    admin: env.mockAdmin,
    logger: env.mockLogger
  });

  // Notification dispatch must NOT force the offline driver to become online or available
  assert.equal(env.driverUpdates.length, 0);
  assert.equal(driverData.isOnline, false, "Offline driver must remain offline");
  assert.equal(driverData.isAvailable, false, "Offline driver must remain unavailable");
  assert.equal(driverData.status, "OFFLINE");
  assert.equal(driverData.driverStatus, "Off Duty");
});

test("CASE E: Driver is operationally unavailable (Occupied) + FCM token exists -> operational state preserved, FCM does not override availability", async () => {
  const driverData = {
    driverId: "driver_cart_1",
    driverName: "Ramesh",
    fcmToken: "valid_fcm_token_xyz_987654321",
    isOnline: true,
    isAvailable: false,
    status: "Occupied",
    driverStatus: "Occupied",
    lastHeartbeatMillis: Date.now()
  };

  const env = createMockEnvironment(driverData);

  const snapshot = {
    data: () => ({
      assignedCartId: "cart_1",
      pickupLocation: "Main Gate",
      studentName: "Alex",
      requesterType: "STUDENT",
      fcmStatus: "PENDING"
    }),
    ref: env.mockSnapshotRef
  };

  await handleRideRequestCreated(snapshot, "req_005", {
    admin: env.mockAdmin,
    logger: env.mockLogger
  });

  // Notification dispatch must NOT override availability
  assert.equal(env.driverUpdates.length, 0);
  assert.equal(driverData.isOnline, true);
  assert.equal(driverData.isAvailable, false, "Driver must remain unavailable (Occupied)");
  assert.equal(driverData.status, "Occupied");
  assert.equal(driverData.driverStatus, "Occupied");
});

test("CASE F: Idempotency - duplicate execution skips sending and leaves driver/ride request untouched", async () => {
  const driverData = {
    driverId: "driver_cart_1",
    fcmToken: "valid_fcm_token_xyz",
    isOnline: true,
    isAvailable: true
  };

  const env = createMockEnvironment(driverData);

  const snapshot = {
    data: () => ({
      assignedCartId: "cart_1",
      fcmStatus: "SENT" // Already sent
    }),
    ref: env.mockSnapshotRef
  };

  const result = await handleRideRequestCreated(snapshot, "req_006", {
    admin: env.mockAdmin,
    logger: env.mockLogger
  });

  assert.equal(result.success, true);
  assert.equal(result.duplicateSkipped, true);
  assert.equal(env.driverUpdates.length, 0);
  assert.equal(env.snapshotUpdates.length, 0);
});

test("CASE G: Transient network failure -> retries via backoff, operational driver state completely untouched", async () => {
  const driverData = {
    driverId: "driver_cart_1",
    fcmToken: "valid_fcm_token_xyz",
    isOnline: true,
    isAvailable: true,
    status: "Available"
  };

  const transientError = new Error("Connection reset by peer");
  transientError.code = "messaging/server-unavailable";

  const env = createMockEnvironment(driverData, async () => {
    throw transientError;
  });

  const snapshot = {
    data: () => ({
      assignedCartId: "cart_1",
      fcmStatus: "PENDING"
    }),
    ref: env.mockSnapshotRef
  };

  // Must re-throw to allow Cloud Functions runtime backoff retry
  await assert.rejects(async () => {
    await handleRideRequestCreated(snapshot, "req_007", {
      admin: env.mockAdmin,
      logger: env.mockLogger
    });
  }, { code: "messaging/server-unavailable" });

  // Driver operational state must NOT be touched
  assert.equal(env.driverUpdates.length, 0);
  assert.equal(driverData.isOnline, true);
  assert.equal(driverData.isAvailable, true);
  assert.equal(driverData.status, "Available");

  // Ride request marked for retry
  assert.equal(env.snapshotUpdates.length, 1);
  assert.equal(env.snapshotUpdates[0].fcmStatus, "RETRYING");
  assert.equal(env.snapshotUpdates[0].status, undefined, "Status must not be marked NO_DRIVER_AVAILABLE");
});
