package com.example.cs423application

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.cs423application.ui.theme.CS423ApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CS423ApplicationTheme {
                ImagePipelineScreen()
            }
        }
    }
}

@Composable
fun ImagePipelineScreen(vm: ImageViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    // System photo-picker — no manual permission required on API 30+
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.processImage(it) } }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Image Pipeline", style = MaterialTheme.typography.headlineMedium)

            // ── Stage 1: Pick & load ─────────────────────────────────────────
            Button(onClick = { pickerLauncher.launch("image/*") }) {
                Text("Pick Image")
            }

            // Coil AsyncImage shows the raw URI immediately while the pipeline runs
            state.sourceUri?.let { uri ->
                if (state.correctedBitmap == null && !state.isProcessing) {
                    Text("Stage 1: loaded via Coil", style = MaterialTheme.typography.labelSmall)
                    AsyncImage(
                        model = uri,
                        contentDescription = "Original image",
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // ── Stage 2: Orientation fix in progress ─────────────────────────
            if (state.isProcessing) {
                CircularProgressIndicator()
                Text("Stage 2: reading EXIF, fixing orientation…")
            }

            // ── Stage 3: Display corrected result ────────────────────────────
            state.correctedBitmap?.let { bmp ->
                Text(
                    "Stage 2 done — ${bmp.width}×${bmp.height} (orientation corrected)",
                    style = MaterialTheme.typography.labelSmall
                )
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Orientation-corrected image",
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentScale = ContentScale.Fit
                )

                // ── Stage 4: Save copy ────────────────────────────────────────
                Button(onClick = { vm.saveImage() }) {
                    Text("Save Copy")
                }
            }

            state.savedFileName?.let { name ->
                Text(
                    "Saved → Pictures/CS423/$name",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            state.error?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
