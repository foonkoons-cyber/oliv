package com.depthmaker.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaStoreSaver {

    /**
     * Scoped-storage save into Movies/DepthMaker/ (RELATIVE_PATH — API 29+,
     * which is why minSdk is 29).
     */
    fun saveToGallery(
        context: Context,
        source: File,
        originalName: String,
        mimeType: String,
        prefix: String = "depth"
    ): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "${prefix}_${sanitizeBaseName(originalName)}_$stamp"

        return when {
            mimeType.startsWith("video/") -> insert(
                context,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "$base.mp4",
                mimeType,
                Environment.DIRECTORY_MOVIES + "/DepthMaker",
                source,
                MediaStore.Video.Media.IS_PENDING
            )
            else -> insert(
                context,
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                base + extensionFor(mimeType),
                mimeType,
                Environment.DIRECTORY_DOWNLOADS + "/DepthMaker",
                source,
                MediaStore.Downloads.IS_PENDING
            )
        }
    }

    private fun extensionFor(mime: String): String = when {
        mime.contains("zip") -> ".zip"
        mime.contains("octet-stream") -> ".npz"
        else -> ".bin"
    }

    private fun insert(
        context: Context,
        collection: Uri,
        fileName: String,
        mimeType: String,
        relativePath: String,
        source: File,
        pendingColumn: String
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(pendingColumn, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: return null

        val done = ContentValues().apply { put(pendingColumn, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }
}
