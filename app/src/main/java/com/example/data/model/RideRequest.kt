package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RequesterType {
    STUDENT,
    FACULTY
}

enum class RideRequestStatus(val label: String) {
    PENDING("Pending Driver"),
    ACCEPTED("Accepted - Driver on the way!"),
    REJECTED("Declined"),
    COMPLETED("Completed")
}

data class RideRequest(
    val id: String = System.currentTimeMillis().toString(),
    val requestId: String = id,
    val requesterType: RequesterType = RequesterType.STUDENT,
    val studentId: String = "",
    val studentName: String = "",
    val pickupLocation: String = "GATE",
    val timestamp: Long = System.currentTimeMillis(),
    val createdAt: Long = timestamp,
    val status: RideRequestStatus = RideRequestStatus.PENDING,
    val distanceToGateMeters: Int? = null,
    val studentLat: Double = 25.2531616,
    val studentLng: Double = 87.0370730,
    val studentsWaiting: Int = 1,
    val waitingCount: Int = studentsWaiting,
    val assignedCartId: String? = null,
    val selectedCartId: String? = assignedCartId?.let { if (it.contains("2")) "CART_2" else "CART_1" },
    val assignedCartName: String? = null,
    val driverLat: Double? = null,
    val driverLng: Double? = null,
    val driverBearing: Float? = null,
    val driverSpeedMps: Float? = null,
    val driverLocationUpdatedAt: Long? = null
) {
    val pickupLocationEnum: PickupLocation
        get() = PickupLocation.fromId(pickupLocation)

    val isFaculty: Boolean
        get() = (requesterType == RequesterType.FACULTY)

    val isPriority: Boolean
        get() = (requesterType == RequesterType.FACULTY)

    val driverLocationZone: String
        get() = com.example.location.CampusLandmarkZone.getStudentFacingDriverLocation(driverLat, driverLng)

    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

