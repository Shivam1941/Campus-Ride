package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class CampusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }

            com.example.notification.CriticalAlertManager.initNotificationChannel(this)

            try {
                com.google.android.gms.maps.MapsInitializer.initialize(
                    applicationContext,
                    com.google.android.gms.maps.MapsInitializer.Renderer.LATEST
                ) { renderer ->
                    Log.d("CampusApplication", "Google Maps initialized with renderer: $renderer")
                }
            } catch (e: Exception) {
                Log.w("CampusApplication", "Google MapsInitializer early init notice: ${e.message}")
            }

            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                if (auth.currentUser == null) {
                    auth.signInAnonymously()
                        .addOnFailureListener { e ->
                            Log.w("CampusApplication", "Early auth initialization notice: ${e.message}")
                        }
                }
            } catch (e: Exception) {
                Log.w("CampusApplication", "Error during early auth check: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("CampusApplication", "Failed to initialize FirebaseApp or channels", e)
        }
    }
}

