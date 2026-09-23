package com.example.ui.roleselection

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserRole
import com.example.data.repository.CampusRideRepository

@Composable
fun RoleSelectionScreen(
    repository: CampusRideRepository,
    onSelectRole: (UserRole) -> Unit
) {
    var activeDialogRole by remember { mutableStateOf<UserRole?>(null) }
    var inputCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var driverStep by remember { mutableStateOf(1) } // 1: Passcode, 2: Name Input
    var driverNameInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Brand Logo Emblem
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(0xFF050A14))
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF00D2FF), Color(0xFF0284C7), Color(0xFF38BDF8))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = com.example.R.drawable.img_tesseract_icon),
                contentDescription = "Campus Ride Logo",
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Campus Ride",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "IIIT Bhagalpur Executive Transport",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(36.dp))

        Text(
            text = "Select Portal",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Role selection is permanent for this installed application.",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Student Role Card
        RoleCard(
            title = "Student Portal",
            subtitle = "Request golf cart pickup near IIIT Bhagalpur main gate.",
            icon = Icons.Default.School,
            badgeText = "Open Access",
            onClick = { onSelectRole(UserRole.STUDENT) }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Faculty Role Card
        RoleCard(
            title = "Faculty Portal",
            subtitle = "Call golf cart to designated campus pickup points.",
            icon = Icons.Default.Person,
            badgeText = "Passcode Required",
            onClick = {
                inputCode = ""
                errorMessage = null
                activeDialogRole = UserRole.FACULTY
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Driver Role Card
        RoleCard(
            title = "Driver Terminal",
            subtitle = "Manage live pickup queue and monitor golf cart telemetry.",
            icon = Icons.Default.Navigation,
            badgeText = "Passcode Required",
            onClick = {
                inputCode = ""
                driverNameInput = ""
                driverStep = 1
                errorMessage = null
                activeDialogRole = UserRole.DRIVER
            }
        )
    }

    // Passcode Verification & Driver Identification Modal
    activeDialogRole?.let { role ->
        val titleText = when {
            role == UserRole.FACULTY -> "Faculty Security Passcode"
            driverStep == 1 -> "Driver Terminal Passcode"
            else -> "Driver Identification"
        }

        AlertDialog(
            onDismissRequest = {
                activeDialogRole = null
                driverStep = 1
                driverNameInput = ""
                inputCode = ""
                errorMessage = null
            },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (role == UserRole.DRIVER && driverStep == 2) Icons.Default.Person else Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = titleText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    if (role == UserRole.DRIVER && driverStep == 2) {
                        Text(
                            text = "Enter your driver name. Cart assignment is automatic and permanently locked based on your name.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = driverNameInput,
                            onValueChange = {
                                driverNameInput = it
                                errorMessage = null
                            },
                            label = { Text("Driver Name") },
                            placeholder = { Text("Enter Shivam or Kartik") },
                            singleLine = true,
                            isError = errorMessage != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    } else {
                        Text(
                            text = "Authorized access required for ${role.displayName} portal.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = inputCode,
                            onValueChange = {
                                inputCode = it
                                errorMessage = null
                            },
                            label = { Text("Security Passcode") },
                            placeholder = { Text("Enter authorized passcode") },
                            singleLine = true,
                            isError = errorMessage != null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }

                    errorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (role == UserRole.FACULTY) {
                            val isValid = repository.verifyFacultyAccessCode(inputCode)
                            if (isValid) {
                                activeDialogRole = null
                                onSelectRole(role)
                            } else {
                                errorMessage = "Incorrect passcode. Please try again."
                            }
                        } else {
                            if (driverStep == 1) {
                                val isValid = repository.verifyDriverAccessCode(inputCode)
                                if (isValid) {
                                    errorMessage = null
                                    inputCode = ""
                                    driverStep = 2
                                } else {
                                    errorMessage = "Incorrect passcode. Please try again."
                                }
                            } else {
                                val cleanName = driverNameInput.trim()
                                if (cleanName.isBlank()) {
                                    errorMessage = "Please enter your name."
                                } else {
                                    val result = repository.setAuthoritativeDriver(cleanName)
                                    if (result.isSuccess) {
                                        activeDialogRole = null
                                        driverStep = 1
                                        driverNameInput = ""
                                        onSelectRole(role)
                                    } else {
                                        errorMessage = "Driver not authorized. Allowed names: Shivam (Cart 1) or Kartik (Cart 2)."
                                    }
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = if (role == UserRole.DRIVER && driverStep == 2) "Confirm & Enter" else "Authenticate",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        activeDialogRole = null
                        driverStep = 1
                        driverNameInput = ""
                        inputCode = ""
                        errorMessage = null
                    }
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun RoleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badgeText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Select",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
