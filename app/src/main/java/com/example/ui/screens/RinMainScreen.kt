package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AddDeviceBottomSheet
import com.example.ui.components.ClipboardHistorySheet
import com.example.ui.components.DeviceCard
import com.example.ui.components.FileTransferDialog
import com.example.ui.components.PacketInspectorSheet
import com.example.ui.components.ReceivedFileBanner
import com.example.ui.components.ShareTargetDialog
import com.example.ui.components.UrlHandoffDialog
import com.example.ui.theme.RinBorder
import com.example.ui.theme.RinCyanAccent
import com.example.ui.theme.RinOnPrimaryDark
import com.example.ui.theme.RinStatusConnected
import com.example.ui.theme.RinTealPrimary
import com.example.ui.viewmodel.RinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RinMainScreen(
    viewModel: RinViewModel,
    modifier: Modifier = Modifier
) {
    val meshInfo by viewModel.meshInfo.collectAsStateWithLifecycle()
    val trustedDevices by viewModel.trustedDevices.collectAsStateWithLifecycle()
    val recentPackets by viewModel.recentPackets.collectAsStateWithLifecycle()
    val clipboardHistory by viewModel.clipboardHistory.collectAsStateWithLifecycle()
    val isAddDeviceSheetVisible by viewModel.isAddDeviceSheetVisible.collectAsStateWithLifecycle()
    val isPacketInspectorVisible by viewModel.isPacketInspectorVisible.collectAsStateWithLifecycle()
    val isClipboardHistoryVisible by viewModel.isClipboardHistoryVisible.collectAsStateWithLifecycle()
    val currentJoinToken by viewModel.currentJoinToken.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val localIp by viewModel.localIpAddress.collectAsStateWithLifecycle()

    val fileTransferTarget by viewModel.fileTransferTarget.collectAsStateWithLifecycle()
    val urlHandoffTarget by viewModel.urlHandoffTarget.collectAsStateWithLifecycle()
    val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
    val transferStatusLabel by viewModel.transferStatusLabel.collectAsStateWithLifecycle()
    val incomingSharePayload by viewModel.incomingSharePayload.collectAsStateWithLifecycle()
    val lastReceivedFile by viewModel.lastReceivedFile.collectAsStateWithLifecycle()

    var quickSendText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.dismissUserMessage()
        }
    }

    if (meshInfo == null) {
        OnboardingScreen(
            onCreateMesh = { name -> viewModel.createMesh(name) },
            onJoinMesh = { token -> viewModel.joinMeshViaScannedToken(token) },
            modifier = modifier
        )
    } else {
        val currentMesh = meshInfo!!
        val onlineCount = trustedDevices.count { it.connectionState.isOnline }

        Scaffold(
            modifier = modifier.fillMaxSize().testTag("rin_main_screen"),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.openAddDevice() },
                    containerColor = RinTealPrimary,
                    contentColor = RinOnPrimaryDark,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add Device", fontWeight = FontWeight.Bold) },
                    modifier = Modifier.testTag("fab_add_device")
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Block
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Rin",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = RinTealPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = RinStatusConnected.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(RinStatusConnected)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "$onlineCount Connected",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = RinStatusConnected,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = currentMesh.meshName,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = "Socket: $localIp:${currentMesh.port}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = RinCyanAccent
                            )
                        }

                        // Header Actions
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.setClipboardHistoryVisible(true) },
                                modifier = Modifier.testTag("top_clipboard_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Clipboard Sync History",
                                    tint = RinCyanAccent
                                )
                            }

                            IconButton(
                                onClick = { viewModel.setPacketInspectorVisible(true) },
                                modifier = Modifier.testTag("top_packet_inspector_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Packet Inspector",
                                    tint = RinTealPrimary
                                )
                            }

                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Menu",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Reset Mesh") },
                                        leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            viewModel.resetMesh()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Received File Banner (if any)
                if (lastReceivedFile != null) {
                    item {
                        ReceivedFileBanner(
                            fileRecord = lastReceivedFile!!,
                            onOpenFile = { viewModel.openReceivedFile(lastReceivedFile!!) },
                            onDismiss = { viewModel.dismissReceivedFile() }
                        )
                    }
                }

                // Automatic Clipboard Synchronization Card
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, RinBorder, RoundedCornerShape(16.dp))
                            .testTag("inline_clipboard_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.ContentPaste,
                                        contentDescription = null,
                                        tint = RinTealPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Automatic Clipboard Sync",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Surface(
                                    color = RinStatusConnected.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "AUTO-SYNC ACTIVE",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RinStatusConnected,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Whenever you copy text on any phone in the mesh, it is automatically added to the clipboard of other devices in real-time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Manual input / test field
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = quickSendText,
                                    onValueChange = { quickSendText = it },
                                    placeholder = { Text("Or type/paste text to push now...", style = MaterialTheme.typography.bodySmall) },
                                    modifier = Modifier.weight(1f).testTag("main_clipboard_input"),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (quickSendText.isNotBlank()) {
                                            viewModel.syncClipboardNow(quickSendText)
                                            quickSendText = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RinTealPrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = quickSendText.isNotBlank(),
                                    modifier = Modifier.testTag("main_clipboard_send_btn")
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = "Send", tint = RinOnPrimaryDark)
                                }
                            }

                            if (clipboardHistory.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "RECENT SYNCS (${clipboardHistory.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                                clipboardHistory.take(4).forEach { item ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.text,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 2,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${item.senderName} • ${timeFormat.format(Date(item.timestamp))}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.syncClipboardNow(item.text) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ContentCopy,
                                                    contentDescription = "Copy",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = RinCyanAccent
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section Label
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CONNECTED DEVICES (${trustedDevices.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Connected Devices List
                if (trustedDevices.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.Devices, contentDescription = null, tint = RinTealPrimary, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No devices in this mesh yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap 'Add Device' below to pair your second Android phone via QR handshake.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(trustedDevices, key = { it.publicKey }) { device ->
                        DeviceCard(
                            device = device,
                            onSendFile = { target -> viewModel.openFileTransfer(target) },
                            onSendUrl = { target -> viewModel.openUrlHandoff(target) },
                            onPing = { target -> viewModel.sendPingToDevice(target) },
                            onRemove = { key -> viewModel.removeDevice(key) }
                        )
                    }
                }
            }
        }

        // Modals and Sheets
        if (isAddDeviceSheetVisible) {
            AddDeviceBottomSheet(
                token = currentJoinToken,
                onDismiss = { viewModel.closeAddDevice() },
                onRefreshToken = { viewModel.refreshJoinToken() },
                onAddDevicePlatform = { platform -> viewModel.addDemoDevice(platform) },
                onJoinViaToken = { token -> viewModel.joinMeshViaScannedToken(token) }
            )
        }

        if (isPacketInspectorVisible) {
            PacketInspectorSheet(
                packets = recentPackets,
                onDismiss = { viewModel.setPacketInspectorVisible(false) },
                onClear = { viewModel.clearPackets() }
            )
        }

        if (isClipboardHistoryVisible) {
            ClipboardHistorySheet(
                history = clipboardHistory,
                onDismiss = { viewModel.setClipboardHistoryVisible(false) },
                onSendClipboard = { text -> viewModel.syncClipboardNow(text) },
                onClear = { viewModel.clearClipboardHistory() }
            )
        }

        if (urlHandoffTarget != null) {
            UrlHandoffDialog(
                targetDevice = urlHandoffTarget,
                onDismiss = { viewModel.closeUrlHandoff() },
                onSend = { url -> viewModel.sendUrlHandoff(url) }
            )
        }

        if (fileTransferTarget != null) {
            FileTransferDialog(
                targetDevice = fileTransferTarget,
                progress = transferProgress,
                statusLabel = transferStatusLabel,
                onDismiss = { viewModel.closeFileTransfer() },
                onSendRealFile = { uri -> viewModel.sendRealFile(uri) },
                onSendFileSimulation = { name, sizeKb -> viewModel.sendFile(name, sizeKb) }
            )
        }

        if (incomingSharePayload != null) {
            ShareTargetDialog(
                payload = incomingSharePayload,
                devices = trustedDevices,
                transferProgress = transferProgress,
                onDismiss = { viewModel.dismissSharePayload() },
                onSelectDevice = { target -> viewModel.sendSharedPayloadToDevice(target) }
            )
        }
    }
}
