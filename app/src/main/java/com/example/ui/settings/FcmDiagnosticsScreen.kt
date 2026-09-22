package com.example.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.CampusBackendClient
import com.example.data.api.FcmTokenSyncRequest
import com.example.notification.FcmRoleNotificationManager
import com.example.ui.components.CampusPullToRefreshBox
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FcmDiagnosticsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // State Variables for Diagnostics
    var isRefreshing by remember { mutableStateOf(false) }
    var isTestingHealth by remember { mutableStateOf(false) }

    var appId by remember { mutableStateOf(context.packageName) }
    var projectId by remember { mutableStateOf("Unknown") }
    var firebaseAppId by remember { mutableStateOf("Unknown") }
    var gcmSenderId by remember { mutableStateOf("Unknown") }
    var isFirebaseInitialized by remember { mutableStateOf(false) }
    var firebaseAppsCount by remember { mutableStateOf(0) }

    var gpsAvailable by remember { mutableStateOf(false) }
    var gpsStatusMsg by remember { mutableStateOf("Checking...") }

    var getTokenStatus by remember { mutableStateOf("NOT_STARTED") }
    var maskedToken by remember { mutableStateOf("None") }
    var exceptionClass by remember { mutableStateOf("None") }
    var exceptionMessage by remember { mutableStateOf("None") }
    var exceptionStatusCode by remember { mutableStateOf("N/A") }

    var firestoreSyncStatus by remember { mutableStateOf("NOT_ATTEMPTED") }
    var renderSyncStatus by remember { mutableStateOf("NOT_ATTEMPTED") }
    var renderResponseText by remember { mutableStateOf("None") }
    var lastSyncTimestamp by remember { mutableStateOf("Never") }

    // Health Test State
    var healthStatus by remember { mutableStateOf("NOT_TESTED") }
    var healthResponseBody by remember { mutableStateOf("Tap 'Test Render Connection' to execute.") }

    fun readAppAndFirebaseInfo() {
        appId = context.packageName
        try {
            val apps = FirebaseApp.getApps(context)
            firebaseAppsCount = apps.size
            if (apps.isNotEmpty()) {
                val app = FirebaseApp.getInstance()
                isFirebaseInitialized = true
                val options = app.options
                projectId = options.projectId ?: "NULL"
                firebaseAppId = options.applicationId ?: "NULL"
                gcmSenderId = options.gcmSenderId ?: "NULL"
            } else {
                isFirebaseInitialized = false
                projectId = "NO_APPS_INITIALIZED"
            }
        } catch (e: Exception) {
            isFirebaseInitialized = false
            projectId = "ERROR: ${e.message}"
        }

        try {
            val googleApiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
            gpsAvailable = (resultCode == ConnectionResult.SUCCESS)
            val errString = googleApiAvailability.getErrorString(resultCode) ?: "Unknown"
            gpsStatusMsg = if (gpsAvailable) "AVAILABLE (Code 0: $errString)" else "UNAVAILABLE (Code $resultCode: $errString)"
        } catch (e: Exception) {
            gpsAvailable = false
            gpsStatusMsg = "ERROR: ${e.message}"
        }
    }

    fun runDiagnostics() {
        scope.launch {
            isRefreshing = true
            getTokenStatus = "FETCHING_TOKEN..."
            readAppAndFirebaseInfo()

            if (!gpsAvailable) {
                getTokenStatus = "SKIPPED_GPS_UNAVAILABLE"
                isRefreshing = false
                return@launch
            }

            try {
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (!token.isNullOrEmpty()) {
                            getTokenStatus = "SUCCESS"
                            maskedToken = FcmRoleNotificationManager.maskToken(token)
                            exceptionClass = "None"
                            exceptionMessage = "None"
                            exceptionStatusCode = "N/A"

                            val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
                            val savedRoleStr = prefs.getString("saved_user_role", null)
                            val activeRole = com.example.data.model.UserRole.fromString(savedRoleStr) ?: com.example.data.model.UserRole.STUDENT
                            val isDriverRole = activeRole == com.example.data.model.UserRole.DRIVER

                            // 1. Sync to Firestore
                            firestoreSyncStatus = "WRITING..."
                            try {
                                val firestore = FirebaseFirestore.getInstance()
                                if (isDriverRole) {
                                    val driverDoc = mapOf(
                                        "cartId" to "cart_1",
                                        "fcmToken" to token,
                                        "driverStatus" to "Available",
                                        "isAvailable" to true,
                                        "lastUpdatedMillis" to System.currentTimeMillis()
                                    )
                                    firestore.collection("drivers")
                                        .document("cart_1")
                                        .set(driverDoc, com.google.firebase.firestore.SetOptions.merge())
                                        .addOnSuccessListener {
                                            firestoreSyncStatus = "SUCCESS (DRIVER drivers/cart_1)"
                                        }
                                        .addOnFailureListener { e ->
                                            firestoreSyncStatus = "FAILED: ${e.message}"
                                        }
                                } else {
                                    val studentDoc = mapOf(
                                        "role" to activeRole.name,
                                        "fcmToken" to token,
                                        "lastUpdatedMillis" to System.currentTimeMillis()
                                    )
                                    firestore.collection("fcm_tokens")
                                        .document("${activeRole.name.lowercase()}_device")
                                        .set(studentDoc, com.google.firebase.firestore.SetOptions.merge())
                                        .addOnSuccessListener {
                                            firestoreSyncStatus = "SUCCESS (${activeRole.name} fcm_tokens)"
                                        }
                                        .addOnFailureListener { e ->
                                            firestoreSyncStatus = "FAILED: ${e.message}"
                                        }
                                }
                            } catch (e: Exception) {
                                firestoreSyncStatus = "EXCEPTION: ${e.message}"
                            }

                            // 2. Upload to Render Backend
                            renderSyncStatus = "UPLOADING..."
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val response = CampusBackendClient.api.syncFcmToken(
                                        FcmTokenSyncRequest(
                                            role = activeRole.name,
                                            userId = if (isDriverRole) "cart_1" else "student_device",
                                            fcmToken = token
                                        )
                                    )
                                    val code = response.code()
                                    val body = response.body()
                                    val errBody = response.errorBody()?.string()

                                    withContext(Dispatchers.Main) {
                                        if (response.isSuccessful) {
                                            renderSyncStatus = "SUCCESS (HTTP $code)"
                                            renderResponseText = "HTTP $code - success=${body?.success}, message=${body?.message}"
                                            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                                            lastSyncTimestamp = sdf.format(Date())
                                        } else {
                                            renderSyncStatus = "FAILED (HTTP $code)"
                                            renderResponseText = "HTTP $code - Error: $errBody"
                                        }
                                        isRefreshing = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        renderSyncStatus = "FAILED (EXCEPTION)"
                                        renderResponseText = "Exception: ${e.javaClass.simpleName} - ${e.message}"
                                        isRefreshing = false
                                    }
                                }
                            }
                        } else {
                            getTokenStatus = "NULL_TOKEN_RETURNED"
                            maskedToken = "NULL"
                            firestoreSyncStatus = "NOT_ATTEMPTED"
                            renderSyncStatus = "NOT_ATTEMPTED"
                            isRefreshing = false
                        }
                    }
                    .addOnFailureListener { e ->
                        getTokenStatus = "FAILED"
                        maskedToken = "None (Token Fetch Failed)"
                        exceptionClass = e.javaClass.name
                        exceptionMessage = e.message ?: "No error message"
                        if (e is ApiException) {
                            exceptionStatusCode = "${e.statusCode}"
                        } else {
                            exceptionStatusCode = "N/A"
                        }
                        firestoreSyncStatus = "SKIPPED_TOKEN_FAILED"
                        renderSyncStatus = "SKIPPED_TOKEN_FAILED"
                        isRefreshing = false
                    }
            } catch (e: Exception) {
                getTokenStatus = "THREW_EXCEPTION"
                exceptionClass = e.javaClass.name
                exceptionMessage = e.message ?: "Unknown exception"
                isRefreshing = false
            }
        }
    }

    fun testRenderHealth() {
        scope.launch {
            isTestingHealth = true
            healthStatus = "TESTING..."
            try {
                val response = withContext(Dispatchers.IO) {
                    CampusBackendClient.api.getHealth()
                }
                val code = response.code()
                if (response.isSuccessful) {
                    val body = response.body()
                    healthStatus = "SUCCESS (HTTP $code)"
                    healthResponseBody = "HTTP $code OK\nStatus: ${body?.status}\nService: ${body?.service}\nProject ID: ${body?.projectId}\nUptime: ${body?.uptime}s"
                } else {
                    val err = response.errorBody()?.string()
                    healthStatus = "FAILED (HTTP $code)"
                    healthResponseBody = "HTTP $code Error: $err"
                }
            } catch (e: Exception) {
                healthStatus = "FAILED (EXCEPTION)"
                healthResponseBody = "Exception: ${e.javaClass.simpleName}\nMessage: ${e.message}"
            } finally {
                isTestingHealth = false
            }
        }
    }

    LaunchedEffect(Unit) {
        readAppAndFirebaseInfo()
        runDiagnostics()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FCM Diagnostics", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { runDiagnostics() }, enabled = !isRefreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        CampusPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { runDiagnostics() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
            // Action Buttons Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Real-Time FCM Diagnostics",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Reads actual runtime configuration and attempts real FCM token registration without fallback generation.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { runDiagnostics() },
                            enabled = !isRefreshing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.width(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Running...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.width(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refresh FCM Token", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = { testRenderHealth() },
                            enabled = !isTestingHealth,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isTestingHealth) {
                                CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.width(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Backend", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Android & Firebase Project Info
            DiagnosticCard(
                title = "1. App & Firebase Configuration",
                icon = Icons.Default.Info
            ) {
                DiagnosticRow("Android applicationId", appId)
                DiagnosticRow("Firebase Initialized", if (isFirebaseInitialized) "YES ($firebaseAppsCount app)" else "NO")
                DiagnosticRow("Firebase Project ID", projectId)
                DiagnosticRow("Firebase Application ID", firebaseAppId)
                DiagnosticRow("GCM Sender ID", gcmSenderId)
                DiagnosticRow("Google Play Services", gpsStatusMsg, isError = !gpsAvailable)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. FCM Token Registration Result
            DiagnosticCard(
                title = "2. FCM Token Retrieval Result",
                icon = Icons.Default.BugReport
            ) {
                val isSuccess = getTokenStatus == "SUCCESS"
                DiagnosticRow("getToken() Status", getTokenStatus, isError = !isSuccess && getTokenStatus != "FETCHING_TOKEN...")
                DiagnosticRow("Masked Real Token", maskedToken, isError = !isSuccess)
                DiagnosticRow("Exception Class", exceptionClass, isError = exceptionClass != "None")
                DiagnosticRow("Exception Message", exceptionMessage, isError = exceptionMessage != "None")
                DiagnosticRow("ApiException Status Code", exceptionStatusCode, isError = exceptionStatusCode != "N/A")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Sync & Backend Results
            DiagnosticCard(
                title = "3. Firestore & Render Sync Status",
                icon = Icons.Default.CheckCircle
            ) {
                DiagnosticRow("Firestore drivers/cart_1", firestoreSyncStatus, isError = firestoreSyncStatus.startsWith("FAILED") || firestoreSyncStatus.startsWith("EXCEPTION"))
                DiagnosticRow("Render POST /fcm-token", renderSyncStatus, isError = renderSyncStatus.startsWith("FAILED"))
                DiagnosticRow("Render HTTP Response", renderResponseText)
                DiagnosticRow("Last Successful Sync", lastSyncTimestamp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Render Health Endpoint Result
            DiagnosticCard(
                title = "4. Backend Health Test",
                icon = Icons.Default.Cloud
            ) {
                DiagnosticRow("Health Test Status", healthStatus, isError = healthStatus.startsWith("FAILED"))
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = healthResponseBody,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    isError: Boolean = false
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.Gray
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isError) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurface
        )
    }
}
