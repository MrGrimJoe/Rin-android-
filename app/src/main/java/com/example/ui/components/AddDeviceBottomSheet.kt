package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.crypto.CryptoEngine
import com.example.core.protocol.PlatformType
import com.example.core.protocol.QrJoinToken
import com.example.ui.theme.RinCyanAccent
import com.example.ui.theme.RinOnPrimaryDark
import com.example.ui.theme.RinTealPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceBottomSheet(
    token: QrJoinToken?,
    onDismiss: () -> Unit,
    onRefreshToken: () -> Unit,
    onAddDevicePlatform: (PlatformType) -> Unit,
    onJoinViaToken: (QrJoinToken) -> Unit,
    onProbePeerEndpoint: (String, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var scanMode by remember { mutableIntStateOf(0) } // 0 = Camera Scanner, 1 = Manual IP / Token
    var manualTokenInput by remember { mutableStateOf("") }
    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("45990") }
    var deviceNameInput by remember { mutableStateOf("Android Device") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag("add_device_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Add Device to Mesh",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Cryptographic zero-config handshake",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pairing QR")
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pair Peer")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (selectedTab == 0) {
                // QR Display Tab
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan this code on your second phone to add it to \"${token?.meshName ?: "Mesh"}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .border(2.dp, RinTealPrimary, RoundedCornerShape(16.dp))
                            .padding(12.dp)
                            .testTag("qr_code_display"),
                        contentAlignment = Alignment.Center
                    ) {
                        QrCodeCanvas(
                            payload = token?.toJson() ?: "RIN:EMPTY",
                            modifier = Modifier.size(176.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = RinTealPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ephemeral Key: ${token?.ephemeralToken?.take(16) ?: ""}...",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    token?.hostIp?.let { ip ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "LAN Socket Endpoint: $ip:${token.hostPort}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = RinCyanAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onRefreshToken,
                        modifier = Modifier.testTag("refresh_token_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Regenerate Handshake Key")
                    }
                }
            } else {
                // Pair Peer Tab with Camera Scanner and Manual Mode
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = scanMode == 0,
                            onClick = { scanMode = 0 },
                            label = { Text("Camera QR Scanner") },
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
                            selected = scanMode == 1,
                            onClick = { scanMode = 1 },
                            label = { Text("Manual IP / Key") },
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

                    var scanErrorMessage by remember { mutableStateOf<String?>(null) }
                    var manualErrorMessage by remember { mutableStateOf<String?>(null) }

                    if (scanMode == 0) {
                        // Live CameraX Scanner
                        Column(modifier = Modifier.fillMaxWidth()) {
                            QrCameraScanner(
                                onQrCodeScanned = { rawPayload ->
                                    val parsed = QrJoinToken.fromJson(rawPayload)
                                    if (parsed != null && CryptoEngine.isValidPublicKey(parsed.hostPublicKey)) {
                                        scanErrorMessage = null
                                        onJoinViaToken(parsed)
                                        onDismiss()
                                    } else {
                                        scanErrorMessage = "Invalid QR Join Token: payload missing cryptographic key or malformed."
                                    }
                                }
                            )

                            if (scanErrorMessage != null) {
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
                                            text = scanErrorMessage!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Manual IP and Token Entry Form
                        Text(
                            text = "Paste a pairing token JSON or enter peer network endpoint:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Paste Pairing Token JSON:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = manualTokenInput,
                            onValueChange = {
                                manualTokenInput = it
                                manualErrorMessage = null
                            },
                            placeholder = { Text("Paste {\"meshName\": ...} token JSON") },
                            modifier = Modifier.fillMaxWidth().testTag("manual_token_input"),
                            shape = RoundedCornerShape(10.dp),
                            minLines = 2,
                            maxLines = 4
                        )

                        if (manualErrorMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = manualErrorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val parsed = QrJoinToken.fromJson(manualTokenInput)
                                if (parsed != null && CryptoEngine.isValidPublicKey(parsed.hostPublicKey)) {
                                    manualErrorMessage = null
                                    onJoinViaToken(parsed)
                                    onDismiss()
                                } else {
                                    manualErrorMessage = "Invalid token format or invalid cryptographic EC public key."
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("pair_from_token_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = RinTealPrimary),
                            shape = RoundedCornerShape(12.dp),
                            enabled = manualTokenInput.isNotBlank()
                        ) {
                            Text("Validate & Join via Token", color = RinOnPrimaryDark, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Or Direct IP Connect (Endpoint Pairing):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = deviceNameInput,
                            onValueChange = { deviceNameInput = it },
                            label = { Text("Peer Device Name") },
                            placeholder = { Text("e.g. Pixel 8 Pro") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = ipInput,
                                onValueChange = { ipInput = it },
                                label = { Text("Peer IP Address") },
                                placeholder = { Text("192.168.1.X") },
                                modifier = Modifier.weight(2f).testTag("peer_ip_input"),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { portInput = it },
                                label = { Text("Port") },
                                modifier = Modifier.weight(1f).testTag("peer_port_input"),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                val parsedPort = portInput.toIntOrNull() ?: 45990
                                if (ipInput.isNotBlank()) {
                                    // Trigger runtime discovery to resolve peer's genuine key over socket handshake
                                    onProbePeerEndpoint(ipInput.trim(), parsedPort)
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("connect_peer_btn"),
                            shape = RoundedCornerShape(12.dp),
                            enabled = ipInput.isNotBlank()
                        ) {
                            Text("Probe & Handshake Peer IP", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
