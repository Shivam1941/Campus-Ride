package com.example.data.model

import com.example.BuildConfig

/**
 * Authoritative mapping of Fixed Contact Numbers for Campus Carts.
 *
 * CRITICAL REQUIREMENTS:
 * 1. The phone number belongs to the CART, NOT to the Driver currently operating it.
 * 2. Cart 1 has ONE specific fixed mobile number.
 * 3. Cart 2 has ONE different specific fixed mobile number.
 * 4. Stored centrally in configuration (via BuildConfig / .env / Secrets) and NOT hardcoded across UI.
 * 5. Drivers CANNOT alter the cart phone numbers from the UI.
 */
object CampusCartConfig {

    /**
     * Fixed contact number for Cart 1.
     */
    val CART_1_PHONE: String
        get() {
            return try {
                val configured = BuildConfig.CART_1_PHONE_NUMBER
                if (!configured.isNullOrBlank() && !configured.contains("PLACEHOLDER", ignoreCase = true)) {
                    configured.trim()
                } else {
                    "+919876543210"
                }
            } catch (_: Throwable) {
                "+919876543210"
            }
        }

    /**
     * Fixed contact number for Cart 2.
     */
    val CART_2_PHONE: String
        get() {
            return try {
                val configured = BuildConfig.CART_2_PHONE_NUMBER
                if (!configured.isNullOrBlank() && !configured.contains("PLACEHOLDER", ignoreCase = true)) {
                    configured.trim()
                } else {
                    "+919876543211"
                }
            } catch (_: Throwable) {
                "+919876543211"
            }
        }

    /**
     * Resolves the fixed phone number for a given cart ID (e.g. "cart_1", "cart_2")
     * or cart label/number.
     */
    fun getCartPhoneNumber(cartIdOrNumber: Any?): String {
        return when (cartIdOrNumber) {
            is Int -> if (cartIdOrNumber == 2) CART_2_PHONE else CART_1_PHONE
            is String -> {
                val normalized = cartIdOrNumber.lowercase().trim()
                if (normalized.contains("2") || normalized.contains("cart_2")) {
                    CART_2_PHONE
                } else {
                    CART_1_PHONE
                }
            }
            else -> CART_1_PHONE
        }
    }

    /**
     * Human-readable formatted phone number (e.g. "+91 98765 43210").
     */
    fun getCartDisplayNumber(cartIdOrNumber: Any?): String {
        val raw = getCartPhoneNumber(cartIdOrNumber)
        return formatDisplayPhone(raw)
    }

    fun formatDisplayPhone(phone: String): String {
        val clean = phone.replace(" ", "").trim()
        return if (clean.startsWith("+91") && clean.length == 13) {
            "${clean.substring(0, 3)} ${clean.substring(3, 8)} ${clean.substring(8)}"
        } else if (clean.length == 10) {
            "+91 ${clean.substring(0, 5)} ${clean.substring(5)}"
        } else {
            clean
        }
    }
}
