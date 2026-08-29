package com.depthmaker.app.net

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface DepthApi {
    @POST("uploads")
    suspend fun createUpload(@Body body: CreateUploadRequest): CreateUploadResponse

    @GET("uploads/{id}")
    suspend fun uploadStatus(@Path("id") id: String): UploadStatusResponse

    @POST("jobs")
    suspend fun createJob(@Body body: CreateJobRequest): CreateJobResponse

    @GET("jobs/{id}")
    suspend fun jobStatus(@Path("id") id: String): JobStatusResponse

    @DELETE("jobs/{id}")
    suspend fun cancelJob(@Path("id") id: String): Response<Unit>
}
