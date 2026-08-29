package com.depthmaker.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.depthmaker.app.data.Settings
import com.depthmaker.app.ui.theme.ErrorColor
import com.depthmaker.app.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    settings: Settings,
    onModel: (String) -> Unit,
    onFormat: (String) -> Unit,
    onServerUrl: (String) -> Boolean,
    onToken: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Model quality", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Choice("Standard — vits (Apache-2.0, commercial-safe)", settings.model == "vits") { onModel("vits") }
        Choice("High Quality — vitl (Large model)", settings.model == "vitl") { onModel("vitl") }
        Spacer(Modifier.height(8.dp))
        Card(shape = RoundedCornerShape(12.dp)) {
            Text(
                "High Quality (Large model) — non-commercial use only. Do not use for paid client work. " +
                    "The Large and Base checkpoints are CC-BY-NC-4.0; only the Standard (Small) model is " +
                    "Apache-2.0 and safe for client delivery.",
                style = MaterialTheme.typography.bodySmall,
                color = ErrorColor,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Output format", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Choice("MP4 grayscale — preview / quick reference only", settings.format == "mp4") { onFormat("mp4") }
        Choice("PNG sequence 16-bit — production deliverable", settings.format == "png16") { onFormat("png16") }
        Choice("NPZ raw float — for scripting", settings.format == "npz") { onFormat("npz") }
        Spacer(Modifier.height(8.dp))
        Text(
            "8-bit MP4 throws away real depth precision. For ControlNet, After Effects, Nuke or parallax " +
                "work, PNG 16-bit is the correct deliverable — MP4 is only for eyeballing the result.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(Modifier.height(24.dp))
        Text("Server", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL (https:// only)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Bearer token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                onToken(token)
                if (onServerUrl(url)) onDone()
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) { Text("Save") }

        Spacer(Modifier.height(24.dp))
        Text(
            "DepthMaker 1.1.0 · Video Depth Anything\n" +
                "Small (vits): Apache-2.0 · Base/Large (vitb/vitl): CC-BY-NC-4.0",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
