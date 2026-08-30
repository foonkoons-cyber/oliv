package com.depthmaker.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.depthmaker.app.toon.FpsPreset
import com.depthmaker.app.toon.ResolutionPreset
import com.depthmaker.app.toon.ToonFilter
import com.depthmaker.app.ui.theme.PrimaryColor
import com.depthmaker.app.ui.theme.SurfaceColor
import com.depthmaker.app.ui.theme.TextSecondary

@Composable
fun ToonScreen(
    state: ToonState,
    onPick: () -> Unit,
    onFilter: (ToonFilter) -> Unit,
    onStrength: (Float) -> Unit,
    onResolution: (ResolutionPreset) -> Unit,
    onFps: (FpsPreset) -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        Text(
            "Cartoon — sab kuch phone par, koi server nahi.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        val meta = state.meta
        if (meta == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceColor)
                    .clickable(onClick = onPick),
                contentAlignment = Alignment.Center
            ) {
                Text("Video select karo", color = PrimaryColor)
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            meta.thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(meta.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(meta.chipText(), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            TextButton(onClick = onClear, enabled = !state.running) { Text("Change") }
        }

        ChipRow(
            label = "Style",
            options = ToonFilter.entries.map { it to it.label },
            selected = state.filter,
            enabled = !state.running,
            onSelect = onFilter
        )

        Column {
            Text(
                "Strength ${(state.strength * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Slider(
                value = state.strength,
                onValueChange = onStrength,
                enabled = !state.running
            )
        }

        ChipRow(
            label = "Export size",
            options = ResolutionPreset.entries.map { it to it.label },
            selected = state.resolution,
            enabled = !state.running,
            onSelect = onResolution
        )

        ChipRow(
            label = "Frame rate",
            options = FpsPreset.entries.map { it to it.label },
            selected = state.fps,
            enabled = !state.running,
            onSelect = onFps
        )

        // Two things people get wrong about "60 fps export", said once, here.
        Text(
            buildString {
                append(
                    if (state.supports720p60) "Is phone par 720p60 supported hai."
                    else "Is phone ka encoder 720p60 report nahi karta — export 30fps par gir jayega."
                )
                append(" 30fps source 60fps nahi ban sakta; frame rate source se match hota hai.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        if (state.running) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        } else if (state.result != null) {
            Text(
                "Ho gaya — ${state.resultSummary} in ${"%.1f".format(state.elapsedMs / 1000.0)}s",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.warning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save to Gallery") }
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Dobara export karo") }
        } else {
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export") }
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, text) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) PrimaryColor else SurfaceColor)
                        .clickable(enabled = enabled) { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) androidx.compose.ui.graphics.Color.White else TextSecondary
                    )
                }
            }
        }
    }
}
