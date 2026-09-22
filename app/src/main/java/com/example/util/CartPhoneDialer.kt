package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.data.model.CampusCartConfig

/**
 * Utility for opening the Android system dialer for Campus Cart phone calls.
 *
 * CRITICAL REQUIREMENTS:
 * - Uses Intent.ACTION_DIAL (never automatically places a call).
 * - Pre-fills the cart-specific number.
 * - Does NOT require CALL_PHONE permission.
 * - Leaves final calling decision entirely to user in system dialer.
 */
object CartPhoneDialer {

    fun dialCart(context: Context, cartIdOrNumber: Any?) {
        val phoneNumber = CampusCartConfig.getCartPhoneNumber(cartIdOrNumber)
        dialNumber(context, phoneNumber)
    }

    fun dialNumber(context: Context, rawNumber: String) {
        try {
            val clean = rawNumber.replace(" ", "").trim()
            val uri = Uri.parse("tel:$clean")
            val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open dialer: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
