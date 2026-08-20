package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.screens.RinMainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.IncomingSharePayload
import com.example.ui.viewmodel.RinViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: RinViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleIncomingShareIntent(intent)
    setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          RinMainScreen(viewModel = viewModel)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingShareIntent(intent)
  }

  private fun handleIncomingShareIntent(intent: Intent?) {
    if (intent == null) return
    val action = intent.action
    val type = intent.type

    when (action) {
      Intent.ACTION_SEND -> {
        if (type?.startsWith("text/") == true || intent.hasExtra(Intent.EXTRA_TEXT)) {
          val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
          if (sharedText.isNotBlank()) {
            val isUrl = sharedText.startsWith("http://", ignoreCase = true) ||
                        sharedText.startsWith("https://", ignoreCase = true) ||
                        sharedText.startsWith("www.", ignoreCase = true)
            viewModel.setIncomingShareIntent(IncomingSharePayload.Text(sharedText, isUrl))
          }
        } else {
          val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
          }
          if (uri != null) {
            val (name, _) = viewModel.runtimeEngine.fileTransferManager.getDisplayNameAndSize(uri)
            viewModel.setIncomingShareIntent(IncomingSharePayload.Files(listOf(uri), listOf(name)))
          }
        }
      }
      Intent.ACTION_SEND_MULTIPLE -> {
        val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        if (!uris.isNullOrEmpty()) {
          val names = uris.map { uri ->
            viewModel.runtimeEngine.fileTransferManager.getDisplayNameAndSize(uri).first
          }
          viewModel.setIncomingShareIntent(IncomingSharePayload.Files(uris, names))
        }
      }
    }
  }
}

