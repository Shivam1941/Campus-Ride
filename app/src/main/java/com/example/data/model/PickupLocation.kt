package com.example.data.model

/**
 * Campus Ride role-based pickup locations.
 *
 * The canonical 4 stops:
 * 1. MAIN_GATE -> "Main Gate"
 * 2. TRUNKUT -> "Trunkut"
 * 3. COMPUTER_CENTRE -> "Computer Centre"
 * 4. ACADEMIC_BLOCK -> "Academic Block"
 * 5. HOSTEL -> "Hostel"
 *
 * STUDENT LOCATION:
 * - GATE -> "Gate" (Automatic, non-changeable)
 *
 * FACULTY LOCATIONS:
 * 1. HOSTEL -> "Hostel"
 * 2. ACADEMIC_BLOCK -> "Academic Block"
 * 3. COMPUTER_CENTRE -> "Computer Centre"
 * 4. TRUNKUT -> "Trunkut"
 * 5. MAIN_GATE -> "Main Gate"
 */
enum class PickupLocation(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val fullAddress: String,
    val emoji: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isCoordinatesConfigured: Boolean = false,
    val allowedForStudent: Boolean = false,
    val allowedForFaculty: Boolean = false
) {
    GATE(
        id = "GATE",
        displayName = "Gate",
        shortLabel = "GATE",
        fullAddress = "IIIT BHAGALPUR Main Gate, Bhagalpur, Bihar 813210",
        emoji = "🚪",
        latitude = 25.2531616,
        longitude = 87.0370730,
        isCoordinatesConfigured = true,
        allowedForStudent = true,
        allowedForFaculty = false
    ),
    HOSTEL(
        id = "HOSTEL",
        displayName = "Boys Hostel",
        shortLabel = "BOYS HOSTEL",
        fullAddress = "Boys Hostel, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏠",
        latitude = 25.2577810,
        longitude = 87.0418910,
        isCoordinatesConfigured = true,
        allowedForStudent = false,
        allowedForFaculty = true
    ),
    BOYS_HOSTEL(
        id = "BOYS_HOSTEL",
        displayName = "Boys Hostel",
        shortLabel = "BOYS HOSTEL",
        fullAddress = "Boys Hostel, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏠",
        latitude = 25.2577810,
        longitude = 87.0418910,
        isCoordinatesConfigured = true,
        allowedForStudent = false,
        allowedForFaculty = false
    ),
    ACADEMIC_BLOCK(
        id = "ACADEMIC_BLOCK",
        displayName = "Academic Block",
        shortLabel = "ACADEMIC BLOCK",
        fullAddress = "Academic Block, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏛️",
        latitude = 25.2590750,
        longitude = 87.0401610,
        isCoordinatesConfigured = true,
        allowedForStudent = false,
        allowedForFaculty = true
    ),
    COMPUTER_CENTRE(
        id = "COMPUTER_CENTRE",
        displayName = "Computer Centre",
        shortLabel = "COMPUTER CENTRE",
        fullAddress = "Computer Centre, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "💻",
        latitude = 25.2590500,
        longitude = 87.0394730,
        isCoordinatesConfigured = true,
        allowedForStudent = false,
        allowedForFaculty = true
    ),
    TRUNKUT(
        id = "TRUNKUT",
        displayName = "Trunkut",
        shortLabel = "TRUNKUT",
        fullAddress = "Trunkut, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "📍",
        latitude = 25.2577186,
        longitude = 87.0381730,
        isCoordinatesConfigured = true,
        allowedForStudent = false,
        allowedForFaculty = true
    ),
    MAIN_GATE(
        id = "MAIN_GATE",
        displayName = "Main Gate",
        shortLabel = "MAIN GATE",
        fullAddress = "IIIT BHAGALPUR Main Gate, Bhagalpur, Bihar 813210",
        emoji = "🚪",
        latitude = 25.2531616,
        longitude = 87.0370730,
        isCoordinatesConfigured = true,
        allowedForStudent = false,
        allowedForFaculty = true
    ),
    FACULTY_RESIDENCE(
        id = "FACULTY_RESIDENCE",
        displayName = "Faculty Residence",
        shortLabel = "FACULTY RESIDENCE",
        fullAddress = "Faculty Residence, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏡",
        latitude = 25.259257426319685,
        longitude = 87.04141528824039,
        isCoordinatesConfigured = true,
        allowedForStudent = false,
        allowedForFaculty = true
    ),
    GIRLS_HOSTEL(
        id = "GIRLS_HOSTEL",
        displayName = "Girls Hostel",
        shortLabel = "GIRLS HOSTEL",
        fullAddress = "Girls Hostel, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏢",
        latitude = 25.26007663539964,
        longitude = 87.0393243817079,
        isCoordinatesConfigured = true,
        allowedForStudent = false,
        allowedForFaculty = true
    );

    companion object {
        val FACULTY_LOCATIONS = listOf(
            HOSTEL,
            FACULTY_RESIDENCE,
            GIRLS_HOSTEL,
            ACADEMIC_BLOCK,
            COMPUTER_CENTRE,
            TRUNKUT,
            MAIN_GATE
        )

        val STUDENT_LOCATION = GATE

        fun fromId(id: String?): PickupLocation {
            if (id.isNullOrBlank()) return GATE
            val trimmed = id.trim()

            // Exact match by ID or DisplayName
            entries.firstOrNull { 
                it.id.equals(trimmed, ignoreCase = true) || 
                it.displayName.equals(trimmed, ignoreCase = true) ||
                it.shortLabel.equals(trimmed, ignoreCase = true)
            }?.let { return it }

            if (trimmed.equals("Main Gate", ignoreCase = true) || trimmed.equals("MAIN_GATE", ignoreCase = true)) {
                return MAIN_GATE
            }
            if (trimmed.contains("Girls", ignoreCase = true)) {
                return GIRLS_HOSTEL
            }
            if (trimmed.contains("Faculty", ignoreCase = true) || trimmed.contains("Residence", ignoreCase = true)) {
                return FACULTY_RESIDENCE
            }
            if (trimmed.contains("Academic", ignoreCase = true)) {
                return ACADEMIC_BLOCK
            }
            if (trimmed.contains("Trunk", ignoreCase = true)) {
                return TRUNKUT
            }
            if (trimmed.contains("Hostel", ignoreCase = true)) {
                return HOSTEL
            }
            if (trimmed.contains("Computer", ignoreCase = true)) {
                return COMPUTER_CENTRE
            }
            if (trimmed.equals("Gate", ignoreCase = true) || trimmed.equals("GATE", ignoreCase = true)) {
                return GATE
            }
            return GATE
        }
    }
}
