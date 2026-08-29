package com.depthmaker.app.net

import com.google.gson.annotations.SerializedName

data class CreateUploadRequest(
    val filename: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    val sha256: String
)

data class CreateUploadResponse(
    @SerializedName("upload_id") val uploadId: String,
    @SerializedName("chunk_size") val chunkSize: Int = 5 * 1024 * 1024,
    @SerializedName("received_bytes") val receivedBytes: Long = 0
)

data class UploadStatusResponse(
    @SerializedName("received_bytes") val receivedBytes: Long
)

data class CreateJobRequest(
    @SerializedName("upload_id") val uploadId: String,
    val model: String,
    val format: String
)

data class CreateJobResponse(
    @SerializedName("job_id") val jobId: String,
    val status: String,
    @SerializedName("queue_position") val queuePosition: Int? = null
)

data class JobStatusResponse(
    @SerializedName("job_id") val jobId: String,
    val status: String,
    @SerializedName("queue_position") val queuePosition: Int? = null,
    val progress: Int = 0,
    @SerializedName("stage_text") val stageText: String? = null,
    @SerializedName("eta_seconds") val etaSeconds: Int? = null,
    @SerializedName("error_code") val errorCode: String? = null,
    @SerializedName("error_message") val errorMessage: String? = null,
    @SerializedName("result_url") val resultUrl: String? = null,
    val warnings: List<String>? = null
)
