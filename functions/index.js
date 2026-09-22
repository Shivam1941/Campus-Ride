const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");

// Initialize Firebase Admin SDK
admin.initializeApp();

/**
 * Production Firebase Cloud Function for Campus Ride
 * Triggers on: ride_requests/{requestId}
 * 
 * Guarantees:
 * 1. High-priority direct FCM messaging ONLY to the assigned driver's token (NO broadcast topics).
 * 2. Idempotent execution (prevents duplicate alerts if fcmStatus === "SENT").
 * 3. Graceful invalid token cleanup & driver status updates.
 * 4. Strict status update ordering (fcmStatus = "SENT" only after successful send).
 * 5. Comprehensive structured JSON logging.
 * 6. Automated retry with exponential backoff for transient failures.
 */
exports.sendDriverRideNotification = onDocumentCreated(
  {
    document: "ride_requests/{requestId}",
    retry: true
  },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) {
      logger.warn("No snapshot data found for Firestore event");
      return null;
    }

    const rideRequest = snapshot.data();
    const requestId = event.params.requestId;
    const deliveryTimestamp = new Date().toISOString();

    const assignedCartId = rideRequest.assignedCartId || "cart_1";
    const pickupLocation = rideRequest.pickupLocation || "Main Gate";
    const studentName = rideRequest.studentName || "Campus Passenger";
    const requesterType = rideRequest.requesterType || "STUDENT";
    const distanceToGateMeters = String(rideRequest.distanceToGateMeters || 0);

    // 2. Prevent duplicate notifications
    if (rideRequest.fcmStatus === "SENT") {
      logger.info(`[DUPLICATE_SKIP] Ride alert already sent for request ${requestId}. Skipping duplicate dispatch.`, {
        requestId,
        assignedCartId,
        notificationStatus: "SENT",
        deliveryTimestamp
      });
      return { success: true, duplicateSkipped: true };
    }

    // Fetch assigned driver document from Firestore `drivers/{assignedCartId}`
    const driverDocRef = admin.firestore().collection("drivers").doc(assignedCartId);
    const driverDoc = await driverDocRef.get();

    const driverData = driverDoc.exists ? driverDoc.data() : {};
    const driverId = driverData.driverId || driverData.driverName || `driver_${assignedCartId}`;
    const targetFcmToken = driverData.fcmToken || null;

    // 1. Handle missing driver FCM token - NO TOPIC BROADCAST FALLBACK
    if (!targetFcmToken) {
      logger.warn(`[NO_FCM_TOKEN] Assigned cart ${assignedCartId} (driverId: ${driverId}) has no valid FCM token registered.`, {
        requestId,
        assignedCartId,
        driverId,
        notificationStatus: "FAILED_NO_TOKEN",
        deliveryTimestamp
      });

      // Mark driver as unavailable in Firestore
      if (driverDoc.exists) {
        await driverDocRef.update({
          isAvailable: false,
          isOnline: false,
          status: "UNAVAILABLE",
          unavailabilityReason: "NO_FCM_TOKEN",
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      }

      // Update Firestore ride request & notify student that driver is unreachable
      await snapshot.ref.update({
        fcmStatus: "FAILED_NO_TOKEN",
        status: "NO_DRIVER_AVAILABLE",
        studentNotification: "No driver is currently reachable or online. Please try again shortly.",
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      return { success: false, reason: "NO_FCM_TOKEN" };
    }

    // Prepare direct high-priority FCM payload targeting assigned driver
    const dataPayload = {
      type: "RIDE_REQUEST",
      requestId: String(requestId),
      requesterType: String(requesterType),
      pickupLocation: String(pickupLocation),
      studentName: String(studentName),
      distanceToGateMeters: distanceToGateMeters,
      assignedCartId: String(assignedCartId),
      assignedCartName: String(rideRequest.assignedCartName || "Golf Cart"),
      timestamp: String(rideRequest.timestamp || Date.now())
    };

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
      logger.info(`[SENDING_FCM] Dispatching high-priority direct FCM notification to driver ${driverId}`, {
        requestId,
        assignedCartId,
        driverId,
        targetFcmToken: `${targetFcmToken.substring(0, 12)}...`,
        deliveryTimestamp
      });

      // Send FCM message via Firebase Admin SDK
      const response = await admin.messaging().send(message);

      // 4. Update fcmStatus = "SENT" ONLY AFTER successful FCM response
      await snapshot.ref.update({
        fcmStatus: "SENT",
        fcmMessageId: response,
        fcmSentAt: admin.firestore.FieldValue.serverTimestamp(),
        status: "DISPATCHED"
      });

      // 5. Cloud Function logging
      logger.info(`[FCM_SUCCESS] Successfully sent FCM message to assigned driver`, {
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
        logger.error(`[INVALID_TOKEN] Permanent token failure for driver ${driverId} (cart: ${assignedCartId})`, {
          requestId,
          assignedCartId,
          driverId,
          notificationStatus: "FAILED_INVALID_TOKEN",
          errorCode,
          error: errorMessage,
          deliveryTimestamp: new Date().toISOString()
        });

        // Clean up invalid token & mark driver offline in Firestore
        await driverDocRef.update({
          fcmToken: admin.firestore.FieldValue.delete(),
          isAvailable: false,
          isOnline: false,
          status: "OFFLINE",
          offlineReason: "INVALID_FCM_TOKEN",
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        // Notify student & mark request
        await snapshot.ref.update({
          fcmStatus: "FAILED_INVALID_TOKEN",
          status: "NO_DRIVER_AVAILABLE",
          studentNotification: "Assigned driver token is invalid or expired. Waiting for driver reconnect.",
          fcmError: errorMessage,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        // Permanent error: DO NOT throw so function won't retry invalid token
        return { success: false, reason: "INVALID_TOKEN", error: errorMessage };
      }

      // 6. Retry transient failures with exponential backoff
      logger.error(`[TRANSIENT_FAILURE] FCM dispatch error for request ${requestId}. Retrying via Cloud Functions backoff.`, {
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
        fcmRetryAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // Re-throw error to trigger Cloud Functions retry mechanism
      throw error;
    }
  }
);
