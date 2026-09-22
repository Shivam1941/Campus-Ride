package com.example.data.model

enum class UserRole(val displayName: String, val description: String) {
    STUDENT("Student", "Request golf cart when near IIIT Bhagalpur Main Gate"),
    FACULTY("Faculty", "Access priority campus transit & administrative overview"),
    DRIVER("Driver", "Broadcast golf cart location & accept ride requests");

    companion object {
        fun fromString(role: String?): UserRole? {
            return entries.find { it.name.equals(role, ignoreCase = true) }
        }
    }
}
