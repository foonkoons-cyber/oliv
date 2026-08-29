package com.depthmaker.app.net

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Byte-offset resumable upload (spec 3.7 / 5.2). A dropped connection never
 * restarts from zero: we re-ask the server how many bytes it has and continue.
 */
class ChunkedUploader(
    private val client: ApiClient,
    private val onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit
) {
    private val octet = "application/octet-stream".toMediaType()

    suspend fun upload(file: File, uploadId: String, chunkSize: Int) {
        val total = file.length()
        var offset = client.api.uploadStatus(uploadId).receivedBytes
        onProgress(offset, total)

        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(chunkSize)
            while (offset < total) {
                val len = minOf(chunkSize.toLong(), total - offset).toInt()
                raf.seek(offset)
                raf.readFully(buffer, 0, len)

                val end = offset + len - 1
                val body = buffer.toRequestBody(octet, 0, len)
                val request = Request.Builder()
                    .url("${client.normalizedBaseUrl}uploads/$uploadId")
                    .put(body)
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .build()

                val newOffset = putWithRetry(request, uploadId, offset)
                // The server is the authority on how much it holds; trust its count.
                offset = if (newOffset > offset) newOffset else offset + len
                onProgress(minOf(offset, total), total)
            }
        }
    }

    private suspend fun putWithRetry(request: Request, uploadId: String, currentOffset: Long): Long {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 3) {
            try {
                client.transferHttp.newCall(request).execute().use { resp ->
                    if (resp.code == 401) throw UnauthorizedException()
                    if (!resp.isSuccessful) throw IOException("upload chunk failed: HTTP ${resp.code}")
                    val text = resp.body?.string().orEmpty()
                    return parseReceived(text, currentOffset)
                }
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                attempt++
                if (attempt >= 3) break
                delay(2000L * (1L shl (attempt - 1)))   // 2s, 4s
                // Re-sync the offset after a drop; the chunk PUT is idempotent.
                runCatching { client.api.uploadStatus(uploadId) }
            }
        }
        throw lastError ?: IOException("upload failed")
    }

    private fun parseReceived(json: String, fallback: Long): Long {
        val m = Regex("\"received_bytes\"\\s*:\\s*(\\d+)").find(json) ?: return fallback
        return m.groupValues[1].toLongOrNull() ?: fallback
    }
}

class UnauthorizedException : IOException("401")
