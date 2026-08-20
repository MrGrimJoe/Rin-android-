package com.example.core.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File

object MeshNotificationHelper {
    private const val CHANNEL_ID = "rin_mesh_channel"
    private const val CHANNEL_NAME = "Rin Mesh Sync & Handoff"
    private const val FILE_CHANNEL_ID = "rin_mesh_files"
    private const val FILE_CHANNEL_NAME = "Rin File Transfers"
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

            val syncChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming clipboard syncs and URL handoffs"
                enableVibration(true)
            }

            val fileChannel = NotificationChannel(
                FILE_CHANNEL_ID,
                FILE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming and outgoing high-throughput file transfers"
                enableVibration(true)
            }

            notificationManager?.createNotificationChannel(syncChannel)
            notificationManager?.createNotificationChannel(fileChannel)
        }
        isInitialized = true
    }

    fun showUrlHandoffNotification(context: Context, url: String, senderName: String) {
        initialize(context)
        try {
            val formatted = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else url

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formatted)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle("Incoming Web Link from $senderName")
                .setContentText(url)
                .setStyle(NotificationCompat.BigTextStyle().bigText("Tap to open link received from $senderName:\n$url"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Open in Browser", pendingIntent)

            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (_: SecurityException) {
            // Notifications permission not granted yet on Android 13+
        } catch (_: Exception) {}
    }

    fun showClipboardSyncNotification(context: Context, text: String, senderName: String) {
        initialize(context)
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("Clipboard Synced from $senderName")
                .setContentText(text.take(60))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (_: SecurityException) {
            // Notifications permission not granted yet
        } catch (_: Exception) {}
    }

    fun showFileReceivedNotification(context: Context, file: File, senderName: String, mimeType: String?) {
        initialize(context)
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                file.name.hashCode(),
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val sizeKb = (file.length() / 1024).coerceAtLeast(1)
            val sizeLabel = if (sizeKb > 1024) "${String.format("%.1f", sizeKb / 1024.0)} MB" else "$sizeKb KB"

            val builder = NotificationCompat.Builder(context, FILE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("File Received from $senderName")
                .setContentText("${file.name} ($sizeLabel)")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Received ${file.name} ($sizeLabel) via Direct Mesh Rail. Tap to open."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Open File", pendingIntent)

            NotificationManagerCompat.from(context).notify(file.name.hashCode(), builder.build())
        } catch (e: Exception) {
            // Permission or file provider issues
        }
    }
}
