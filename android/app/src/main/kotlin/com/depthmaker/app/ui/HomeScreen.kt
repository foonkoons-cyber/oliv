package com.depthmaker.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.depthmaker.app.ui.theme.PrimaryColor
import com.depthmaker.app.ui.theme.SurfaceColor
import com.depthmaker.app.ui.theme.TextSecondary
import com.depthmaker.app.util.VideoMeta

@Composable
fun HomeScreen(
    meta: VideoMeta?,
    serverConfigured: Boolean,
    onOpenSettings: () -> Unit,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (!serverConfigured) {
            ServerNotSetBanner(onOpenSettings)
            Spacer(Modifier.height(16.dp))
        }

        if (meta == null) {
            DropZone(onPick)
        } else {
            SelectedVideo(meta, onClear, onPick)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onCreate,
            enabled = meta != null,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Create Depth Map", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ServerNotSetBanner(onOpenSettings: () -> Unit) {
    Surface(
        color = Color(0xFF2A1F14),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Server set nahi hai", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Depth processing GPU server par hoti hai, phone par nahi. " +
                    "Settings me apne server ka https:// URL aur token daalo.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun DropZone(onPick: () -> Unit) {
    val stroke = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), 0f))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceColor)
            .drawBehind {
                drawRoundRect(
                    color = Color(0xFF3A3A40),
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                    style = stroke
                )
            }
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = PrimaryColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("Add Video", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "MP4 · MOV · up to 60 seconds",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SelectedVideo(meta: VideoMeta, onClear: () -> Unit, onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceColor)
            .clickable(onClick = onPick)
    ) {
        meta.thumbnail?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        Surface(
            color = Color(0xCC000000),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
        ) {
            Text(
                meta.chipText(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        IconButton(
            onClick = onClear,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        ) {
            Row {
                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
            }
        }
    }
}
