package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.example.ui.navigation.CampusAppNavigation
import com.example.ui.theme.CampusRideTheme

class MainActivity : ComponentActivity() {
  private val currentIntentState = mutableStateOf<Intent?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    currentIntentState.value = intent

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
      )
    }

    enableEdgeToEdge()
    com.example.notification.CriticalAlertManager.initNotificationChannel(applicationContext)
    setContent {
      CampusRideTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          CampusAppNavigation(intent = currentIntentState.value)
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    try {
      com.example.data.repository.CampusRideRepository.getInstance(applicationContext).resumePassengerLiveTracking()
    } catch (_: Exception) {}
  }

  override fun onStop() {
    super.onStop()
    try {
      com.example.data.repository.CampusRideRepository.getInstance(applicationContext).pausePassengerLiveTracking()
    } catch (_: Exception) {}
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    currentIntentState.value = intent
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
  }
}

