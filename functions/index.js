const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");

// Initialize Firebase Admin SDK
if (!admin.apps || admin.apps.length === 0) {
  try {
    admin.initializeApp();
  } catch (e) {
    // In restricted test environments, credentials might be provided via mock
  }
}

/**
 * Production Firebase Cloud Function for Campus Ride
 * Triggers on: ride_requests/{requestId}
 * 
 * Guarantees:
 * 1. High-priority direct FCM messaging ONLY to the assigned driver's token (NO broadcast topics).
 * 2. Idempotent execution (prevents duplicate alerts if fcmStatus === "SENT").
 * 3. Strict FCM safety invariant: Missing, expired, or invalid FCM tokens MUST NEVER affect driver
 *    operational availability, online status, cart telemetry freshness, or ride request status.
 * 4. Strict status update ordering (fcmStatus = "SENT" only after successful send).
 * 5. Comprehensive structured JSON logging (without exposing sensitive tokens).
 * 6. Automated retry with exponential backoff for transient failures only.
 */
async function handleRideRequestCreated(snapshot, requestId, dependencies = {}) {
  const currentAdmin = dependencies.admin || admin;
  const currentLogger = dependencies.logger || logger;
  const deliveryTimestamp = dependencies.deliveryTimestamp || new Date().toISOString();

  if (!snapshot) {
    currentLogger.warn("No snapshot data found for Firestore event");
    return null;
  }

  const rideRequest = snapshot.data() || {};
  const assignedCartId = rideRequest.assignedCartId || "cart_1";
  const pickupLocation = rideRequest.pickupLocation || "Main Gate";
  const studentName = rideRequest.studentName || "Campus Passenger";
  const requesterType = rideRequest.requesterType || "STUDENT";
  const distanceToGateMeters = String(rideRequest.distanceToGateMeters || 0);

  // 1. Prevent duplicate notifications
  if (rideRequest.fcmStatus === "SENT") {
    currentLogger.info(`[DUPLICATE_SKIP] Ride alert already sent for request ${requestId}. Skipping duplicate dispatch.`, {
      requestId,
      assignedCartId,
      notificationStatus: "SENT",
      deliveryTimestamp
    });
    return { success: true, duplicateSkipped: true };
  }

  // Fetch assigned driver document from Firestore `drivers/{assignedCartId}`
  const driverDocRef = currentAdmin.firestore().collection("drivers").doc(assignedCartId);
  const driverDoc = await driverDocRef.get();

  const driverData = driverDoc.exists ? driverDoc.data() : {};
  const driverId = driverData.driverId || driverData.driverName || `driver_${assignedCartId}`;
  const targetFcmToken = driverData.fcmToken || null;

  // 2. Handle missing driver FCM token - notification skipped safely
  // CORE INVARIANT: Missing FCM token does NOT mean driver is offline or unavailable.
  // Physical presence and ride availability remain completely untouched.
  if (!targetFcmToken) {
    currentLogger.info(`[NO_FCM_TOKEN] No FCM token registered for cart ${assignedCartId} (driverId: ${driverId}); skipping push notification dispatch. Operational state remains untouched.`, {
      requestId,
      assignedCartId,
      driverId,
      notificationStatus: "SKIPPED_NO_TOKEN",
      deliveryTimestamp
    });

    // Update Firestore ride request notification tracking only (do NOT alter driver status or cancel ride)
    await snapshot.ref.update({
      fcmStatus: "SKIPPED_NO_TOKEN",
      updatedAt: currentAdmin.firestore.FieldValue.serverTimestamp()
    });

    return { success: true, skipped: true, reason: "NO_FCM_TOKEN" };
  }

  // Prepare direct high-priority FCM payload targeting assigned driver
  const message = {
    token: targetFcmToken,
    data: {
      type: "RIDE_REQUEST",
      requestId: String(requestId),
      rideId: String(requestId),
      requesterType: String(requesterType),
      passengerName: String(studentName),
      studentName: String(studentName),
      pickupLocation: String(pickupLocation),
      dropoffLocation: String(rideRequest.dropoffLocation || "Campus"),
      distanceToGateMeters: String(distanceToGateMeters || "0"),
      assignedCartId: String(assignedCartId),
      assignedCartName: String(rideRequest.assignedCartName || "Golf Cart"),
      timestamp: String(rideRequest.timestamp || Date.now()),
      title: `🚨 URGENT ${requesterType} RIDE REQUEST`,
      body: `Pickup Location: ${pickupLocation}`
    },
    android: {
      priority: "high",
      ttl: 0
    }
  };

  try {
    currentLogger.info(`[SENDING_FCM] Dispatching high-priority direct FCM notification to driver ${driverId}`, {
      requestId,
      assignedCartId,
      driverId,
      targetFcmToken: `${targetFcmToken.substring(0, 12)}...`,
      deliveryTimestamp
    });

    // Send FCM message via Firebase Admin SDK
    const response = await currentAdmin.messaging().send(message);

    // Update fcmStatus = "SENT" ONLY AFTER successful FCM response
    await snapshot.ref.update({
      fcmStatus: "SENT",
      fcmMessageId: response,
      fcmSentAt: currentAdmin.firestore.FieldValue.serverTimestamp(),
      status: "DISPATCHED"
    });

    // Cloud Function logging
    currentLogger.info(`[FCM_SUCCESS] Successfully sent FCM message to assigned driver`, {
      requestId,
      assignedCartId,
      driverId,
      notificationStatus: "SENT",
      messageId: response,
      deliveryTimestamp: new Date().toISOString()
    });

    return { success: true, messageId: response };

  } catch (error) {
    const errorCode = error.code || "unknown";
    const errorMessage = error.message || String(error);

    // 3. Handle invalid or expired FCM tokens
    const isInvalidTokenError =
      errorCode === "messaging/registration-token-not-registered" ||
      errorCode === "messaging/invalid-registration-token" ||
      errorCode === "messaging/mismatched-credential" ||
      errorCode === "messaging/invalid-argument" ||
      errorMessage.includes("not-registered") ||
      errorMessage.includes("invalid-registration-token");

    if (isInvalidTokenError) {
      currentLogger.warn(`[INVALID_TOKEN] FCM token for cart ${assignedCartId} (driverId: ${driverId}) is invalid or expired. Cleared invalid token from driver document; operational status remains untouched.`, {
        requestId,
        assignedCartId,
        driverId,
        notificationStatus: "FAILED_INVALID_TOKEN",
        errorCode,
        error: errorMessage,
        deliveryTimestamp: new Date().toISOString()
      });

      // Clean up ONLY the invalid FCM token in Firestore.
      // INVARIANT: DO NOT mark driver offline, DO NOT set isAvailable false, DO NOT touch status.
      if (driverDoc.exists) {
        await driverDocRef.update({
          fcmToken: currentAdmin.firestore.FieldValue.delete(),
          updatedAt: currentAdmin.firestore.FieldValue.serverTimestamp()
        });
      }

      // Update ride request notification tracking only (do NOT alter ride operational status)
      await snapshot.ref.update({
        fcmStatus: "FAILED_INVALID_TOKEN",
        fcmError: errorMessage,
        updatedAt: currentAdmin.firestore.FieldValue.serverTimestamp()
      });

      // Permanent error: DO NOT throw so function won't retry invalid token
      return { success: false, reason: "INVALID_TOKEN", error: errorMessage };
    }

    // 4. Retry transient failures with exponential backoff
    currentLogger.error(`[TRANSIENT_FAILURE] FCM dispatch error for request ${requestId}. Retrying via Cloud Functions backoff.`, {
      requestId,
      assignedCartId,
      driverId,
      notificationStatus: "RETRYING",
      errorCode,
      error: errorMessage,
      deliveryTimestamp: new Date().toISOString()
    });

    await snapshot.ref.update({
      fcmStatus: "RETRYING",
      fcmError: errorMessage,
      fcmRetryAt: currentAdmin.firestore.FieldValue.serverTimestamp()
    });

    // Re-throw error to trigger Cloud Functions retry mechanism
    throw error;
  }
}

const sendDriverRideNotification = onDocumentCreated(
  {
    document: "ride_requests/{requestId}",
    retry: true
  },
  async (event) => {
    return handleRideRequestCreated(event.data, event.params.requestId);
  }
);

module.exports = {
  sendDriverRideNotification,
  handleRideRequestCreated
};
