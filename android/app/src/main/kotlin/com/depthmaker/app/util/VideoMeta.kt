package com.depthmaker.app.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.math.max

data class VideoMeta(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val thumbnail: Bitmap?
) {
    /** "0:10 · 478×850 · 24fps" */
    fun chipText(): String {
        val secs = (durationMs / 1000).toInt()
        val fpsText = if (fps > 0f) "${Math.round(fps)}fps" else "?fps"
        return "%d:%02d · %d×%d · %s".format(secs / 60, secs % 60, width, height, fpsText)
    }
}

sealed class PickResult {
    data class Ok(val meta: VideoMeta) : PickResult()
    data class Rejected(val message: String) : PickResult()
}

object VideoInspector {

    const val MAX_DURATION_MS = 60_000L
    const val MIN_DURATION_MS = 500L
    const val MAX_LONG_EDGE = 4096
    const val MAX_SIZE_BYTES = 200L * 1024 * 1024

    fun inspect(context: Context, uri: Uri): PickResult {
        val (name, size) = queryNameAndSize(context, uri)

        if (size > MAX_SIZE_BYTES) {
            return PickResult.Rejected("File 200MB se badi hai.")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
        } catch (e: Exception) {
            retriever.release()
            return PickResult.Rejected("Ye video kharab lag rahi hai, open nahi ho rahi.")
        }

        try {
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (!hasVideo) return PickResult.Rejected("Isme video track nahi hai.")

            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            if (!mime.startsWith("video/")) {
                return PickResult.Rejected("Ye video file nahi hai. MP4 ya MOV select karo.")
            }

            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (duration <= 0L) return PickResult.Rejected("Ye video kharab lag rahi hai, open nahi ho rahi.")
            if (duration < MIN_DURATION_MS) {
                return PickResult.Rejected("Video bahut chhoti hai, kam se kam 1 second chahiye.")
            }
            if (duration > MAX_DURATION_MS) {
                return PickResult.Rejected("Video 60 second se lambi hai. Chhota clip try karo.")
            }

            // Rotation-corrected dimensions: portrait in must stay portrait out.
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val width = if (rotation == 90 || rotation == 270) rawH else rawW
            val height = if (rotation == 90 || rotation == 270) rawW else rawH
            if (width <= 0 || height <= 0) {
                return PickResult.Rejected("Ye video kharab lag rahi hai, open nahi ho rahi.")
            }
            if (max(width, height) > MAX_LONG_EDGE) {
                return PickResult.Rejected("Resolution bahut zyada hai. 1080p ya chhota use karo.")
            }

            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toFloatOrNull()
                    ?.let { frames -> if (duration > 0) frames / (duration / 1000f) else 0f }
                ?: 0f

            val thumb = runCatching {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()

            return PickResult.Ok(
                VideoMeta(uri, name, size, duration, width, height, fps, thumb)
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = "video"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size == 0L) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            }
        }
        return name to size
    }
}

/** MediaStore inserts break on exotic SAF display names (spec 4.2). */
fun sanitizeBaseName(displayName: String): String {
    val stem = displayName.substringBeforeLast('.', displayName)
    val cleaned = stem.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
    val safe = if (cleaned.isEmpty()) "video" else cleaned
    return if (safe.length > 40) safe.substring(0, 40) else safe
}
