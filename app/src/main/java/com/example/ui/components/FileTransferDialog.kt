package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.entity.TrustedDeviceEntity
import com.example.ui.theme.RinBorder
import com.example.ui.theme.RinCyanAccent
import com.example.ui.theme.RinOnPrimaryDark
import com.example.ui.theme.RinTealPrimary

data class MockTransferFile(
    val name: String,
    val sizeKb: Long,
    val sizeLabel: String,
    val type: String
)

@Composable
fun FileTransferDialog(
    targetDevice: TrustedDeviceEntity?,
    progress: Float?,
    statusLabel: String?,
    onDismiss: () -> Unit,
    onSendRealFile: (Uri) -> Unit,
    onSendFileSimulation: (String, Long) -> Unit
) {
    if (targetDevice == null) return
    val context = LocalContext.current

    var selectedRealUri by remember { mutableStateOf<Uri?>(null) }
    var selectedRealName by remember { mutableStateOf<String?>(null) }

    val sampleFiles = listOf(
        MockTransferFile("Project_Presentation.pdf", 4200, "4.2 MB", "PDF"),
        MockTransferFile("Source_Code_Archive.zip", 18500, "18.5 MB", "ZIP"),
        MockTransferFile("Camera_Photo_HDR.jpg", 3100, "3.1 MB", "IMAGE")
    )
    var selectedSample by remember { mutableStateOf<MockTransferFile?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedRealUri = uri
            selectedSample = null
            // Extract filename
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val name = cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) it.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment ?: "Selected File"
            selectedRealName = name
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (progress == null) onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    tint = RinTealPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Beam to ${targetDevice.name}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Direct end-to-end encrypted transfer over ${targetDevice.activeRail.label}:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (progress != null) {
                    val animatedProgress by animateFloatAsState(targetValue = progress, label = "file_progress")
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = statusLabel ?: "Transmitting data chunks...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = RinTealPrimary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${(animatedProgress * 100).toInt()}% • Direct P2P Rail (${targetDevice.activeRail.label})",
                            style = MaterialTheme.typography.labelSmall,
                            color = RinTealPrimary
                        )
                    }
                } else {
                    // Option 1: Pick Real File / Photo from Storage
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { filePickerLauncher.launch("*/*") }
                            .border(
                                1.5.dp,
                                if (selectedRealUri != null) RinTealPrimary else RinBorder,
                                RoundedCornerShape(12.dp)
                            ),
                        color = if (selectedRealUri != null) RinTealPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (selectedRealUri != null) Icons.Default.CheckCircle else Icons.Default.UploadFile,
                                contentDescription = null,
                                tint = if (selectedRealUri != null) RinTealPrimary else RinCyanAccent,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedRealName ?: "Choose Any Photo / File from Device",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selectedRealUri != null) RinTealPrimary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (selectedRealUri != null) "Ready to beam via direct LAN socket" else "Tap to open storage & photos",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = RinBorder)
                        Text(
                            text = "  OR PRESET SAMPLES  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = RinBorder)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    sampleFiles.forEach { file ->
                        val isSelected = selectedSample == file && selectedRealUri == null
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedSample = file
                                    selectedRealUri = null
                                    selectedRealName = null
                                }
                                .border(
                                    1.dp,
                                    if (isSelected) RinTealPrimary else RinBorder,
                                    RoundedCornerShape(8.dp)
                                ),
                            color = if (isSelected) RinTealPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (file.type) {
                                        "PDF" -> Icons.Default.Description
                                        "ZIP" -> Icons.Default.FolderZip
                                        else -> Icons.Default.Image
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) RinTealPrimary else RinCyanAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = file.sizeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (progress == null) {
                val canSend = selectedRealUri != null || selectedSample != null
                Button(
                    onClick = {
                        if (selectedRealUri != null) {
                            onSendRealFile(selectedRealUri!!)
                        } else if (selectedSample != null) {
                            onSendFileSimulation(selectedSample!!.name, selectedSample!!.sizeKb)
                        }
                    },
                    enabled = canSend,
                    colors = ButtonDefaults.buttonColors(containerColor = RinTealPrimary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("start_transfer_btn")
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = RinOnPrimaryDark, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Beam File", color = RinOnPrimaryDark, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (progress == null) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}
