package com.depthmaker.app.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Two client shapes on purpose (spec 5.2): short per-request timeouts for the
 * status polls, and a generous one for the big transfers. The overall job
 * deadline is tracked separately by the worker, never by an HTTP read timeout.
 */
class ApiClient(baseUrl: String, token: String) {

    private val auth = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        chain.proceed(req)
    }

    val normalizedBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val apiHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Chunk uploads and result downloads: long write/read budgets. */
    val transferHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    val api: DepthApi = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(apiHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(DepthApi::class.java)
}
