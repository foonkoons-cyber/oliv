package com.depthmaker.app.ui

import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.depthmaker.app.ui.theme.SurfaceColor
import com.depthmaker.app.ui.theme.TextSecondary
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun ResultScreen(
    result: ResultInfo,
    onSave: () -> Unit,
    onNewVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isVideo = result.mime.startsWith("video/")

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isVideo) {
            val player = remember {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(result.file)))
                    repeatMode = Player.REPEAT_MODE_ALL
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(Unit) { onDispose { player.release() } }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (result.mime.contains("zip")) "PNG 16-bit sequence (ZIP)" else "Raw depth (NPZ)",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(summaryLine(result), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSave,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) { Text("Save to Gallery") }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", result.file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = result.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share depth map"))
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) { Text("Share") }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onNewVideo,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) { Text("New Video") }
    }
}

private fun summaryLine(r: ResultInfo): String {
    val mb = r.sizeBytes / (1024.0 * 1024.0)
    val secs = (r.durationMs / 1000).toInt()
    val dims = if (r.width > 0) "${r.width}×${r.height} · " else ""
    val fps = if (r.fps > 0f) "${Math.round(r.fps)}fps · " else ""
    return String.format(Locale.US, "%s%s%ds · %.1fMB", dims, fps, secs, mb)
}
