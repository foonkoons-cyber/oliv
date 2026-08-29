package com.depthmaker.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.depthmaker.app.R
import com.depthmaker.app.net.ApiClient
import com.depthmaker.app.net.ChunkedUploader
import com.depthmaker.app.net.CreateJobRequest
import com.depthmaker.app.net.CreateUploadRequest
import com.depthmaker.app.net.UnauthorizedException
import com.depthmaker.app.util.sanitizeBaseName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.roundToInt

class DepthJobWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private var serverJobId: String? = null
    private var client: ApiClient? = null

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceUri = inputData.getString(KEY_SOURCE_URI)?.let(Uri::parse)
            ?: return@withContext fail("ERR_INPUT", "Video select nahi hui.")
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "video"
        val serverUrl = inputData.getString(KEY_SERVER_URL).orEmpty()
        val token = inputData.getString(KEY_TOKEN).orEmpty()
        val model = inputData.getString(KEY_MODEL) ?: "vits"
        val format = inputData.getString(KEY_FORMAT) ?: "mp4"

        if (!serverUrl.startsWith("https://")) {
            return@withContext fail("ERR_CONFIG", "Server URL set nahi hai. Settings kholo.")
        }

        setForegroundSafely(0, "Taiyari ho rahi hai")
        val api = ApiClient(serverUrl, token).also { client = it }

        var localCopy: File? = null
        try {
            // ---- stage: copy source locally (needed for sha256 + resumable reads)
            localCopy = copyToCache(sourceUri)
            if (localCopy.length() == 0L) {
                return@withContext fail("ERR_INPUT", "Ye video kharab lag rahi hai, open nahi ho rahi.")
            }
            report(1, "Video read ho rahi hai", null)

            val deadline = System.currentTimeMillis() + OVERALL_DEADLINE_MS
            val sha = sha256(localCopy)

            // ---- stage: upload (0 -> 15%)
            val created = withColdStartRetry {
                api.api.createUpload(
                    CreateUploadRequest(sanitizeBaseName(displayName) + ".mp4", localCopy.length(), sha)
                )
            }
            val uploader = ChunkedUploader(api) { sent, total ->
                val pct = if (total > 0) (sent.toDouble() / total * 15).roundToInt() else 0
                report(pct.coerceIn(0, 15), "Upload ho raha hai", null)
            }
            uploader.upload(localCopy, created.uploadId, created.chunkSize)
            report(15, "Upload complete", null)

            // ---- stage: job
            val job = api.api.createJob(CreateJobRequest(created.uploadId, model, format))
            serverJobId = job.jobId

            var lastPct = 15
            var resultUrl: String? = null
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    cancelServerJob()
                    return@withContext fail("ERR_TIMEOUT", "Job bahut lamba chal gaya. Dobara try karo.")
                }
                val st = try {
                    api.api.jobStatus(job.jobId)
                } catch (e: IOException) {
                    // transient: keep polling until the overall deadline
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                when (st.status) {
                    "failed" -> return@withContext fail(
                        st.errorCode ?: "ERR_SERVER",
                        st.errorMessage ?: "Server par process fail ho gaya."
                    )
                    "done" -> {
                        resultUrl = st.resultUrl ?: "jobs/${job.jobId}/result"
                        report(97, "Download ho raha hai", null)
                        break
                    }
                    else -> {
                        val pct = mapProgress(st.status, st.progress)
                        lastPct = maxOf(lastPct, pct)   // never goes backwards
                        val stage = st.stageText ?: defaultStageText(st.status, st.queuePosition)
                        report(lastPct, stage, st.etaSeconds)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }

            // ---- stage: download (97 -> 100)
            val outFile = downloadResult(api, resultUrl!!, displayName, format)
            report(100, "Ho gaya", null)

            Result.success(
                workDataOf(
                    KEY_OUT_FILE to outFile.absolutePath,
                    KEY_OUT_MIME to mimeFor(format),
                    KEY_OUT_NAME to displayName,
                    KEY_OUT_SIZE to outFile.length()
                )
            )
        } catch (e: UnauthorizedException) {
            fail("ERR_AUTH", "App ka server token galat hai. Settings check karo.")
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> fail("ERR_AUTH", "App ka server token galat hai. Settings check karo.")
                503 -> fail("ERR_BUSY", "Server abhi band hai. Thodi der baad try karo.")
                else -> fail("ERR_SERVER", "Server ne request reject kar di (${e.code()}).")
            }
        } catch (e: IOException) {
            fail("ERR_NET", "Internet nahi hai. Ye app processing ke liye server use karta hai.")
        } catch (e: Exception) {
            if (isStopped) {
                withContext(NonCancellable) { cancelServerJob() }
                Result.failure(workDataOf(KEY_ERROR_CODE to "ERR_CANCELLED", KEY_ERROR to "Cancel kar diya."))
            } else {
                fail("ERR_UNKNOWN", "Kuch galat ho gaya: ${e.javaClass.simpleName}")
            }
        } finally {
            localCopy?.delete()
        }
    }

    private suspend fun cancelServerJob() {
        val id = serverJobId ?: return
        val c = client ?: return
        runCatching { c.api.cancelJob(id) }
        serverJobId = null
    }

    // ---------- helpers ----------

    private fun mapProgress(status: String, serverProgress: Int): Int = when (status) {
        "queued" -> 15
        // 15 -> 90 across inference
        "processing" -> 15 + (serverProgress.coerceIn(0, 100) * 0.75).roundToInt()
        // 90 -> 97 across encoding
        "encoding" -> 90 + (serverProgress.coerceIn(0, 100) * 0.07).roundToInt()
        else -> 15
    }

    private fun defaultStageText(status: String, queuePosition: Int?): String = when (status) {
        "queued" -> if (queuePosition != null && queuePosition > 0) {
            "$queuePosition video aapse aage hain"
        } else {
            "Server par queue me hai"
        }
        "encoding" -> "Video encode ho rahi hai"
        else -> "Process ho rahi hai"
    }

    private suspend fun report(percent: Int, stage: String, eta: Int?) {
        setProgress(
            workDataOf(
                KEY_PROGRESS to percent,
                KEY_STAGE to stage,
                KEY_ETA to (eta ?: -1)
            )
        )
        setForegroundSafely(percent, stage)
    }

    private suspend fun setForegroundSafely(percent: Int, stage: String) {
        runCatching { setForeground(buildForegroundInfo(percent, stage)) }
    }

    private fun buildForegroundInfo(percent: Int, stage: String): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Depth processing", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("DepthMaker — $percent%")
            .setContentText(stage)
            .setSmallIcon(R.drawable.ic_stat_depth)
            .setOngoing(true)
            .setProgress(100, percent, false)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** A rented GPU box may be cold: keep trying for ~90 s before giving up. */
    private suspend fun <T> withColdStartRetry(block: suspend () -> T): T {
        val until = System.currentTimeMillis() + COLD_START_WINDOW_MS
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (System.currentTimeMillis() > until) throw e
                attempt++
                report(0, "Server start ho raha hai, 30–60 second", null)
                delay(minOf(8000L, 2000L * attempt))
            }
        }
    }

    private fun copyToCache(uri: Uri): File {
        val dir = File(applicationContext.cacheDir, "uploads").apply { mkdirs() }
        val f = File(dir, "src_${id}.mp4")
        applicationContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open input" }
            f.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        return f
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadResult(api: ApiClient, resultUrl: String, displayName: String, format: String): File {
        val url = if (resultUrl.startsWith("http")) resultUrl
        else api.normalizedBaseUrl + resultUrl.removePrefix("/")

        val dir = File(applicationContext.filesDir, "results").apply { mkdirs() }
        val out = File(dir, "depth_${sanitizeBaseName(displayName)}_${System.currentTimeMillis()}${extFor(format)}")

        val request = Request.Builder().url(url).get().build()
        api.transferHttp.newCall(request).execute().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw IOException("download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty result body")
            out.outputStream().use { fileOut -> body.byteStream().copyTo(fileOut, 1 shl 16) }
        }
        if (out.length() == 0L) throw IOException("empty result file")
        return out
    }

    private fun extFor(format: String) = when (format) {
        "png16" -> ".zip"
        "npz" -> ".npz"
        else -> ".mp4"
    }

    private fun mimeFor(format: String) = when (format) {
        "png16" -> "application/zip"
        "npz" -> "application/octet-stream"
        else -> "video/mp4"
    }

    private fun fail(code: String, message: String) =
        Result.failure(workDataOf(KEY_ERROR_CODE to code, KEY_ERROR to message))

    companion object {
        const val UNIQUE_WORK = "depthmaker_job"
        const val CHANNEL_ID = "depthmaker_progress"
        const val NOTIFICATION_ID = 4711
        const val POLL_INTERVAL_MS = 2000L
        const val OVERALL_DEADLINE_MS = 20 * 60 * 1000L
        const val COLD_START_WINDOW_MS = 90_000L

        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_MODEL = "model"
        const val KEY_FORMAT = "format"

        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_ETA = "eta"

        const val KEY_OUT_FILE = "out_file"
        const val KEY_OUT_MIME = "out_mime"
        const val KEY_OUT_NAME = "out_name"
        const val KEY_OUT_SIZE = "out_size"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_CODE = "error_code"
    }
}
