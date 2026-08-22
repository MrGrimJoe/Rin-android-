package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.crypto.CryptoEngine
import com.example.core.protocol.QrJoinToken
import com.example.ui.components.QrCameraScanner
import com.example.ui.components.QrCodeCanvas
import com.example.ui.theme.RinBorder
import com.example.ui.theme.RinCyanAccent
import com.example.ui.theme.RinOnPrimaryDark
import com.example.ui.theme.RinTealPrimary

enum class OnboardingState {
    CHOICE,
    CREATE_MESH,
    JOIN_MESH_SCAN,
    CONNECTING
}

@Composable
fun OnboardingScreen(
    onCreateMesh: (String) -> Unit,
    onJoinMesh: (QrJoinToken) -> Unit,
    modifier: Modifier = Modifier
) {
    var state by remember { mutableStateOf(OnboardingState.CHOICE) }
    var meshNameInput by remember { mutableStateOf("") }
    var manualTokenJson by remember { mutableStateOf("") }
    var joinScanMode by remember { mutableIntStateOf(0) } // 0 = Camera QR, 1 = Manual Token / Host IP
    var onboardingScanError by remember { mutableStateOf<String?>(null) }
    var onboardingManualError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Branding Logo
            Surface(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(2.dp, RinTealPrimary.copy(alpha = 0.5f), CircleShape),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(id = R.drawable.rin_icon),
                        contentDescription = "Rin Logo",
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Rin Device Mesh",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Instant automatic clipboard synchronization across Android devices over local Wi-Fi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            when (state) {
                OnboardingState.CHOICE -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Button(
                            onClick = { state = OnboardingState.CREATE_MESH },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("btn_create_mesh"),
                            colors = ButtonDefaults.buttonColors(containerColor = RinTealPrimary),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = RinOnPrimaryDark)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Create a New Mesh",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = RinOnPrimaryDark
                            )
                        }

                        OutlinedButton(
                            onClick = { state = OnboardingState.JOIN_MESH_SCAN },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("btn_join_mesh"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = RinCyanAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Join an Existing Mesh",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                OnboardingState.CREATE_MESH -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { state = OnboardingState.CHOICE }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Name Your Mesh",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedTextField(
                                value = meshNameInput,
                                onValueChange = { meshNameInput = it },
                                label = { Text("Mesh Name") },
                                placeholder = { Text("e.g. My Phone Mesh") },
                                modifier = Modifier.fillMaxWidth().testTag("input_mesh_name"),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = RinTealPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Generates secure Ed25519 identity keypair locally",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = { onCreateMesh(meshNameInput.ifBlank { "My Phone Mesh" }) },
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("btn_confirm_create_mesh"),
                                colors = ButtonDefaults.buttonColors(containerColor = RinTealPrimary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Generate Keypair & Start Mesh", color = RinOnPrimaryDark, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                OnboardingState.JOIN_MESH_SCAN -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { state = OnboardingState.CHOICE }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Join Existing Mesh",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = joinScanMode == 0,
                                    onClick = { joinScanMode = 0 },
                                    label = { Text("Scan Pairing QR") },
                                    leadingIcon = {
                                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = RinTealPrimary.copy(alpha = 0.2f),
                                        selectedLabelColor = RinTealPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )

                                FilterChip(
                                    selected = joinScanMode == 1,
                                    onClick = { joinScanMode = 1 },
                                    label = { Text("Paste Token") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = RinTealPrimary.copy(alpha = 0.2f),
                                        selectedLabelColor = RinTealPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (joinScanMode == 0) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    QrCameraScanner(
                                        onQrCodeScanned = { rawPayload ->
                                            val parsed = QrJoinToken.fromJson(rawPayload)
                                            if (parsed != null && CryptoEngine.isValidPublicKey(parsed.hostPublicKey)) {
                                                onboardingScanError = null
                                                state = OnboardingState.CONNECTING
                                                onJoinMesh(parsed)
                                            } else {
                                                onboardingScanError = "Invalid QR Join Token: missing cryptographic key or malformed."
                                            }
                                        }
                                    )

                                    if (onboardingScanError != null) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = onboardingScanError!!,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "Paste the JSON pairing token generated from the host phone:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = manualTokenJson,
                                    onValueChange = {
                                        manualTokenJson = it
                                        onboardingManualError = null
                                    },
                                    placeholder = { Text("Paste {\"meshName\": ...} token JSON") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    minLines = 3,
                                    maxLines = 5
                                )

                                if (onboardingManualError != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = onboardingManualError!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        val parsed = QrJoinToken.fromJson(manualTokenJson)
                                        if (parsed != null && CryptoEngine.isValidPublicKey(parsed.hostPublicKey)) {
                                            onboardingManualError = null
                                            state = OnboardingState.CONNECTING
                                            onJoinMesh(parsed)
                                        } else {
                                            onboardingManualError = "Invalid pairing token or corrupted cryptographic EC public key."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RinTealPrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = manualTokenJson.isNotBlank()
                                ) {
                                    Text("Validate & Join Mesh", color = RinOnPrimaryDark, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                OnboardingState.CONNECTING -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = RinTealPrimary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Authenticating handshake...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Exchanging identity keys & trusted member list",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
