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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.protocol.ConnectionState
import com.example.core.protocol.PlatformType
import com.example.data.local.entity.TrustedDeviceEntity
import com.example.ui.theme.RinBorder
import com.example.ui.theme.RinCyanAccent
import com.example.ui.theme.RinStatusConnected
import com.example.ui.theme.RinStatusIdle
import com.example.ui.theme.RinStatusOffline
import com.example.ui.theme.RinStatusReconnecting
import com.example.ui.theme.RinTealPrimary

@Composable
fun DeviceCard(
    device: TrustedDeviceEntity,
    onSendFile: (TrustedDeviceEntity) -> Unit,
    onSendUrl: (TrustedDeviceEntity) -> Unit,
    onPing: (TrustedDeviceEntity) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val stateColor = when (device.connectionState) {
        ConnectionState.CONNECTED, ConnectionState.ACTIVE -> RinStatusConnected
        ConnectionState.IDLE -> RinStatusIdle
        ConnectionState.RECONNECTING, ConnectionState.AUTHENTICATING -> RinStatusReconnecting
        ConnectionState.OFFLINE, ConnectionState.LOST, ConnectionState.DISCOVERED -> RinStatusOffline
    }

    val platformIcon: ImageVector = when (device.platform) {
        PlatformType.ANDROID -> Icons.Default.PhoneAndroid
        PlatformType.WINDOWS -> Icons.Default.Laptop
        PlatformType.LINUX -> Icons.Default.Computer
        PlatformType.MACOS -> Icons.Default.Laptop
        PlatformType.TABLET -> Icons.Default.TabletAndroid
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("device_card_${device.publicKey.take(6)}")
            .border(1.dp, if (device.isSelf) RinTealPrimary.copy(alpha = 0.4f) else RinBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (device.isSelf) RinTealPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = platformIcon,
                            contentDescription = device.platform.displayName,
                            tint = if (device.isSelf) RinTealPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (device.isSelf) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = RinTealPrimary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "THIS PHONE",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RinTealPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(stateColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (device.isSelf) "Active Mesh Host" else device.connectionState.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = stateColor,
                                fontWeight = FontWeight.Medium
                            )

                            if (!device.ipAddress.isNullOrBlank() && device.ipAddress != "127.0.0.1") {
                                Text(
                                    text = " • ${device.ipAddress}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                if (!device.isSelf) {
                    IconButton(
                        onClick = { onRemove(device.publicKey) },
                        modifier = Modifier.testTag("device_remove_btn_${device.publicKey.take(6)}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove device",
                            tint = Color(0xFFEF4444)
                        )
                    }
                }
            }

            if (!device.isSelf) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onSendFile(device) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = RinTealPrimary.copy(alpha = 0.15f),
                            contentColor = RinTealPrimary
                        )
                    ) {
                        Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Send File", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = { onSendUrl(device) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = RinCyanAccent.copy(alpha = 0.15f),
                            contentColor = RinCyanAccent
                        )
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Push URL", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = { onPing(device) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp), tint = RinTealPrimary)
                    }
                }
            }
        }
    }
}
